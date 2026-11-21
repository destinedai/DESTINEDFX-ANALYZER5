#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="/etc/destinedfx"
CRED_DIR="/etc/credstore.encrypted"
SERVICE_USER="${DESTINEDFX_SERVICE_USER:-destinedfx}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "BLOCKED: run bootstrap-self-hosted-host.sh as root." >&2
  exit 2
fi
command -v systemd-creds >/dev/null || { echo "BLOCKED: systemd-creds is required." >&2; exit 3; }
command -v openssl >/dev/null || { echo "BLOCKED: openssl is required to create the management signing key." >&2; exit 3; }
command -v systemctl >/dev/null
command -v findmnt >/dev/null
SYSTEMD_VERSION="$(systemctl --version | awk 'NR==1 {print $2}')"
if [[ -z "$SYSTEMD_VERSION" || "$SYSTEMD_VERSION" -lt 250 ]]; then
  echo "BLOCKED: systemd 250 or later is required for LoadCredentialEncrypted." >&2
  exit 3
fi
if [[ "$(findmnt -n -o FSTYPE --target /run)" != "tmpfs" ]]; then
  echo "BLOCKED: /run must be tmpfs so bootstrap plaintext secrets are never stored on persistent disk." >&2
  exit 3
fi

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /var/lib/destinedfx --create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi
OWNER_UID="$(id -u "$SERVICE_USER")"
OWNER_GID="$(id -g "$SERVICE_USER")"
API_USER=destinedfx-api
if ! id "$API_USER" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /var/lib/destinedfx-api --create-home --shell /usr/sbin/nologin "$API_USER"
fi
API_UID="$(id -u "$API_USER")"
API_GID="$(id -g "$API_USER")"
if [[ "$API_UID" -le 0 || "$API_GID" -le 0 ]]; then
  echo "BLOCKED: management API service identity must not be root." >&2
  exit 4
fi
if [[ "$OWNER_UID" -le 0 || "$OWNER_GID" -le 0 ]]; then
  echo "BLOCKED: the DestinedFX service account must not be root." >&2
  exit 4
fi

install -d -o root -g root -m 0700 "$CONFIG_DIR" "$CRED_DIR"
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0700   /var/lib/destinedfx   /var/lib/destinedfx/host-runtime   /var/lib/destinedfx/host-runtime/runtime   /var/lib/destinedfx/host-runtime/runtime/slot-runtime   /var/lib/destinedfx/host-runtime/isolated-slots
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0750 /var/lib/destinedfx/terminal-template-prefix
install -d -o "$API_UID" -g "$API_GID" -m 0700 /var/lib/destinedfx-api
install -d -o root -g root -m 0700 /var/lib/destinedfx-management /var/lib/destinedfx-management/postgres
install -d -o root -g root -m 0711 /home/destinedfx-slots

if [[ ! -f "$CONFIG_DIR/host.env" ]]; then
  GENERATION="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  sed \
    -e "s/__OWNER_UID__/$OWNER_UID/g" \
    -e "s/__OWNER_GID__/$OWNER_GID/g" \
    -e "s/__HOST_GENERATION__/$GENERATION/g" \
    "$SCRIPT_DIR/host.env.example" > "$CONFIG_DIR/host.env"
  chown root:root "$CONFIG_DIR/host.env"
  chmod 0600 "$CONFIG_DIR/host.env"
fi
if [[ ! -f "$CONFIG_DIR/bridge.env" ]]; then
  install -o root -g root -m 0600 "$SCRIPT_DIR/bridge.env.example" "$CONFIG_DIR/bridge.env"
fi
if [[ ! -e "$CONFIG_DIR/management-step-up-public.pem" ]]; then
  install -o root -g root -m 0644 /dev/null "$CONFIG_DIR/management-step-up-public.pem"
fi

TMP_DIR="$(mktemp -d /run/destinedfx-bootstrap.XXXXXX)"
chmod 0700 "$TMP_DIR"
cleanup() {
  rm -f "$TMP_DIR"/*
  rmdir "$TMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

make_credential() {
  local name="$1"
  local output="$2"
  local generator="$3"
  if [[ -f "$output" ]]; then
    return
  fi
  local plain="$TMP_DIR/$name"
  case "$generator" in
    fernet)
      python3 -c 'import base64,secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())' > "$plain"
      ;;
    token)
      python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > "$plain"
      ;;
    *)
      echo "BLOCKED: unsupported credential generator." >&2
      return 5
      ;;
  esac
  chmod 0600 "$plain"
  systemd-creds encrypt --name="$name" "$plain" "$output"
  chown root:root "$output"
  chmod 0600 "$output"
  rm -f "$plain"
}

make_credential credentials-fernet-key "$CRED_DIR/destinedfx-credentials-fernet-key.cred" fernet
make_credential worker-bootstrap-token "$CRED_DIR/destinedfx-worker-bootstrap-token.cred" token
make_credential host-agent-token "$CRED_DIR/destinedfx-host-agent-token.cred" token
make_credential postgres-password "$CRED_DIR/destinedfx-postgres-password.cred" token
make_credential postgres-app-password "$CRED_DIR/destinedfx-postgres-app-password.cred" token

if [[ ! -f "$CRED_DIR/destinedfx-management-step-up-private-key-pem.cred" ]]; then
  openssl genpkey -algorithm ED25519 -out "$TMP_DIR/step-up-private-key-pem"
  chmod 0600 "$TMP_DIR/step-up-private-key-pem"
  systemd-creds encrypt --name=step-up-private-key-pem "$TMP_DIR/step-up-private-key-pem" "$CRED_DIR/destinedfx-management-step-up-private-key-pem.cred"
  chown root:root "$CRED_DIR/destinedfx-management-step-up-private-key-pem.cred"
  chmod 0600 "$CRED_DIR/destinedfx-management-step-up-private-key-pem.cred"
fi
systemd-creds decrypt --name=step-up-private-key-pem "$CRED_DIR/destinedfx-management-step-up-private-key-pem.cred" "$TMP_DIR/step-up-private-key-pem"
chmod 0600 "$TMP_DIR/step-up-private-key-pem"
openssl pkey -in "$TMP_DIR/step-up-private-key-pem" -pubout -out "$CONFIG_DIR/management-step-up-public.pem"
chmod 0644 "$CONFIG_DIR/management-step-up-public.pem"
rm -f "$TMP_DIR/step-up-private-key-pem"

chown root:root "$CONFIG_DIR/host.env" "$CONFIG_DIR/bridge.env" "$CONFIG_DIR/management-step-up-public.pem"
chmod 0600 "$CONFIG_DIR/host.env" "$CONFIG_DIR/bridge.env"
chmod 0644 "$CONFIG_DIR/management-step-up-public.pem"

echo "DESTINEDFX_HOST_BOOTSTRAP_FILES_READY"
echo "Bridge service identity: $SERVICE_USER (uid=$OWNER_UID gid=$OWNER_GID)"
echo "Management API service identity: $API_USER (uid=$API_UID gid=$API_GID)"
echo "Created non-secret configuration: $CONFIG_DIR/host.env and $CONFIG_DIR/bridge.env (existing files were preserved)."
echo "Created/retained encrypted Bridge, worker, PostgreSQL and management-signing credentials under $CRED_DIR; plaintext values were not printed."
echo "The management API Ed25519 public key was derived locally; its private key remains host-bound and encrypted."
echo "Next: install genuine MT5/MetaEditor with --wineprefix /var/lib/destinedfx/terminal-template-prefix, then review the template paths in host.env."
echo "Then configure both HTTPS hostnames, Caddy contact and initial admin email in bridge.env; the management public key is generated automatically."
