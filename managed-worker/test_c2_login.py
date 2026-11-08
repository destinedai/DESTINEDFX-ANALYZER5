import importlib.util
import os
import stat
from pathlib import Path

MODULE = Path(__file__).parent / "worker.py"
spec = importlib.util.spec_from_file_location("worker_c2", MODULE)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


def test_journal_failure_classes_are_bounded_and_secret_free():
    cases = {
        "invalid password for account": "WRONG_CREDENTIALS",
        "account or server mismatch": "ACCOUNT_OR_SERVER_MISMATCH",
        "no connection to trade server": "NO_CONNECTION",
        "server not found": "SERVER_NOT_FOUND",
        "expert advisor stopped": "EA_OFFLINE",
        "algo trading disabled": "ALGO_TRADING_DISABLED",
        "WebRequest is not allowed": "WEBREQUEST_NOT_ALLOWED",
    }
    for text, expected in cases.items():
        assert worker.classify_terminal_journal(text) == expected
        assert "password" not in worker.classify_terminal_journal(text).lower()


def test_investor_password_is_preferred_without_logging_or_persisting_it():
    account = {"login": "123", "server": "Demo", "password": "master-secret", "investor_password": "investor-secret"}
    assert worker.login_password(account) == "investor-secret"
    job = {"account": account}
    text = worker.ini_text(job, "INVESTOR", "EURUSD")
    assert "Password=investor-secret" in text
    assert "master-secret" not in text


def test_connect_config_is_private_and_never_contains_a_password(tmp_path):
    path = tmp_path / "startup.ini"
    worker.write_private(path, "[Common]\nLogin=123\nServer=Demo\n")
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert "password" not in path.read_text().lower()


def test_job_expiry_is_short_and_idempotent_in_bridge_source():
    bridge = (Path(__file__).parent.parent / "bridge" / "app.py").read_text()
    assert "CONNECT_JOB_TTL_SECONDS=600" in bridge
    assert "t+CONNECT_JOB_TTL_SECONDS" in bridge
