from pathlib import Path
import importlib.util
import os
import subprocess
import sys
from unittest.mock import patch

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


def test_mt5_parity_matrix_marks_native_terminal_gaps_and_keeps_them_unavailable():
    matrix = (ROOT / "docs" / "mt5-phone-remote-controller-matrix.md").read_text(encoding="utf-8")
    assert "No pending-order creation form" in matrix
    assert "monotonic SL tightening" in matrix
    assert "No verified command to change the actual MT5 terminal chart timeframe" in matrix
    assert "No remote add/remove/configure interface" in matrix
    assert "No phone Depth screen" in matrix
    assert "No terminal-native MT5 news stream" in matrix
    assert "must remain visibly unavailable" in matrix



def test_acceptance_private_directory_rejects_public_mode_and_symlink(tmp_path):
    directory = tmp_path / "private"
    directory.mkdir()
    directory.chmod(0o700)
    acceptance._private_directory(directory, owner_uid=os.getuid(), mode=0o700)

    directory.chmod(0o755)
    try:
        acceptance._private_directory(directory, owner_uid=os.getuid(), mode=0o700)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PRIVATE_DIRECTORY_MODE_INVALID"
    else:
        raise AssertionError("A group/world-accessible slot directory must be rejected.")

    alias = tmp_path / "private-alias"
    alias.symlink_to(directory, target_is_directory=True)
    try:
        acceptance._private_directory(alias, owner_uid=os.getuid(), mode=0o700)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PRIVATE_DIRECTORY_TYPE_INVALID"
    else:
        raise AssertionError("A symlink must not stand in for a private slot directory.")


def test_acceptance_xauthority_requires_private_regular_single_link_file(tmp_path):
    auth = tmp_path / ".Xauthority"
    auth.write_bytes(b"test-only-fake-cookie")
    auth.chmod(0o600)
    acceptance._private_regular_file(auth, owner_uid=os.getuid(), mode=0o600)

    auth.chmod(0o644)
    try:
        acceptance._private_regular_file(auth, owner_uid=os.getuid(), mode=0o600)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PRIVATE_FILE_OWNERSHIP_OR_MODE_INVALID"
    else:
        raise AssertionError("Xauthority with widened permissions must be rejected.")

    auth.chmod(0o600)
    hardlink = tmp_path / "xauthority-hardlink"
    hardlink.hardlink_to(auth)
    try:
        acceptance._private_regular_file(auth, owner_uid=os.getuid(), mode=0o600)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PRIVATE_FILE_TYPE_INVALID"
    else:
        raise AssertionError("A hard-linked Xauthority file must be rejected.")

    symlink = tmp_path / "xauthority-symlink"
    symlink.symlink_to(auth)
    try:
        acceptance._private_regular_file(symlink, owner_uid=os.getuid(), mode=0o600)
    except acceptance.AcceptanceError as error:
        assert str(error) == "PRIVATE_FILE_TYPE_INVALID"
    else:
        raise AssertionError("An Xauthority symlink must be rejected.")


def test_cross_user_permission_probe_timeout_is_sanitized():
    from pathlib import Path
    slots = [
        (10001, "destinedfx-slot-a", Path("/private/a/.Xauthority")),
        (10002, "destinedfx-slot-b", Path("/private/b/.Xauthority")),
    ]
    with patch.object(acceptance.subprocess, "run", side_effect=subprocess.TimeoutExpired("sudo", 5)):
        try:
            acceptance._deny_cross_slot_access(slots)
        except acceptance.AcceptanceError as error:
            assert str(error) == "CROSS_USER_PERMISSION_PROBE_FAILED"
        else:
            raise AssertionError("A timed-out permission probe cannot be reported as successful.")



def test_acceptance_terminal_executable_rejects_symlink_and_non_absolute_path(tmp_path):
    executable = tmp_path / "terminal64.exe"
    executable.write_bytes(b"fixture-not-a-real-terminal")
    executable.chmod(0o700)
    acceptance._private_executable(executable, owner_uid=os.getuid())

    alias = tmp_path / "terminal-link.exe"
    alias.symlink_to(executable)
    try:
        acceptance._private_executable(alias, owner_uid=os.getuid())
    except acceptance.AcceptanceError as error:
        assert str(error) == "MT5_TERMINAL_EXECUTABLE_TYPE_INVALID"
    else:
        raise AssertionError("A terminal executable symlink must not pass the slot acceptance check.")

    try:
        acceptance._private_executable(Path("relative/terminal64.exe"), owner_uid=os.getuid())
    except acceptance.AcceptanceError as error:
        assert str(error) == "MT5_TERMINAL_EXECUTABLE_PATH_NOT_ABSOLUTE"
    else:
        raise AssertionError("A relative terminal path must not pass owner-host acceptance.")


def test_acceptance_cli_status_is_plain_and_actionable():
    assert acceptance.acceptance_status_line(True) == "ACCEPTED: true - LIVE UNLOCKED"
    failed = acceptance.acceptance_status_line(False, "NOT_ENOUGH_ACTIVE_USER_SLOTS")
    assert failed == (
        "FAILED: NOT_ENOUGH_ACTIVE_USER_SLOTS - fix: "
        "sudo systemctl status destinedfx-linux-slot-worker.service --no-pager --full"
    )
    assert acceptance.acceptance_status_line(False, "HOST_AGENT_HEALTH_PROBE_FAILED").endswith(
        "sudo systemctl status destinedfx-host-agent.service --no-pager --full"
    )
    assert acceptance.acceptance_status_line(False, "UNCLASSIFIED").endswith(
        "sudo /opt/destinedfx/scripts/linux-host/host-doctor.sh"
    )
