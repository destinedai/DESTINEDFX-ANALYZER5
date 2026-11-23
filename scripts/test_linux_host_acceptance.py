from pathlib import Path
import importlib.util
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "scripts" / "linux-host" / "accept_two_user_runtime.py"
sys.path.insert(0, str(ROOT / "scripts" / "linux-host"))
spec = importlib.util.spec_from_file_location("destinedfx_host_acceptance", MODULE)
acceptance = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(acceptance)


def persistent_linux_config():
    return {
        "status": "configured",
        "host_profile": "PERSISTENT",
        "linux_host_mode": True,
        "per_user_terminal_isolation": "VERIFIED",
        "multi_user_terminal_isolation": "VERIFIED",
        "isolated_slots": 2,
        "max_isolated_slots": 2,
    }


def test_two_user_acceptance_requires_live_persistent_linux_bridge_configuration():
    result = acceptance.validate_persistent_linux_configuration(persistent_linux_config(), expected_users=2)
    assert result["persistent_host_verified"] is True
    assert result["root_attested_slots"] == 2


def test_two_user_acceptance_rejects_ephemeral_codespaces_even_with_reported_slots():
    config = persistent_linux_config()
    config["host_profile"] = "EPHEMERAL"
    try:
        acceptance.validate_persistent_linux_configuration(config, expected_users=2)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PERSISTENT_HOST_PROFILE_REQUIRED"
    else:
        raise AssertionError("An ephemeral Codespaces host must never pass persistent-host acceptance.")


def test_two_user_acceptance_rejects_windows_or_non_linux_worker_runtime():
    config = persistent_linux_config()
    config["linux_host_mode"] = False
    try:
        acceptance.validate_persistent_linux_configuration(config, expected_users=2)
    except acceptance.AcceptanceError as error:
        assert str(error) == "LINUX_HOST_MODE_REQUIRED"
    else:
        raise AssertionError("A Windows compatibility worker must not pass Linux host acceptance.")


def test_two_user_acceptance_rejects_unattested_or_under_capacity_host():
    cases = []
    missing_isolation = persistent_linux_config()
    missing_isolation["multi_user_terminal_isolation"] = "NOT_VERIFIED"
    cases.append((missing_isolation, "ROOT_ATTESTED_MULTI_USER_ISOLATION_REQUIRED"))
    insufficient_active = persistent_linux_config()
    insufficient_active["isolated_slots"] = 1
    cases.append((insufficient_active, "INSUFFICIENT_ROOT_ATTESTED_ACTIVE_SLOTS"))
    insufficient_capacity = persistent_linux_config()
    insufficient_capacity["max_isolated_slots"] = 1
    cases.append((insufficient_capacity, "HOST_SLOT_CAPACITY_BELOW_EXPECTED"))
    for config, expected in cases:
        try:
            acceptance.validate_persistent_linux_configuration(config, expected_users=2)
        except acceptance.AcceptanceError as error:
            assert str(error) == expected
        else:
            raise AssertionError(f"Host acceptance must reject {expected}.")


def test_bridge_acceptance_probe_rejects_non_loopback_origins():
    for url in ("https://bridge.example.com", "http://10.0.0.4:8000", "http://127.0.0.1:8000/path"):
        try:
            acceptance._check_loopback_bridge_url(url)
        except acceptance.AcceptanceError:
            pass
        else:
            raise AssertionError("Real-host acceptance must use only the local loopback Bridge.")


def test_documentation_separates_owner_acceptance_from_ci_fixtures_and_real_trading():
    acceptance_doc = (ROOT / "docs" / "deployment" / "LINUX_HOST_ACCEPTANCE.md").read_text(encoding="utf-8")
    signing_doc = (ROOT / "docs" / "deployment" / "APK_SIGNING.md").read_text(encoding="utf-8")
    assert "Source-code tests" in acceptance_doc
    assert "CI fixture tests" in acceptance_doc
    assert "Host installation verification" in acceptance_doc
    assert "Real two-user isolation acceptance" in acceptance_doc
    assert "debug certificate" in signing_doc.lower()
    assert "fresh debug key" in signing_doc.lower()
