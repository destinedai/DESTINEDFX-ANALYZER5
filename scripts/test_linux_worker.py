import importlib.util
import json
import os
import stat
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "managed-worker"))
import worker

MODULE_PATH = ROOT / "scripts" / "linux-host" / "linux-worker.py"
spec = importlib.util.spec_from_file_location("linux_worker", MODULE_PATH)
linux_worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(linux_worker)


def epoch(local_stamp):
    return datetime.strptime(local_stamp, "%Y.%m.%d %H:%M:%S").timestamp()


def sample_job():
    return {
        "job_id": "job_123",
        "kind": "PROVISION",
        "pairing_id": "pair_456",
        "pairing_code": "abcdefgh1234",
        "bridge_url": "https://bridge.destinedfx.internal",
        "account": {
            "login": "123456",
            "server": "Demo-Server",
            "password": "master-secret-fixture",
            "investor_password": "investor-secret-fixture",
            "access_type": "INVESTOR",
            "account_mode": "DEMO",
            "broker_brand": "FBS",
        },
    }


def settings(tmp_path):
    terminal = tmp_path / "terminal"
    return {
        "control_plane_url": "https://bridge.destinedfx.internal",
        "worker_token": "test-worker-token-not-a-real-secret",
        "worker_id": "linux-host-01",
        "startup_config": str(terminal / "startup.ini"),
        "ea_set_path": str(terminal / "MQL5" / "Presets" / "DestinedFXTraderEA.set"),
        "active_job_path": str(tmp_path / "active-job.json"),
        "connect_start_file": str(tmp_path / "connect-start.epoch"),
        "journal_path": str(tmp_path / "journal.log"),
        "mt5_unit": "destinedfx-mt5.service",
        "auth_timeout_seconds": 270,
    }


def capture_posts(monkeypatch):
    posted = []
    monkeypatch.setattr(worker, "post_state", lambda *args: posted.append(args))
    return posted


def test_linux_connect_happy_path_waits_for_backend_ready_and_wipes_private_config(tmp_path, monkeypatch):
    cfg = settings(tmp_path)
    job = sample_job()
    posted = capture_posts(monkeypatch)
    services = []
    waited = []

    def systemctl(action, unit):
        services.append((action, unit))

    def wait_status(job_id, worker_id, timeout):
        waited.append((job_id, worker_id, timeout))
        startup = Path(cfg["startup_config"])
        mode = stat.S_IMODE(startup.stat().st_mode)
        assert mode == 0o600
        assert startup.stat().st_uid == os.geteuid()
        text = startup.read_text(encoding="utf-8")
        assert "Password=investor-secret-fixture" in text
        assert "Password=master-secret-fixture" not in text
        active = json.loads(Path(cfg["active_job_path"]).read_text())
        assert "password" not in json.dumps(active).lower()
        return "READY"

    outcome = linux_worker.execute_connect_job(
        job, cfg, systemctl=systemctl, wait_status=wait_status,
        clock=lambda: epoch("2026.11.08 07:01:30"),
    )
    assert outcome == "READY"
    assert waited == [("job_123", "linux-host-01", 270)]
    assert services == [("restart", "destinedfx-mt5.service")]
    assert [item[4] for item in posted] == ["PROVISIONING", "AUTHENTICATING"]
    assert not Path(cfg["startup_config"]).exists()
    assert not Path(cfg["ea_set_path"]).exists()
    assert not Path(cfg["connect_start_file"]).exists()
    active = json.loads(Path(cfg["active_job_path"]).read_text())
    assert active["status"] == "READY"
    assert "master-secret-fixture" not in json.dumps(active)
    assert "investor-secret-fixture" not in json.dumps(active)
    assert job["account"]["password"] == ""
    assert job["account"]["investor_password"] == ""
    assert job["pairing_code"] == ""


def test_linux_connect_timeout_classifies_only_current_utf16_journal_and_cleans_up(tmp_path, monkeypatch):
    cfg = settings(tmp_path)
    Path(cfg["journal_path"]).write_bytes(
        b"\xff\xfe" + "2026.11.08 07:02:00.000 Network '00000000': authorization on Fixture-Demo failed (No connection)\n".encode("utf-16-le")
    )
    posted = capture_posts(monkeypatch)
    services = []
    outcome = linux_worker.execute_connect_job(
        sample_job(), cfg,
        systemctl=lambda action, unit: services.append((action, unit)),
        wait_status=lambda *_: "TIMEOUT",
        clock=lambda: epoch("2026.11.08 07:01:30"),
    )
    assert outcome == "NO_CONNECTION"
    assert services == [("restart", "destinedfx-mt5.service"), ("stop", "destinedfx-mt5.service")]
    assert any(item[4] == "FAILED" and item[5] == "NO_CONNECTION" for item in posted)
    for name in ("startup_config", "ea_set_path", "connect_start_file", "active_job_path"):
        assert not Path(cfg[name]).exists()


def test_linux_connect_launch_failure_stops_terminal_and_removes_config(tmp_path, monkeypatch):
    cfg = settings(tmp_path)
    posted = capture_posts(monkeypatch)
    services = []

    def systemctl(action, unit):
        services.append((action, unit))
        if action == "restart":
            raise linux_worker.LinuxRunnerError("SYSTEMD_CONTROL_FAILED")

    result = linux_worker.execute_connect_job(sample_job(), cfg, systemctl=systemctl,
                                               wait_status=lambda *_: "READY")
    assert result == "TERMINAL_LAUNCH_FAILED"
    assert services == [("restart", "destinedfx-mt5.service"), ("stop", "destinedfx-mt5.service")]
    assert any(item[4] == "FAILED" and item[5] == "TERMINAL_LAUNCH_FAILED" for item in posted)
    assert not Path(cfg["startup_config"]).exists()
    assert not Path(cfg["ea_set_path"]).exists()


def test_unverified_terminal_stop_preserves_private_config_for_operator_review(tmp_path, monkeypatch):
    cfg = settings(tmp_path)
    posted = capture_posts(monkeypatch)

    def systemctl(action, unit):
        if action == "stop":
            raise linux_worker.LinuxRunnerError("SYSTEMD_CONTROL_FAILED")

    result = linux_worker.execute_connect_job(sample_job(), cfg, systemctl=systemctl,
                                               wait_status=lambda *_: "TIMEOUT",
                                               clock=lambda: epoch("2026.11.08 07:01:30"))
    assert result == "OPERATOR_REVIEW"
    startup = Path(cfg["startup_config"])
    assert startup.exists()
    assert stat.S_IMODE(startup.stat().st_mode) == 0o600
    active = json.loads(Path(cfg["active_job_path"]).read_text())
    assert active["status"] == "OPERATOR_REVIEW"
    assert "master-secret-fixture" not in json.dumps(active)
    assert any(item[4] == "FAILED" and item[5] == "TERMINAL_OWNERSHIP_UNVERIFIED" for item in posted)


def test_private_file_creation_refuses_existing_config_without_unlinking(tmp_path):
    path = tmp_path / "startup.ini"
    path.write_text("existing private content")
    try:
        linux_worker.write_private_file(path, "replacement")
    except linux_worker.LinuxRunnerError:
        pass
    else:
        raise AssertionError("existing config must be refused")
    assert path.read_text() == "existing private content"


def test_worker_token_is_read_from_systemd_runtime_credentials(tmp_path):
    cred = tmp_path / "worker-token"
    cred.write_text("runtime-only-test-token")
    cfg = linux_worker.load_settings({
        "DESTINEDFX_CONTROL_PLANE_URL": "https://bridge.destinedfx.internal",
        "DESTINEDFX_WORKER_ID": "linux-host-01",
        "CREDENTIALS_DIRECTORY": str(tmp_path),
    })
    assert cfg["worker_token"] == "runtime-only-test-token"


def test_run_once_claims_from_control_plane_with_single_terminal_capacity(tmp_path, monkeypatch):
    cfg = settings(tmp_path)
    calls = []

    def request_json(url, token, method, payload=None, timeout=20):
        calls.append((url, method, payload))
        return sample_job()

    monkeypatch.setattr(worker, "request_json", request_json)
    monkeypatch.setattr(linux_worker, "execute_connect_job", lambda job, settings, **kwargs: "READY")
    assert linux_worker.run_once(cfg) == "READY"
    assert calls == [(
        "https://bridge.destinedfx.internal/v1/worker/jobs/claim",
        "POST",
        {"worker_id": "linux-host-01", "active_terminals": 0, "max_terminals": 1},
    )]


def _env(**extra):
    base = {
        "DESTINEDFX_CONTROL_PLANE_URL": "https://bridge.destinedfx.internal",
        "DESTINEDFX_WORKER_TOKEN": "test-worker-token-not-a-real-secret",
        "DESTINEDFX_WORKER_ID": "linux-host-01",
    }
    base.update(extra)
    return base


def test_loopback_control_plane_needs_explicit_opt_in_and_stays_loopback_only():
    loopback = _env(DESTINEDFX_CONTROL_PLANE_URL="http://127.0.0.1:8000")
    try:
        linux_worker.load_settings(loopback)
        raise AssertionError("loopback http must be rejected without the opt-in")
    except linux_worker.LinuxRunnerError as exc:
        assert exc.code == "INVALID_WORKER_CONFIG"
    allowed = linux_worker.load_settings({**loopback, "DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE": "true"})
    assert allowed["control_plane_url"] == "http://127.0.0.1:8000"
    for remote in ("http://10.0.0.5:8000", "http://example.org:8000", "http://127.0.0.1.evil.net:8000",
                   "http://127.0.0.1:8000/x"):
        try:
            linux_worker.load_settings({**_env(DESTINEDFX_CONTROL_PLANE_URL=remote),
                                        "DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE": "true"})
            raise AssertionError(f"{remote} must be rejected even with the opt-in")
        except linux_worker.LinuxRunnerError as exc:
            assert exc.code == "INVALID_WORKER_CONFIG"


def test_default_journal_directory_is_platform_logs_not_expert_logs():
    cfg = linux_worker.load_settings(_env())
    assert cfg["journal_path"].endswith("/terminal/Logs")
    assert "MQL5" not in cfg["journal_path"]
    agent_source = (ROOT / "scripts" / "linux-host" / "host-agent.py").read_text(encoding="utf-8")
    assert "terminal/MQL5/Logs" not in agent_source


def test_process_control_start_restart_stop_manages_a_real_process_group(tmp_path):
    launcher = tmp_path / "launch.sh"
    launcher.write_text("#!/bin/sh\nexec sleep 300\n", encoding="utf-8")
    launcher.chmod(0o700)
    pidfile = tmp_path / "terminal.pid"
    control = linux_worker.make_process_control(pidfile, launcher, stop_grace_seconds=3.0)
    control("start", "destinedfx-mt5.service")
    first = int(pidfile.read_text().strip())
    assert stat.S_IMODE(pidfile.stat().st_mode) == 0o600
    os.kill(first, 0)
    control("start", "destinedfx-mt5.service")
    assert int(pidfile.read_text().strip()) == first
    control("restart", "destinedfx-mt5.service")
    second = int(pidfile.read_text().strip())
    assert second != first
    try:
        os.kill(first, 0)
        raise AssertionError("old process must be gone after restart")
    except ProcessLookupError:
        pass
    control("stop", "destinedfx-mt5.service")
    assert not pidfile.exists()
    try:
        os.kill(second, 0)
        raise AssertionError("process must be gone after stop")
    except ProcessLookupError:
        pass


def test_process_control_refuses_missing_launcher_and_invalid_action(tmp_path):
    control = linux_worker.make_process_control(tmp_path / "p.pid", tmp_path / "missing.sh")
    for action in ("start", "restart"):
        try:
            control(action, "destinedfx-mt5.service")
            raise AssertionError("missing launcher must fail closed")
        except linux_worker.LinuxRunnerError as exc:
            assert exc.code == "PROCESS_CONTROL_FAILED"
    try:
        control("reload", "destinedfx-mt5.service")
        raise AssertionError("unknown action must be rejected")
    except linux_worker.LinuxRunnerError as exc:
        assert exc.code == "SYSTEMD_ACTION_INVALID"


def test_build_controller_modes_validate_configuration(tmp_path):
    assert linux_worker.build_controller({}) is linux_worker._run_systemctl
    try:
        linux_worker.build_controller({"DESTINEDFX_TERMINAL_CONTROL": "process"})
        raise AssertionError("process mode needs a pidfile and launcher")
    except linux_worker.LinuxRunnerError as exc:
        assert exc.code == "INVALID_WORKER_CONFIG"
    controller = linux_worker.build_controller({
        "DESTINEDFX_TERMINAL_CONTROL": "process",
        "DESTINEDFX_TERMINAL_PIDFILE": str(tmp_path / "t.pid"),
        "DESTINEDFX_TERMINAL_LAUNCHER": str(tmp_path / "l.sh"),
    })
    assert callable(controller)
    try:
        linux_worker.build_controller({"DESTINEDFX_TERMINAL_CONTROL": "bogus"})
        raise AssertionError("unknown control mode must be rejected")
    except linux_worker.LinuxRunnerError as exc:
        assert exc.code == "INVALID_WORKER_CONFIG"
