import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import doctor  # noqa: E402


def _settings(tmp_path):
    terminal = tmp_path / "terminal64.exe"
    metaeditor = tmp_path / "metaeditor64.exe"
    ea = tmp_path / "DestinedFXTraderEA.mq5"
    for path in (terminal, metaeditor, ea):
        path.write_text("fixture")
    return {
        "control_plane_url": "https://bridge.example.test",
        "data_root": str(tmp_path / "accounts"),
        "ea_source": str(ea),
        "broker_terminals": {"FBS": {
            "terminal_exe": str(terminal),
            "metaeditor_exe": str(metaeditor),
        }},
    }


def _args(python_check=False):
    return type("Args", (), {
        "python_check": python_check,
        "login": None,
        "password": None,
        "server": None,
    })()


def _patch_terminal(monkeypatch, tmp_path, marker):
    settings = _settings(tmp_path)
    monkeypatch.setattr(doctor.worker, "settings_for_broker", lambda settings, brand: settings["broker_terminals"][brand])
    monkeypatch.setattr(doctor.worker, "terminal_records", lambda root: [(tmp_path / "marker.json", marker)] if marker else [])
    return settings


def test_http_200_alone_is_not_ready_and_exit_contract_is_blocked(monkeypatch, tmp_path):
    settings = _patch_terminal(monkeypatch, tmp_path, {"broker_brand": "FBS", "job_id": "job-1", "status": "READY"})

    def request(url, token=None, timeout=8):
        if url.endswith("/health/ready"):
            return 200, {"status": "ready"}
        return 200, {"status": "AUTHENTICATING"}

    monkeypatch.setattr(doctor, "safe_request", request)
    result = doctor.inspect_one("FBS", settings, "worker-token", _args())

    assert result["doctor_status"] == "BLOCKED"
    assert result["pairing_state"] == "PAIRING_OR_AUTHENTICATION_REQUIRED"
    assert result["webrequest_allowed"] != "YES_BY_FRESH_EA_HEARTBEAT"


def test_unreachable_bridge_missing_terminal_and_missing_pairing_fail_closed(monkeypatch, tmp_path):
    settings = _patch_terminal(monkeypatch, tmp_path, None)
    (tmp_path / "terminal64.exe").unlink()
    monkeypatch.setattr(doctor, "safe_request", lambda *args, **kwargs: (0, {}))

    result = doctor.inspect_one("FBS", settings, "", _args())

    assert result["doctor_status"] == "BLOCKED"
    assert result["bridge_reachability"] == "UNREACHABLE"
    assert result["pairing_state"] == "NO_LOCAL_TERMINAL_PAIRING"
    assert result["terminal_path"]["found"] is False


@pytest.mark.parametrize("job_status", ["AUTHENTICATING", "FAILED", "READY"])
def test_stale_or_wrong_identity_never_becomes_verified(monkeypatch, tmp_path, job_status):
    marker = {
        "broker_brand": "FBS", "job_id": "job-1", "status": "READY",
        "expected_login": "123", "expected_server": "FBS-Demo", "expected_company": "FBS",
    }
    settings = _patch_terminal(monkeypatch, tmp_path, marker)

    def request(url, token=None, timeout=8):
        if url.endswith("/health/ready"):
            return 503, {"detail": {"code": "NO_VERIFIED_TERMINAL_PAIRING"}}
        if job_status == "READY":
            return 409, {"status": "AUTHENTICATING", "error_code": "TERMINAL_HEARTBEAT_STALE"}
        return 200, {"status": job_status, "error_code": "TERMINAL_HEARTBEAT_STALE"}

    monkeypatch.setattr(doctor, "safe_request", request)
    result = doctor.inspect_one("FBS", settings, "worker-token", _args())

    assert result["doctor_status"] == "BLOCKED"
    assert result["webrequest_allowed"] != "YES_BY_FRESH_EA_HEARTBEAT"
    assert result["reported_identity"]["server"] == "FBS-Demo"


def test_main_never_prints_worker_or_mt5_password(monkeypatch, tmp_path, capsys):
    config = tmp_path / "worker.toml"
    config.write_text("fixture")
    monkeypatch.setattr(doctor.worker, "load_settings", lambda path: _settings(tmp_path))
    monkeypatch.setattr(doctor, "inspect_one", lambda brand, settings, token, args: {"broker": brand, "doctor_status": "BLOCKED"})
    monkeypatch.setenv("DESTINEDFX_WORKER_TOKEN", "worker-secret-token")

    exit_code = doctor.main(["--config", str(config), "--broker", "FBS"])
    output = capsys.readouterr().out

    assert exit_code == 2
    assert "worker-secret-token" not in output
    assert "password" not in output.lower()
    payload = json.loads(output)
    assert payload["brokers"][0]["doctor_status"] == "BLOCKED"


def test_safe_request_attaches_optional_cloudflare_headers(monkeypatch, caplog):
    import io
    import urllib.request
    captured = {}
    class Response(io.BytesIO):
        status = 200
    def open_request(req, timeout):
        captured.update(req.header_items())
        return Response(b"{}")
    monkeypatch.setenv("CF_ACCESS_CLIENT_ID", "doctor-id-test")
    monkeypatch.setenv("CF_ACCESS_CLIENT_SECRET", "doctor-secret-test")
    monkeypatch.setattr(urllib.request, "urlopen", open_request)
    assert doctor.safe_request("https://bridge.invalid/health/ready")[0] == 200
    headers = {key.lower(): value for key, value in captured.items()}
    assert headers["cf-access-client-id"] == "doctor-id-test"
    assert headers["cf-access-client-secret"] == "doctor-secret-test"
    assert "doctor-secret-test" not in caplog.text


def test_safe_request_omits_partial_cloudflare_credentials(monkeypatch):
    import io
    import urllib.request
    captured = {}
    class Response(io.BytesIO):
        status = 200
    def open_request(req, timeout):
        captured.update(req.header_items())
        return Response(b"{}")
    monkeypatch.setenv("CF_ACCESS_CLIENT_ID", "doctor-id-test")
    monkeypatch.delenv("CF_ACCESS_CLIENT_SECRET", raising=False)
    monkeypatch.setattr(urllib.request, "urlopen", open_request)
    doctor.safe_request("https://bridge.invalid/health/ready")
    assert not any(key.lower().startswith("cf-access-client-") for key in captured)


def test_safe_request_without_cloudflare_credentials_keeps_headers_unchanged(monkeypatch):
    import io
    import urllib.request
    captured = {}
    class Response(io.BytesIO):
        status = 200
    def open_request(req, timeout):
        captured.update(req.header_items())
        return Response(b"{}")
    monkeypatch.delenv("CF_ACCESS_CLIENT_ID", raising=False)
    monkeypatch.delenv("CF_ACCESS_CLIENT_SECRET", raising=False)
    monkeypatch.setattr(urllib.request, "urlopen", open_request)
    assert doctor.safe_request("https://bridge.invalid/health/ready", token="worker-token")[0] == 200
    assert not any(key.lower().startswith("cf-access-client-") for key in captured)
    assert captured["Authorization"] == "Bearer worker-token"
