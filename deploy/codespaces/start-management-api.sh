#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
MANAGEMENT_DIR="$STATE/management"
RUNTIME="$STATE/runtime"
VENV="$STATE/management-venv"
ENV_FILE="$MANAGEMENT_DIR/management.env"
PASS_FILE="$MANAGEMENT_DIR/postgres-password"
PRIVATE_KEY="$MANAGEMENT_DIR/step-up-private.pem"
PUBLIC_KEY="$MANAGEMENT_DIR/step-up-public.pem"
OWNER_TOKEN="$MANAGEMENT_DIR/owner-setup-token"

fail() { printf 'BLOCKED: %s\n' "$*" >&2; exit 2; }
pass() { printf 'PASS: %s\n' "$*"; }

[[ "${CODESPACES:-}" == "true" && -n "${CODESPACE_NAME:-}" ]] || fail "This Management API launcher is for the DestinedFX GitHub Codespaces host."
command -v sudo >/dev/null && sudo -n true >/dev/null 2>&1 || fail "Passwordless sudo is required for the private Codespaces PostgreSQL service."
command -v pg_ctlcluster >/dev/null || fail "PostgreSQL server packages are missing from this Codespaces image."
command -v pg_lsclusters >/dev/null || fail "PostgreSQL cluster tools are missing."
command -v psql >/dev/null || fail "PostgreSQL client is missing."

install -d -m 0700 "$STATE" "$MANAGEMENT_DIR" "$RUNTIME"
if [[ ! -s "$PASS_FILE" ]]; then
  openssl rand -hex 32 > "$PASS_FILE"
  chmod 0600 "$PASS_FILE"
fi
if [[ ! -s "$OWNER_TOKEN" ]]; then
  python3 - <<'PY' > "$OWNER_TOKEN"
import secrets
print(secrets.token_urlsafe(48))
PY
  chmod 0600 "$OWNER_TOKEN"
fi

# Ubuntu 24.04 Codespaces uses PostgreSQL 16. Data lives in the Codespace's
# persisted filesystem; it is not exposed on a forwarded/public database port.
read -r PG_VERSION PG_CLUSTER _ < <(pg_lsclusters --no-header | awk 'NR == 1 {print $1, $2, $3}')
[[ "${PG_VERSION:-}" == "16" && -n "${PG_CLUSTER:-}" ]] || fail "Expected the PostgreSQL 16 main cluster in this Codespaces image."
if ! pg_lsclusters --no-header | awk -v v="$PG_VERSION" -v c="$PG_CLUSTER" '$1==v && $2==c && $4=="online" {found=1} END{exit !found}'; then
  sudo -n pg_ctlcluster "$PG_VERSION" "$PG_CLUSTER" start
fi
pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1 || fail "Local PostgreSQL is not accepting connections."

DB_PASSWORD="$(cat "$PASS_FILE")"
[[ "$DB_PASSWORD" =~ ^[0-9a-f]{64}$ ]] || fail "Local PostgreSQL credential file is invalid."
# Password is supplied to psql over stdin, never on its process command line.
sudo -n -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'destinedfx_api') THEN
    CREATE ROLE destinedfx_api LOGIN;
  END IF;
END
\$\$;
ALTER ROLE destinedfx_api WITH LOGIN PASSWORD '$DB_PASSWORD';
SELECT 'CREATE DATABASE destinedfx_management OWNER destinedfx_api'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'destinedfx_management') \\gexec
ALTER DATABASE destinedfx_management OWNER TO destinedfx_api;
SQL
unset DB_PASSWORD
pass "Local PostgreSQL 16 database and least-privilege application role are ready."

if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
fi
if [[ ! -x "$VENV/bin/uvicorn" || ! -x "$VENV/bin/alembic" ]]; then
  "$VENV/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$ROOT/backend/requirements.txt"
fi

"$VENV/bin/python" - "$MANAGEMENT_DIR" <<'PY'
import os, sys
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

root = Path(sys.argv[1])
private = root / "step-up-private.pem"
public = root / "step-up-public.pem"
if not private.exists():
    key = Ed25519PrivateKey.generate()
    private_bytes = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_bytes = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    private.write_bytes(private_bytes)
    os.chmod(private, 0o600)
    public.write_bytes(public_bytes)
    os.chmod(public, 0o644)
else:
    key = serialization.load_pem_private_key(private.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise SystemExit("Existing Codespaces management signing key is not Ed25519.")
    expected = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    if not public.exists() or public.read_bytes() != expected:
        public.write_bytes(expected)
        os.chmod(public, 0o644)
PY

CODESPACE_OWNER="$(gh api user --jq .login 2>/dev/null || true)"
[[ "$CODESPACE_OWNER" =~ ^[A-Za-z0-9-]{1,39}$ ]] || fail "GitHub CLI could not verify the Codespace owner identity with gh api user."
cat > "$ENV_FILE" <<EOF
POSTGRES_PASSWORD_FILE=$PASS_FILE
POSTGRES_USER=destinedfx_api
POSTGRES_DB=destinedfx_management
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5432
ADMIN_EMAILS=
STEP_UP_PRIVATE_KEY_PEM_FILE=$PRIVATE_KEY
OWNER_SETUP_TOKEN_FILE=$OWNER_TOKEN
STEP_UP_ISSUER=destinedfx-management
STEP_UP_AUDIENCE=destinedfx-bridge
CODESPACES=true
DESTINEDFX_CODESPACES_OWNER_BOOTSTRAP=true
GITHUB_USER=$CODESPACE_OWNER
EOF
chmod 0600 "$ENV_FILE"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
cd "$ROOT/backend"
"$VENV/bin/alembic" upgrade head
pass "Management API PostgreSQL migrations are current."

# Use a dedicated loopback listener. GitHub forwards port 8010 to the phone over HTTPS.
exec "$VENV/bin/uvicorn" app.main:app --host 127.0.0.1 --port 8010 --no-proxy-headers --timeout-keep-alive 5 --limit-concurrency 50
