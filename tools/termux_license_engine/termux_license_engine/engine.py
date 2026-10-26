"""App-independent orchestration for requests, pricing, payments, and review."""

from __future__ import annotations

from dataclasses import dataclass

from .catalog import CatalogError, resolve_profile
from .models import AppProfile, ParsedRequest, RequestRecord, SmsRecord, WorkflowState
from .parser import RequestParseError, parse_request
from .payments import parse_payment_sms
from .storage import DuplicatePayment, StateStore, StorageError


class WorkflowError(RuntimeError):
    pass


@dataclass(frozen=True)
class IngestResult:
    record: RequestRecord
    inserted: bool
    display_name: str


@dataclass(frozen=True)
class SmsScanSummary:
    messages_seen: int
    parsed: int
    verified: int
    needs_review: int
    ambiguous: int
    automatic_profiles_disabled: int


class LicenseEngine:
    def __init__(self, profiles: dict[str, AppProfile], store: StateStore) -> None:
        self.profiles = profiles
        self.store = store

    def ingest_request(self, message: str, explicit_app_id: str | None = None) -> IngestResult:
        try:
            profile = resolve_profile(message, self.profiles.values(), explicit_app_id)
        except CatalogError as exc:
            raise WorkflowError(str(exc)) from exc
        try:
            parsed = parse_request(message, profile)
        except RequestParseError as exc:
            raise WorkflowError(f"request rejected: {exc.code}") from exc

        state, review_code = self._initial_state(profile, parsed)
        record, inserted = self.store.record_request(
            request_id=parsed.request_id,
            app_id=profile.app_id,
            state=state,
            plan=parsed.plan,
            secondary_count=parsed.secondary_count,
            expected_amount_cup=parsed.expected_amount_cup,
            quoted_amount_cup=parsed.quoted_amount_cup,
            review_code=review_code,
        )
        if record.app_id != profile.app_id:
            raise WorkflowError("request digest is already associated with a different app profile")
        return IngestResult(record=record, inserted=inserted, display_name=profile.display_name)

    def bind_customer_transaction(self, request_id: str, transaction: str) -> RequestRecord:
        record = self.store.get_request(request_id)
        if not record.app_id:
            raise WorkflowError("request has no resolved app profile")
        return self.store.bind_customer_transaction(request_id, transaction)

    def verify_manual_payment(
        self,
        *,
        request_id: str,
        customer_transaction: str,
        sms_transaction: str,
        amount_cup: int,
        sender_address: str,
        operator_confirmed_sms_review: bool,
    ) -> RequestRecord:
        record = self.store.get_request(request_id)
        profile = self._profile_for(record)
        if record.state not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
            raise WorkflowError(f"payment cannot be reviewed in state {record.state.value}")
        if not profile.automatic_sms_verification_configured:
            raise WorkflowError("payment remains unverified until sender, amount, currency, and transaction rules are validated and configured")
        if not operator_confirmed_sms_review:
            raise WorkflowError("explicit manual SMS review is required")
        if profile.sms_sender_address and sender_address.strip().casefold() != profile.sms_sender_address.casefold():
            raise WorkflowError("SMS sender does not match the configured profile")
        if record.expected_amount_cup is None or amount_cup != record.expected_amount_cup:
            raise WorkflowError("amount does not match this app profile's price")
        try:
            return self.store.verify_payment(
                request_id=request_id,
                customer_transaction=customer_transaction,
                sms_transaction=sms_transaction,
                amount_cup=amount_cup,
                sender_address=sender_address,
                source="manual",
            )
        except StorageError as exc:
            raise WorkflowError(str(exc)) from exc

    def scan_sms(self, messages: list[SmsRecord]) -> SmsScanSummary:
        profiles = tuple(self.profiles.values())
        enabled = [profile for profile in profiles if profile.automatic_sms_verification_configured]
        disabled_count = len(profiles) - len(enabled)
        parsed_count = verified_count = review_count = ambiguous_count = 0

        for sms in messages:
            candidates = []
            for profile in enabled:
                result = parse_payment_sms(profile, sms, self.store.digest_secret)
                if result.observation is not None:
                    candidates.append((profile, result.observation))
            if len(candidates) > 1:
                ambiguous_count += 1
                continue
            if not candidates:
                continue

            profile, observation = candidates[0]
            parsed_count += 1
            self.store.record_sms_observation(
                event_id=observation.event_id,
                app_id=observation.app_id,
                transaction_hash=observation.transaction_hash,
                amount_cup=observation.amount_cup,
                sender_hash=observation.sender_hash,
                received=observation.received,
            )
            pending = self.store.find_pending_by_customer_tx(
                profile.app_id, observation.transaction_hash
            )
            if len(pending) > 1:
                ambiguous_count += 1
                continue
            if not pending:
                continue
            request = pending[0]
            try:
                result_record = self.store.verify_observed_payment(
                    request_id=request.request_id,
                    transaction_hash=observation.transaction_hash,
                    amount_cup=observation.amount_cup,
                    sender_hash=observation.sender_hash,
                )
            except DuplicatePayment:
                review_count += 1
                continue
            if result_record.state is WorkflowState.PAYMENT_VERIFIED:
                verified_count += 1
            else:
                review_count += 1

        return SmsScanSummary(
            messages_seen=len(messages),
            parsed=parsed_count,
            verified=verified_count,
            needs_review=review_count,
            ambiguous=ambiguous_count,
            automatic_profiles_disabled=disabled_count,
        )

    def _profile_for(self, record: RequestRecord) -> AppProfile:
        if record.app_id is None or record.app_id not in self.profiles:
            raise WorkflowError("request has no configured app profile")
        return self.profiles[record.app_id]

    @staticmethod
    def _initial_state(profile: AppProfile, parsed: ParsedRequest) -> tuple[WorkflowState, str | None]:
        if not profile.production or not profile.issuance_enabled:
            return WorkflowState.DEMO_BLOCKED, "nonproduction_profile"
        if parsed.is_recovery:
            if profile.recovery_policy == "manual_only":
                return WorkflowState.RECOVERY_MANUAL, "recovery_manual_only"
            return WorkflowState.REQUEST_REVIEW, "recovery_disabled"
        review_code = parsed.review_code
        if review_code:
            state = WorkflowState.PRICE_REVIEW if review_code.startswith("quote_") or review_code == "price_unavailable" else WorkflowState.REQUEST_REVIEW
            return state, review_code
        return WorkflowState.NEEDS_PAYMENT, None
