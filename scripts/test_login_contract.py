import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
CONTRACT = ROOT / "docs/contracts/mt5-login.json"


def test_shared_contract_contains_all_required_statuses_and_fixture_label():
    payload = json.loads(CONTRACT.read_text())
    assert payload["fixture"] is True
    assert [item["stage"] for item in payload["statuses"]] == ["SENT", "TERMINAL_LOGGING_IN", "HEARTBEAT", "VERIFIED"]
    assert set(payload["failures"]) == {"WRONG_CREDENTIALS", "ACCOUNT_OR_SERVER_MISMATCH", "NO_CONNECTION", "SERVER_NOT_FOUND", "TERMINAL_OFFLINE", "EA_OFFLINE", "ALGO_TRADING_DISABLED", "WEBREQUEST_NOT_ALLOWED", "LOGIN_FAILED_UNCLASSIFIED", "EPHEMERAL_HOST_LIVE_BLOCKED"}


def test_fake_terminal_harness_emits_utf16le_journal_and_heartbeat(tmp_path):
    journal, heartbeat = tmp_path / "journal.log", tmp_path / "heartbeat.json"
    subprocess.run([sys.executable, str(ROOT / "scripts/fake_mt5_login_harness.py"), "--journal", str(journal), "--heartbeat", str(heartbeat), "--scenario", "wrong_password"], check=True)
    raw = journal.read_bytes()
    assert raw.decode("utf-16le").find("authorization on Fixture-Demo failed (Invalid password)") >= 0
    report = json.loads(heartbeat.read_text())
    assert report["fixture"] is True and report["account"]["account_mode"] == "DEMO"
    assert "password" not in json.dumps(report).lower()
