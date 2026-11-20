#!/usr/bin/env python3
"""Record and verify successful execution of the pinned MetaQuotes installer."""
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


def _check_file(path: Path, uid: int, *, private: bool) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as exc:
        raise CompletionError(f"installer artifact is unavailable: {path.name}") from exc
    if not stat.S_ISREG(info.st_mode) or path.is_symlink() or info.st_uid != uid:
        raise CompletionError(f"installer artifact type or owner is unsafe: {path.name}")
    if private and info.st_mode & 0o077:
        raise CompletionError(f"installer metadata permissions are unsafe: {path.name}")
    return info


def _fingerprint(path: Path, uid: int, *, private: bool) -> str:
    _check_file(path, uid, private=private)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise CompletionError(f"cannot securely open installer artifact: {path.name}") from exc
    digest = hashlib.sha256()
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != uid:
            raise CompletionError(f"installer artifact changed during verification: {path.name}")
        if private and info.st_mode & 0o077:
            raise CompletionError(f"installer metadata permissions changed: {path.name}")
        while True:
            chunk = os.read(fd, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    finally:
        os.close(fd)
    return digest.hexdigest()


def _read_private_json(path: Path, uid: int) -> dict[str, Any]:
    _check_file(path, uid, private=True)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise CompletionError("installer completion record cannot be opened safely") from exc
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != uid or info.st_mode & 0o077:
            raise CompletionError("installer completion record changed during verification")
        raw = bytearray()
        while True:
            part = os.read(fd, 65536)
            if not part:
                break
            raw.extend(part)
            if len(raw) > 65536:
                raise CompletionError("installer completion record exceeds the size limit")
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError) as exc:
        raise CompletionError("installer completion record is malformed") from exc
    finally:
        os.close(fd)
    if not isinstance(value, dict):
        raise CompletionError("installer completion record must be an object")
    return value


def _validate_provenance(provenance: Path, uid: int) -> str:
    digest = _fingerprint(provenance, uid, private=True)
    try:
        raw = provenance.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise CompletionError("MetaQuotes provenance is unavailable") from exc
    if (
        "authenticode_status=verified" not in raw
        or "authenticode_signer=MetaQuotes Ltd" not in raw
    ):
        raise CompletionError("MetaQuotes Authenticode provenance is not verified")
    return digest


def _validate_manifest_parent(parent: Path, uid: int) -> None:
    try:
        info = parent.lstat()
        resolved = parent.resolve(strict=True)
    except OSError as exc:
        raise CompletionError("installer completion directory is unavailable") from exc
    if (
        not stat.S_ISDIR(info.st_mode)
        or parent.is_symlink()
        or info.st_uid != uid
        or info.st_mode & 0o077
        or resolved != Path(os.path.abspath(parent))
    ):
        raise CompletionError("installer completion directory must be owner-private and non-symlinked")


def record_completion(
    manifest: Path,
    provenance: Path,
    terminal: Path,
    metaeditor: Path,
    mode: str,
    exit_code: int,
) -> dict[str, Any]:
    if mode not in {"gui", "headless"} or exit_code != 0:
        raise CompletionError("installer completion requires successful GUI/headless setup exit")
    uid = os.geteuid()
    provenance_hash = _validate_provenance(provenance, uid)
    terminal_hash = _fingerprint(terminal, uid, private=False)
    editor_hash = _fingerprint(metaeditor, uid, private=False)
    _validate_manifest_parent(manifest.parent, uid)
    value = {
        "version": VERSION,
        "mode": mode,
        "installer_exit_code": 0,
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "provenance_path": str(provenance.resolve()),
        "provenance_sha256": provenance_hash,
        "terminal_path": str(terminal.resolve()),
        "terminal_sha256": terminal_hash,
        "metaeditor_path": str(metaeditor.resolve()),
        "metaeditor_sha256": editor_hash,
    }
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix=f".{manifest.name}.", suffix=".tmp",
            dir=manifest.parent, delete=False,
        ) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, manifest)
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
    uid = os.geteuid()
    value = _read_private_json(manifest, uid)
    if value.get("version") != VERSION or value.get("installer_exit_code") != 0:
        raise CompletionError("installer completion record does not prove a successful supported install")
    if value.get("mode") not in {"gui", "headless"}:
        raise CompletionError("installer completion mode is invalid")
    checks = (
        ("provenance", provenance, "provenance_path", "provenance_sha256", True),
        ("terminal", terminal, "terminal_path", "terminal_sha256", False),
        ("MetaEditor", metaeditor, "metaeditor_path", "metaeditor_sha256", False),
    )
    for label, path, path_key, hash_key, private in checks:
        fingerprint = _fingerprint(path, uid, private=private)
        if value.get(path_key) != str(path.resolve()) or value.get(hash_key) != fingerprint:
            raise CompletionError(f"{label} no longer matches the successful installer record")
    _validate_provenance(provenance, uid)
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
            value = verify_completion(args.manifest, args.provenance, args.terminal, args.metaeditor)
            print(f"PASS: installer completion record verified ({value['mode']})")
    except (CompletionError, OSError, ValueError, TypeError) as exc:
        print(f"FAIL: installer completion ({exc})")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
