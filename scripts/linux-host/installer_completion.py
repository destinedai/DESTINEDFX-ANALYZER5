#!/usr/bin/env python3
"""Create/verify an atomic completion record for the pinned MetaQuotes installer."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

VERSION = 1


class CompletionError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _private_regular(path: Path, *, expected_uid: int | None = None) -> None:
    try:
        info = path.lstat()
    except OSError as exc:
        raise CompletionError(f"required installer artifact is unavailable: {path.name}") from exc
    if not stat.S_ISREG(info.st_mode) or path.is_symlink() or info.st_mode & 0o077:
        raise CompletionError(f"installer artifact permissions or file type are unsafe: {path.name}")
    if expected_uid is not None and info.st_uid != expected_uid:
        raise CompletionError(f"installer artifact owner is unexpected: {path.name}")


def record_completion(
    manifest: Path,
    provenance: Path,
    terminal: Path,
    metaeditor: Path,
    mode: str,
    exit_code: int,
) -> dict[str, Any]:
    if mode not in {"gui", "headless"} or exit_code != 0:
        raise CompletionError("installer completion requires a successful GUI or headless exit")
    uid = os.geteuid()
    for artifact in (provenance, terminal, metaeditor):
        _private_regular(artifact, expected_uid=uid)
    provenance_text = provenance.read_text(encoding="utf-8")
    if (
        "authenticode_status=verified" not in provenance_text.splitlines()
        or "authenticode_signer=MetaQuotes Ltd" not in provenance_text.splitlines()
    ):
        raise CompletionError("MetaQuotes Authenticode provenance is not verified")
    parent = manifest.parent
    parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if parent.is_symlink() or not parent.is_dir():
        raise CompletionError("installer completion directory must be a real directory")
    os.chmod(parent, 0o700)
    value = {
        "version": VERSION,
        "mode": mode,
        "installer_exit_code": exit_code,
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "provenance_path": str(provenance.resolve()),
        "provenance_sha256": sha256(provenance),
        "terminal_path": str(terminal.resolve()),
        "terminal_sha256": sha256(terminal),
        "metaeditor_path": str(metaeditor.resolve()),
        "metaeditor_sha256": sha256(metaeditor),
    }
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix=f".{manifest.name}.", suffix=".tmp",
            dir=parent, delete=False,
        ) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, manifest)
        os.chmod(manifest, 0o600)
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)
    return value


def verify_completion(
    manifest: Path,
    provenance: Path,
    terminal: Path,
    metaeditor: Path,
) -> dict[str, Any]:
    _private_regular(manifest, expected_uid=os.geteuid())
    try:
        value = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise CompletionError("installer completion record is unavailable or malformed") from exc
    if not isinstance(value, dict) or value.get("version") != VERSION or value.get("installer_exit_code") != 0:
        raise CompletionError("installer completion record is not a successful supported record")
    if value.get("mode") not in {"gui", "headless"}:
        raise CompletionError("installer completion mode is invalid")
    for name, path, path_key, hash_key in (
        ("provenance", provenance, "provenance_path", "provenance_sha256"),
        ("terminal", terminal, "terminal_path", "terminal_sha256"),
        ("MetaEditor", metaeditor, "metaeditor_path", "metaeditor_sha256"),
    ):
        _private_regular(path, expected_uid=os.geteuid())
        if value.get(path_key) != str(path.resolve()) or value.get(hash_key) != sha256(path):
            raise CompletionError(f"{name} no longer matches the completed installer record")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("record", "verify"):
        cmd = commands.add_parser(name)
        cmd.add_argument("--manifest", type=Path, required=True)
        cmd.add_argument("--provenance", type=Path, required=True)
        cmd.add_argument("--terminal", type=Path, required=True)
        cmd.add_argument("--metaeditor", type=Path, required=True)
    record = commands.choices["record"]
    record.add_argument("--mode", choices=("gui", "headless"), required=True)
    record.add_argument("--exit-code", type=int, required=True)
    args = parser.parse_args()
    try:
        if args.command == "record":
            record_completion(
                args.manifest, args.provenance, args.terminal, args.metaeditor,
                args.mode, args.exit_code,
            )
            print("PASS: successful installer completion recorded against exact artifacts")
        else:
            result = verify_completion(args.manifest, args.provenance, args.terminal, args.metaeditor)
            print(f"PASS: installer completion record verified ({result['mode']})")
    except (CompletionError, OSError, ValueError, TypeError) as exc:
        print(f"FAIL: installer completion ({exc})")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
