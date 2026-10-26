"""Command-line entry points for Termux operation."""

from __future__ import annotations

import argparse
import getpass
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Any

from .adapters import (
    AdapterError,
    GlHandoffSettings,
    GlUiHandoffProvider,
    TermuxClipboard,
    TermuxNotifier,
    TermuxSmsClient,
)
from .catalog import CatalogError, load_catalog
from .config import database_path, load_settings, profiles_path, settings_path
from .engine import LicenseEngine, WorkflowError
from .models import AppProfile, RequestRecord, WorkflowState
from .parser import RequestParseError, parse_request
from .storage import InvalidTransition, StateStore, StorageError


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            return _command_init(args)
        settings, profiles, store, engine = _runtime(args)
        if args.command == "profiles":
            return _command_profiles(profiles)
        if args.command == "ingest":
            return _command_ingest(args, engine, _notifier(settings))
        if args.command == "list":
            return _command_list(store, args.limit)
        if args.command == "status":
            return _command_status(store, args.request_id)
        if args.command == "bind-customer-tx":
            return _command_bind_customer_tx(store, engine, args.request_id)
        if args.command == "review-payment":
            return _command_review_payment(store, engine, args.request_id)
        if args.command == "scan-sms":
            return _command_scan_sms(engine, args.limit, _notifier(settings))
        if args.command == "handoff":
            return _command_handoff(args, profiles, store, settings)
        if args.command == "reconcile":
            return _command_reconcile(args, store)
        if args.command == "complete":
            return _command_complete(args, store)
        if args.command == "daemon":
            return _command_daemon(engine, settings, args.interval)
        parser.error("unknown command")
    except (AdapterError, CatalogError, WorkflowError, StorageError, InvalidTransition, RequestParseError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Stopped.", file=sys.stderr)
        return 130
    except Exception:
        # Keep SMS bodies, requests, and personal data out of tracebacks/logs.
        print("ERROR: unexpected internal failure; no request or SMS content was logged", file=sys.stderr)
        return 1


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="license-engine",
        description="Privacy-minimizing, profile-driven Termux license workflow.",
    )
    parser.add_argument("--config", type=Path, help="settings JSON path")
    parser.add_argument("--profiles", type=Path, help="app profiles JSON path")
    parser.add_argument("--db", type=Path, help="SQLite state path")
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("init", help="create private local settings, catalog, and database")
    commands.add_parser("profiles", help="list configured app profiles")

    ingest = commands.add_parser("ingest", help="ingest a full request from clipboard or stdin")
    source = ingest.add_mutually_exclusive_group(required=True)
    source.add_argument("--clipboard", action="store_true", help="read the current Termux clipboard")
    source.add_argument("--stdin", action="store_true", help="read request text from stdin")
    ingest.add_argument("--app-id", help="explicit app choice if routing is ambiguous")

    listing = commands.add_parser("list", help="list request state without personal data")
    listing.add_argument("--limit", type=int, default=50)
    status = commands.add_parser("status", help="show a redacted request status")
    status.add_argument("request_id")

    bind = commands.add_parser("bind-customer-tx", help="attach a customer transaction reference privately")
    bind.add_argument("request_id")
    review = commands.add_parser("review-payment", help="manually verify a payment against the profile price")
    review.add_argument("request_id")

    scan = commands.add_parser("scan-sms", help="scan Termux SMS using only configured profile rules")
    scan.add_argument("--limit", type=int, default=50)

    handoff = commands.add_parser("handoff", help="open GL with the encrypted request token; no auto-send")
    handoff.add_argument("request_id")
    handoff.add_argument("--confirm", action="store_true", help="confirm payment and request were reviewed")

    reconcile = commands.add_parser("reconcile", help="manually reconcile an uncertain GL handoff")
    reconcile.add_argument("request_id")
    result = reconcile.add_mutually_exclusive_group(required=True)
    result.add_argument("--generated", action="store_true")
    result.add_argument("--not-generated", action="store_true")
    reconcile.add_argument("--registry-checked", action="store_true", help="confirm GL registry was checked first")

    complete = commands.add_parser("complete", help="record manually sent and registered license")
    complete.add_argument("request_id")
    complete.add_argument("--sent", action="store_true", help="confirm you manually sent the prefilled message")
    complete.add_argument("--registered", action="store_true", help="confirm GL registration was completed")

    daemon = commands.add_parser("daemon", help="run the optional SMS watcher (never issues licenses)")
    daemon.add_argument("--interval", type=int, help="poll interval in seconds")
    return parser


def _runtime(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, AppProfile], StateStore, LicenseEngine]:
    settings = load_settings(args.config)
    profile_source = args.profiles
    if profile_source is None and args.config is not None:
        adjacent_profiles = args.config.expanduser().parent / "profiles.json"
        if adjacent_profiles.exists():
            profile_source = adjacent_profiles
    profiles = load_catalog(profile_source or profiles_path())
    configured_db = Path(args.db).expanduser() if args.db else database_path(settings)
    if os.environ.get("LICENSE_ENGINE_DB") and not args.db:
        configured_db = Path(os.environ["LICENSE_ENGINE_DB"]).expanduser()
    store = StateStore(configured_db)
    return settings, profiles, store, LicenseEngine(profiles, store)


def _command_init(args: argparse.Namespace) -> int:
    target_settings = (args.config or settings_path()).expanduser()
    env_profiles = os.environ.get("LICENSE_ENGINE_PROFILES")
    target_profiles = (args.profiles or (Path(env_profiles) if env_profiles else target_settings.parent / "profiles.json")).expanduser()
    for target in (target_settings, target_profiles):
        parent_existed = target.parent.exists()
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not parent_existed:
            try:
                os.chmod(target.parent, 0o700)
            except OSError:
                pass
    package_dir = Path(__file__).parent
    if not target_settings.exists():
        shutil.copyfile(package_dir / "settings.example.json", target_settings)
        try:
            os.chmod(target_settings, 0o600)
        except OSError:
            pass
    if not target_profiles.exists():
        shutil.copyfile(package_dir / "profiles.json", target_profiles)
        try:
            os.chmod(target_profiles, 0o600)
        except OSError:
            pass
    settings = load_settings(target_settings)
    db_path = Path(args.db).expanduser() if args.db else database_path(settings)
    StateStore(db_path)
    print("Initialized private engine state.")
    print(f"Settings: {target_settings}")
    print(f"Profiles: {target_profiles}")
    print(f"Database: {db_path}")
    print("Payment verification remains blocked until real sender, amount, currency, and transaction rules are validated and configured.")
    return 0


def _command_profiles(profiles: dict[str, AppProfile]) -> int:
    for profile in sorted(profiles.values(), key=lambda item: item.app_id):
        mode = "production" if profile.production and profile.issuance_enabled else "non-production/disabled"
        sms = "rules validated" if profile.automatic_sms_verification_configured else "blocked (rules missing/unvalidated)"
        print(f"{profile.app_id}\t{profile.display_name}\t{mode}\tSMS: {sms}")
    return 0


def _command_ingest(args: argparse.Namespace, engine: LicenseEngine, notifier: TermuxNotifier) -> int:
    text = TermuxClipboard().get() if args.clipboard else sys.stdin.read()
    if not text.strip():
        raise WorkflowError("request input is empty")
    result = engine.ingest_request(text, args.app_id)
    print(_format_record(result.record, result.display_name))
    print("Input text and personal fields were not stored or printed.")
    if not result.inserted:
        print("Duplicate request: existing workflow state was preserved.")
    elif result.record.state is WorkflowState.NEEDS_PAYMENT:
        notifier.notify("New license request", "Request is ready for payment review.", 73144)
    elif result.record.state is WorkflowState.RECOVERY_MANUAL:
        notifier.notify("Recovery needs review", "Recovery requests are not automated.", 73145)
    else:
        notifier.notify("Request needs review", "The request is held for manual review.", 73146)
    return 0


def _command_list(store: StateStore, limit: int) -> int:
    if not 1 <= limit <= 500:
        raise WorkflowError("limit must be between 1 and 500")
    for record in store.list_requests(limit):
        print(_format_record(record, record.app_id or "unresolved"))
    return 0


def _command_status(store: StateStore, request_id: str) -> int:
    record = store.get_request(request_id)
    print(_format_record(record, record.app_id or "unresolved"))
    print(f"GL handoff attempts: {record.provider_attempt_count}")
    return 0


def _command_bind_customer_tx(store: StateStore, engine: LicenseEngine, request_id: str) -> int:
    record = store.get_request(request_id)
    if record.state not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
        raise WorkflowError(f"customer transaction cannot be attached in state {record.state.value}")
    print("Enter the transaction reference supplied separately by the customer.")
    transaction = getpass.getpass("Customer transaction reference (hidden): ")
    record = engine.bind_customer_transaction(request_id, transaction)
    print(f"Customer transaction digest attached; state={record.state.value}.")
    print("The reference itself was not stored or printed.")
    return 0


def _command_review_payment(store: StateStore, engine: LicenseEngine, request_id: str) -> int:
    record = store.get_request(request_id)
    if record.state not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
        raise WorkflowError(f"payment cannot be reviewed in state {record.state.value}")
    if not record.app_id or record.app_id not in engine.profiles:
        raise WorkflowError("request has no configured app profile")
    profile = engine.profiles[record.app_id]
    if not profile.automatic_sms_verification_configured:
        raise WorkflowError("payment remains unverified until sender, amount, currency, and transaction rules are validated and configured")
    print(f"Expected profile price: {record.expected_amount_cup} CUP")
    print("Check the original SMS in the phone's inbox. Do not use 4,500 CUP from the reference screenshot as a tariff.")
    customer_tx = getpass.getpass("Customer transaction reference (hidden): ")
    sms_tx = getpass.getpass("SMS transaction reference (hidden): ")
    sender = getpass.getpass("Exact SMS sender address (hidden): ")
    amount_text = input("Amount shown in the SMS, whole CUP: ").strip()
    if not amount_text.isdigit():
        raise WorkflowError("amount must be a whole number of CUP")
    confirmation = input("Have you verified the sender, transaction, and amount in the actual inbox SMS? Type YES: ")
    if confirmation != "YES":
        raise WorkflowError("manual SMS verification was not confirmed")
    verified = engine.verify_manual_payment(
        request_id=request_id,
        customer_transaction=customer_tx,
        sms_transaction=sms_tx,
        amount_cup=int(amount_text),
        sender_address=sender,
        operator_confirmed_sms_review=True,
    )
    print(f"Payment marked verified by operator; state={verified.state.value}.")
    print("Transaction references, sender, and SMS body were not stored in clear text or printed.")
    return 0


def _command_scan_sms(engine: LicenseEngine, limit: int, notifier: TermuxNotifier) -> int:
    if not 1 <= limit <= 1000:
        raise WorkflowError("SMS limit must be between 1 and 1000")
    messages = TermuxSmsClient().list_inbox(limit)
    summary = engine.scan_sms(messages)
    print(
        "SMS scan complete: "
        f"seen={summary.messages_seen} parsed={summary.parsed} verified={summary.verified} "
        f"review={summary.needs_review} ambiguous={summary.ambiguous} "
        f"profiles_without_rules={summary.automatic_profiles_disabled}"
    )
    if summary.verified:
        notifier.notify("Payment verified", "A configured payment matched a pending request. Review GL manually.")
    if summary.ambiguous or summary.needs_review:
        notifier.notify("Payment needs review", "An SMS could not be safely matched. Check the engine status.", 73143)
    if summary.automatic_profiles_disabled:
        print("Unconfigured profiles were not used to accept any SMS.")
    return 0


def _command_handoff(
    args: argparse.Namespace,
    profiles: dict[str, AppProfile],
    store: StateStore,
    settings: dict[str, Any],
) -> int:
    if not args.confirm:
        raise WorkflowError("handoff requires --confirm after manual payment review")
    record = store.get_request(args.request_id)
    if record.state is not WorkflowState.PAYMENT_VERIFIED:
        raise WorkflowError(f"request is not ready for GL handoff (state={record.state.value})")
    if record.app_id is None or record.app_id not in profiles:
        raise WorkflowError("request has no configured app profile")
    profile = profiles[record.app_id]
    if not profile.production or not profile.issuance_enabled:
        raise WorkflowError("non-production profile cannot be handed to GL")
    provider = GlUiHandoffProvider(GlHandoffSettings.from_settings(settings))
    if profile.provider_kind != "gl-ui-handoff" or not provider.supports_profile(profile):
        raise WorkflowError("profile protocol or price table is not supported by the shared provider")

    request_text = TermuxClipboard().get()
    try:
        parsed = parse_request(request_text, profile)
    except RequestParseError as exc:
        raise WorkflowError(f"clipboard request is invalid: {exc.code}") from exc
    if parsed.request_id != record.request_id:
        raise WorkflowError("clipboard request does not match this workflow id")
    if parsed.is_recovery:
        raise WorkflowError("recovery requests stay in manual review and cannot be sent to GL")
    if parsed.review_code:
        raise WorkflowError(f"request requires review: {parsed.review_code}")

    provider.preflight()
    store.begin_provider_attempt(record.request_id)
    try:
        provider.handoff(parsed)
    except AdapterError:
        store.mark_provider_uncertain(record.request_id, "handoff_result_unconfirmed")
        raise
    TermuxNotifier(enabled=bool(settings.get("notifications", True))).notify(
        "GL request ready", "Review decrypted details and payment in GL; no message was sent."
    )
    print(f"GL handoff started; request_id={record.request_id} state=provider_in_progress")
    print("No Generate, Register, WhatsApp, or SMS action was automated.")
    print("Verify decrypted request fields and both transaction references in GL, then continue manually.")
    return 0


def _command_reconcile(args: argparse.Namespace, store: StateStore) -> int:
    record = store.reconcile_provider_result(
        args.request_id,
        generated=args.generated,
        registry_checked=args.registry_checked,
    )
    print(_format_record(record, record.app_id or "unresolved"))
    if args.not_generated:
        print("GL registry check is required before any new handoff; no automatic retry was performed.")
    else:
        print("Confirm the message was manually sent and the GL registry entry was completed before marking complete.")
    return 0


def _command_complete(args: argparse.Namespace, store: StateStore) -> int:
    record = store.complete_manual_delivery(
        args.request_id,
        confirmed_sent=args.sent,
        confirmed_registered=args.registered,
    )
    print(_format_record(record, record.app_id or "unresolved"))
    return 0


def _command_daemon(engine: LicenseEngine, settings: dict[str, Any], interval_override: int | None) -> int:
    interval = interval_override or int(settings.get("poll_seconds", 60))
    if interval < 15:
        raise WorkflowError("daemon poll interval must be at least 15 seconds")
    sms_client = TermuxSmsClient()
    notifier = _notifier(settings)
    print("SMS watcher started; it never generates, sends, or registers licenses.", flush=True)
    while True:
        has_rules = any(profile.automatic_sms_verification_configured for profile in engine.profiles.values())
        if has_rules:
            try:
                summary = engine.scan_sms(sms_client.list_inbox(50))
                print(
                    f"event=sms_scan seen={summary.messages_seen} parsed={summary.parsed} "
                    f"verified={summary.verified} review={summary.needs_review} ambiguous={summary.ambiguous}",
                    flush=True,
                )
                if summary.verified:
                    notifier.notify("Payment verified", "A configured payment matched a pending request. Review GL manually.")
                if summary.needs_review or summary.ambiguous:
                    notifier.notify("Payment needs review", "An SMS could not be safely matched. Check the engine status.", 73143)
            except AdapterError:
                print("event=sms_scan_error reason=termux_api_unavailable_or_denied", flush=True)
        time.sleep(interval)


def _notifier(settings: dict[str, Any]) -> TermuxNotifier:
    return TermuxNotifier(enabled=bool(settings.get("notifications", True)))


def _format_record(record: RequestRecord, display_name: str) -> str:
    plan = record.plan or "unknown"
    expected = "unknown" if record.expected_amount_cup is None else f"{record.expected_amount_cup} CUP"
    secondary_count = "unknown" if record.secondary_count is None else str(record.secondary_count)
    review = record.review_code or "none"
    return (
        f"request_id={record.request_id} app={display_name} state={record.state.value} "
        f"plan={plan} secondaries={secondary_count} expected={expected} review={review}"
    )
