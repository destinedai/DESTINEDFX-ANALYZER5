"""Record owner-stack health and return failure for a Windows scheduled task."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.error
import urllib.request


def check(
    url: str,
    timeout: int,
    data_root: Path | None = None,
    backup_root: Path | None = None,
    max_backup_age_hours: int = 26,
) -> dict[str, object]:
    result: dict[str, object] = {
        "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "url": url,
    }
    for suffix in ("/health/live", "/health/config", "/health/ready"):
        try:
            with urllib.request.urlopen(url.rstrip("/") + suffix, timeout=timeout) as response:
                body = json.loads(response.read(256 * 1024).decode("utf-8"))
                result[suffix.rsplit("/", 1)[-1]] = {"status": response.status, "body": body}
        except urllib.error.HTTPError as error:
            try:
                body = json.loads(error.read(256 * 1024).decode("utf-8"))
            except Exception:
                body = {}
            result[suffix.rsplit("/", 1)[-1]] = {"status": error.code, "body": body}
        except Exception as error:
            result[suffix.rsplit("/", 1)[-1]] = {"status": 0, "error": type(error).__name__}
    live = result["live"]
    config = result["config"]
    ready = result["ready"]
    result["account_ready"] = isinstance(ready, dict) and ready.get("status") == 200
    result["infrastructure_healthy"] = (
        isinstance(live, dict) and live.get("status") == 200 and
        isinstance(config, dict) and config.get("status") == 200
    )
    result["states"] = [
        "BRIDGE_OK" if result["infrastructure_healthy"] else "BRIDGE_UNHEALTHY",
        "ACCOUNT_READY" if result["account_ready"] else "ACCOUNT_NOT_READY",
    ]
    if data_root is not None:
        try:
            usage = shutil.disk_usage(data_root)
            result["disk"] = {
                "path": str(data_root),
                "free_bytes": usage.free,
                "total_bytes": usage.total,
                "free_percent": round(usage.free / usage.total * 100, 2),
            }
            if usage.free / usage.total < 0.10:
                result["states"].append("DISK_LOW")
        except OSError:
            result["states"].append("DISK_UNAVAILABLE")
    if backup_root is not None:
        backups = sorted(backup_root.glob("backup-*.dpapi"), key=lambda path: path.stat().st_mtime, reverse=True)
        if backups:
            age_hours = (dt.datetime.now(dt.timezone.utc).timestamp() - backups[0].stat().st_mtime) / 3600
            result["last_backup"] = {"path": str(backups[0]), "age_hours": round(age_hours, 2)}
            if age_hours > max_backup_age_hours:
                result["states"].append("BACKUP_STALE")
        else:
            result["states"].append("BACKUP_MISSING")
    result["healthy"] = result["infrastructure_healthy"] and "DISK_LOW" not in result["states"] and "BACKUP_STALE" not in result["states"] and "BACKUP_MISSING" not in result["states"]
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=8)
    parser.add_argument("--data-root", type=Path)
    parser.add_argument("--backup-root", type=Path)
    parser.add_argument("--max-backup-age-hours", type=int, default=26)
    args = parser.parse_args()
    result = check(args.url, args.timeout, args.data_root, args.backup_root, args.max_backup_age_hours)
    args.state_file.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.state_file.parent, delete=False) as handle:
        json.dump(result, handle, sort_keys=True, indent=2)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.replace(args.state_file)
    print(json.dumps({"healthy": result["healthy"], "state_file": str(args.state_file)}))
    return 0 if result["healthy"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
