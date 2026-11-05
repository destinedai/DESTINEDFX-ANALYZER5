"""Create a consistent SQLite online backup of the owner-operated bridge DB."""
from __future__ import annotations
import datetime as dt
import os
from pathlib import Path
import shutil
import sqlite3

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
for old in sorted(target_dir.glob("backup-*"), key=lambda p: p.stat().st_mtime, reverse=True)[14:]:
    if old.is_dir():
        shutil.rmtree(old, ignore_errors=True)
    else:
        old.unlink(missing_ok=True)
print(f"Verified SQLite and owner-config backup: {snapshot_dir}")
