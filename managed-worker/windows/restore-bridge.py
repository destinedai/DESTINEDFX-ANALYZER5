"""Validate and restore a Windows owner-stack backup into a separate root."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import tomllib
import re


REQUIRED = ("bridge.sqlite3", "owner-secrets.dpapi", "worker-id.txt", "worker.toml")
MAX_BYTES = 20 * 1024 * 1024 * 1024
SAFE_PART = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")


def safe_relative(name: str) -> Path:
    path = Path(name)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"unsafe backup path: {name}")
    if any(not part or any(char not in SAFE_PART for char in part) for part in path.parts):
        raise ValueError(f"unsafe backup path: {name}")
    return path


def validate_snapshot(snapshot: Path) -> dict[str, dict[str, object]]:
    manifest_path = snapshot / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("backup manifest is missing")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("format") != 2:
        raise ValueError("unsupported backup manifest version")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise ValueError("backup manifest is invalid")
    for name in files:
        safe_relative(name)
    if not isinstance(manifest.get("worker_id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", manifest["worker_id"]):
        raise ValueError("backup worker ID is missing")
    actual = {}
    total_bytes = 0
    for path in snapshot.rglob("*"):
        if path.is_dir():
            continue
        relative = path.relative_to(snapshot).as_posix()
        if relative == "manifest.json":
            continue
        size = path.stat().st_size
        total_bytes += size
        if total_bytes > MAX_BYTES:
            raise ValueError("backup exceeds the safety limit")
        actual[relative] = {"size": size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    if set(actual) != set(files):
        raise ValueError("backup file inventory mismatch")
    for name, metadata in files.items():
        if not isinstance(metadata, dict) or metadata.get("size") != actual[name]["size"] or metadata.get("sha256") != actual[name]["sha256"]:
            raise ValueError(f"backup checksum or size mismatch: {name}")
    for name in REQUIRED:
        if name not in actual:
            raise ValueError(f"required recovery file is missing: {name}")
    with sqlite3.connect(snapshot / "bridge.sqlite3") as connection:
        result = connection.execute("PRAGMA integrity_check").fetchone()
    if not result or result[0] != "ok":
        raise ValueError("restored SQLite copy failed integrity_check")
    worker_id = (snapshot / "worker-id.txt").read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", worker_id):
        raise ValueError("backup worker ID is invalid")
    if worker_id != manifest["worker_id"]:
        raise ValueError("backup worker ID does not match the manifest")
    try:
        settings = tomllib.loads((snapshot / "worker.toml").read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as error:
        raise ValueError("worker TOML is invalid") from error
    if not isinstance(settings.get("data_root"), str) or not isinstance(settings.get("control_plane_url"), str):
        raise ValueError("worker TOML is incomplete")
    return files


def restore(snapshot: Path, target_root: Path, replace: bool = False) -> None:
    validate_snapshot(snapshot)
    target_root = target_root.resolve()
    if target_root.exists() and any(target_root.iterdir()) and not replace:
        raise FileExistsError("restore target is not empty; use an empty root or explicit replacement")
    target_root.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".destinedfx-restore-", dir=target_root.parent))
    rollback = target_root.with_name(f"{target_root.name}.rollback")
    try:
        for item in snapshot.rglob("*"):
            relative = item.relative_to(snapshot)
            if relative.as_posix() == "manifest.json":
                continue
            destination = staging / relative
            if item.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, destination)
        shutil.copy2(snapshot / "manifest.json", staging / "manifest.json")
        validate_snapshot(staging)
        activation = staging / "activation"
        (activation / "data").mkdir(parents=True)
        (activation / "config").mkdir(parents=True)
        shutil.move(str(staging / "bridge.sqlite3"), activation / "data" / "bridge.sqlite3")
        for name in ("owner-secrets.dpapi", "worker-id.txt", "worker.toml"):
            shutil.move(str(staging / name), activation / "config" / name)
        if (staging / "accounts").exists():
            shutil.move(str(staging / "accounts"), activation / "accounts")
        if target_root.exists():
            if rollback.exists():
                raise FileExistsError("restore rollback path already exists")
            target_root.rename(rollback)
        activation.rename(target_root)
        if rollback.exists():
            shutil.rmtree(rollback)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        if rollback.exists() and not target_root.exists():
            rollback.rename(target_root)
        raise
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
