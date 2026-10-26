"""Parsing for incoming request text; personal fields are deliberately ignored."""

from __future__ import annotations

import hashlib
import re
from decimal import Decimal, InvalidOperation

from .catalog import normalize_for_matching
from .models import AppProfile, ParsedRequest


class RequestParseError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def extract_request_token(message: str, prefix: str) -> str:
    positions = [match.start() for match in re.finditer(re.escape(prefix), message)]
    if not positions:
        raise RequestParseError("token_missing", "required encrypted request token is missing")
    if len(positions) != 1:
        raise RequestParseError("multiple_tokens", "more than one request token was found")

    start = positions[0] + len(prefix)
    token_chars: list[str] = []
    for char in message[start:]:
        if char.isascii() and char.isalnum() or char in "-_":
            token_chars.append(char)
        elif char.isspace():
            continue
        else:
            break
    if not token_chars:
        raise RequestParseError("token_empty", "encrypted request token is empty")
    return prefix + "".join(token_chars)


def parse_cup_amount(value: str) -> int | None:
    """Parse a whole-CUP amount with common Cuban/English grouping formats.

    Fractional CUP values are rejected instead of rounded. This is only a
    parser for a displayed quote or configured SMS field, not a price source.
    """

    cleaned = re.sub(r"(?i)\b(?:CUP|MN|CUP\$)\b", "", value)
    cleaned = cleaned.replace("\u00a0", " ").strip()
    match = re.search(r"[+-]?\d[\d\s.,]*", cleaned)
    if not match:
        return None
    number = match.group(0).replace(" ", "")
    if number.startswith("-") or number.startswith("+"):
        sign, number = number[0], number[1:]
        if sign == "-":
            return None
    if not number or not any(char.isdigit() for char in number):
        return None

    decimal_separator: str | None = None
    if "," in number and "." in number:
        decimal_separator = "," if number.rfind(",") > number.rfind(".") else "."
    else:
        separators = [separator for separator in (",", ".") if separator in number]
        if separators:
            separator = separators[0]
            tail = number.rsplit(separator, 1)[1]
            # A single separator followed by three digits is a thousands group;
            # one or two trailing digits are interpreted as decimal cents.
            if len(tail) in (1, 2):
                decimal_separator = separator

    if decimal_separator is not None:
        integer_part, fractional_part = number.rsplit(decimal_separator, 1)
        fractional_digits = re.sub(r"\D", "", fractional_part)
        if fractional_digits and int(fractional_digits) != 0:
            return None
        integer_digits = re.sub(r"\D", "", integer_part)
    else:
        integer_digits = re.sub(r"\D", "", number)

    if not integer_digits:
        return None
    try:
        # Decimal guards against accidental float coercion and documents that
        # all accepted prices are integer CUP values.
        return int(Decimal(integer_digits))
    except (InvalidOperation, ValueError):
        return None


def parse_request(message: str, profile: AppProfile) -> ParsedRequest:
    """Parse only routing and non-personal fields from a full request message."""

    token = extract_request_token(message, profile.request_token_prefix)
    request_id = hashlib.sha256(token.encode("ascii")).hexdigest()
    normalized = normalize_for_matching(message)
    if profile.request_markers and not any(
        normalize_for_matching(marker) in normalized for marker in profile.request_markers
    ):
        raise RequestParseError("profile_marker_missing", "configured app marker is missing")

    is_recovery = any(
        normalize_for_matching(marker) in normalized
        for marker in profile.recovery_markers
    )

    plan_text = _line_value(message, profile.request_field_labels.get("plan", ()))
    plan = _normalize_plan(plan_text) if plan_text is not None else None
    secondary_text = _line_value(message, profile.request_field_labels.get("secondaries", ()))
    secondary_count = _parse_nonnegative_int(secondary_text)
    quoted_text = _line_value(message, profile.request_field_labels.get("price", ()))
    quoted_amount = parse_cup_amount(quoted_text) if quoted_text is not None else None
    expected = profile.expected_price(plan, secondary_count)

    review_code: str | None = None
    if not is_recovery:
        if plan is None or secondary_count is None:
            review_code = "request_fields_missing"
        elif plan not in profile.prices_cup:
            review_code = "unknown_plan"
        elif secondary_count > profile.max_secondaries:
            review_code = "secondary_count_out_of_range"
        elif expected is None:
            review_code = "price_unavailable"
        elif quoted_text is None or quoted_amount is None:
            review_code = "quote_unreadable"
        elif quoted_amount != expected:
            review_code = "quote_mismatch"

    return ParsedRequest(
        app_id=profile.app_id,
        request_id=request_id,
        request_protocol=profile.request_protocol,
        plan=plan,
        secondary_count=secondary_count,
        quoted_amount_cup=quoted_amount,
        expected_amount_cup=expected,
        is_recovery=is_recovery,
        request_token=token,
        review_code=review_code,
    )


def _line_value(message: str, labels: tuple[str, ...]) -> str | None:
    label_keys = {normalize_for_matching(label) for label in labels}
    for line in message.splitlines():
        if ":" not in line:
            continue
        left, right = line.split(":", 1)
        if normalize_for_matching(left) in label_keys:
            return right.strip()
    return None


def _normalize_plan(value: str | None) -> str | None:
    if value is None:
        return None
    return normalize_for_matching(value).replace(" ", "").upper()


def _parse_nonnegative_int(value: str | None) -> int | None:
    if value is None:
        return None
    match = re.fullmatch(r"\s*(\d+)\s*", value)
    return int(match.group(1)) if match else None
