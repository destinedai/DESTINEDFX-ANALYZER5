import json
import os
import sqlite3
import time
from datetime import datetime, timezone

import pytest

from host_lifecycle import backup_database, inspect_shutdown_safety


def _make_db(path, *, heartbeat=None, account=None, ledger_status=None,
             command_ack="ACK", job_status="READY"):
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.executescript(
        """
        CREATE TABLE pairings (
            id TEXT PRIMARY KEY,
            consumed INTEGER NOT NULL,
            heartbeat TEXT,
            account TEXT
        );
        CREATE TABLE managed_accounts (
            pairing_id TEXT PRIMARY KEY,
            revoked INTEGER NOT NULL,
            account_mode TEXT NOT NULL
        );
        CREATE TABLE trade_ledger (
            pairing_id TEXT NOT NULL,
            identity TEXT NOT NULL,
            status TEXT NOT NULL
        );
        CREATE TABLE commands (
            pairing_id TEXT NOT NULL,
            ack TEXT
        );
        CREATE TABLE worker_jobs (
            pairing_id TEXT NOT NULL,
            status TEXT NOT NULL
        );
        """
    )
    if account is None:
        account = {
            "login": "700001",
            "server": "Broker-Demo",
            "company": "Broker",
            "currency": "USD",
            "account_mode": "DEMO",
        }
    if heartbeat is None:
        heartbeat = {
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "state": "READY",
            "account": account,
            "open_positions": [],
            "active_orders": [],
        }
    connection.execute(
        "INSERT INTO pairings(id, consumed, heartbeat, account) VALUES(?,?,?,?)",
        ("pair-1", 1, json.dumps(heartbeat), json.dumps(account)),
    )
    connection.execute(
        "INSERT INTO managed_accounts(pairing_id, revoked, account_mode) VALUES(?,?,?)",
        ("pair-1", 0, "DEMO"),
    )
    if ledger_status is not None:
        connection.execute(
            "INSERT INTO trade_ledger(pairing_id, identity, status) VALUES(?,?,?)",
            ("pair-1", "position:123", ledger_status),
        )
    if command_ack != "NO_COMMAND":
        connection.execute("INSERT INTO commands(pairing_id, ack) VALUES(?,?)", ("pair-1", None if command_ack is None else command_ack))
    if job_status is not None:
        connection.execute("INSERT INTO worker_jobs(pairing_id, status) VALUES(?,?)", ("pair-1", job_status))
    connection.commit()
    return connection


def test_missing_database_fails_closed(tmp_path):
    result = inspect_shutdown_safety(tmp_path / "missing.sqlite3")
    assert result == {"safe": False, "reasons": ["DATABASE_UNAVAILABLE"]}


def test_fresh_empty_demo_account_can_be_stopped(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result == {"safe": True, "reasons": []}


def test_idle_sleep_requires_ea_stopped_not_merely_ready(tmp_path):
    ready_path = tmp_path / "ready.sqlite3"
    ready = _make_db(ready_path)
    ready.close()
    blocked = inspect_shutdown_safety(ready_path, require_ea_stopped=True)
    assert blocked["safe"] is False
    assert "EA_NOT_STOPPED" in blocked["reasons"]

    stopped_path = tmp_path / "stopped.sqlite3"
    stopped = {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "state": "STOPPED",
        "account": {
            "login": "700001", "server": "Broker-Demo", "company": "Broker",
            "currency": "USD", "account_mode": "DEMO",
        },
        "open_positions": [],
        "active_orders": [],
    }
    connection = _make_db(stopped_path, heartbeat=stopped)
    connection.close()
    allowed = inspect_shutdown_safety(stopped_path, require_ea_stopped=True)
    assert allowed == {"safe": True, "reasons": []}


def test_open_positions_block_host_stop(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path, heartbeat={
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "account": {"account_mode": "DEMO"},
        "open_positions": [{"position_id": "123", "symbol": "EURUSD"}],
        "active_orders": [],
    })
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "OPEN_POSITIONS" in result["reasons"]


def test_pending_orders_block_host_stop(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path, heartbeat={
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "account": {"account_mode": "DEMO"},
        "open_positions": [],
        "active_orders": [{"ticket": "456", "symbol": "EURUSD"}],
    })
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "PENDING_OR_ACTIVE_ORDERS" in result["reasons"]


def test_stale_heartbeat_blocks_host_stop(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    heartbeat = {
        "timestamp": "2000-01-01T00:00:00Z",
        "account": {"account_mode": "DEMO"},
        "open_positions": [],
        "active_orders": [],
    }
    connection = _make_db(path, heartbeat=heartbeat)
    connection.close()
    result = inspect_shutdown_safety(path, heartbeat_stale_seconds=15)
    assert result["safe"] is False
    assert "BROKER_STATE_UNVERIFIED" in result["reasons"]


@pytest.mark.parametrize("ledger_status", ["OPEN", "UNKNOWN", "PENDING"])
def test_non_closed_trade_ledger_blocks_host_stop(tmp_path, ledger_status):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path, ledger_status=ledger_status)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "ACTIVE_TRADE_LEDGER" in result["reasons"]


def test_unacknowledged_command_blocks_host_stop(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path, command_ack=None)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "UNACKNOWLEDGED_COMMANDS" in result["reasons"]


@pytest.mark.parametrize("job_status", ["LEASED", "PROVISIONING", "AUTHENTICATING"])
def test_in_progress_worker_job_blocks_host_stop(tmp_path, job_status):
    path = tmp_path / "bridge.sqlite3"
    connection = _make_db(path, job_status=job_status)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "WORKER_JOB_IN_PROGRESS" in result["reasons"]


def test_wal_committed_data_is_present_in_verified_backup(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("CREATE TABLE evidence (value TEXT NOT NULL)")
    connection.execute("INSERT INTO evidence(value) VALUES('committed-in-wal')")
    connection.commit()
    backup = backup_database(path, tmp_path / "backups")
    connection.close()

    assert backup.is_file()
    assert (backup.stat().st_mode & 0o777) == 0o600
    with sqlite3.connect(backup) as snapshot:
        assert snapshot.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert snapshot.execute("SELECT value FROM evidence").fetchone()[0] == "committed-in-wal"


def test_running_ea_blocks_host_stop_even_without_positions_or_orders(tmp_path):
    path = tmp_path / "bridge.sqlite3"
    heartbeat = {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "state": "RUNNING",
        "account": {
            "login": "700001",
            "server": "Broker-Demo",
            "company": "Broker",
            "currency": "USD",
            "account_mode": "DEMO",
        },
        "open_positions": [],
        "active_orders": [],
    }
    connection = _make_db(path, heartbeat=heartbeat)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "EA_ACTIVE" in result["reasons"]


@pytest.mark.parametrize("state", ["ERROR", "UNKNOWN", ""])
def test_unknown_ea_state_blocks_host_stop(tmp_path, state):
    path = tmp_path / "bridge.sqlite3"
    heartbeat = {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "state": state,
        "account": {
            "login": "700001",
            "server": "Broker-Demo",
            "company": "Broker",
            "currency": "USD",
            "account_mode": "DEMO",
        },
        "open_positions": [],
        "active_orders": [],
    }
    connection = _make_db(path, heartbeat=heartbeat)
    connection.close()
    result = inspect_shutdown_safety(path)
    assert result["safe"] is False
    assert "EA_STATE_UNVERIFIED" in result["reasons"]


def test_cancel_stop_clears_generation_scoped_database_gate(tmp_path):
    from host_lifecycle import cancel_stop
    path = tmp_path / "bridge.sqlite3"
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    (runtime / "stop.request").write_text("pending", encoding="utf-8")
    (runtime / "host-draining").write_text("pending", encoding="utf-8")
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE host_control (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated REAL NOT NULL)")
    connection.execute("INSERT INTO host_control(key,value,updated) VALUES('shutdown_prepared','generation-a',1)")
    connection.commit()
    connection.close()
    cancel_stop(runtime, path, "generation-a")
    assert not (runtime / "stop.request").exists()
    assert not (runtime / "host-draining").exists()
    with sqlite3.connect(path) as check:
        assert check.execute("SELECT COUNT(*) FROM host_control").fetchone()[0] == 0
