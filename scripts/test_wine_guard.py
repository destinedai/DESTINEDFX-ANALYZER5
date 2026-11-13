import os
import shlex
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
GUARD = ROOT / "scripts" / "linux-host" / "wine-guard.sh"


def fake_wine(tmp_path, version, call_log=None):
    bindir = tmp_path / "bin"
    bindir.mkdir(exist_ok=True)
    wine = bindir / "wine"
    log_line = f"printf '%s\\n' \"$*\" >> {shlex.quote(str(call_log))}\n" if call_log else ""
    wine.write_text(f"#!/bin/sh\n{log_line}printf '%s\\n' {version!r}\n")
    wine.chmod(0o755)
    return bindir


def run_guard(bindir, *args, env=None):
    return subprocess.run(
        ["bash", str(GUARD), *args],
        cwd=ROOT,
        env={**os.environ, "WINE_BIN": "wine", "PATH": f"{bindir}:{os.environ['PATH']}", **(env or {})},
        text=True,
        capture_output=True,
        check=False,
    )


@pytest.mark.parametrize(
    ("version", "allowed"),
    [
        ("wine-9.0", True),
        ("wine-9.22", True),
        ("wine-10.0", True),
        ("wine-10.2", True),
        ("wine-10.3", False),
        ("wine-10.9", False),
        ("wine-11.0", False),
        ("wine-11.7", False),
        ("wine-8.21", False),
        ("garbage", False),
    ],
)
def test_version_policy(version, allowed, tmp_path):
    result = run_guard(fake_wine(tmp_path, version))
    assert (result.returncode == 0) is allowed
    reported_version = version if version.startswith("wine-") else "unparsable (output redacted)"
    assert f"Wine version: {reported_version}" in result.stdout
    assert f"Wine version allowed: {'PASS' if allowed else 'FAIL'}" in result.stdout
    if not allowed:
        assert "sudo bash scripts/linux-host/wine-guard.sh --fix" in result.stderr


def test_fix_refuses_non_project_prefix_before_privileged_operations(tmp_path):
    bindir = fake_wine(tmp_path, "wine-10.3")
    external_prefix = tmp_path / "not-the-project-prefix"
    external_prefix.mkdir()
    result = run_guard(
        bindir,
        "--fix",
        env={"HOME": str(tmp_path), "WINEPREFIX": str(external_prefix)},
    )
    assert result.returncode != 0
    assert "refusing to remove a Wine prefix other than the project prefix" in result.stderr
    assert external_prefix.is_dir()
    assert "apt-get" not in result.stderr


def test_report_redacts_external_prefix_path(tmp_path):
    bindir = fake_wine(tmp_path, "wine-10.0")
    private_prefix = tmp_path / "private-prefix-name"
    result = run_guard(
        bindir,
        "--report",
        env={"WINEPREFIX": str(private_prefix)},
    )
    assert result.returncode == 0
    assert str(private_prefix) not in result.stdout
    assert "WINEPREFIX: [external path redacted]" in result.stdout


def test_installer_stops_before_wine_setup_for_disallowed_version(tmp_path):
    call_log = tmp_path / "wine.calls"
    bindir = fake_wine(tmp_path, "wine-10.3", call_log)
    result = subprocess.run(
        ["bash", str(ROOT / "scripts/linux-host/install-mt5.sh"), "--headless"],
        cwd=ROOT,
        env={**os.environ, "WINE_BIN": "wine", "PATH": f"{bindir}:{os.environ['PATH']}", "HOME": str(tmp_path)},
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 28
    assert "unsupported" in result.stderr
    assert call_log.read_text().splitlines() == ["--version"]


def test_gui_stops_before_starting_xvfb_for_disallowed_version(tmp_path):
    bindir = fake_wine(tmp_path, "wine-10.3")
    marker = tmp_path / "xvfb.started"
    for name in ("x11vnc", "websockify"):
        command = bindir / name
        command.write_text("#!/bin/sh\nexit 0\n")
        command.chmod(0o755)
    xvfb = bindir / "Xvfb"
    xvfb.write_text(f"#!/bin/sh\n: > {shlex.quote(str(marker))}\n")
    xvfb.chmod(0o755)
    state = tmp_path / "state"
    python = state / "venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("#!/bin/sh\nexit 0\n")
    python.chmod(0o755)
    result = subprocess.run(
        ["bash", str(ROOT / "deploy/codespaces/gui.sh")],
        cwd=ROOT,
        env={
            **os.environ,
            "WINE_BIN": "wine",
            "PATH": f"{bindir}:{os.environ['PATH']}",
            "HOME": str(tmp_path),
            "CODESPACE_NAME": "test-codespace",
            "DESTINEDFX_CODESPACES_STATE_DIR": str(state),
        },
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode != 0
    assert "Wine version allowed check failed" in result.stderr
    assert not marker.exists()
