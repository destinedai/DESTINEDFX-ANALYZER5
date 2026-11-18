"""Fail-closed Codespaces host shutdown checks and SQLite backup helpers.

This module deliberately refuses an unattended stop when broker state cannot be
verified. It never closes positions or cancels orders.
"""
from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _epoch(value: Any) -> float | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value.strip().replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError, OverflowError):
        return None


def _read_json(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def inspect_shutdown_safety(
    db_path: str | os.PathLike[str],
    *,
    heartbeat_stale_seconds: int = 15,
    now_epoch: float | None = None,
) -> dict[str, Any]:
    """Check broker and worker state without liquidating or mutating any account."""
    path = Path(db_path)
    if not path.is_file():
        return {"safe": False, "reasons": ["DATABASE_UNAVAILABLE"]}

    now = time.time() if now_epoch is None else float(now_epoch)
    reasons: set[str] = set()
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(f"file:{path.resolve()}?mode=ro", uri=True, timeout=5)
        connection.row_factory = sqlite3.Row
        tables = {
            row["name"]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        required = {"pairings", "managed_accounts", "trade_ledger", "commands", "worker_jobs"}
        if not required.issubset(tables):
            return {"safe": False, "reasons": ["DATABASE_SCHEMA_INCOMPLETE"]}

        pairing_rows = connection.execute(
            "SELECT id, consumed, heartbeat FROM pairings WHERE consumed=1"
        ).fetchall()
        managed_ids: set[str] = set()

        for row in connection.execute(
            """SELECT p.id, p.heartbeat, m.account_mode, m.revoked
               FROM pairings p
               JOIN managed_accounts m ON m.pairing_id=p.id
               WHERE p.consumed=1 AND m.revoked=0
                 AND m.account_mode IN ('DEMO','LIVE')"""
        ).fetchall():
            pairing_id = str(row["id"])
            managed_ids.add(pairing_id)
            heartbeat = _read_json(row["heartbeat"])
            timestamp = _epoch(heartbeat.get("timestamp") if heartbeat else None)
            age = None if timestamp is None else now - timestamp
            if age is None or age < -30 or age > heartbeat_stale_seconds:
                reasons.add("BROKER_STATE_UNVERIFIED")
                continue
            positions = heartbeat.get("open_positions")
            orders = heartbeat.get("active_orders")
            if not isinstance(positions, list) or not isinstance(orders, list):
                reasons.add("BROKER_STATE_INCOMPLETE")
                continue
            if positions:
                reasons.add("OPEN_POSITIONS")
            if orders:
                reasons.add("PENDING_OR_ACTIVE_ORDERS")

        # Also inspect manually paired terminals that have a broker account in
        # their heartbeat even when no managed-account row exists.
        for row in pairing_rows:
            pairing_id = str(row["id"])
            heartbeat = _read_json(row["heartbeat"])
            account = heartbeat.get("account") if heartbeat else None
            if not isinstance(account, dict):
                continue
            mode = str(account.get("account_mode", "")).upper()
            if mode not in {"DEMO", "LIVE"}:
                continue
            timestamp = _epoch(heartbeat.get("timestamp"))
            age = None if timestamp is None else now - timestamp
            if age is None or age < -30 or age > heartbeat_stale_seconds:
                reasons.add("BROKER_STATE_UNVERIFIED")
                continue
            positions = heartbeat.get("open_positions")
            orders = heartbeat.get("active_orders")
            if not isinstance(positions, list) or not isinstance(orders, list):
                reasons.add("BROKER_STATE_INCOMPLETE")
                continue
            if positions:
                reasons.add("OPEN_POSITIONS")
            if orders:
                reasons.add("PENDING_OR_ACTIVE_ORDERS")

        # The append-only ledger is an independent block against stale or missing
        # terminal snapshots. OPEN is the only active lifecycle state written by
        # the current bridge; any unknown non-CLOSED value fails closed.
        active_ledger = connection.execute(
            "SELECT COUNT(*) AS n FROM trade_ledger WHERE UPPER(status) <> 'CLOSED'"
        ).fetchone()["n"]
        if int(active_ledger or 0) > 0:
            reasons.add("ACTIVE_TRADE_LEDGER")

        pending_commands = connection.execute(
            "SELECT COUNT(*) AS n FROM commands WHERE ack IS NULL"
        ).fetchone()["n"]
        if int(pending_commands or 0) > 0:
            reasons.add("UNACKNOWLEDGED_COMMANDS")

        active_jobs = connection.execute(
            """SELECT COUNT(*) AS n FROM worker_jobs
               WHERE UPPER(status) IN ('LEASED','PROVISIONING','AUTHENTICATING')"""
        ).fetchone()["n"]
        if int(active_jobs or 0) > 0:
            reasons.add("WORKER_JOB_IN_PROGRESS")

        # A stale broker heartbeat for a managed account is a blocker even if
        # its last reported ledger happens to be empty.
        return {"safe": not reasons, "reasons": sorted(reasons)}
    except (OSError, sqlite3.Error, TypeError, ValueError):
        return {"safe": False, "reasons": ["DATABASE_INSPECTION_FAILED"]}
    finally:
        if connection is not None:
            connection.close()


def backup_database(
    db_path: str | os.PathLike[str],
    backup_dir: str | os.PathLike[str],
    *,
    keep: int = 10,
) -> Path:
    """Create and verify a consistent SQLite snapshot, including committed WAL data."""
    source_path = Path(db_path)
    target_dir = Path(backup_dir)
    if not source_path.is_file():
        raise FileNotFoundError("Bridge database is unavailable")
    target_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(target_dir, 0o700)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    final_path = target_dir / f"bridge-{stamp}-{os.getpid()}.sqlite3"
    fd, temporary_name = tempfile.mkstemp(prefix=".bridge-backup-", suffix=".sqlite3", dir=target_dir)
    os.close(fd)
    temporary_path = Path(temporary_name)
    try:
        with sqlite3.connect(str(source_path), timeout=10) as source:
            with sqlite3.connect(str(temporary_path), timeout=10) as destination:
                source.backup(destination, pages=256, sleep=0.05)
                integrity = destination.execute("PRAGMA integrity_check").fetchone()
                if not integrity or integrity[0] != "ok":
                    raise sqlite3.DatabaseError("SQLite backup integrity check failed")
                destination.commit()
        os.chmod(temporary_path, 0o600)
        with temporary_path.open("rb") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary_path, final_path)
        try:
            directory_fd = os.open(target_dir, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError:
            pass
        backups = sorted(
            target_dir.glob("bridge-*.sqlite3"),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )
        for old in backups[max(1, keep):]:
            try:
                old.unlink()
            except OSError:
                pass
        return final_path
    finally:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass


def _atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(payload, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def request_stop(runtime_dir: str | os.PathLike[str], *, reason: str, grace_seconds: int = 30) -> dict[str, Any]:
    runtime = Path(runtime_dir)
    request_id = os.urandom(16).hex()
    payload = {
        "request_id": request_id,
        "reason": reason[:32],
        "requested_at": time.time(),
        "grace_seconds": max(5, min(180, int(grace_seconds))),
    }
    _atomic_json(runtime / "stop.request", payload)
    return payload


def cancel_stop(runtime_dir: str | os.PathLike[str]) -> None:
    runtime = Path(runtime_dir)
    for name in ("stop.request", "host-draining"):
        try:
            (runtime / name).unlink()
        except FileNotFoundError:
            pass


def touch_activity(runtime_dir: str | os.PathLike[str]) -> None:
    runtime = Path(runtime_dir)
    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = runtime / "last-activity"
    try:
        path.touch(mode=0o600, exist_ok=True)
        os.chmod(path, 0o600)
    except OSError:
        # Activity accounting must never make the trading bridge unavailable.
        pass


def read_status(runtime_dir: str | os.PathLike[str]) -> dict[str, Any]:
    path = Path(runtime_dir) / "host-status.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {"status": "UNKNOWN"}
    except (OSError, ValueError):
        return {"status": "UNKNOWN"}
