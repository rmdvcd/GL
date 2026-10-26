"""Termux config locations and settings loader."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .catalog import CatalogError


APP_NAME = "termux-license-engine"


def config_dir() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / APP_NAME


def data_dir() -> Path:
    base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    return base / APP_NAME


def default_database_path() -> Path:
    return data_dir() / "state.sqlite3"


def settings_path() -> Path:
    return Path(os.environ.get("LICENSE_ENGINE_SETTINGS", config_dir() / "settings.json")).expanduser()


def profiles_path() -> Path:
    override = os.environ.get("LICENSE_ENGINE_PROFILES")
    if override:
        return Path(override).expanduser()
    local = config_dir() / "profiles.json"
    if local.exists():
        return local
    return Path(__file__).with_name("profiles.json")


def load_settings(path: Path | None = None) -> dict[str, Any]:
    source = path or settings_path()
    if not source.exists():
        return {}
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError("cannot read settings file") from exc
    if not isinstance(data, dict):
        raise CatalogError("settings must be a JSON object")
    return data


def database_path(settings: dict[str, Any]) -> Path:
    configured = settings.get("database_path")
    if not configured:
        return default_database_path()
    return Path(str(configured)).expanduser()
