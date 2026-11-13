import hashlib
import os
import sqlite3
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINUX = ROOT / "scripts" / "linux-host"


def run_script(path, *args, env=None):
    return subprocess.run(
        ["bash", str(path), *map(str, args)],
        cwd=ROOT,
        env={**os.environ, **(env or {})},
        text=True,
        capture_output=True,
        check=False,
    )


def test_sqlite_backup_and_restore_test_round_trip(tmp_path):
    db = tmp_path / "bridge.sqlite3"
    with sqlite3.connect(db) as conn:
        conn.execute("CREATE TABLE fixture (label TEXT NOT NULL, value INTEGER NOT NULL)")
        conn.execute("INSERT INTO fixture VALUES (?, ?)", ("SYNTHETIC_FIXTURE", 42))
    backup_dir = tmp_path / "backups"
    result = run_script(LINUX / "backup.sh", db, backup_dir)
    assert result.returncode == 0, result.stderr
    name = result.stdout.strip().split("BACKUP_OK ", 1)[1]
    backup = Path(name)
    assert backup.exists()
    assert stat.S_IMODE(backup.stat().st_mode) == 0o600
    assert stat.S_IMODE(Path(str(backup) + ".sha256").stat().st_mode) == 0o600
    with sqlite3.connect(backup) as restored_copy:
        assert restored_copy.execute("SELECT label, value FROM fixture").fetchall() == [("SYNTHETIC_FIXTURE", 42)]
    rehearsal = run_script(LINUX / "restore-test.sh", backup)
    assert rehearsal.returncode == 0, rehearsal.stderr
    assert "RESTORE_TEST_OK" in rehearsal.stdout
    # The rehearsal must never replace or mutate the production-source database.
    with sqlite3.connect(db) as original:
        assert original.execute("SELECT label, value FROM fixture").fetchall() == [("SYNTHETIC_FIXTURE", 42)]


def test_restore_test_refuses_a_checksum_mismatch(tmp_path):
    db = tmp_path / "source.sqlite3"
    with sqlite3.connect(db) as conn:
        conn.execute("CREATE TABLE fixture (value TEXT)")
        conn.execute("INSERT INTO fixture VALUES ('SYNTHETIC_FIXTURE')")
    result = run_script(LINUX / "backup.sh", db, tmp_path / "backups")
    assert result.returncode == 0, result.stderr
    backup = Path(result.stdout.strip().split("BACKUP_OK ", 1)[1])
    Path(str(backup) + ".sha256").write_text("0" * 64 + "\n")
    rejected = run_script(LINUX / "restore-test.sh", backup)
    assert rejected.returncode == 4
    assert "SHA-256 mismatch" in rejected.stderr


def _fake_curl_bin(tmp_path, payload):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    curl = bindir / "curl"
    curl.write_text(
        "#!/usr/bin/env python3\n"
        "import pathlib,sys\n"
        "args=sys.argv[1:]\n"
        "out=pathlib.Path(args[args.index('--output')+1])\n"
        f"out.write_bytes({payload!r})\n"
    )
    curl.chmod(0o755)
    return bindir


def test_ea_release_download_installs_only_matching_sha256(tmp_path):
    payload = b"SYNTHETIC_TEST_EA_BINARY_FIXTURE"
    bindir = _fake_curl_bin(tmp_path, payload)
    url = "https://github.com/destinedai/DESTINEDFX-ANALYZER5/releases/download/host-v0.1.0/DestinedFXTraderEA.ex5"
    correct = hashlib.sha256(payload).hexdigest()
    accepted_path = tmp_path / "good" / "DestinedFXTraderEA.ex5"
    accepted = run_script(LINUX / "download-ea-release.sh", url, correct, accepted_path,
                          env={"PATH": f"{bindir}:{os.environ['PATH']}"})
    assert accepted.returncode == 0, accepted.stderr
    assert accepted_path.read_bytes() == payload
    assert stat.S_IMODE(accepted_path.stat().st_mode) == 0o644

    rejected_path = tmp_path / "bad" / "DestinedFXTraderEA.ex5"
    rejected = run_script(LINUX / "download-ea-release.sh", url, "0" * 64, rejected_path,
                          env={"PATH": f"{bindir}:{os.environ['PATH']}"})
    assert rejected.returncode == 4
    assert not rejected_path.exists()


def test_ea_release_downloader_rejects_unpinned_or_non_release_urls(tmp_path):
    dest = tmp_path / "ea.ex5"
    result = run_script(LINUX / "download-ea-release.sh", "https://example.invalid/ea.ex5", "0" * 64, dest)
    assert result.returncode == 3
    assert not dest.exists()


def test_unattended_security_updates_are_idempotent_and_do_not_reboot(tmp_path):
    apt = tmp_path / "apt.conf.d"
    env = {
        "DESTINEDFX_APT_ROOT": str(apt),
        "DESTINEDFX_CODENAME": "noble",
        "DESTINEDFX_SKIP_PACKAGE_INSTALL": "1",
    }
    first = run_script(LINUX / "setup-unattended-upgrades.sh", env=env)
    assert first.returncode == 0, first.stderr
    auto = apt / "20auto-upgrades"
    security = apt / "50unattended-upgrades"
    auto_mtime = auto.stat().st_mtime_ns
    security_mtime = security.stat().st_mtime_ns
    assert 'APT::Periodic::Unattended-Upgrade "1";' in auto.read_text()
    text = security.read_text()
    assert 'codename=noble-security' in text
    assert 'Automatic-Reboot "false"' in text
    assert "${" not in text
    second = run_script(LINUX / "setup-unattended-upgrades.sh", env=env)
    assert second.returncode == 0, second.stderr
    assert auto.stat().st_mtime_ns == auto_mtime
    assert security.stat().st_mtime_ns == security_mtime


def test_rebuild_check_validates_host_package_without_root_changes():
    result = run_script(LINUX / "rebuild.sh", "all", "--check")
    assert result.returncode == 0, result.stderr
    assert "REBUILD_CHECK_OK" in result.stdout
