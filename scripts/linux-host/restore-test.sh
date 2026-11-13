#!/usr/bin/env bash
set -euo pipefail
umask 077

BACKUP="${1:-}"
if [[ -z "$BACKUP" || ! -f "$BACKUP" || -L "$BACKUP" ]]; then
  echo "Usage: restore-test.sh /path/to/destinedfx-YYYYMMDDTHHMMSSZ.sqlite3" >&2
  exit 2
fi
CHECKSUM_FILE="$BACKUP.sha256"
if [[ ! -f "$CHECKSUM_FILE" || -L "$CHECKSUM_FILE" ]]; then
  echo "BLOCKED: backup SHA-256 sidecar is missing or not a regular file." >&2
  exit 3
fi
expected="$(cat -- "$CHECKSUM_FILE")"
if [[ ! "$expected" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: backup SHA-256 sidecar is malformed." >&2
  exit 3
fi
actual="$(sha256sum -- "$BACKUP" | awk '{print $1}')"
if [[ "${expected,,}" != "${actual,,}" ]]; then
  echo "BLOCKED: backup SHA-256 mismatch; restore test refused." >&2
  exit 4
fi
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
chmod 700 "$work"
restored="$work/restored.sqlite3"
python3 - "$BACKUP" "$restored" <<'PY'
import sqlite3, sys
source_path, dest_path = sys.argv[1:]
source = sqlite3.connect(f"file:{source_path}?mode=ro", uri=True, timeout=10)
try:
    dest = sqlite3.connect(dest_path, timeout=10)
    try:
        source.backup(dest)
        result = dest.execute("PRAGMA integrity_check").fetchone()
        if not result or result[0] != "ok":
            raise SystemExit("BLOCKED: restored SQLite copy failed integrity_check.")
    finally:
        dest.close()
finally:
    source.close()
PY
chmod 600 "$restored"
printf 'RESTORE_TEST_OK; live database was not changed. Restored copy was temporary and removed.\n'
