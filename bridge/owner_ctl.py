#!/usr/bin/env python3
"""Owner control commands for a self-hosted bridge.

  owner_ctl.py issue-code
  owner_ctl.py rotate-tokens
  owner_ctl.py ensure-worker WORKER_ID PRIVATE_TOKEN_FILE

Worker credentials are printed only by neither this command nor the service logs.
The named worker credential is written atomically to a mode-0600 owner-only file.
"""
from __future__ import annotations

import os
import secrets
import sys
from pathlib import Path

import app


def _write_private_token(path: Path, credential: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    temporary = path.with_name(path.name + ".tmp")
    try:
        info = path.lstat()
        if not path.is_file() or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError("WORKER_TOKEN_FILE_PERMISSIONS_INVALID")
    except FileNotFoundError:
        pass
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    except FileExistsError:
        temporary.unlink()
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(credential + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        dir_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
    finally:
        temporary.unlink(missing_ok=True)


def ensure_worker(worker_id: str, token_file: Path) -> None:
    model = app.WorkerProvision(worker_id=worker_id)
    token = ""
    try:
        info = token_file.lstat()
        if (not token_file.is_file() or info.st_uid != os.getuid() or
                info.st_mode & 0o077 or info.st_nlink != 1):
            raise RuntimeError("WORKER_TOKEN_FILE_PERMISSIONS_INVALID")
        token = token_file.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        pass
    if token:
        try:
            current = app.worker_identity(authorization="Bearer " + token)
            if current.get("worker_id") == worker_id and current.get("status") == "ACTIVE":
                return
        except app.HTTPException:
            pass

    bootstrap = os.environ.get("WORKER_BOOTSTRAP_TOKEN", "").strip()
    if len(bootstrap) < 32:
        raise RuntimeError("WORKER_BOOTSTRAP_TOKEN_UNAVAILABLE")
    authorization = "Bearer " + bootstrap
    try:
        result = app.rotate_worker(worker_id, model, authorization=authorization)
    except app.HTTPException as exc:
        if exc.status_code != 404:
            raise
        result = app.provision_worker(model, authorization=authorization)
    credential = str(result.get("credential", ""))
    if not credential or not credential.startswith(worker_id + "."):
        raise RuntimeError("WORKER_CREDENTIAL_ISSUANCE_FAILED")
    _write_private_token(token_file, credential)


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "issue-code":
        code, ttl = app.issue_owner_bootstrap_code()
        print(f"{code} (one-time, valid {ttl // 60} minutes)")
        return 0
    if len(argv) == 2 and argv[1] == "rotate-tokens":
        print(f"Revoked app tokens for {app.rotate_all_app_tokens()} pairing(s). Apps must re-pair.")
        return 0
    if len(argv) == 4 and argv[1] == "ensure-worker":
        ensure_worker(argv[2], Path(argv[3]))
        print(f"Named worker identity ready: {argv[2]}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except app.HTTPException as exc:
        # The response contains a bounded operational reason, never credentials.
        detail = exc.detail if isinstance(exc.detail, str) else exc.detail.get("code", "OWNER_CONTROL_FAILED")
        print(str(detail), file=sys.stderr)
        raise SystemExit(2)
    except (OSError, RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2)
