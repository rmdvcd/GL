"""Data-driven app catalog and fail-closed request routing."""

from __future__ import annotations

import json
import unicodedata
from pathlib import Path
from typing import Iterable

from .models import AppProfile


class CatalogError(ValueError):
    """The catalog is invalid or a request cannot be routed safely."""


class AppNotResolved(CatalogError):
    pass


class AmbiguousApp(CatalogError):
    pass


def normalize_for_matching(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    unaccented = "".join(char for char in decomposed if not unicodedata.combining(char))
    return " ".join(unaccented.split())


def load_catalog(path: Path | None = None) -> dict[str, AppProfile]:
    source = path or Path(__file__).with_name("profiles.json")
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError("cannot read app profile catalog") from exc

    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise CatalogError("unsupported app catalog schema")
    raw_profiles = data.get("profiles")
    if not isinstance(raw_profiles, list):
        raise CatalogError("catalog profiles must be a list")

    profiles: dict[str, AppProfile] = {}
    try:
        for item in raw_profiles:
            if not isinstance(item, dict):
                raise ValueError("each profile must be an object")
            profile = AppProfile.from_mapping(item)
            if profile.app_id in profiles:
                raise ValueError(f"duplicate app_id {profile.app_id!r}")
            profiles[profile.app_id] = profile
    except ValueError as exc:
        raise CatalogError(str(exc)) from exc

    if not profiles:
        raise CatalogError("app catalog is empty")
    return profiles


def resolve_profile(
    message: str,
    profiles: Iterable[AppProfile],
    explicit_app_id: str | None = None,
) -> AppProfile:
    """Resolve from configured markers and token prefixes; never pick a default.

    The encrypted token is mandatory even if the visible fields identify an app.
    If multiple profiles claim the same message, the caller must supply an
    explicit app id and the selection must still match that profile.
    """

    profile_list = tuple(profiles)
    normalized_message = normalize_for_matching(message)
    token_matches = [
        profile
        for profile in profile_list
        if profile.request_token_prefix in message
    ]
    marker_matches = [
        profile
        for profile in profile_list
        if any(
            normalize_for_matching(marker) in normalized_message
            for marker in profile.request_markers
        )
    ]
    combined = {profile.app_id: profile for profile in token_matches if profile in marker_matches}

    if explicit_app_id is not None:
        selected = next((p for p in profile_list if p.app_id == explicit_app_id), None)
        if selected is None:
            raise AppNotResolved("unknown app_id")
        if selected not in token_matches:
            raise AppNotResolved("selected profile token is missing")
        if selected.request_markers and selected not in marker_matches:
            raise AppNotResolved("selected profile marker is missing")
        return selected

    if not combined:
        if token_matches and not marker_matches:
            raise AppNotResolved("request token found but app marker is missing")
        raise AppNotResolved("no configured app profile matches this request")
    if len(combined) != 1:
        raise AmbiguousApp("message matches more than one app profile")
    return next(iter(combined.values()))
