"""Record owner-stack health and return failure for a Windows scheduled task."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import tempfile
import urllib.error
import urllib.request


def check(url: str, timeout: int) -> dict[str, object]:
    result: dict[str, object] = {
        "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "url": url,
    }
    for suffix in ("/health/live", "/health/ready"):
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
    ready = result["ready"]
    result["healthy"] = (
        isinstance(live, dict) and live.get("status") == 200 and
        isinstance(ready, dict) and ready.get("status") == 200
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=8)
    args = parser.parse_args()
    result = check(args.url, args.timeout)
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
