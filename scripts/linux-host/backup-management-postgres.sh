#!/usr/bin/env bash
set -euo pipefail
umask 077

BACKUP_DIR=/var/backups/destinedfx-management
CONTAINER=destinedfx-management-postgres
DB_NAME=app_management
DB_USER=destinedfx_db_admin
RETENTION_DAYS=14

[[ "${EUID}" -eq 0 ]] || { echo "BLOCKED: management database backup must run as root." >&2; exit 2; }
command -v docker >/dev/null
command -v sha256sum >/dev/null
command -v find >/dev/null
install -d -o root -g root -m 0700 "$BACKUP_DIR"
[[ "$(stat -c '%u:%a' "$BACKUP_DIR")" == "0:700" ]] || {
  echo "BLOCKED: backup directory must be root-owned mode 0700." >&2
  exit 3
}
docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="destinedfx-management-$stamp"
tmp_dump="$BACKUP_DIR/.$name.dump.tmp"
tmp_sha="$BACKUP_DIR/.$name.sha256.tmp"
final_dump="$BACKUP_DIR/$name.dump"
final_sha="$BACKUP_DIR/$name.sha256"
cleanup() { rm -f -- "$tmp_dump" "$tmp_sha"; }
trap cleanup EXIT INT TERM

# PostgreSQL writes its custom-format archive to stdout. No DB password or
# credential is needed on the local Docker socket, and no secret is logged.
docker exec "$CONTAINER" pg_dump --no-password --format=custom --no-owner --no-acl \
  --username="$DB_USER" --dbname="$DB_NAME" > "$tmp_dump"
[[ -s "$tmp_dump" ]] || { echo "BLOCKED: PostgreSQL produced an empty backup." >&2; exit 4; }
chmod 0600 "$tmp_dump"
sha256sum "$tmp_dump" > "$tmp_sha"
chmod 0600 "$tmp_sha"
mv -- "$tmp_dump" "$final_dump"
sha256sum "$final_dump" | awk '{print $1 "  " ENVIRON["BACKUP_NAME"]}' BACKUP_NAME="$name.dump" > "$tmp_sha"
mv -- "$tmp_sha" "$final_sha"

# Delete only backups with our exact name pattern after verifying the file
# extension; this never touches unrelated operator backups.
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'destinedfx-management-*.dump' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'destinedfx-management-*.sha256' -mtime +"$RETENTION_DAYS" -delete
echo "DESTINEDFX_MANAGEMENT_POSTGRES_BACKUP_OK file=$name.dump"
