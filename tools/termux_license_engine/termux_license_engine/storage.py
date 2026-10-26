"""Small, privacy-minimizing SQLite journal for idempotent operator workflows."""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from .models import RequestRecord, WorkflowState


class StorageError(RuntimeError):
    pass


class InvalidTransition(StorageError):
    pass


class DuplicatePayment(StorageError):
    pass


class StateStore:
    """Persist only digests and non-personal workflow metadata, never raw messages."""

    def __init__(self, path: Path) -> None:
        self.path = path.expanduser()
        database_existed = self.path.exists() and self.path.stat().st_size > 0
        parent_existed = self.path.parent.exists()
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not parent_existed:
            try:
                os.chmod(self.path.parent, 0o700)
            except OSError:
                pass
        if not self.path.exists():
            try:
                descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                pass
            else:
                os.close(descriptor)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass
        self._digest_key = self._load_digest_key(allow_create=not database_existed)
        self._initialize()

    def _load_digest_key(self, *, allow_create: bool) -> bytes:
        key_path = Path(f"{self.path}.hmac-key")
        if not key_path.exists() and not allow_create:
            raise StorageError("local digest key is missing; preserve the database for manual recovery")
        try:
            descriptor = os.open(key_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            try:
                key = key_path.read_bytes()
            except OSError as exc:
                raise StorageError("cannot read local digest key") from exc
        else:
            key = secrets.token_bytes(32)
            try:
                os.write(descriptor, key)
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        if len(key) != 32:
            raise StorageError("local digest key has invalid size; preserve state for manual recovery")
        try:
            os.chmod(key_path, 0o600)
        except OSError:
            pass
        return key

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA secure_delete = ON")
        return connection

    def _initialize(self) -> None:
        with closing(self._connect()) as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS requests (
                    request_id TEXT PRIMARY KEY,
                    app_id TEXT,
                    state TEXT NOT NULL,
                    plan TEXT,
                    secondary_count INTEGER,
                    expected_amount_cup INTEGER,
                    quoted_amount_cup INTEGER,
                    review_code TEXT,
                    customer_tx_hash TEXT,
                    sms_tx_hash TEXT,
                    sender_hash TEXT,
                    payment_amount_cup INTEGER,
                    payment_source TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    provider_attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_provider_attempt_at TEXT,
                    last_reconcile_code TEXT
                );
                CREATE TABLE IF NOT EXISTS used_payment_transactions (
                    transaction_hash TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL UNIQUE REFERENCES requests(request_id),
                    recorded_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sms_observations (
                    event_id TEXT PRIMARY KEY,
                    app_id TEXT NOT NULL,
                    transaction_hash TEXT NOT NULL,
                    amount_cup INTEGER NOT NULL,
                    sender_hash TEXT NOT NULL,
                    received TEXT NOT NULL,
                    recorded_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS requests_state_idx ON requests(state, updated_at);
                CREATE INDEX IF NOT EXISTS sms_transaction_idx ON sms_observations(transaction_hash);
                """
            )
        try:
            os.chmod(self.path, 0o600)
            for suffix in ("-wal", "-shm"):
                sidecar = Path(f"{self.path}{suffix}")
                if sidecar.exists():
                    os.chmod(sidecar, 0o600)
        except OSError:
            pass

    def digest_secret(self, value: str) -> str:
        """Return a keyed digest so low-entropy transaction ids resist DB-only guessing."""
        return hmac.new(self._digest_key, value.strip().encode("utf-8"), hashlib.sha256).hexdigest()

    def record_request(
        self,
        *,
        request_id: str,
        app_id: str,
        state: WorkflowState,
        plan: str | None,
        secondary_count: int | None,
        expected_amount_cup: int | None,
        quoted_amount_cup: int | None,
        review_code: str | None,
    ) -> tuple[RequestRecord, bool]:
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                """INSERT OR IGNORE INTO requests
                   (request_id, app_id, state, plan, secondary_count,
                    expected_amount_cup, quoted_amount_cup, review_code,
                    created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    request_id,
                    app_id,
                    state.value,
                    plan,
                    secondary_count,
                    expected_amount_cup,
                    quoted_amount_cup,
                    review_code,
                    now,
                    now,
                ),
            )
            inserted = cursor.rowcount == 1
            connection.commit()
        return self.get_request(request_id), inserted

    def record_unresolved_request(self, request_id: str, state: WorkflowState, review_code: str) -> RequestRecord:
        """Record a token digest without retaining any message text or personal fields."""
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute(
                """INSERT OR IGNORE INTO requests
                   (request_id, app_id, state, review_code, created_at, updated_at)
                   VALUES (?, NULL, ?, ?, ?, ?)""",
                (request_id, state.value, review_code, now, now),
            )
        return self.get_request(request_id)

    def get_request(self, request_id: str) -> RequestRecord:
        with closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT * FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
        if row is None:
            raise StorageError("request not found")
        return _to_record(row)

    def list_requests(self, limit: int = 50) -> list[RequestRecord]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT * FROM requests ORDER BY updated_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [_to_record(row) for row in rows]

    def find_pending_by_customer_tx(self, app_id: str, transaction_hash: str) -> list[RequestRecord]:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                """SELECT * FROM requests WHERE app_id = ? AND customer_tx_hash = ?
                   AND state IN (?, ?) ORDER BY created_at""",
                (
                    app_id,
                    transaction_hash,
                    WorkflowState.NEEDS_PAYMENT.value,
                    WorkflowState.PAYMENT_REVIEW.value,
                ),
            ).fetchall()
        return [_to_record(row) for row in rows]

    def bind_customer_transaction(self, request_id: str, transaction: str) -> RequestRecord:
        transaction = transaction.strip()
        if not transaction:
            raise StorageError("customer transaction reference is required")
        transaction_hash = self.digest_secret(transaction)
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT state, customer_tx_hash FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
                connection.rollback()
                raise InvalidTransition(f"customer transaction cannot be attached in state {current.value}")
            existing = row["customer_tx_hash"]
            if existing and existing != transaction_hash:
                connection.rollback()
                raise StorageError("a different customer transaction reference is already attached")
            connection.execute(
                "UPDATE requests SET customer_tx_hash = ?, updated_at = ? WHERE request_id = ?",
                (transaction_hash, now, request_id),
            )
            connection.commit()
        return self.get_request(request_id)

    def verify_observed_payment(
        self,
        *,
        request_id: str,
        transaction_hash: str,
        amount_cup: int,
        sender_hash: str,
    ) -> RequestRecord:
        """Verify a pre-parsed SMS using only hashes; no SMS body is persisted."""
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
                connection.rollback()
                raise InvalidTransition(f"payment cannot be attached in state {current.value}")
            if not row["customer_tx_hash"] or row["customer_tx_hash"] != transaction_hash:
                connection.rollback()
                raise StorageError("SMS transaction does not match the customer transaction")
            if row["expected_amount_cup"] is None or row["expected_amount_cup"] != amount_cup:
                connection.execute(
                    "UPDATE requests SET state = ?, review_code = ?, updated_at = ? WHERE request_id = ?",
                    (WorkflowState.PAYMENT_REVIEW.value, "sms_amount_mismatch", now, request_id),
                )
                connection.commit()
                return self.get_request(request_id)
            try:
                connection.execute(
                    "INSERT INTO used_payment_transactions(transaction_hash, request_id, recorded_at) VALUES (?, ?, ?)",
                    (transaction_hash, request_id, now),
                )
            except sqlite3.IntegrityError as exc:
                connection.rollback()
                raise DuplicatePayment("transaction reference has already been used") from exc
            connection.execute(
                """UPDATE requests SET state = ?, sms_tx_hash = ?, sender_hash = ?,
                   payment_amount_cup = ?, payment_source = 'sms_parser', review_code = NULL,
                   updated_at = ? WHERE request_id = ?""",
                (WorkflowState.PAYMENT_VERIFIED.value, transaction_hash, sender_hash, amount_cup, now, request_id),
            )
            connection.commit()
        return self.get_request(request_id)

    def verify_payment(
        self,
        *,
        request_id: str,
        customer_transaction: str,
        sms_transaction: str,
        amount_cup: int,
        sender_address: str,
        source: str,
    ) -> RequestRecord:
        customer_tx = customer_transaction.strip()
        sms_tx = sms_transaction.strip()
        sender = sender_address.strip()
        if not customer_tx or not sms_tx or not sender:
            raise StorageError("transaction references and sender are required")
        if customer_tx != sms_tx:
            raise StorageError("customer and SMS transaction references do not match")
        if amount_cup < 0:
            raise StorageError("payment amount must be non-negative")
        if source not in {"manual", "sms_parser"}:
            raise StorageError("unsupported payment verification source")

        tx_hash = self.digest_secret(sms_tx)
        customer_hash = self.digest_secret(customer_tx)
        sender_hash = self.digest_secret(sender)
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current not in {WorkflowState.NEEDS_PAYMENT, WorkflowState.PAYMENT_REVIEW}:
                connection.rollback()
                raise InvalidTransition(f"payment cannot be attached in state {current.value}")
            expected = row["expected_amount_cup"]
            if expected is None or amount_cup != expected:
                connection.rollback()
                raise StorageError("payment amount does not equal the profile price")
            try:
                connection.execute(
                    "INSERT INTO used_payment_transactions(transaction_hash, request_id, recorded_at) VALUES (?, ?, ?)",
                    (tx_hash, request_id, now),
                )
            except sqlite3.IntegrityError as exc:
                connection.rollback()
                raise DuplicatePayment("transaction reference has already been used") from exc
            connection.execute(
                """UPDATE requests
                   SET state = ?, customer_tx_hash = ?, sms_tx_hash = ?, sender_hash = ?,
                       payment_amount_cup = ?, payment_source = ?, review_code = NULL, updated_at = ?
                   WHERE request_id = ?""",
                (
                    WorkflowState.PAYMENT_VERIFIED.value,
                    customer_hash,
                    tx_hash,
                    sender_hash,
                    amount_cup,
                    source,
                    now,
                    request_id,
                ),
            )
            connection.commit()
        return self.get_request(request_id)

    def record_sms_observation(
        self,
        *,
        event_id: str,
        app_id: str,
        transaction_hash: str,
        amount_cup: int,
        sender_hash: str,
        received: str,
    ) -> bool:
        with closing(self._connect()) as connection:
            cursor = connection.execute(
                """INSERT OR IGNORE INTO sms_observations
                   (event_id, app_id, transaction_hash, amount_cup, sender_hash, received, recorded_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (event_id, app_id, transaction_hash, amount_cup, sender_hash, received, _now()),
            )
        return cursor.rowcount == 1

    def begin_provider_attempt(self, request_id: str) -> RequestRecord:
        """Commit the at-most-once guard before any provider side effect."""
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT state FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current is not WorkflowState.PAYMENT_VERIFIED:
                connection.rollback()
                raise InvalidTransition(f"provider handoff cannot start in state {current.value}")
            connection.execute(
                """UPDATE requests
                   SET state = ?, provider_attempt_count = provider_attempt_count + 1,
                       last_provider_attempt_at = ?, updated_at = ?
                   WHERE request_id = ?""",
                (WorkflowState.PROVIDER_IN_PROGRESS.value, now, now, request_id),
            )
            connection.commit()
        return self.get_request(request_id)

    def mark_provider_uncertain(self, request_id: str, review_code: str = "provider_result_unconfirmed") -> RequestRecord:
        return self._change_state(
            request_id,
            expected={WorkflowState.PROVIDER_IN_PROGRESS},
            target=WorkflowState.PROVIDER_UNCERTAIN,
            review_code=review_code,
        )

    def reconcile_provider_result(
        self,
        request_id: str,
        *,
        generated: bool,
        registry_checked: bool,
    ) -> RequestRecord:
        if not registry_checked:
            raise StorageError("manual reconciliation requires checking the provider registry")
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT state FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current not in {WorkflowState.PROVIDER_IN_PROGRESS, WorkflowState.PROVIDER_UNCERTAIN}:
                connection.rollback()
                raise InvalidTransition(f"generation cannot be reconciled in state {current.value}")
            target = WorkflowState.AWAITING_MANUAL_DELIVERY if generated else WorkflowState.PAYMENT_VERIFIED
            code = "operator_confirmed_generated" if generated else "operator_checked_not_generated"
            connection.execute(
                "UPDATE requests SET state = ?, review_code = ?, last_reconcile_code = ?, updated_at = ? WHERE request_id = ?",
                (target.value, None if generated else code, code, now, request_id),
            )
            connection.commit()
        return self.get_request(request_id)

    def complete_manual_delivery(
        self,
        request_id: str,
        *,
        confirmed_sent: bool,
        confirmed_registered: bool,
    ) -> RequestRecord:
        if not confirmed_sent or not confirmed_registered:
            raise StorageError("completion requires confirming manual send and provider registration")
        return self._change_state(
            request_id,
            expected={WorkflowState.AWAITING_MANUAL_DELIVERY},
            target=WorkflowState.COMPLETED,
            review_code=None,
        )

    def _change_state(
        self,
        request_id: str,
        *,
        expected: set[WorkflowState],
        target: WorkflowState,
        review_code: str | None,
    ) -> RequestRecord:
        now = _now()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT state FROM requests WHERE request_id = ?", (request_id,)
            ).fetchone()
            if row is None:
                connection.rollback()
                raise StorageError("request not found")
            current = WorkflowState(row["state"])
            if current not in expected:
                connection.rollback()
                raise InvalidTransition(f"cannot change state from {current.value}")
            connection.execute(
                "UPDATE requests SET state = ?, review_code = ?, updated_at = ? WHERE request_id = ?",
                (target.value, review_code, now, request_id),
            )
            connection.commit()
        return self.get_request(request_id)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _to_record(row: sqlite3.Row) -> RequestRecord:
    return RequestRecord(
        request_id=row["request_id"],
        app_id=row["app_id"],
        state=WorkflowState(row["state"]),
        plan=row["plan"],
        secondary_count=row["secondary_count"],
        expected_amount_cup=row["expected_amount_cup"],
        quoted_amount_cup=row["quoted_amount_cup"],
        review_code=row["review_code"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        provider_attempt_count=row["provider_attempt_count"],
    )
