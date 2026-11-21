#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEST_ROOT="$DESTINEDFX_ROOT"
CONFIG_DIR="$DESTINEDFX_CONFIG_DIR"
CREDENTIAL_DIR="$DESTINEDFX_CREDENTIAL_DIR"
UNIT_DIR="$DESTINEDFX_SYSTEMD_DIR"
SERVICE_USER="$DESTINEDFX_SERVICE_USER"
PYTHON=python3
MODE=install
if [[ -n "$1" ]]; then MODE="$1"; fi
if [[ -z "$DEST_ROOT" ]]; then DEST_ROOT=/opt/destinedfx; fi
if [[ -z "$CONFIG_DIR" ]]; then CONFIG_DIR=/etc/destinedfx; fi
if [[ -z "$CREDENTIAL_DIR" ]]; then CREDENTIAL_DIR=/etc/credstore.encrypted; fi
if [[ -z "$UNIT_DIR" ]]; then UNIT_DIR=/etc/systemd/system; fi
if [[ -z "$SERVICE_USER" ]]; then SERVICE_USER=destinedfx; fi
HOST_ENV="$CONFIG_DIR/host.env"
BRIDGE_ENV="$CONFIG_DIR/bridge.env"

say() { printf 'DestinedFX self-hosted host: %s\n' "$*"; }
block() { printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }

for rel in \
  bridge/app.py bridge/host_lifecycle.py bridge/quantitative.py bridge/owner_ctl.py bridge/requirements-production.txt \
  backend/app/config.py backend/app/main.py backend/app/db.py backend/app/models.py backend/app/schemas.py \
  backend/alembic/env.py backend/alembic.ini backend/requirements.txt \
  managed-worker/worker.py managed-worker/mt5_journal.py mt5-ea/DestinedFXTraderEA.mq5 \
  scripts/linux-host/host-agent.py scripts/linux-host/linux-slot-worker.py scripts/linux-host/linux_isolation.py \
  scripts/linux-host/host_lifecycle.py scripts/linux-host/provision-worker-token.sh \
  scripts/linux-host/Caddyfile \
  scripts/linux-host/systemd/destinedfx-host-agent.service scripts/linux-host/systemd/destinedfx-bridge.service \
  scripts/linux-host/systemd/destinedfx-linux-slot-worker.service \
  scripts/linux-host/systemd/destinedfx-provision-linux-worker.service scripts/linux-host/systemd/destinedfx-caddy.service \
  scripts/linux-host/systemd/destinedfx-postgres.service scripts/linux-host/systemd/destinedfx-management-api.service; do
  [[ -f "$SOURCE_ROOT/$rel" && ! -L "$SOURCE_ROOT/$rel" ]] || block "required source file missing or symlinked: $rel"
done

if [[ "$MODE" == "--check" ]]; then
  for file in "$SCRIPT_DIR"/*.sh; do bash -n "$file"; done
  "$PYTHON" - "$SOURCE_ROOT" <<'PY'
import ast
import pathlib
import re
import sys
root = pathlib.Path(sys.argv[1])
for item in [
    "bridge/app.py", "bridge/host_lifecycle.py", "bridge/quantitative.py", "bridge/owner_ctl.py",
    "backend/app/config.py", "backend/app/main.py", "backend/app/db.py", "backend/app/models.py",
    "backend/app/schemas.py", "backend/alembic/env.py",
    "managed-worker/worker.py", "managed-worker/mt5_journal.py",
    "scripts/linux-host/host-agent.py", "scripts/linux-host/linux-slot-worker.py",
    "scripts/linux-host/linux_isolation.py", "scripts/linux-host/host_lifecycle.py",
]:
    path = root / item
    ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
base = root / "scripts/linux-host/systemd"
units = {
    "agent": (base / "destinedfx-host-agent.service").read_text(),
    "bridge": (base / "destinedfx-bridge.service").read_text(),
    "worker": (base / "destinedfx-linux-slot-worker.service").read_text(),
    "provision": (base / "destinedfx-provision-linux-worker.service").read_text(),
    "postgres": (base / "destinedfx-postgres.service").read_text(),
    "management": (base / "destinedfx-management-api.service").read_text(),
    "caddy": (base / "destinedfx-caddy.service").read_text(),
}
for name, text in units.items():
    if not text.startswith("[Unit]\n") or "[Service]\n" not in text:
        raise SystemExit("invalid systemd unit structure: " + name)
    if re.search(r"(?m)^Environment=.*(TOKEN|PASSWORD|FERNET_KEY)=", text):
        raise SystemExit("secret appears in Environment directive: " + name)
assert "User=root" in units["agent"] and "LoadCredentialEncrypted=host-agent-token:" in units["agent"]
assert "User=destinedfx" in units["bridge"] and "BRIDGE_HOST_PROFILE=PERSISTENT" in units["bridge"]
assert "LoadCredentialEncrypted=credentials-fernet-key:" in units["bridge"]
assert "LoadCredentialEncrypted=worker-bootstrap-token:" in units["bridge"]
assert "User=root" in units["worker"] and "linux-slot-worker.py --loop" in units["worker"]
assert "LoadCredentialEncrypted=worker-token:" in units["worker"]
assert "destinedfx-provision-linux-worker.service" in units["worker"]
assert "LoadCredentialEncrypted=worker-bootstrap-token:" in units["provision"]
assert "LoadCredentialEncrypted=credentials-fernet-key:" in units["provision"]
assert "127.0.0.1:5433:5432" in units["postgres"]
assert "LoadCredentialEncrypted=postgres-password:" in units["postgres"]
assert "User=destinedfx-api" in units["management"]
assert "LoadCredentialEncrypted=step-up-private-key-pem:" in units["management"]
assert "User=caddy" in units["caddy"] and "CAP_NET_BIND_SERVICE" in units["caddy"]
print("SELF_HOSTED_SOURCE_CHECK_OK")
PY
  if command -v systemd-analyze >/dev/null 2>&1; then
    systemd-analyze verify "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-host-agent.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-bridge.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-linux-slot-worker.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-provision-linux-worker.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-postgres.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-management-api.service" \
      "$SOURCE_ROOT/scripts/linux-host/systemd/destinedfx-caddy.service"
  fi
  say SELF_HOSTED_INSTALL_CHECK_OK
  exit 0
fi

[[ "$MODE" == "install" ]] || block "usage: install-self-hosted.sh [install|--check]"
[[ "$EUID" -eq 0 ]] || block "run install-self-hosted.sh as root."
for cmd in systemctl systemd-creds systemd-analyze install curl visudo findmnt sudo useradd userdel Xvfb xauth wine caddy sqlite3 docker; do
  command -v "$cmd" >/dev/null || block "required command '$cmd' is missing; install Ubuntu runtime packages first."
done
VERSION="$(systemctl --version | awk 'NR==1 {print $2}')"
[[ "$VERSION" =~ ^[0-9]+$ && "$VERSION" -ge 250 ]] || block "systemd 250+ is required for encrypted service credentials."
[[ "$(uname -m)" == "x86_64" ]] || block "MT5 Linux managed execution is supported only on x86_64; ARM is not verified."
. /etc/os-release
[[ "$ID" == "ubuntu" && ( "$VERSION_ID" == "22.04" || "$VERSION_ID" == "24.04" ) ]] || block "use Ubuntu 22.04 or 24.04."
[[ -x /usr/bin/wine && -x /usr/bin/caddy && -x /usr/bin/Xvfb && -x /usr/bin/xauth ]] || block "Wine, Caddy, Xvfb and xauth must be at standard Ubuntu paths."
[[ "$(findmnt -n -o FSTYPE --target /run)" == "tmpfs" ]] || block "/run must be tmpfs for transient decrypted credentials."

private_root_file() {
  local path="$1" mode
  [[ -f "$path" && ! -L "$path" ]] || block "required regular file missing: $path"
  [[ "$(stat -c '%u' "$path")" == "0" ]] || block "file must be root-owned: $path"
  mode="$(stat -c '%a' "$path")"
  [[ "$mode" == "600" || "$mode" == "640" ]] || block "file mode must be 0600 or 0640: $path"
}
private_root_file "$HOST_ENV"
private_root_file "$BRIDGE_ENV"
for name in credentials-fernet-key worker-bootstrap-token host-agent-token postgres-password management-step-up-private-key-pem; do
  private_root_file "$CREDENTIAL_DIR/destinedfx-$name.cred"
done
set -a
. "$HOST_ENV"
. "$BRIDGE_ENV"
set +a

id "$SERVICE_USER" >/dev/null 2>&1 || block "service account '$SERVICE_USER' is missing; run bootstrap-self-hosted-host.sh."
OWNER_UID="$(id -u "$SERVICE_USER")"
OWNER_GID="$(id -g "$SERVICE_USER")"
API_USER=destinedfx-api
id "$API_USER" >/dev/null 2>&1 || block "management service account '$API_USER' is missing; run bootstrap-self-hosted-host.sh."
API_UID="$(id -u "$API_USER")"
API_GID="$(id -g "$API_USER")"
[[ "$API_UID" -gt 0 && "$API_GID" -gt 0 ]] || block "Management API service account must not be root."
[[ "$OWNER_UID" -gt 0 && "$OWNER_GID" -gt 0 ]] || block "Bridge service account must not be root."
[[ "$DESTINEDFX_HOST_OWNER_UID" == "$OWNER_UID" && "$DESTINEDFX_HOST_OWNER_GID" == "$OWNER_GID" ]] || block "host.env owner UID/GID does not match the Bridge service user."
[[ "$BRIDGE_HOST_PROFILE" == "PERSISTENT" && "$BRIDGE_HOST_MODE" == "linux-host" && "$EXECUTION_STORAGE_DURABLE" == "true" ]] || block "host.env must select persistent Linux execution."
[[ "$DESTINEDFX_ISOLATED_SLOTS" == "true" ]] || block "per-user isolated slots must be enabled."
[[ "$DESTINEDFX_WORKER_ID" =~ ^[A-Za-z0-9._-]{1,128}$ && "$HOST_GENERATION" =~ ^[A-Za-z0-9._-]{1,128}$ ]] || block "worker ID or host generation is invalid."
[[ "$DESTINEDFX_SLOT_MAX_TERMINALS" =~ ^[0-9]+$ ]] || block "slot capacity must be numeric."
MAX_SLOTS="$DESTINEDFX_SLOT_MAX_TERMINALS"
[[ "$MAX_SLOTS" -ge 2 && "$MAX_SLOTS" -le 8 ]] || block "configure 2..8 isolated slots."

"$PYTHON" - "$BRIDGE_BASE_URL" "$BRIDGE_HOST" "$EXPECTED_FORWARDED_HOST" "$APP_DOMAIN" "$CADDY_EMAIL" "$ADMIN_EMAILS" "$MANAGEMENT_STEP_UP_PUBLIC_KEY_FILE" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlsplit
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
origin, proxy_host, allowed_host, api_host, caddy_email, admin_email, key_file = sys.argv[1:]
u = urlsplit(origin)
host = (u.hostname or "").lower().rstrip(".")
try:
    port = u.port
except ValueError:
    raise SystemExit("BLOCKED: invalid Bridge HTTPS port.") from None
if u.scheme != "https" or not host or u.username or u.password or port not in (None, 443) or u.path not in ("", "/") or u.query or u.fragment:
    raise SystemExit("BLOCKED: BRIDGE_BASE_URL must be an exact HTTPS origin without credentials, custom port, path, query or fragment.")
if host.endswith(".example.com") or host in {"example.com", "localhost"}:
    raise SystemExit("BLOCKED: replace the sample domain with a hostname controlled by the operator.")
if host != proxy_host.lower().rstrip(".") or host != allowed_host.lower().rstrip("."):
    raise SystemExit("BLOCKED: BRIDGE_HOST and EXPECTED_FORWARDED_HOST must match BRIDGE_BASE_URL.")
if not api_host or api_host.lower().rstrip(".") in {"example.com", "localhost"} or api_host.lower().rstrip(".").endswith(".example.com"):
    raise SystemExit("BLOCKED: APP_DOMAIN must be a real hostname controlled by the operator.")
if api_host.lower().rstrip(".") == host:
    raise SystemExit("BLOCKED: APP_DOMAIN and BRIDGE_HOST must be separate HTTPS hostnames.")
if "@" not in caddy_email or caddy_email.lower().endswith("@example.com"):
    raise SystemExit("BLOCKED: set a real CADDY_EMAIL for TLS expiry notices.")
if "@" not in admin_email or admin_email.lower().endswith("@example.com"):
    raise SystemExit("BLOCKED: set the real initial administrator email in ADMIN_EMAILS.")
path = Path(key_file)
if not path.is_file() or path.is_symlink() or path.stat().st_size < 80:
    raise SystemExit("BLOCKED: management backend Ed25519 public key is missing.")
try:
    key = serialization.load_pem_public_key(path.read_bytes())
except Exception:
    raise SystemExit("BLOCKED: management public key is invalid PEM.") from None
if not isinstance(key, Ed25519PublicKey):
    raise SystemExit("BLOCKED: management public key must be Ed25519.")
print("SELF_HOSTED_DOMAIN_AND_MANAGEMENT_KEY_OK")
PY
for name in credentials-fernet-key worker-bootstrap-token host-agent-token postgres-password management-step-up-private-key-pem; do
  systemd-creds decrypt --name="$name" "$CREDENTIAL_DIR/destinedfx-$name.cred" /dev/null >/dev/null || block "encrypted credential invalid: $name"
done

TEMPLATE="$DESTINEDFX_MT5_TERMINAL_TEMPLATE"
[[ -n "$TEMPLATE" && -f "$MT5_TERMINAL_EXE" && ! -L "$MT5_TERMINAL_EXE" ]] || block "genuine terminal64.exe is missing from host.env template."
[[ -f "$MT5_METAEDITOR_EXE" && ! -L "$MT5_METAEDITOR_EXE" ]] || block "genuine MetaEditor64.exe is missing from host.env template."
[[ -f "$TEMPLATE/MQL5/Include/Trade/Trade.mqh" ]] || block "MT5 Standard Library is missing."
[[ -f "$SOURCE_ROOT/mt5-ea/DestinedFXTraderEA.mq5" ]] || block "DestinedFX EA source is missing."

TOTAL_KIB="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
CPU_COUNT="$(getconf _NPROCESSORS_ONLN)"
TOTAL_BYTES=$((TOTAL_KIB * 1024))
RAM_SLOTS=0
if (( TOTAL_BYTES > 2 * 1024 * 1024 * 1024 )); then RAM_SLOTS=$(((TOTAL_BYTES - 2 * 1024 * 1024 * 1024) / (3 * 1024 * 1024 * 1024))); fi
STATIC_CAPACITY="$RAM_SLOTS"
(( CPU_COUNT < STATIC_CAPACITY )) && STATIC_CAPACITY="$CPU_COUNT"
(( STATIC_CAPACITY > 8 )) && STATIC_CAPACITY=8
(( STATIC_CAPACITY >= MAX_SLOTS )) || block "configured slots exceed measured CPU/RAM capacity (2 GiB OS reserve + 3 GiB per terminal)."

for unit in destinedfx-mt5.service destinedfx-xvfb.service destinedfx-linux-worker.service destinedfx-linux-worker.timer; do
  if systemctl is-active --quiet "$unit"; then
    block "legacy single-terminal unit '$unit' is active; reconcile and stop it safely before migration. No process was stopped."
  fi
done
if pgrep -af 'terminal64\.exe|terminal\.exe' | grep -v -E 'pgrep -af|install-self-hosted' >/dev/null 2>&1; then
  block "a MetaTrader process is already running outside the isolated host; reconcile it before migration. No process was stopped."
fi

install -d -o root -g root -m 0755 "$DEST_ROOT" "$DEST_ROOT/bridge" "$DEST_ROOT/management" "$DEST_ROOT/managed-worker" "$DEST_ROOT/mt5-ea" "$DEST_ROOT/scripts/linux-host" "$UNIT_DIR"
install -d -o "$API_UID" -g "$API_GID" -m 0700 /var/lib/destinedfx-api
install -d -o root -g root -m 0700 /var/lib/destinedfx-management /var/lib/destinedfx-management/postgres
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0700 /var/lib/destinedfx /var/lib/destinedfx/host-runtime \
  /var/lib/destinedfx/host-runtime/runtime /var/lib/destinedfx/host-runtime/runtime/slot-runtime \
  /var/lib/destinedfx/host-runtime/isolated-slots
install -d -o "$OWNER_UID" -g "$OWNER_GID" -m 0750 /var/lib/destinedfx-caddy /var/lib/destinedfx-caddy/data /var/lib/destinedfx-caddy/config
install -d -o root -g root -m 0711 /home/destinedfx-slots
chown "$OWNER_UID:$OWNER_GID" "$TEMPLATE"
chmod 0750 "$TEMPLATE"

"$PYTHON" -m venv "$DEST_ROOT/venv" || block "Python virtual environment creation failed; install python3-venv."
"$DEST_ROOT/venv/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$SOURCE_ROOT/bridge/requirements-production.txt"
"$PYTHON" -m venv "$DEST_ROOT/management/venv" || block "Management API Python virtual environment creation failed."
"$DEST_ROOT/management/venv/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$SOURCE_ROOT/backend/requirements.txt"
for rel in app.py host_lifecycle.py quantitative.py owner_ctl.py; do
  install -o root -g root -m 0644 "$SOURCE_ROOT/bridge/$rel" "$DEST_ROOT/bridge/$rel"
done
for rel in worker.py mt5_journal.py; do
  install -o root -g root -m 0644 "$SOURCE_ROOT/managed-worker/$rel" "$DEST_ROOT/managed-worker/$rel"
done
cp -a "$SOURCE_ROOT/backend/app" "$DEST_ROOT/management/app"
cp -a "$SOURCE_ROOT/backend/alembic" "$DEST_ROOT/management/alembic"
install -o root -g root -m 0644 "$SOURCE_ROOT/backend/alembic.ini" "$SOURCE_ROOT/backend/requirements.txt" "$DEST_ROOT/management/"
chown -R root:root "$DEST_ROOT/management"
chmod -R a+rX,go-w "$DEST_ROOT/management"
for rel in host-agent.py linux-slot-worker.py linux_isolation.py host_lifecycle.py provision-worker-token.sh; do
  install -o root -g root -m 0755 "$SCRIPT_DIR/$rel" "$DEST_ROOT/scripts/linux-host/$rel"
done
install -o root -g root -m 0644 "$SOURCE_ROOT/bridge/requirements-production.txt" "$DEST_ROOT/bridge/requirements-production.txt"
cp -a "$SOURCE_ROOT/mt5-ea/." "$DEST_ROOT/mt5-ea/"
chown -R root:root "$DEST_ROOT/mt5-ea"
chmod -R a+rX,go-w "$DEST_ROOT/mt5-ea"
install -o root -g root -m 0644 "$SCRIPT_DIR/Caddyfile" /etc/caddy/Caddyfile
chown root:caddy "$BRIDGE_ENV"
chmod 0640 "$BRIDGE_ENV"
chown root:root "$HOST_ENV"
chmod 0600 "$HOST_ENV"

for unit in destinedfx-host-agent.service destinedfx-postgres.service destinedfx-management-api.service destinedfx-bridge.service destinedfx-provision-linux-worker.service destinedfx-linux-slot-worker.service destinedfx-caddy.service; do
  install -o root -g root -m 0644 "$SCRIPT_DIR/systemd/$unit" "$UNIT_DIR/$unit"
done
systemd-analyze verify "$UNIT_DIR/destinedfx-host-agent.service" "$UNIT_DIR/destinedfx-postgres.service" \
  "$UNIT_DIR/destinedfx-management-api.service" "$UNIT_DIR/destinedfx-bridge.service" \
  "$UNIT_DIR/destinedfx-provision-linux-worker.service" "$UNIT_DIR/destinedfx-linux-slot-worker.service" \
  "$UNIT_DIR/destinedfx-caddy.service" || block "systemd unit validation failed."
systemctl daemon-reload
systemctl enable --now docker.service
systemctl disable destinedfx-linux-worker.timer >/dev/null 2>&1 || true
systemctl disable destinedfx-linux-worker.service destinedfx-mt5.service destinedfx-xvfb.service >/dev/null 2>&1 || true
for unit in destinedfx-host-agent.service destinedfx-postgres.service destinedfx-management-api.service destinedfx-bridge.service destinedfx-provision-linux-worker.service destinedfx-linux-slot-worker.service destinedfx-caddy.service; do
  systemctl enable "$unit" >/dev/null
  systemctl start "$unit"
done
for attempt in $(seq 1 30); do
  if curl --fail --silent --max-time 3 -H 'X-Forwarded-Proto: https' -H "Host: $BRIDGE_HOST" http://127.0.0.1:8000/health/live >/dev/null 2>&1; then break; fi
  sleep 2
done
curl --fail --silent --max-time 5 -H 'X-Forwarded-Proto: https' -H "Host: $BRIDGE_HOST" http://127.0.0.1:8000/health/live >/dev/null || block "Bridge liveness failed; inspect journalctl -u destinedfx-bridge.service."
for attempt in $(seq 1 45); do
  if curl --fail --silent --max-time 5 "$BRIDGE_BASE_URL/health/live" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl --fail --silent --max-time 10 "$BRIDGE_BASE_URL/health/live" >/dev/null || block "public HTTPS failed; check DNS, TCP 80/443 and Caddy certificate issuance."
READY_CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 "$BRIDGE_BASE_URL/health/ready" || true)"
[[ "$READY_CODE" == "200" ]] || block "Bridge /health/ready returned HTTP $READY_CODE; inspect host-agent, Wine, Xvfb, xauth, memory and journals."
for attempt in $(seq 1 30); do
  if curl --fail --silent --max-time 5 "https://$APP_DOMAIN/ready" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl --fail --silent --max-time 10 "https://$APP_DOMAIN/health" >/dev/null || block "Management API liveness failed; inspect destinedfx-management-api.service."
curl --fail --silent --max-time 10 "https://$APP_DOMAIN/ready" >/dev/null || block "Management API database readiness failed; inspect destinedfx-postgres.service."
KEY_JSON="$(curl --fail --silent --show-error --max-time 10 "https://$APP_DOMAIN/api/v1/auth/bridge-verification-key")" || block "Management API cannot publish its Bridge verification key."
"$PYTHON" - "$KEY_JSON" <<'PY'
import json, sys
try:
    payload = json.loads(sys.argv[1])
except Exception:
    raise SystemExit("BLOCKED: management API verification-key response is not valid JSON.")
if payload.get("algorithm") != "EdDSA" or payload.get("issuer") != "destinedfx-management" or payload.get("audience") != "destinedfx-bridge" or not str(payload.get("public_key_pem", "")).startswith("-----BEGIN PUBLIC KEY-----"):
    raise SystemExit("BLOCKED: management API did not publish the expected EdDSA Bridge verification key.")
print("MANAGEMENT_BRIDGE_VERIFICATION_KEY_OK")
PY
say SELF_HOSTED_RUNTIME_INSTALLED
say "DestinedFX Bridge, root attestation agent, per-user slot worker and local Caddy HTTPS are enabled."
say "No external execution host was configured. No broker credentials were requested or imported."
say "Infrastructure readiness is not a broker login, DEMO fill or LIVE trade."
