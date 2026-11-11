import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
CONTRACT = ROOT / "docs/contracts/mt5-login.json"
sys.path.insert(0, str(ROOT / "managed-worker"))
from mt5_journal import classify_mt5_journal


def test_shared_contract_contains_all_required_statuses_and_fixture_label():
    payload = json.loads(CONTRACT.read_text())
    assert payload["fixture"] is True
    assert [item["stage"] for item in payload["statuses"]] == ["SENT", "TERMINAL_LOGGING_IN", "HEARTBEAT", "VERIFIED"]
    assert set(payload["failures"]) == {
        "WRONG_CREDENTIALS", "ACCOUNT_OR_SERVER_MISMATCH", "NO_CONNECTION", "SERVER_NOT_FOUND",
        "TERMINAL_OFFLINE", "EA_OFFLINE", "ALGO_TRADING_DISABLED", "WEBREQUEST_NOT_ALLOWED",
        "ACCOUNT_DISABLED", "EPHEMERAL_HOST_LIVE_BLOCKED", "WAITING_FOR_SPOTWARE_APPROVAL",
        "PROVIDER_AUTH_FAILED", "ACCOUNT_IDENTITY_MISMATCH", "PROVIDER_DATA_STALE",
        "SECURE_STORAGE_UNAVAILABLE", "CONNECT_TIMEOUT", "FORM_INCOMPLETE",
        "LOGIN_FAILED_UNCLASSIFIED", "BRIDGE_NEEDS_BROWSER_APPROVAL", "BRIDGE_URL_CHANGED",
    }


def test_bridge_and_linux_failure_codes_are_present_in_the_shared_fixture():
    payload = json.loads(CONTRACT.read_text())
    bridge_source = (ROOT / "bridge/app.py").read_text()
    journal_source = (ROOT / "managed-worker/mt5_journal.py").read_text()
    assert payload["bridge_worker_failures"]
    assert all(code in bridge_source for code in payload["bridge_worker_failures"])
    assert all(code in journal_source for code in payload["linux_journal_failures"])


def test_linux_journal_runner_emits_each_contract_failure_code():
    payload = json.loads(CONTRACT.read_text())
    reasons = {
        "WRONG_CREDENTIALS": "Invalid password",
        "NO_CONNECTION": "No connection",
        "SERVER_NOT_FOUND": "Server not found",
        "ACCOUNT_DISABLED": "Account disabled",
        "TERMINAL_VERSION_UNSUPPORTED": "Client version too old",
        "LOGIN_FAILED_UNCLASSIFIED": "Authorization failed",
    }
    assert set(reasons) == set(payload["linux_journal_failures"])
    for code, reason in reasons.items():
        journal = f"2026.11.08 07:01:00.000 Network '123456': authorization on Fixture-Demo failed ({reason})"
        assert classify_mt5_journal(journal) == code


def test_fake_terminal_harness_emits_utf16le_journal_and_heartbeat(tmp_path):
    journal, heartbeat = tmp_path / "journal.log", tmp_path / "heartbeat.json"
    subprocess.run([sys.executable, str(ROOT / "scripts/fake_mt5_login_harness.py"), "--journal", str(journal), "--heartbeat", str(heartbeat), "--scenario", "wrong_password"], check=True)
    raw = journal.read_bytes()
    assert raw.decode("utf-16le").find("authorization on Fixture-Demo failed (Invalid password)") >= 0
    report = json.loads(heartbeat.read_text())
    assert report["fixture"] is True and report["account"]["account_mode"] == "DEMO"
    assert "password" not in json.dumps(report).lower()
