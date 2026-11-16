"""Validate and restore a Windows owner-stack backup into a separate root."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3


REQUIRED = ("bridge.sqlite3", "owner-secrets.dpapi", "worker-id.txt", "worker.toml")


def validate_snapshot(snapshot: Path) -> dict[str, str]:
    manifest_path = snapshot / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("backup manifest is missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise ValueError("backup manifest is invalid")
    for name in REQUIRED:
        path = snapshot / name
        if not path.is_file():
            raise ValueError(f"required recovery file is missing: {name}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != files.get(name):
            raise ValueError(f"backup checksum mismatch: {name}")
    with sqlite3.connect(snapshot / "bridge.sqlite3") as connection:
        result = connection.execute("PRAGMA integrity_check").fetchone()
    if not result or result[0] != "ok":
        raise ValueError("restored SQLite copy failed integrity_check")
    worker_id = (snapshot / "worker-id.txt").read_text(encoding="utf-8").strip()
    if not worker_id or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for char in worker_id):
        raise ValueError("backup worker ID is invalid")
    return files


def restore(snapshot: Path, target_root: Path, replace: bool = False) -> None:
    validate_snapshot(snapshot)
    target_data = target_root / "data"
    target_config = target_root / "config"
    destination_files = {
        "bridge.sqlite3": target_data / "bridge.sqlite3",
        "owner-secrets.dpapi": target_config / "owner-secrets.dpapi",
        "worker-id.txt": target_config / "worker-id.txt",
        "worker.toml": target_config / "worker.toml",
    }
    if not replace and any(path.exists() for path in destination_files.values()):
        raise FileExistsError("restore target is not empty; use an empty root or explicit replacement")
    target_data.mkdir(parents=True, exist_ok=True)
    target_config.mkdir(parents=True, exist_ok=True)
    staging = target_root / ".restore-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    try:
        for name, destination in destination_files.items():
            staged = staging / name
            shutil.copy2(snapshot / name, staged)
            destination.parent.mkdir(parents=True, exist_ok=True)
            staged.replace(destination)
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("target_root", type=Path)
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    try:
        restore(args.snapshot.resolve(), args.target_root.resolve(), args.replace)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"RESTORE BLOCKED: {error}")
        return 2
    print(f"Verified and restored owner-stack state to {args.target_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
