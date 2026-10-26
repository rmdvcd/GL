"""App-agnostic domain types for the Termux licensing engine.

App-specific behavior is supplied by data-only profiles; provider adapters live
outside this domain module.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import re
from typing import Mapping


class WorkflowState(str, Enum):
    APP_REVIEW = "app_review"
    REQUEST_REVIEW = "request_review"
    RECOVERY_MANUAL = "recovery_manual"
    PRICE_REVIEW = "price_review"
    NEEDS_PAYMENT = "needs_payment"
    PAYMENT_REVIEW = "payment_review"
    PAYMENT_VERIFIED = "payment_verified"
    PROVIDER_IN_PROGRESS = "provider_in_progress"
    PROVIDER_UNCERTAIN = "provider_uncertain"
    AWAITING_MANUAL_DELIVERY = "awaiting_manual_delivery"
    COMPLETED = "completed"
    DEMO_BLOCKED = "demo_blocked"


@dataclass(frozen=True)
class PriceRule:
    base_cup: int
    per_secondary_cup: int

    def __post_init__(self) -> None:
        if type(self.base_cup) is not int or type(self.per_secondary_cup) is not int:
            raise ValueError("CUP prices must be whole integers")
        if self.base_cup < 0 or self.per_secondary_cup < 0:
            raise ValueError("CUP prices must be non-negative integers")

    def total(self, secondary_count: int) -> int:
        if secondary_count < 0:
            raise ValueError("secondary_count must be non-negative")
        return self.base_cup + self.per_secondary_cup * secondary_count


@dataclass(frozen=True)
class AppProfile:
    """Data-only profile used to route and price an app's requests."""

    app_id: str
    display_name: str
    request_markers: tuple[str, ...]
    request_token_prefix: str
    request_protocol: str
    license_code_prefix: str
    prices_cup: Mapping[str, PriceRule]
    max_secondaries: int
    production: bool
    issuance_enabled: bool
    recovery_policy: str
    request_field_labels: Mapping[str, tuple[str, ...]] = field(default_factory=dict)
    recovery_markers: tuple[str, ...] = ()
    consumer_package: str | None = None
    consumer_bundle_id: str | None = None
    provider_kind: str = "manual"
    sms_sender_address: str | None = None
    sms_amount_pattern: str | None = None
    sms_currency_pattern: str | None = None
    sms_transaction_pattern: str | None = None
    sms_rules_validated: bool = False
    activation_instructions: str = ""
    notes: str = ""

    @classmethod
    def from_mapping(cls, data: Mapping[str, object]) -> "AppProfile":
        try:
            raw_prices = data.get("prices_cup", {})
            if not isinstance(raw_prices, Mapping):
                raise ValueError("prices_cup must be an object")
            prices: dict[str, PriceRule] = {}
            for plan, values in raw_prices.items():
                if not isinstance(values, Mapping):
                    raise ValueError(f"price entry for {plan!r} must be an object")
                base = values.get("base_cup")
                extra = values.get("per_secondary_cup", 0)
                if type(base) is not int or type(extra) is not int:
                    raise ValueError(f"prices for {plan!r} must be whole CUP integers")
                prices[str(plan).strip().upper()] = PriceRule(base, extra)

            request_markers = data.get("request_markers", ())
            recovery_markers = data.get("recovery_markers", ())
            field_labels = data.get("request_field_labels", {})
            if not isinstance(request_markers, (list, tuple)):
                raise ValueError("request_markers must be a list")
            if not isinstance(recovery_markers, (list, tuple)):
                raise ValueError("recovery_markers must be a list")
            if not isinstance(field_labels, Mapping):
                raise ValueError("request_field_labels must be an object")
            labels: dict[str, tuple[str, ...]] = {}
            for name, label_values in field_labels.items():
                if not isinstance(label_values, (list, tuple)):
                    raise ValueError(f"request field labels for {name!r} must be a list")
                labels[str(name)] = tuple(str(label).strip() for label in label_values if str(label).strip())

            max_secondaries = data.get("max_secondaries", 0)
            if type(max_secondaries) is not int:
                raise ValueError("max_secondaries must be an integer")
            production = _strict_bool(data, "production", True)
            issuance_enabled = _strict_bool(data, "issuance_enabled", True)

            profile = cls(
                app_id=str(data["app_id"]).strip(),
                display_name=str(data["display_name"]).strip(),
                request_markers=tuple(str(item).strip() for item in request_markers if str(item).strip()),
                request_token_prefix=str(data["request_token_prefix"]),
                request_protocol=str(data.get("request_protocol", "unknown")),
                license_code_prefix=str(data.get("license_code_prefix", "")),
                prices_cup=prices,
                max_secondaries=max_secondaries,
                production=production,
                issuance_enabled=issuance_enabled,
                recovery_policy=str(data.get("recovery_policy", "manual_only")),
                request_field_labels=labels,
                recovery_markers=tuple(str(item).strip() for item in recovery_markers if str(item).strip()),
                consumer_package=_optional_string(data.get("consumer_package")),
                consumer_bundle_id=_optional_string(data.get("consumer_bundle_id")),
                provider_kind=str(data.get("provider_kind", "manual")),
                sms_sender_address=_optional_string(data.get("sms_sender_address")),
                sms_amount_pattern=_optional_string(data.get("sms_amount_pattern")),
                sms_currency_pattern=_optional_string(data.get("sms_currency_pattern")),
                sms_transaction_pattern=_optional_string(data.get("sms_transaction_pattern")),
                sms_rules_validated=_strict_bool(data, "sms_rules_validated", False),
                activation_instructions=str(data.get("activation_instructions", "")),
                notes=str(data.get("notes", "")),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(f"invalid app profile: {exc}") from exc

        if not profile.app_id:
            raise ValueError("app_id must not be empty")
        if not profile.display_name:
            raise ValueError("display_name must not be empty")
        if profile.production and not profile.request_markers:
            raise ValueError(f"production profile {profile.app_id!r} needs request_markers")
        if not profile.request_token_prefix:
            raise ValueError(f"profile {profile.app_id!r} needs request_token_prefix")
        if profile.production and not profile.license_code_prefix:
            raise ValueError(f"profile {profile.app_id!r} needs license_code_prefix")
        if profile.production and profile.request_protocol == "unknown":
            raise ValueError(f"profile {profile.app_id!r} needs a verified request_protocol")
        if profile.production and not profile.prices_cup:
            raise ValueError(f"production profile {profile.app_id!r} needs a price table")
        if profile.production and not all(profile.request_field_labels.get(key) for key in ("plan", "secondaries", "price")):
            raise ValueError(f"production profile {profile.app_id!r} needs request field labels")
        if profile.max_secondaries < 0:
            raise ValueError("max_secondaries must be non-negative")
        if profile.recovery_policy not in {"manual_only", "disabled"}:
            raise ValueError("recovery_policy must be manual_only or disabled")

        sms_values = (
            profile.sms_sender_address,
            profile.sms_amount_pattern,
            profile.sms_currency_pattern,
            profile.sms_transaction_pattern,
        )
        if any(sms_values) and not all(sms_values):
            raise ValueError("automatic SMS verification needs a sender and amount, currency, and transaction rules")
        if profile.sms_rules_validated and not all(sms_values):
            raise ValueError("sms_rules_validated requires a complete SMS parser configuration")
        try:
            for pattern in sms_values[1:]:
                if pattern:
                    re.compile(pattern)
        except re.error as exc:
            raise ValueError("SMS profile contains an invalid regular expression") from exc
        return profile

    @property
    def automatic_sms_verification_configured(self) -> bool:
        return self.sms_rules_validated and all((
            self.sms_sender_address,
            self.sms_amount_pattern,
            self.sms_currency_pattern,
            self.sms_transaction_pattern,
        ))

    def expected_price(self, plan: str | None, secondary_count: int | None) -> int | None:
        if plan is None or secondary_count is None or secondary_count < 0:
            return None
        rule = self.prices_cup.get(plan.strip().upper())
        if rule is None or secondary_count > self.max_secondaries:
            return None
        return rule.total(secondary_count)


@dataclass(frozen=True)
class ParsedRequest:
    app_id: str
    request_id: str
    request_protocol: str
    plan: str | None
    secondary_count: int | None
    quoted_amount_cup: int | None
    expected_amount_cup: int | None
    is_recovery: bool
    request_token: str = field(repr=False)
    review_code: str | None = None


@dataclass(frozen=True)
class SmsRecord:
    """SMS payload and metadata exist in memory only; repr hides every raw field."""

    address: str = field(repr=False)
    received: str = field(repr=False)
    body: str = field(repr=False)
    sms_id: str | None = field(default=None, repr=False)
    message_type: str | None = field(default=None, repr=False)


@dataclass(frozen=True)
class PaymentObservation:
    app_id: str
    event_id: str
    transaction_hash: str
    amount_cup: int
    received: str
    sender_hash: str


@dataclass(frozen=True)
class RequestRecord:
    request_id: str
    app_id: str | None
    state: WorkflowState
    plan: str | None
    secondary_count: int | None
    expected_amount_cup: int | None
    quoted_amount_cup: int | None
    review_code: str | None
    created_at: str
    updated_at: str
    provider_attempt_count: int


def _strict_bool(data: Mapping[str, object], key: str, default: bool) -> bool:
    value = data.get(key, default)
    if not isinstance(value, bool):
        raise ValueError(f"{key} must be a boolean")
    return value


def _optional_string(value: object) -> str | None:
    if value is None:
        return None
    result = str(value).strip()
    return result or None
