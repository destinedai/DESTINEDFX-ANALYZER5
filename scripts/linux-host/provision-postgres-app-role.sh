#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ "${EUID}" -eq 0 ]] || { echo "BLOCKED: PostgreSQL role provisioning must run as root." >&2; exit 2; }
: "${POSTGRES_APP_PASSWORD_FILE:?systemd app-password credential path is missing}"
DB_NAME=app_management
ADMIN_USER=destinedfx_db_admin
APP_USER=destinedfx_app
CONTAINER=destinedfx-management-postgres

[[ -f "$POSTGRES_APP_PASSWORD_FILE" && ! -L "$POSTGRES_APP_PASSWORD_FILE" ]] || {
  echo "BLOCKED: protected PostgreSQL app credential is missing." >&2
  exit 3
}
mode="$(stat -c '%a' "$POSTGRES_APP_PASSWORD_FILE")"
[[ "$mode" == "400" || "$mode" == "600" ]] || {
  echo "BLOCKED: PostgreSQL app credential permissions are too broad." >&2
  exit 3
}
app_password="$(cat -- "$POSTGRES_APP_PASSWORD_FILE")"
[[ "$app_password" =~ ^[A-Za-z0-9_-]{48,128}$ ]] || {
  echo "BLOCKED: PostgreSQL app credential format is invalid." >&2
  exit 4
}

# Password is sent only over stdin to psql inside the local container; it is
# never included in process arguments, environment variables, SQL logs or output.
{
  printf '%s\n' "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_USER') THEN CREATE ROLE $APP_USER LOGIN; END IF; END \$\$;"
  printf '%s\n' "ALTER ROLE $APP_USER WITH LOGIN PASSWORD '$app_password';"
  printf '%s\n' "ALTER DATABASE $DB_NAME OWNER TO $APP_USER;"
  printf '%s\n' "\\connect $DB_NAME"
  printf '%s\n' "ALTER SCHEMA public OWNER TO $APP_USER;"
  printf '%s\n' "GRANT ALL PRIVILEGES ON SCHEMA public TO $APP_USER;"
  printf '%s\n' "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $APP_USER;"
  printf '%s\n' "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $APP_USER;"
  printf '%s\n' "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $APP_USER;"
  printf '%s\n' "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $APP_USER;"
} | docker exec -i "$CONTAINER" psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --username="$ADMIN_USER" --dbname=postgres

unset app_password
echo "DESTINEDFX_POSTGRES_APP_ROLE_READY role=$APP_USER database=$DB_NAME superuser=false"
