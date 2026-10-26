"""Termux and shared-provider adapters; no license or SMS text is logged."""

from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
from dataclasses import dataclass
from typing import Any

from .models import AppProfile, ParsedRequest, PriceRule, SmsRecord


class AdapterError(RuntimeError):
    pass


class TermuxSmsClient:
    def __init__(self, command: str = "termux-sms-list") -> None:
        self.command = command

    def list_inbox(self, limit: int = 50) -> list[SmsRecord]:
        if not 1 <= limit <= 1000:
            raise ValueError("SMS limit must be between 1 and 1000")
        if not _command_exists(self.command):
            raise AdapterError("termux-sms-list is unavailable; install Termux:API")
        try:
            result = subprocess.run(
                [self.command, "-l", str(limit), "-t", "inbox"],
                check=False,
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise AdapterError("Termux SMS query failed") from exc
        if result.returncode != 0:
            # Do not include stderr/stdout: either can contain SMS content.
            raise AdapterError("Termux SMS query failed; check Termux:API permission")
        try:
            payload = json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise AdapterError("Termux SMS response was not valid JSON") from exc
        if isinstance(payload, dict):
            payload = payload.get("sms", payload.get("messages"))
        if not isinstance(payload, list):
            raise AdapterError("Termux SMS response had an unsupported shape")

        records: list[SmsRecord] = []
        for row in payload:
            if not isinstance(row, dict):
                continue
            address = row.get("address") or row.get("number") or ""
            body = row.get("body")
            if body is None:
                continue
            records.append(
                SmsRecord(
                    address=str(address),
                    received=str(row.get("received", "")),
                    body=str(body),
                    sms_id=_optional(row.get("_id", row.get("id"))),
                    message_type=_optional(row.get("type")),
                )
            )
        return records


class TermuxClipboard:
    def __init__(
        self,
        get_command: str = "termux-clipboard-get",
        set_command: str = "termux-clipboard-set",
    ) -> None:
        self.get_command = get_command
        self.set_command = set_command

    def get(self) -> str:
        if not _command_exists(self.get_command):
            raise AdapterError("termux-clipboard-get is unavailable; install Termux:API")
        try:
            result = subprocess.run(
                [self.get_command], capture_output=True, text=True, timeout=15, check=False
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise AdapterError("clipboard read failed") from exc
        if result.returncode != 0:
            raise AdapterError("clipboard read failed; check Termux:API permission")
        return result.stdout

    def set(self, text: str) -> None:
        if not _command_exists(self.set_command):
            raise AdapterError("termux-clipboard-set is unavailable; install Termux:API")
        try:
            result = subprocess.run(
                [self.set_command, text], capture_output=True, text=True, timeout=15, check=False
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise AdapterError("clipboard write failed") from exc
        if result.returncode != 0:
            raise AdapterError("clipboard write failed; check Termux:API permission")


@dataclass(frozen=True)
class GlHandoffSettings:
    package: str = "dev.gl.license"
    activity: str = "dev.gl.license.presentation.MainActivity"
    rish_command: str = "rish"
    clipboard_set_command: str = "termux-clipboard-set"

    @classmethod
    def from_settings(cls, settings: dict[str, Any]) -> "GlHandoffSettings":
        raw = settings.get("gl", {})
        if not isinstance(raw, dict):
            raise AdapterError("gl settings must be an object")
        return cls(
            package=str(raw.get("package", cls.package)).strip(),
            activity=str(raw.get("activity", cls.activity)).strip(),
            rish_command=str(raw.get("rish_command", cls.rish_command)).strip(),
            clipboard_set_command=str(raw.get("clipboard_set_command", cls.clipboard_set_command)).strip(),
        )


class GlUiHandoffProvider:
    """Open GL with the encrypted request token on clipboard; generation stays manual.

    The current GL app has no identified external API. This adapter deliberately
    stops before touching payment controls, tapping Generate, or sending a message.
    Provider-specific protocol and metadata compatibility stay in this adapter,
    not in the app-agnostic engine.
    """

    _RECORDED_PRICE_TABLE = {
        "MENSUAL": PriceRule(6000, 1000),
        "SEMESTRAL": PriceRule(30000, 5000),
        "ANUAL": PriceRule(50000, 9000),
        "PERPETUA": PriceRule(90000, 17000),
    }

    def __init__(self, settings: GlHandoffSettings) -> None:
        self.settings = settings

    @classmethod
    def supports_profile(cls, profile: AppProfile) -> bool:
        return (
            profile.request_protocol == "spvi-r1"
            and profile.request_token_prefix == "SPVIR1:"
            and profile.license_code_prefix == "SPVI2:"
            and dict(profile.prices_cup) == cls._RECORDED_PRICE_TABLE
        )

    def preflight(self) -> None:
        if not _command_exists(self.settings.rish_command):
            raise AdapterError("rish is unavailable; start Shizuku and install its shell helper")
        if not _command_exists(self.settings.clipboard_set_command):
            raise AdapterError("termux-clipboard-set is unavailable; install Termux:API")
        component = f"{self.settings.package}/{self.settings.activity}"
        result = self._run_rish(f"pm path {shlex.quote(self.settings.package)}")
        if result.returncode != 0 or "package:" not in result.stdout:
            raise AdapterError("configured GL package is not installed or Shizuku cannot query it")
        if not self.settings.package or not self.settings.activity or "/" not in component:
            raise AdapterError("GL package/activity settings are incomplete")

    def handoff(self, parsed: ParsedRequest) -> None:
        clipboard = TermuxClipboard(set_command=self.settings.clipboard_set_command)
        # Only the encrypted request token replaces the full copied message, so
        # clear-text name, identity number, and phone do not remain on clipboard.
        clipboard.set(parsed.request_token)
        component = f"{self.settings.package}/{self.settings.activity}"
        shell_command = f"am start -n {shlex.quote(component)}"
        result = self._run_rish(shell_command)
        if result.returncode != 0 or "Error:" in result.stdout:
            raise AdapterError("GL launch failed; request state requires manual reconciliation")

    def _run_rish(self, shell_command: str) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                [self.settings.rish_command, "-c", shell_command],
                capture_output=True,
                text=True,
                timeout=20,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise AdapterError("Shizuku command failed") from exc


class TermuxNotifier:
    def __init__(self, command: str = "termux-notification", enabled: bool = True) -> None:
        self.command = command
        self.enabled = enabled

    def notify(self, title: str, content: str, notification_id: int = 73142) -> None:
        if not self.enabled or not _command_exists(self.command):
            return
        try:
            subprocess.run(
                [
                    self.command,
                    "--id",
                    str(notification_id),
                    "--title",
                    title,
                    "--content",
                    content,
                    "--priority",
                    "default",
                ],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            # Notifications are best-effort; failure never changes payment state.
            return


def _command_exists(command: str) -> bool:
    if os.path.sep in command:
        return os.path.isfile(command) and os.access(command, os.X_OK)
    return shutil.which(command) is not None


def _optional(value: object) -> str | None:
    if value is None:
        return None
    result = str(value).strip()
    return result or None
