from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from termux_license_engine.adapters import GlUiHandoffProvider
from termux_license_engine.catalog import AmbiguousApp, AppNotResolved, load_catalog, resolve_profile
from termux_license_engine.engine import LicenseEngine, WorkflowError
from termux_license_engine.models import AppProfile, PriceRule, SmsRecord, WorkflowState
from termux_license_engine.parser import parse_cup_amount, parse_request
from termux_license_engine.payments import parse_payment_sms
from termux_license_engine.storage import InvalidTransition, StateStore, StorageError


SYNTHETIC_MESSAGE = """SYNTHETIC TEST REQUEST — NOT A REAL APP
Tipo: TEST
Apps secundarias: 2
Precio: 100 CUP
TESTREQ:AbCd_123-xyz
"""


def synthetic_profile(**overrides: object) -> AppProfile:
    values: dict[str, object] = {
        "app_id": "synthetic_test",
        "display_name": "SYNTHETIC TEST ONLY",
        "production": True,
        "issuance_enabled": True,
        "request_markers": ["SYNTHETIC TEST REQUEST"],
        "request_token_prefix": "TESTREQ:",
        "request_protocol": "test-only",
        "request_field_labels": {
            "plan": ["Tipo"],
            "secondaries": ["Apps secundarias"],
            "price": ["Precio"],
        },
        "license_code_prefix": "TEST-LICENSE:",
        "prices_cup": {"TEST": {"base_cup": 80, "per_secondary_cup": 10}},
        "max_secondaries": 5,
        "recovery_policy": "manual_only",
        "provider_kind": "disabled",
        "sms_sender_address": None,
        "sms_amount_pattern": None,
        "sms_currency_pattern": None,
        "sms_transaction_pattern": None,
        "sms_rules_validated": False,
    }
    values.update(overrides)
    return AppProfile.from_mapping(values)


def synthetic_payment_profile(**overrides: object) -> AppProfile:
    values: dict[str, object] = {
        "sms_sender_address": "FAKE-SENDER",
        "sms_amount_pattern": r"Amount:\s*(?P<amount>[\d, ]+)",
        "sms_currency_pattern": r"\bCUP\b",
        "sms_transaction_pattern": r"Reference:\s*(?P<transaction>[A-Z0-9-]+)",
        "sms_rules_validated": True,
    }
    values.update(overrides)
    return synthetic_profile(**values)


def engine_with_payment_rules(store: StateStore) -> LicenseEngine:
    profile = synthetic_payment_profile()
    return LicenseEngine({profile.app_id: profile}, store)


class TermuxLicenseEngineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp.name) / "private" / "state.sqlite3"
        self.store = StateStore(self.db_path)
        self.profile = synthetic_profile()
        self.engine = LicenseEngine({self.profile.app_id: self.profile}, self.store)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_catalog_includes_real_profile_and_nonproduction_demo(self) -> None:
        profiles = load_catalog()
        shalev = profiles["shalev"]
        self.assertEqual(shalev.expected_price("MENSUAL", 2), 8000)
        self.assertEqual(shalev.expected_price("SEMESTRAL", 1), 35000)
        self.assertFalse(shalev.automatic_sms_verification_configured)
        demo = profiles["demo_synthetic_nonproduction"]
        self.assertFalse(demo.production)
        self.assertFalse(demo.issuance_enabled)

    def test_shared_provider_blocks_protocol_or_price_table_mismatch(self) -> None:
        shalev = load_catalog()["shalev"]
        self.assertTrue(GlUiHandoffProvider.supports_profile(shalev))
        changed = dict(shalev.prices_cup)
        changed["MENSUAL"] = PriceRule(6100, 1000)
        self.assertFalse(GlUiHandoffProvider.supports_profile(replace(shalev, prices_cup=changed)))
        self.assertFalse(
            GlUiHandoffProvider.supports_profile(replace(shalev, request_protocol="unsupported"))
        )

    def test_price_parser_accepts_grouping_but_rejects_fractional_cup(self) -> None:
        self.assertEqual(parse_cup_amount("8 000 CUP"), 8000)
        self.assertEqual(parse_cup_amount("8,000.00 CUP"), 8000)
        self.assertEqual(parse_cup_amount("8.000,00 CUP"), 8000)
        self.assertIsNone(parse_cup_amount("8,000.50 CUP"))

    def test_full_shalev_message_requires_token_and_uses_profile_prices(self) -> None:
        profile = load_catalog()["shalev"]
        # The payload below is a structural fixture only; its token is not valid crypto.
        message = """Solicitud de licencia Shalev
Tipo: Mensual
Apps secundarias: 2
Precio: 8 000 CUP
SPVIR1:AbCd_123-xyz
"""
        selected = resolve_profile(message, [profile])
        parsed = parse_request(message, selected)
        self.assertEqual(parsed.app_id, "shalev")
        self.assertEqual(parsed.expected_amount_cup, 8000)
        self.assertEqual(parsed.quoted_amount_cup, 8000)
        self.assertEqual(len(parsed.request_id), 64)
        self.assertNotIn("AbCd_123", repr(parsed))

    def test_visible_fields_do_not_replace_encrypted_token(self) -> None:
        profile = load_catalog()["shalev"]
        message = "Solicitud de licencia Shalev\nTipo: Mensual\nApps secundarias: 0\nPrecio: 6 000 CUP"
        with self.assertRaises(AppNotResolved):
            resolve_profile(message, [profile])

    def test_mismatched_visible_quote_is_held_for_review(self) -> None:
        profiles = load_catalog()
        engine = LicenseEngine(profiles, self.store)
        message = """Solicitud de licencia Shalev
Tipo: Mensual
Apps secundarias: 2
Precio: 8 001 CUP
SPVIR1:QuoteMismatchToken_123
"""
        result = engine.ingest_request(message)
        self.assertEqual(result.record.state, WorkflowState.PRICE_REVIEW)
        self.assertEqual(result.record.expected_amount_cup, 8000)
        self.assertEqual(result.record.quoted_amount_cup, 8001)

    def test_recovery_routes_to_manual_review(self) -> None:
        profiles = load_catalog()
        engine = LicenseEngine(profiles, self.store)
        message = """Recuperar licencia Shalev
Licencia anterior: fake-license-id-for-test
SPVIR1:RecoveryToken_123
"""
        result = engine.ingest_request(message)
        self.assertEqual(result.record.state, WorkflowState.RECOVERY_MANUAL)
        self.assertEqual(result.record.review_code, "recovery_manual_only")

    def test_demo_profile_is_never_issuable(self) -> None:
        demo = load_catalog()["demo_synthetic_nonproduction"]
        engine = LicenseEngine({demo.app_id: demo}, self.store)
        message = "DEMO FICTICIO - NO PRODUCTIVO\nDEMO-REQUEST:synthetic-value"
        result = engine.ingest_request(message)
        self.assertEqual(result.record.state, WorkflowState.DEMO_BLOCKED)

    def test_ambiguous_routing_requires_an_explicit_profile(self) -> None:
        duplicate = synthetic_profile(app_id="synthetic_test_two")
        with self.assertRaises(AmbiguousApp):
            resolve_profile(SYNTHETIC_MESSAGE, [self.profile, duplicate])
        selected = resolve_profile(SYNTHETIC_MESSAGE, [self.profile, duplicate], "synthetic_test_two")
        self.assertEqual(selected.app_id, "synthetic_test_two")

    def test_ingestion_is_idempotent_and_database_does_not_store_message_or_pii(self) -> None:
        message = SYNTHETIC_MESSAGE + "Nombre: SYNTHETIC_PERSONAL_MARKER\nTeléfono: SYNTHETIC_PHONE_MARKER\n"
        first = self.engine.ingest_request(message)
        second = self.engine.ingest_request(message)
        self.assertTrue(first.inserted)
        self.assertFalse(second.inserted)
        raw = b"".join(
            path.read_bytes()
            for path in self.db_path.parent.glob("state.sqlite3*")
            if path.is_file()
        )
        self.assertNotIn(b"SYNTHETIC_PERSONAL_MARKER", raw)
        self.assertNotIn(b"SYNTHETIC_PHONE_MARKER", raw)
        self.assertNotIn(message.encode(), raw)

    def test_missing_hmac_key_fails_closed_for_an_existing_database(self) -> None:
        key_path = Path(f"{self.db_path}.hmac-key")
        key_path.unlink()
        with self.assertRaises(StorageError):
            StateStore(self.db_path)

    def test_manual_payment_is_not_verified_until_sms_rules_are_configured(self) -> None:
        request = self.engine.ingest_request(SYNTHETIC_MESSAGE).record
        with self.assertRaises(WorkflowError):
            self.engine.verify_manual_payment(
                request_id=request.request_id,
                customer_transaction="TX-FAKE-001",
                sms_transaction="TX-FAKE-001",
                amount_cup=100,
                sender_address="FAKE-SENDER",
                operator_confirmed_sms_review=True,
            )
        self.assertEqual(self.store.get_request(request.request_id).state, WorkflowState.NEEDS_PAYMENT)

    def test_wrong_price_or_transaction_cannot_verify_payment(self) -> None:
        engine = engine_with_payment_rules(self.store)
        request = engine.ingest_request(SYNTHETIC_MESSAGE).record
        with self.assertRaises(WorkflowError):
            engine.verify_manual_payment(
                request_id=request.request_id,
                customer_transaction="TX-FAKE-001",
                sms_transaction="TX-FAKE-001",
                amount_cup=99,
                sender_address="FAKE-SENDER",
                operator_confirmed_sms_review=True,
            )
        with self.assertRaises(WorkflowError):
            engine.verify_manual_payment(
                request_id=request.request_id,
                customer_transaction="TX-FAKE-001",
                sms_transaction="TX-FAKE-002",
                amount_cup=100,
                sender_address="FAKE-SENDER",
                operator_confirmed_sms_review=True,
            )
        self.assertEqual(self.store.get_request(request.request_id).state, WorkflowState.NEEDS_PAYMENT)

    def test_duplicate_payment_transaction_is_blocked_across_requests(self) -> None:
        engine = engine_with_payment_rules(self.store)
        first = engine.ingest_request(SYNTHETIC_MESSAGE).record
        verified = engine.verify_manual_payment(
            request_id=first.request_id,
            customer_transaction="TX-FAKE-REUSED",
            sms_transaction="TX-FAKE-REUSED",
            amount_cup=100,
            sender_address="FAKE-SENDER",
            operator_confirmed_sms_review=True,
        )
        self.assertEqual(verified.state, WorkflowState.PAYMENT_VERIFIED)

        second_message = SYNTHETIC_MESSAGE.replace("AbCd_123-xyz", "OtherToken_123-xyz")
        second = engine.ingest_request(second_message).record
        with self.assertRaises(WorkflowError):
            engine.verify_manual_payment(
                request_id=second.request_id,
                customer_transaction="TX-FAKE-REUSED",
                sms_transaction="TX-FAKE-REUSED",
                amount_cup=100,
                sender_address="FAKE-SENDER",
                operator_confirmed_sms_review=True,
            )

    def test_auto_sms_rules_are_required_and_matching_is_idempotent(self) -> None:
        configured = synthetic_profile(
            sms_sender_address="FAKE-BANK",
            sms_amount_pattern=r"Amount:\s*(?P<amount>[\d, ]+)",
            sms_currency_pattern=r"\bCUP\b",
            sms_transaction_pattern=r"Reference:\s*(?P<transaction>[A-Z0-9-]+)",
            sms_rules_validated=True,
        )
        engine = LicenseEngine({configured.app_id: configured}, self.store)
        request = engine.ingest_request(SYNTHETIC_MESSAGE).record
        with self.assertRaises(WorkflowError):
            engine.verify_manual_payment(
                request_id=request.request_id,
                customer_transaction="TX-FAKE-003",
                sms_transaction="TX-FAKE-003",
                amount_cup=100,
                sender_address="FAKE-BANK",
                operator_confirmed_sms_review=False,
            )
        engine.bind_customer_transaction(request.request_id, "TX-FAKE-003")
        sms = SmsRecord(
            address="FAKE-BANK",
            received="synthetic-time",
            body="SYNTHETIC TEST SMS Amount: 100 CUP Reference: TX-FAKE-003",
            sms_id="synthetic-sms-id",
        )
        parsed = parse_payment_sms(configured, sms)
        self.assertEqual(parsed.code, "parsed")
        self.assertNotIn("TX-FAKE-003", repr(parsed))
        self.assertNotIn("FAKE-BANK", repr(sms))
        self.assertNotIn("synthetic-time", repr(sms))
        self.assertNotIn("synthetic-sms-id", repr(sms))
        summary = engine.scan_sms([sms])
        self.assertEqual(summary.verified, 1)
        self.assertEqual(self.store.get_request(request.request_id).state, WorkflowState.PAYMENT_VERIFIED)
        stored_bytes = b"".join(
            path.read_bytes()
            for path in self.db_path.parent.glob("state.sqlite3*")
            if path.is_file()
        )
        self.assertNotIn(sms.body.encode(), stored_bytes)
        self.assertNotIn(b"TX-FAKE-003", stored_bytes)
        self.assertNotIn(b"FAKE-BANK", stored_bytes)
        duplicate_summary = engine.scan_sms([sms])
        self.assertEqual(duplicate_summary.verified, 0)

    def test_unconfigured_sms_profile_does_not_parse_any_message(self) -> None:
        sms = SmsRecord(address="UNKNOWN", received="synthetic", body="SYNTHETIC SMS")
        result = parse_payment_sms(self.profile, sms)
        self.assertIsNone(result.observation)
        self.assertEqual(result.code, "sms_parser_not_configured")
        summary = self.engine.scan_sms([sms])
        self.assertEqual(summary.parsed, 0)
        self.assertEqual(summary.automatic_profiles_disabled, 1)

    def test_sms_rules_without_explicit_validation_flag_remain_disabled(self) -> None:
        unvalidated = synthetic_profile(
            sms_sender_address="FAKE-BANK",
            sms_amount_pattern=r"Amount:\s*(?P<amount>[\d, ]+)",
            sms_currency_pattern=r"\bCUP\b",
            sms_transaction_pattern=r"Reference:\s*(?P<transaction>[A-Z0-9-]+)",
        )
        self.assertFalse(unvalidated.automatic_sms_verification_configured)
        engine = LicenseEngine({unvalidated.app_id: unvalidated}, self.store)
        request = engine.ingest_request(SYNTHETIC_MESSAGE).record
        with self.assertRaises(WorkflowError):
            engine.verify_manual_payment(
                request_id=request.request_id,
                customer_transaction="TX-FAKE-006",
                sms_transaction="TX-FAKE-006",
                amount_cup=100,
                sender_address="FAKE-BANK",
                operator_confirmed_sms_review=True,
            )

    def test_uncertain_provider_handoff_cannot_be_retried_without_manual_reconciliation(self) -> None:
        engine = engine_with_payment_rules(self.store)
        request = engine.ingest_request(SYNTHETIC_MESSAGE).record
        verified = engine.verify_manual_payment(
            request_id=request.request_id,
            customer_transaction="TX-FAKE-004",
            sms_transaction="TX-FAKE-004",
            amount_cup=100,
            sender_address="FAKE-SENDER",
            operator_confirmed_sms_review=True,
        )
        self.store.begin_provider_attempt(verified.request_id)
        self.store.mark_provider_uncertain(verified.request_id)
        with self.assertRaises(StorageError):
            self.store.reconcile_provider_result(verified.request_id, generated=False, registry_checked=False)
        with self.assertRaises(InvalidTransition):
            self.store.begin_provider_attempt(verified.request_id)
        reconciled = self.store.reconcile_provider_result(
            verified.request_id, generated=False, registry_checked=True
        )
        self.assertEqual(reconciled.state, WorkflowState.PAYMENT_VERIFIED)
        self.assertEqual(reconciled.provider_attempt_count, 1)

    def test_completion_requires_manual_send_and_registration(self) -> None:
        engine = engine_with_payment_rules(self.store)
        request = engine.ingest_request(SYNTHETIC_MESSAGE).record
        verified = engine.verify_manual_payment(
            request_id=request.request_id,
            customer_transaction="TX-FAKE-005",
            sms_transaction="TX-FAKE-005",
            amount_cup=100,
            sender_address="FAKE-SENDER",
            operator_confirmed_sms_review=True,
        )
        self.store.begin_provider_attempt(verified.request_id)
        self.store.reconcile_provider_result(verified.request_id, generated=True, registry_checked=True)
        with self.assertRaises(StorageError):
            self.store.complete_manual_delivery(
                verified.request_id, confirmed_sent=True, confirmed_registered=False
            )
        completed = self.store.complete_manual_delivery(
            verified.request_id, confirmed_sent=True, confirmed_registered=True
        )
        self.assertEqual(completed.state, WorkflowState.COMPLETED)


if __name__ == "__main__":
    unittest.main()
