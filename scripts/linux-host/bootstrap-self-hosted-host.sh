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
if [[ "$OWNER_UID" -le 0 || "$OWNER_GID" -le 0 ]]; then
  echo "BLOCKED: the DestinedFX service account must not be root." >&2
  exit 4
fi

install -d -o root -g root -m 0700 "$CONFIG_DIR" "$CRED_DIR"
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0700   /var/lib/destinedfx   /var/lib/destinedfx/host-runtime   /var/lib/destinedfx/host-runtime/runtime   /var/lib/destinedfx/host-runtime/runtime/slot-runtime   /var/lib/destinedfx/host-runtime/isolated-slots
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0750 /var/lib/destinedfx/terminal-template
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

chown root:root "$CONFIG_DIR/host.env" "$CONFIG_DIR/bridge.env" "$CONFIG_DIR/management-step-up-public.pem"
chmod 0600 "$CONFIG_DIR/host.env" "$CONFIG_DIR/bridge.env"
chmod 0644 "$CONFIG_DIR/management-step-up-public.pem"

echo "DESTINEDFX_HOST_BOOTSTRAP_FILES_READY"
echo "Service identity: $SERVICE_USER (uid=$OWNER_UID gid=$OWNER_GID)"
echo "Created non-secret configuration: $CONFIG_DIR/host.env and $CONFIG_DIR/bridge.env (existing files were preserved)."
echo "Created/retained encrypted systemd credentials under $CRED_DIR; plaintext values were not printed."
echo "Next: install a genuine MT5 terminal/MetaEditor into /var/lib/destinedfx/terminal-template."
echo "Then edit bridge.env with your HTTPS domain, set the Ed25519 public key in management-step-up-public.pem, configure the news provider, and run rebuild.sh self-hosted."
