"""Create a consistent SQLite online backup of the owner-operated bridge DB."""
from __future__ import annotations
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tomllib

root = Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / "DestinedFX"
source = root / "data" / "bridge.sqlite3"
target_dir = root / "backups"
target_dir.mkdir(parents=True, exist_ok=True)
if not source.exists():
    raise SystemExit("Bridge database not found; no backup created.")
stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
snapshot_dir = target_dir / f"backup-{stamp}"
snapshot_dir.mkdir()
target = snapshot_dir / "bridge.sqlite3"
worker_config = root / "config" / "worker.toml"
try:
    settings = tomllib.loads(worker_config.read_text(encoding="utf-8"))
    accounts_root = Path(settings["data_root"]).expanduser()
except (OSError, KeyError, TypeError, tomllib.TOMLDecodeError) as error:
    shutil.rmtree(snapshot_dir, ignore_errors=True)
    raise SystemExit(f"Worker data root could not be resolved: {error}") from error
if not accounts_root.is_absolute():
    accounts_root = (worker_config.parent / accounts_root).resolve()
else:
    accounts_root = accounts_root.resolve()
if not accounts_root.is_dir():
    shutil.rmtree(snapshot_dir, ignore_errors=True)
    raise SystemExit(f"Required accounts directory missing: {accounts_root}")


def excluded(relative: Path) -> bool:
    name = relative.name.lower()
    return (
        name.startswith("startup-") and name.endswith(".ini")
        or name.startswith("restart-") and name.endswith(".ini")
        or name.endswith((".pid", ".lock", ".tmp"))
        or name in {"compile.log", "terminal.ini"}
        or ".quarantine" in relative.parts
    )


def copy_accounts() -> None:
    destination = snapshot_dir / "accounts"
    for item in accounts_root.rglob("*"):
        relative = item.relative_to(accounts_root)
        if excluded(relative):
            continue
        if item.is_symlink():
            raise SystemExit(f"Refusing to back up symlinked account state: {relative}")
        target_item = destination / relative
        if item.is_dir():
            target_item.mkdir(parents=True, exist_ok=True)
        elif item.is_file():
            target_item.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target_item)


with sqlite3.connect(f"file:{source.as_posix()}?mode=ro", uri=True, timeout=15) as src:
    with sqlite3.connect(target, timeout=15) as dst:
        src.backup(dst)
        result = dst.execute("PRAGMA integrity_check").fetchone()
        if not result or result[0] != "ok":
            dst.close()
            shutil.rmtree(snapshot_dir, ignore_errors=True)
            raise SystemExit("Backup integrity check failed; backup removed.")
for name in ("config/owner-secrets.dpapi", "config/worker-id.txt", "config/worker.toml"):
    item = root / name
    if not item.is_file():
        shutil.rmtree(snapshot_dir, ignore_errors=True)
        raise SystemExit(f"Required recovery file missing: {item}")
    shutil.copy2(item, snapshot_dir / item.name)
copy_accounts()
worker_id = (root / "config" / "worker-id.txt").read_text(encoding="utf-8").strip()
host_generation = os.environ.get("HOST_GENERATION", "").strip()
manifest = {
    "format": 2,
    "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    "worker_id": worker_id,
    "host_generation": host_generation,
    "files": {},
}
total_bytes = 0
for item in sorted(path for path in snapshot_dir.rglob("*") if path.is_file()):
    relative = item.relative_to(snapshot_dir).as_posix()
    size = item.stat().st_size
    total_bytes += size
    if total_bytes > 20 * 1024 * 1024 * 1024:
        shutil.rmtree(snapshot_dir, ignore_errors=True)
        raise SystemExit("Backup exceeds the 20 GiB safety limit.")
    manifest["files"][relative] = {
        "size": size,
        "sha256": hashlib.sha256(item.read_bytes()).hexdigest(),
    }
(snapshot_dir / "manifest.json").write_text(
    json.dumps(manifest, sort_keys=True, indent=2) + "\n",
    encoding="utf-8",
)
for old in sorted(target_dir.glob("backup-*"), key=lambda p: p.stat().st_mtime, reverse=True)[14:]:
    if old.is_dir():
        shutil.rmtree(old, ignore_errors=True)
    else:
        old.unlink(missing_ok=True)
print(f"Verified SQLite and owner-config backup: {snapshot_dir}")
