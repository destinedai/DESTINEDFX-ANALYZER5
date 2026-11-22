#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; }

if [[ "$(uname -m)" != x86_64 ]]; then fail 'Expected x86_64 Codespaces host.'; exit 1; fi
if [[ -z "${CODESPACE_NAME:-}" ]]; then fail 'CODESPACE_NAME is unavailable; this script expects GitHub Codespaces.'; exit 1; fi
IDLE_TIMEOUT_MINUTES="${DESTINEDFX_IDLE_TIMEOUT_MINUTES:-20}"
if [[ ! "$IDLE_TIMEOUT_MINUTES" =~ ^[0-9]+$ ]] || (( IDLE_TIMEOUT_MINUTES < 5 || IDLE_TIMEOUT_MINUTES > 240 )); then
  fail 'DESTINEDFX_IDLE_TIMEOUT_MINUTES must be an integer from 5 to 240.'; exit 1
fi
if "$ROOT/scripts/linux-host/wine-guard.sh"; then
  pass 'Wine version allowed'
else
  fail 'Wine version allowed'
  exit 1
fi
HOST_GENERATION="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"

# All generated bridge credentials live only in this 0700 Codespaces-private directory.
if [[ ! -s "$STATE/bridge.env" ]]; then
  umask 077
  "$STATE/venv/bin/python" - "$STATE/bridge.env" <<'PY'
import secrets, sys
from cryptography.fernet import Fernet
with open(sys.argv[1], 'x', encoding='utf-8') as f:
    f.write('CREDENTIALS_FERNET_KEY=' + Fernet.generate_key().decode() + '\n')
    f.write('WORKER_TOKEN=' + secrets.token_urlsafe(48) + '\n')
    f.write('WORKER_BOOTSTRAP_TOKEN=' + secrets.token_urlsafe(48) + '\n')
    f.write('HOST_AGENT_TOKEN=' + secrets.token_urlsafe(48) + '\n')
PY
fi
chmod 600 "$STATE/bridge.env"
# Existing installations created before host-agent authentication receive a new
# random token once; the token is never passed in the process command line.
if ! grep -q '^HOST_AGENT_TOKEN=' "$STATE/bridge.env"; then
  umask 077
  printf 'HOST_AGENT_TOKEN=%s\n' "$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')" >>"$STATE/bridge.env"
  chmod 600 "$STATE/bridge.env"
fi
if ! grep -q '^WORKER_BOOTSTRAP_TOKEN=' "$STATE/bridge.env"; then
  umask 077
  printf 'WORKER_BOOTSTRAP_TOKEN=%s\n' "$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')" >>"$STATE/bridge.env"
  chmod 600 "$STATE/bridge.env"
fi
set -a
# shellcheck disable=SC1090
source "$STATE/bridge.env"
set +a
if [[ -z "${HOST_AGENT_TOKEN:-}" || "${#HOST_AGENT_TOKEN}" -lt 32 ]]; then
  fail 'Root host-agent token is missing or invalid.'; exit 1
fi

# Public reach (K3). The marker is created only by verify-public-ready.sh after the hardening tests pass
# for the exact bridge code; without it the port stays private and the bridge stays loopback-only.
PUBLIC_READY=false
if "$ROOT/deploy/codespaces/verify-public-ready.sh" --check 2>/dev/null \
   || "$ROOT/deploy/codespaces/verify-public-ready.sh" generate; then
  PUBLIC_READY=true
fi
FORWARD_DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
PUBLIC_HOST="${CODESPACE_NAME}-8000.${FORWARD_DOMAIN}"
MANAGEMENT_HOST="${CODESPACE_NAME}-8010.${FORWARD_DOMAIN}"
printf 'PASS: bridge binding remains restricted to 127.0.0.1.\n'

# ---- Per-user terminal template and isolated Linux slot prerequisites. ----
if ! command -v sudo >/dev/null 2>&1 || ! sudo -n true >/dev/null 2>&1; then
  fail 'Passwordless sudo is required to create isolated non-login MT5 slot identities.'; exit 1
fi
for tool in useradd userdel Xvfb xauth wine wineboot winepath; do
  command -v "$tool" >/dev/null 2>&1 || { fail "Required isolation tool is missing: $tool"; exit 1; }
done
find_terminal() {
  local candidate
  for candidate in "${MT5_TERMINAL_EXE:-}" \
      "$HOME/.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe" \
      "$HOME/.wine/drive_c/Program Files/MetaTrader 5/terminal64.exe"; do
    [[ -n "$candidate" && -f "$candidate" ]] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}
TERMINAL_EXE=""
if ! TERMINAL_EXE="$(find_terminal)"; then
  fail 'MT5 terminal template is missing; install the official terminal before enabling managed account sessions.'; exit 1
fi
TERMINAL_ROOT="$(dirname "$TERMINAL_EXE")"
find_metaeditor() {
  local candidate
  for candidate in \
      "$TERMINAL_ROOT/metaeditor64.exe" \
      "$TERMINAL_ROOT/MetaEditor64.exe" \
      "$TERMINAL_ROOT/MetaEditor.exe"; do
    [[ -f "$candidate" ]] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}
METAEDITOR_EXE=""
if ! METAEDITOR_EXE="$(find_metaeditor)"; then
  fail 'MetaEditor is missing from the MT5 terminal template (checked supported filename casing); isolated EA compilation is required.'; exit 1
fi
export MT5_TERMINAL_EXE="$TERMINAL_EXE"
export DESTINEDFX_MT5_TERMINAL_TEMPLATE="$TERMINAL_ROOT"
export MT5_METAEDITOR_EXE="$METAEDITOR_EXE"
export DESTINEDFX_EA_SOURCE="$ROOT/mt5-ea/DestinedFXTraderEA.mq5"
export WINE_BIN="$(command -v wine)"
# The shared installation is a read-only program template only. No user session
# launches it, and no global WINEPREFIX or terminal config is exported.
unset WINEPREFIX MT5_JOURNAL_DIR DESTINEDFX_EA_SET_PATH
export DESTINEDFX_SLOT_ROOT="${DESTINEDFX_SLOT_ROOT:-/home/destinedfx-slots}"
export DESTINEDFX_SLOT_RUNTIME="$RUNTIME/slot-runtime"
export DESTINEDFX_ISOLATED_SLOTS=true
export DESTINEDFX_HOST_OWNER_UID="$(id -u)"
export DESTINEDFX_CONTROL_PLANE_URL="http://127.0.0.1:8000"
export DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE=true
export DESTINEDFX_WORKER_ID="${DESTINEDFX_WORKER_ID:-codespaces-host-01}"
SLOT_MAX_CONFIG="${DESTINEDFX_SLOT_MAX_TERMINALS:-2}"
if [[ ! "$SLOT_MAX_CONFIG" =~ ^[0-9]+$ ]] || (( SLOT_MAX_CONFIG < 1 || SLOT_MAX_CONFIG > 8 )); then
  fail 'DESTINEDFX_SLOT_MAX_TERMINALS must be from 1 to 8.'; exit 1
fi
DESTINEDFX_SLOT_MAX_TERMINALS="$(python3 - "$SLOT_MAX_CONFIG" <<'PY'
import os, sys
values = {}
try:
    for line in open('/proc/meminfo', encoding='ascii'):
        if ':' in line:
            key, value = line.split(':', 1)
            values[key] = int(value.split()[0]) * 1024
except (OSError, ValueError, IndexError):
    pass
available = values.get('MemAvailable', 0)
total = values.get('MemTotal', 0)
# Keep 2 GiB for the OS and reserve 3 GiB per slot. Also require 3 GiB
# currently available before launching the first MT5 session.
memory_slots = max(0, (total - 2 * 1024**3) // (3 * 1024**3))
if available < 3 * 1024**3:
    memory_slots = 0
print(min(int(sys.argv[1]), os.cpu_count() or 1, memory_slots))
PY
)"
export DESTINEDFX_SLOT_MAX_TERMINALS
if (( DESTINEDFX_SLOT_MAX_TERMINALS < 1 )); then
  fail 'Insufficient available memory for one isolated MT5 terminal; at least 3 GiB available RAM is required. No broker credentials will be provisioned.'
  exit 1
fi
mkdir -p "$DESTINEDFX_SLOT_RUNTIME"
chmod 700 "$DESTINEDFX_SLOT_RUNTIME"
sudo -n install -d -o root -g root -m 0711 "$DESTINEDFX_SLOT_ROOT"
HOST_AGENT_TOKEN_FILE="$RUNTIME/host-agent.token"
printf '%s\n' "$HOST_AGENT_TOKEN" | sudo -n tee "$HOST_AGENT_TOKEN_FILE" >/dev/null
sudo -n chown root:root "$HOST_AGENT_TOKEN_FILE"
sudo -n chmod 0600 "$HOST_AGENT_TOKEN_FILE"

PROCESS_IDENTITY="$ROOT/deploy/codespaces/process_identity.py"
track_pid() {
  local pid="$1" expected="$2" expected_uid="${3:-$EUID}"
  local record="$RUNTIME/process-$pid.identity.json"
  if [[ "$expected_uid" == "0" ]]; then
    sudo -n python3 "$PROCESS_IDENTITY" capture --pid "$pid" --record "$record" \
      --expected-uid "$expected_uid" --expected-fragment "$expected" || {
        fail "Could not capture complete root process identity for $expected."
        return 1
      }
  else
    python3 "$PROCESS_IDENTITY" capture --pid "$pid" --record "$record" \
      --expected-uid "$expected_uid" --expected-fragment "$expected" || {
      fail "Could not capture complete process identity for $expected."
      return 1
    }
  fi
  printf '%s|%s\n' "$pid" "${record##*/}" >>"$RUNTIME/pids"
  chmod 600 "$RUNTIME/pids"
}
if [[ -f "$RUNTIME/pids" && -s "$RUNTIME/pids" ]]; then
  all_running=true
  while IFS='|' read -r pid identity_file; do
    [[ "$pid" =~ ^[0-9]+$ && "$identity_file" == "process-$pid.identity.json" ]] || { all_running=false; continue; }
    sudo -n python3 "$PROCESS_IDENTITY" verify --record "$RUNTIME/$identity_file" >/dev/null 2>&1 || all_running=false
  done < "$RUNTIME/pids"
  if [[ "$all_running" == true ]]; then
    # An older Bridge-only Codespaces launch predates the port-8010 Management API.
    # Do not take the fast path unless both services and the EdDSA key contract
    # are still available on loopback.
    management_ready="$(curl -fsS --max-time 3 http://127.0.0.1:8010/ready 2>/dev/null || true)"
    management_key="$(curl -fsS --max-time 3 http://127.0.0.1:8010/api/v1/auth/bridge-verification-key 2>/dev/null || true)"
    management_key_valid="$(python3 - "$management_ready" "$management_key" <<'PY'
import json,sys
try:
    ready=json.loads(sys.argv[1])
    key=json.loads(sys.argv[2])
except Exception:
    print("1")
else:
    pem=str(key.get("public_key_pem","")).strip()
    valid=(
        ready.get("status")=="ready"
        and ready.get("database")=="ok"
        and key.get("algorithm")=="EdDSA"
        and key.get("issuer")=="destinedfx-management"
        and key.get("audience")=="destinedfx-bridge"
        and pem.startswith("-----BEGIN PUBLIC KEY-----")
        and pem.endswith("-----END PUBLIC KEY-----")
    )
    print("0" if valid else "1")
PY
)"
    if [[ "$management_key_valid" == "0" ]]; then
      pass 'Existing managed Codespaces processes and the PostgreSQL-backed Management API/EdDSA key are healthy.'
      python3 "$ROOT/scripts/linux-host/mt5_diagnostics.py" || true
      exit 0
    fi
    pass 'Existing Bridge-only Codespaces runtime detected; safely upgrading its tracked processes to the two-API host.'
  fi
  "$ROOT/deploy/codespaces/stop.sh" || {
    fail 'Existing Codespaces process identities could not be safely stopped; refusing to replace their records.'
    exit 1
  }
fi
: > "$RUNTIME/pids"
chmod 600 "$RUNTIME/pids"
cleanup() { "$ROOT/deploy/codespaces/stop.sh" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

# Start the existing PostgreSQL-backed Management API beside the Bridge.
(
  exec bash "$ROOT/deploy/codespaces/start-management-api.sh"
) >>"$RUNTIME/management-api.log" 2>&1 &
MANAGEMENT_API_PID="$!"
for _ in $(seq 1 60); do
  if curl -fs --max-time 2 http://127.0.0.1:8010/ready >/dev/null 2>&1 \
     && curl -fs --max-time 2 http://127.0.0.1:8010/api/v1/auth/bridge-verification-key >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if ! curl -fs --max-time 2 http://127.0.0.1:8010/ready >/dev/null 2>&1; then
  kill "$MANAGEMENT_API_PID" >/dev/null 2>&1 || true
  wait "$MANAGEMENT_API_PID" >/dev/null 2>&1 || true
  fail "Management API/PostgreSQL failed loopback readiness. Bridge and MT5 provisioning will not start."
  exit 1
fi
if ! curl -fs --max-time 2 http://127.0.0.1:8010/api/v1/auth/bridge-verification-key >/dev/null 2>&1; then
  kill "$MANAGEMENT_API_PID" >/dev/null 2>&1 || true
  wait "$MANAGEMENT_API_PID" >/dev/null 2>&1 || true
  fail "Management API did not publish its EdDSA Bridge verification key."
  exit 1
fi
track_pid "$MANAGEMENT_API_PID" "uvicorn app.main:app"
export MANAGEMENT_STEP_UP_PUBLIC_KEY_FILE="$STATE/management/step-up-public.pem"
pass "Management API and PostgreSQL are ready on loopback port 8010."

# vnc-up.sh is the single owner of Xvfb creation/adoption and PID tracking.
export DISPLAY=:99
bash "$ROOT/deploy/codespaces/vnc-up.sh"
sudo -n env PATH="$PATH" HOST_AGENT_TOKEN_FILE="$HOST_AGENT_TOKEN_FILE" \
  DESTINEDFX_ISOLATED_SLOTS=true DESTINEDFX_SLOT_ROOT="$DESTINEDFX_SLOT_ROOT" \
  DESTINEDFX_SLOT_RUNTIME="$DESTINEDFX_SLOT_RUNTIME" DESTINEDFX_HOST_OWNER_UID="$DESTINEDFX_HOST_OWNER_UID" \
  DESTINEDFX_SLOT_MAX_TERMINALS="$DESTINEDFX_SLOT_MAX_TERMINALS" WINE_BIN="$WINE_BIN" \
  python3 "$ROOT/scripts/linux-host/host-agent.py" --host 127.0.0.1 --port 8787 >>"$RUNTIME/host-agent.log" 2>&1 &
for _ in $(seq 1 20); do
  if curl -fs --max-time 2 http://127.0.0.1:8787/health/ready >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! curl -fs --max-time 2 http://127.0.0.1:8787/health/ready >/dev/null 2>&1; then
  fail 'Root host agent failed its isolated-slot readiness check.'; exit 1
fi
HOST_AGENT_IDENTITY="$RUNTIME/process-host-agent.identity.json"
HOST_AGENT_PID="$(sudo -n python3 "$PROCESS_IDENTITY" find-capture \
  --record "$HOST_AGENT_IDENTITY" --expected-uid 0 --expected-exe "$(command -v python3)" \
  --expected-args "$ROOT/scripts/linux-host/host-agent.py" --host 127.0.0.1 --port 8787)" || {
    fail 'Could not identify the unique root host-agent process; refusing an untracked host.'
    exit 1
  }
printf '%s|%s\n' "$HOST_AGENT_PID" "${HOST_AGENT_IDENTITY##*/}" >>"$RUNTIME/pids"
chmod 600 "$RUNTIME/pids"
HOST_STARTED_AT_EPOCH_SECONDS="$(date +%s)"
export HOST_AGENT_TOKEN HOST_AGENT_URL=http://127.0.0.1:8787/health/ready
export BRIDGE_DB_PATH="$STATE/bridge.sqlite3" BRIDGE_BASE_URL=http://127.0.0.1:8000 ALLOW_LOOPBACK_HTTP=true BRIDGE_HOST_MODE=linux-host
if [[ "$PUBLIC_READY" == true ]]; then export BRIDGE_PUBLIC_EXPOSURE=true EXPECTED_FORWARDED_HOST="$PUBLIC_HOST"; fi
export BRIDGE_HOST_PROFILE=EPHEMERAL EXECUTION_STORAGE_DURABLE=true HOST_GENERATION HOST_IDLE_TIMEOUT_SECONDS="$((IDLE_TIMEOUT_MINUTES * 60))" HOST_STARTED_AT_EPOCH_SECONDS HOST_MAX_LIFETIME_SECONDS=43200 HOST_WARNING_SECONDS=3600 HOST_EMERGENCY_STOP_LEAD_SECONDS=600
(
  cd "$ROOT/bridge"
  "$STATE/venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port 8000 --no-proxy-headers --timeout-keep-alive 5 --limit-concurrency 50
) >>"$RUNTIME/bridge.log" 2>&1 &
track_pid "$!" "uvicorn app:app"
for _ in $(seq 1 30); do
  if curl -fs --max-time 2 http://127.0.0.1:8000/health/live >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! curl -fs --max-time 2 http://127.0.0.1:8000/health/live >/dev/null 2>&1; then
  fail 'Bridge failed to start; no worker identity or broker provisioning will be enabled.'; exit 1
fi
WORKER_IDENTITY_FILE="$STATE/worker-identity.token"
chmod 700 "$STATE"
(
  cd "$ROOT/bridge"
  "$STATE/venv/bin/python" owner_ctl.py ensure-worker "$DESTINEDFX_WORKER_ID" "$WORKER_IDENTITY_FILE"
)
chmod 600 "$WORKER_IDENTITY_FILE"
# Do not pass the operator bootstrap secret to the worker process.
unset WORKER_BOOTSTRAP_TOKEN

# Multi-user Linux runner: each pairing receives a distinct OS identity,
# Wine prefix, private portable MT5 tree, Xauthority cookie and Xvfb display.
(
  set -a
  # shellcheck disable=SC1090
  source "$STATE/bridge.env"
  set +a
  export DESTINEDFX_WORKER_TOKEN_FILE="$WORKER_IDENTITY_FILE"
  unset DESTINEDFX_WORKER_TOKEN
  export DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE=true
  export DESTINEDFX_CONTROL_PLANE_URL=http://127.0.0.1:8000
  export HOST_AGENT_TOKEN HOST_AGENT_URL
  export BRIDGE_DB_PATH="$STATE/bridge.sqlite3"
  exec python3 "$ROOT/scripts/linux-host/linux-slot-worker.py" --loop --interval 3
) >>"$RUNTIME/linux-slot-worker.log" 2>&1 &
track_pid "$!" "linux-slot-worker.py --loop"
pass "Isolated MT5 slot worker started (up to $DESTINEDFX_SLOT_MAX_TERMINALS resource-bounded sessions)."
python3 "$ROOT/scripts/linux-host/mt5_diagnostics.py" || true
python3 "$ROOT/scripts/linux-host/mt5_diagnostics.py" || true

expose_publicly() {
  # Leave the port protocol as HTTP: switching a public port to HTTPS makes it private again.
  gh codespace ports visibility 8000:public -c "$CODESPACE_NAME" >/dev/null 2>&1
}
expose_management_publicly() {
  gh codespace ports visibility 8010:public -c "$CODESPACE_NAME" >/dev/null 2>&1
}
http_code() {  # $1 = path, rest = extra curl args
  local path="$1"; shift
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' \
    -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "$@" "https://${PUBLIC_HOST}${path}" || true
}
for _ in $(seq 1 30); do curl -fs --max-time 2 http://127.0.0.1:8000/health/live >/dev/null 2>&1 && break; sleep 1; done
if [[ "$PUBLIC_READY" == true ]]; then
  if expose_publicly && expose_management_publicly; then
    # A random, never-valid Bridge credential must be refused with 401.
    BAD_AUTH="Authorization: Bearer $(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
    live="$(http_code /health/live)"; bad="$(http_code /v1/app/state -H "$BAD_AUTH")"; none="$(http_code /v1/app/state)"
    api_health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "https://${MANAGEMENT_HOST}/health" || true)"
    api_ready="$(curl -s -o /dev/null -w '%{http_code}' --max-time 12 -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "https://${MANAGEMENT_HOST}/ready" || true)"
    api_key="$(curl -fs --max-time 12 -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "https://${MANAGEMENT_HOST}/api/v1/auth/bridge-verification-key" 2>/dev/null || true)"
    api_key_valid="$(python3 - "${api_key}" <<'PY'
import json,sys
try:
    d=json.loads(sys.argv[1])
except Exception:
    print("1")
else:
    valid = (
        d.get("algorithm") == "EdDSA"
        and d.get("issuer") == "destinedfx-management"
        and d.get("audience") == "destinedfx-bridge"
        and str(d.get("public_key_pem","")).startswith("-----BEGIN PUBLIC KEY-----")
        and str(d.get("public_key_pem","")).strip().endswith("-----END PUBLIC KEY-----")
    )
    print("0" if valid else "1")
PY
)"
    if [[ "$live" == 200 && "$bad" == 401 && "$none" == 401 && "$api_health" == 200 && "$api_ready" == 200 && "$api_key_valid" == 0 ]]; then
      pass "Public Bridge and Management API passed forwarded HTTPS, authentication, PostgreSQL readiness and EdDSA key checks."
      printf 'MANAGEMENT API: https://%s\n' "$MANAGEMENT_HOST"
      owner_code="$(cd "$ROOT/bridge" && "$STATE/venv/bin/python" owner_ctl.py issue-code)"
      printf 'BRIDGE URL:   https://%s\n' "$PUBLIC_HOST"
      printf 'PAIRING CODE: %s\n' "$owner_code"
      printf 'The phone app discovers the Bridge URL automatically after GitHub host authorization; do not paste a URL into locked APK settings.\n'
    else
      gh codespace ports visibility 8000:private -c "$CODESPACE_NAME" >/dev/null 2>&1 || true
      gh codespace ports visibility 8010:private -c "$CODESPACE_NAME" >/dev/null 2>&1 || true
      fail "Public self-check failed (Bridge health=$live bad-token=$bad no-token=$none; Management health=$api_health ready=$api_ready EdDSA=$api_key_valid). Both ports were returned to private."
    fi
  else
    gh codespace ports visibility 8000:private -c "$CODESPACE_NAME" >/dev/null 2>&1 || true
    gh codespace ports visibility 8010:private -c "$CODESPACE_NAME" >/dev/null 2>&1 || true
    fail 'Could not set both Bridge port 8000 and Management API port 8010 public through GitHub CLI. Both ports remain private.'
  fi
else
  fail 'Public exposure disabled: K3 hardening tests did not pass. Port stays private.'
fi
pass 'Foreground service processes are tracked in private Codespaces state.'
wait
