"""SMS observation parsing and conservative payment verification."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import Callable

from .models import AppProfile, PaymentObservation, SmsRecord
from .parser import parse_cup_amount

DigestFunction = Callable[[str], str]


@dataclass(frozen=True)
class SmsParseResult:
    observation: PaymentObservation | None
    code: str


def parse_payment_sms(
    profile: AppProfile,
    sms: SmsRecord,
    digest_secret: DigestFunction | None = None,
) -> SmsParseResult:
    """Parse a payment SMS only when the profile has validated rules.

    The returned object contains hashes and parsed non-personal values only.
    The SMS body is never copied into an exception, log record, or database row.
    """

    if not profile.automatic_sms_verification_configured:
        return SmsParseResult(None, "sms_parser_not_configured")
    if sms.address.strip().casefold() != (profile.sms_sender_address or "").strip().casefold():
        return SmsParseResult(None, "sender_mismatch")

    try:
        amount_match = re.search(profile.sms_amount_pattern or "", sms.body, re.IGNORECASE)
        currency_match = re.search(profile.sms_currency_pattern or "", sms.body, re.IGNORECASE)
        transaction_match = re.search(profile.sms_transaction_pattern or "", sms.body, re.IGNORECASE)
    except re.error:
        return SmsParseResult(None, "sms_parser_invalid")
    if amount_match is None or transaction_match is None:
        return SmsParseResult(None, "sms_format_mismatch")
    if currency_match is None:
        return SmsParseResult(None, "sms_currency_unverified")

    amount_text = _captured_value(amount_match, "amount")
    transaction = _captured_value(transaction_match, "transaction")
    if amount_text is None or transaction is None:
        return SmsParseResult(None, "sms_capture_missing")
    amount = parse_cup_amount(amount_text)
    transaction = transaction.strip()
    if amount is None or not transaction:
        return SmsParseResult(None, "sms_value_invalid")

    digest = digest_secret or (lambda value: hashlib.sha256(value.strip().encode("utf-8")).hexdigest())
    body_hash = digest(sms.body)
    source_identity = sms.sms_id.strip() if sms.sms_id else body_hash
    event_id = digest("\0".join((sms.address.strip(), sms.received, source_identity)))
    observation = PaymentObservation(
        app_id=profile.app_id,
        event_id=event_id,
        transaction_hash=digest(transaction),
        amount_cup=amount,
        received=sms.received,
        sender_hash=digest(sms.address.strip()),
    )
    return SmsParseResult(observation, "parsed")


def _captured_value(match: re.Match[str], group_name: str) -> str | None:
    if group_name in match.groupdict():
        return match.group(group_name)
    if match.lastindex:
        return match.group(1)
    return match.group(0)
