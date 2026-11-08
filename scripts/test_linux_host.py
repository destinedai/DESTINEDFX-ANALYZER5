import importlib.util
import json
import os
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKER_DIR = ROOT / "managed-worker"
sys.path.insert(0, str(WORKER_DIR))
import mt5_journal
import worker

MODULE_PATH = Path(__file__).parent / "linux-host" / "host-agent.py"
spec = importlib.util.spec_from_file_location("host_agent", MODULE_PATH)
host_agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host_agent)
FIXTURE = WORKER_DIR / "fixtures" / "mt5-journal-synthetic-utf16le.log"


def fixture_text():
    return mt5_journal.decode_mt5_journal(FIXTURE.read_bytes())


def epoch(local_stamp):
    return datetime.strptime(local_stamp, "%Y.%m.%d %H:%M:%S").timestamp()


def bounded_fixture():
    lines = [line for line in fixture_text().splitlines() if "Network '00000000'" in line]
    # Keep two old credential rejections and one fresh connection error.
    return "\n".join(lines[:3])


def test_utf16le_bom_fixture_decodes_as_labeled_synthetic_mt5_journal():
    raw = FIXTURE.read_bytes()
    assert raw.startswith(b"\xff\xfe")
    text = mt5_journal.decode_mt5_journal(raw)
    assert "SYNTHETIC TEST FIXTURE" in text
    assert "authorization on Fixture-Demo failed (Invalid account)" in text


def test_real_format_reason_mapping_is_conservative():
    lines = [line for line in fixture_text().splitlines() if "Network '00000000'" in line]
    expected = [
        "WRONG_CREDENTIALS",
        "WRONG_CREDENTIALS",
        "NO_CONNECTION",
        "SERVER_NOT_FOUND",
        "ACCOUNT_DISABLED",
        "LOGIN_FAILED_UNCLASSIFIED",
        "LOGIN_FAILED_UNCLASSIFIED",
    ]
    assert [mt5_journal.classify_mt5_journal(line) for line in lines] == expected
    assert worker.classify_terminal_journal("2026.11.08 07:08:00.000 Network status: network not allowed") == "LOGIN_FAILED_UNCLASSIFIED"


def test_unknown_real_format_reason_maps_to_unclassified_not_generic_auth_failure():
    line = "2026.11.08 07:05:00.000 Network '00000000': authorization on Fixture-Demo failed (Synthetic unrecognized reason)"
    assert mt5_journal.classify_mt5_journal(line) == "LOGIN_FAILED_UNCLASSIFIED"
    assert worker.classify_terminal_journal("unrelated network not allowed") == "LOGIN_FAILED_UNCLASSIFIED"


def test_journal_classifier_ignores_failures_before_connect_job_start():
    text = bounded_fixture()
    assert mt5_journal.classify_mt5_journal(text, epoch("2026.11.08 07:01:30")) == "NO_CONNECTION"
    assert mt5_journal.classify_mt5_journal(text, epoch("2026.11.08 07:02:30")) == "LOGIN_FAILED_UNCLASSIFIED"


def test_worker_journal_failure_code_reads_utf16_and_applies_cutoff(tmp_path):
    lines = bounded_fixture().splitlines()
    path = tmp_path / "journal.log"
    path.write_bytes(b"\xff\xfe" + "\n".join(lines).encode("utf-16-le"))
    assert worker.journal_failure_code({"journal_path": str(path)}, epoch("2026.11.08 07:01:30")) == "NO_CONNECTION"


def test_host_agent_journal_classification_requires_current_attempt_marker(tmp_path, monkeypatch):
    lines = bounded_fixture().splitlines()
    journal = tmp_path / "fixture.log"
    journal.write_bytes(b"\xff\xfe" + "\n".join(lines).encode("utf-16-le"))
    start_file = tmp_path / "connect-start.epoch"
    monkeypatch.setattr(host_agent, "JOURNAL_PATH", str(journal))
    monkeypatch.setattr(host_agent, "CONNECT_JOB_START_FILE", str(start_file))
    start_file.write_text(str(epoch("2026.11.08 07:01:30")))
    assert host_agent.journal_classification() == {"status": "CLASSIFIED", "class": "NO_CONNECTION"}
    start_file.unlink()
    assert host_agent.journal_classification() == {"status": "EMPTY", "class": "NO_ACTIVE_CONNECT_JOB"}


def test_stale_and_missing_heartbeat_fail_closed(monkeypatch):
    monkeypatch.setattr(host_agent, "HEARTBEAT_PATH", "")
    assert host_agent.heartbeat_report()["fresh"] is False


def test_heartbeat_report_only_keeps_identity_fields(tmp_path, monkeypatch):
    path = tmp_path / "heartbeat.json"
    path.write_text(json.dumps({"timestamp": 1000, "account": {"login": "7", "server": "Demo", "company": "FBS", "account_mode": "DEMO", "password": "never"}, "terminal_connected": True}))
    monkeypatch.setattr(host_agent, "HEARTBEAT_PATH", str(path))
    result = host_agent.heartbeat_report(1001)
    assert result["fresh"] is True
    assert "password" not in json.dumps(result)
    assert result["account"] == {"login": "7", "server": "Demo", "company": "FBS", "account_mode": "DEMO"}


def test_report_is_blocked_when_terminal_dead_or_memory_low(monkeypatch):
    monkeypatch.setattr(host_agent, "terminal_process_alive", lambda: False)
    monkeypatch.setattr(host_agent, "heartbeat_report", lambda: {"fresh": True, "terminal_connected": True})
    assert host_agent.report()["healthy"] is False


def test_windows_mode_bridge_guard_is_noop():
    source = ROOT / "bridge" / "app.py"
    text = source.read_text()
    assert "BRIDGE_HOST_MODE" in text
    assert "EA_HEARTBEAT_FILE" not in text


def test_recent_journal_reader_honors_byte_limit_and_discards_partial_line(tmp_path):
    path = tmp_path / "tail.log"
    path.write_bytes(("x" * 100 + "\nFRESH").encode("utf-16-le"))
    result = mt5_journal.read_recent_mt5_journal(path, max_bytes=32)
    assert result == "FRESH"
    assert len(result.encode("utf-16-le")) <= 32
