#!/usr/bin/env bash
set -euo pipefail
umask 077

DB_PATH="${1:-${BRIDGE_DB_PATH:-/var/lib/destinedfx/bridge.sqlite3}}"
BACKUP_DIR="${2:-${DESTINEDFX_BACKUP_DIR:-/var/lib/destinedfx/backups}}"
if [[ ! -f "$DB_PATH" || -L "$DB_PATH" ]]; then
  echo "BLOCKED: bridge database is missing or is not a regular file: $DB_PATH" >&2
  exit 2
fi
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
name="destinedfx-$(date -u +%Y%m%dT%H%M%SZ).sqlite3"
dest="$BACKUP_DIR/$name"
tmp="$BACKUP_DIR/.$name.tmp.$$"
side_tmp="$tmp.sha256"
if [[ -e "$dest" || -e "$dest.sha256" ]]; then
  echo "BLOCKED: a backup already exists for this timestamp; refusing to overwrite it." >&2
  exit 5
fi
trap 'rm -f -- "$tmp" "$side_tmp"' EXIT

python3 - "$DB_PATH" "$tmp" <<'PY'
import sqlite3, sys
source_path, dest_path = sys.argv[1:]
source = sqlite3.connect(f"file:{source_path}?mode=ro", uri=True, timeout=10)
try:
    dest = sqlite3.connect(dest_path, timeout=10)
    try:
        source.backup(dest)
        result = dest.execute("PRAGMA integrity_check").fetchone()
        if not result or result[0] != "ok":
            raise SystemExit("BLOCKED: SQLite backup integrity check failed.")
    finally:
        dest.close()
finally:
    source.close()
PY
chmod 600 "$tmp"
sha256sum "$tmp" | awk '{print $1}' > "$side_tmp"
chmod 600 "$side_tmp"
mv -- "$tmp" "$dest"
mv -- "$side_tmp" "$dest.sha256"
trap - EXIT
printf 'BACKUP_OK %s\n' "$dest"
