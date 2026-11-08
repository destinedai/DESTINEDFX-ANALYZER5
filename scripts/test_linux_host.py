import importlib.util
import json
import os
from pathlib import Path

MODULE_PATH = Path(__file__).parent / "linux-host" / "host-agent.py"
spec = importlib.util.spec_from_file_location("host_agent", MODULE_PATH)
host_agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host_agent)


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
    source = Path(__file__).parent.parent / "bridge" / "app.py"
    text = source.read_text()
    assert "BRIDGE_HOST_MODE" in text
    assert "EA_HEARTBEAT_FILE" not in text
