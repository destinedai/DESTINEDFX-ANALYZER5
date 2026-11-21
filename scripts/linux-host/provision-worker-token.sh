#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "BLOCKED: run this credential provisioning helper as root." >&2
  exit 2
fi
: "${DESTINEDFX_WORKER_ID:?DESTINEDFX_WORKER_ID must be set by host.env}"
[[ "${DESTINEDFX_WORKER_ID}" =~ ^[A-Za-z0-9._-]{1,128}$ ]] || {
  echo "BLOCKED: DESTINEDFX_WORKER_ID is invalid." >&2
  exit 2
}
[[ -x /opt/destinedfx/venv/bin/python && -f /opt/destinedfx/bridge/owner_ctl.py ]] || {
  echo "BLOCKED: the self-hosted Bridge runtime is not installed." >&2
  exit 3
}
[[ -f /etc/credstore.encrypted/destinedfx-worker-bootstrap-token.cred ]] || {
  echo "BLOCKED: encrypted worker-bootstrap credential is missing." >&2
  exit 3
}
[[ -f /etc/credstore.encrypted/destinedfx-credentials-fernet-key.cred ]] || {
  echo "BLOCKED: encrypted Fernet credential is missing." >&2
  exit 3
}
command -v systemd-creds >/dev/null
command -v findmnt >/dev/null
if [[ "$(findmnt -n -o FSTYPE --target /run)" != "tmpfs" ]]; then
  echo "BLOCKED: /run must be tmpfs so the short-lived plaintext worker token is not written to persistent storage." >&2
  exit 4
fi

CREDENTIAL_DIR="/etc/credstore.encrypted"
TMP_DIR="$(mktemp -d /run/destinedfx-worker-credential.XXXXXX)"
chmod 0700 "$TMP_DIR"
cleanup() {
  rm -f "$TMP_DIR/worker-token"
  rmdir "$TMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
TOKEN_FILE="$TMP_DIR/worker-token"
export WORKER_BOOTSTRAP_TOKEN_FILE WORKER_BOOTSTRAP_TOKEN
export CREDENTIALS_FERNET_KEY_FILE CREDENTIALS_FERNET_KEY
export PYTHONPATH="/opt/destinedfx/bridge:/opt/destinedfx/managed-worker"
export BRIDGE_HOST_PROFILE=PERSISTENT BRIDGE_HOST_MODE=linux-host
export BRIDGE_DB_PATH="${BRIDGE_DB_PATH:-/var/lib/destinedfx/bridge.sqlite3}"

/opt/destinedfx/venv/bin/python /opt/destinedfx/bridge/owner_ctl.py ensure-worker "$DESTINEDFX_WORKER_ID" "$TOKEN_FILE"
[[ -s "$TOKEN_FILE" ]] || {
  echo "BLOCKED: Bridge did not issue the named worker credential." >&2
  exit 5
}
chmod 0600 "$TOKEN_FILE"
systemd-creds encrypt --name=worker-token "$TOKEN_FILE" "$CREDENTIAL_DIR/destinedfx-worker-token.cred"
chown root:root "$CREDENTIAL_DIR/destinedfx-worker-token.cred"
chmod 0600 "$CREDENTIAL_DIR/destinedfx-worker-token.cred"
echo "DESTINEDFX_WORKER_CREDENTIAL_READY worker_id=$DESTINEDFX_WORKER_ID"
