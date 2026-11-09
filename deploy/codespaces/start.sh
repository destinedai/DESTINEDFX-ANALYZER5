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
IDLE_TIMEOUT_MINUTES="${DESTINEDFX_IDLE_TIMEOUT_MINUTES:-240}"
if [[ ! "$IDLE_TIMEOUT_MINUTES" =~ ^[0-9]+$ ]] || (( IDLE_TIMEOUT_MINUTES < 5 || IDLE_TIMEOUT_MINUTES > 240 )); then
  fail 'DESTINEDFX_IDLE_TIMEOUT_MINUTES must be an integer from 5 to 240.'; exit 1
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
PY
fi
chmod 600 "$STATE/bridge.env"

# Public reach (K3). The marker is created only by verify-public-ready.sh after the hardening tests pass
# for the exact bridge code; without it the port stays private and the bridge stays loopback-only.
PUBLIC_READY=false
if "$ROOT/deploy/codespaces/verify-public-ready.sh" --check 2>/dev/null \
   || "$ROOT/deploy/codespaces/verify-public-ready.sh" generate; then
  PUBLIC_READY=true
fi
FORWARD_DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
PUBLIC_HOST="${CODESPACE_NAME}-8000.${FORWARD_DOMAIN}"
printf 'PASS: bridge binding remains restricted to 127.0.0.1.\n'

# ---- One path scheme for every component (T1): derived once from the real terminal location. ----
find_terminal() {
  local c
  for c in "${MT5_TERMINAL_EXE:-}" \
           "$HOME/.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe" \
           "$HOME/.wine/drive_c/Program Files/MetaTrader 5/terminal64.exe"; do
    [[ -n "$c" && -f "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 1
}
TERMINAL_EXE=""
if TERMINAL_EXE="$(find_terminal)"; then
  TERMINAL_ROOT="$(dirname "$TERMINAL_EXE")"
  # In /portable mode the data folder is the terminal folder: the terminal Journal is the Logs folder (Experts logs live elsewhere).
  export MT5_TERMINAL_EXE="$TERMINAL_EXE" MT5_JOURNAL_DIR="$TERMINAL_ROOT/Logs"
  export DESTINEDFX_EA_SET_PATH="$TERMINAL_ROOT/MQL5/Presets/DestinedFXTraderEA.set"
  if [[ "$TERMINAL_EXE" == "$HOME/.mt5/"* ]]; then export WINEPREFIX="$HOME/.mt5"; fi
fi
export DESTINEDFX_TERMINAL_STARTUP_CONFIG="$STATE/startup.ini"
export DESTINEDFX_ACTIVE_JOB_PATH="$STATE/active-job.json" CONNECT_JOB_START_FILE="$STATE/connect-job-start.epoch"
export DESTINEDFX_TERMINAL_CONTROL=process DESTINEDFX_TERMINAL_PIDFILE="$RUNTIME/terminal.pid"
export DESTINEDFX_TERMINAL_LAUNCHER="$ROOT/scripts/linux-host/launch-mt5.sh" DESTINEDFX_TERMINAL_LOG="$RUNTIME/terminal.log"
export DESTINEDFX_CONTROL_PLANE_URL="http://127.0.0.1:8000" DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE=true
export DESTINEDFX_WORKER_ID="${DESTINEDFX_WORKER_ID:-codespaces-host-01}"

if [[ -f "$RUNTIME/pids" ]]; then
  while read -r pid; do [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && { pass 'Existing managed Codespaces processes are already running.'; exit 0; }; done < "$RUNTIME/pids"
  rm -f "$RUNTIME/pids"
fi
: > "$RUNTIME/pids"
chmod 600 "$RUNTIME/pids"
cleanup() { "$ROOT/deploy/codespaces/stop.sh" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 & echo $! >>"$RUNTIME/pids"
export DISPLAY=:99
python3 "$ROOT/scripts/linux-host/host-agent.py" --host 127.0.0.1 --port 8787 >>"$RUNTIME/host-agent.log" 2>&1 & echo $! >>"$RUNTIME/pids"

set -a
# shellcheck disable=SC1090
source "$STATE/bridge.env"
set +a
HOST_STARTED_AT_EPOCH_SECONDS="$(date +%s)"
export BRIDGE_DB_PATH="$STATE/bridge.sqlite3" ALLOW_LOOPBACK_HTTP=true BRIDGE_HOST_MODE=linux-host HOST_AGENT_URL=http://127.0.0.1:8787/health/ready
if [[ "$PUBLIC_READY" == true ]]; then export BRIDGE_PUBLIC_EXPOSURE=true EXPECTED_FORWARDED_HOST="$PUBLIC_HOST"; fi
export BRIDGE_HOST_PROFILE=EPHEMERAL HOST_GENERATION HOST_IDLE_TIMEOUT_SECONDS="$((IDLE_TIMEOUT_MINUTES * 60))" HOST_STARTED_AT_EPOCH_SECONDS
(
  cd "$ROOT/bridge"
  "$STATE/venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port 8000 --no-proxy-headers --timeout-keep-alive 5 --limit-concurrency 50
) >>"$RUNTIME/bridge.log" 2>&1 & echo $! >>"$RUNTIME/pids"

# Connect-job runner: claims jobs from the loopback bridge, writes the private startup config, restarts the terminal.
(
  set -a
  # shellcheck disable=SC1090
  source "$STATE/bridge.env"
  set +a
  export DESTINEDFX_WORKER_TOKEN="$WORKER_TOKEN"
  exec python3 "$ROOT/scripts/linux-host/linux-worker.py" --loop --interval 3
) >>"$RUNTIME/linux-worker.log" 2>&1 & echo $! >>"$RUNTIME/pids"
pass 'Linux connect-job runner started (process control, loopback control plane).'

if [[ -n "$TERMINAL_EXE" ]]; then
  if python3 "$ROOT/scripts/linux-host/linux-worker.py" --terminal start >>"$RUNTIME/terminal-control.log" 2>&1; then
    pass 'MT5 terminal process started under the process controller.'
  else
    fail 'MT5 terminal could not be started; see terminal-control.log (no secrets are written there).'
  fi
else
  fail 'MT5 terminal is not installed (checked ~/.mt5 and ~/.wine); host runtime is diagnostic-only until it is.'
fi

expose_publicly() {
  # Leave the port protocol as HTTP: switching a public port to HTTPS makes it private again.
  gh codespace ports visibility 8000:public -c "$CODESPACE_NAME" >/dev/null 2>&1
}
http_code() {  # $1 = path, rest = extra curl args
  local path="$1"; shift
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' \
    -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "$@" "https://${PUBLIC_HOST}${path}" || true
}
for _ in $(seq 1 30); do curl -fs --max-time 2 http://127.0.0.1:8000/health/live >/dev/null 2>&1 && break; sleep 1; done
if [[ "$PUBLIC_READY" == true ]]; then
  if expose_publicly; then
    # A random, never-valid credential built at runtime: it must be refused with 401.
    BAD_AUTH="Authorization: Bearer $(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
    live="$(http_code /health/live)"; bad="$(http_code /v1/app/state -H "$BAD_AUTH")"; none="$(http_code /v1/app/state)"
    if [[ "$live" == 200 && "$bad" == 401 && "$none" == 401 ]]; then
      pass "Public port reachable (health 200, bad token 401, no token 401): https://${PUBLIC_HOST}"
      owner_code="$(cd "$ROOT/bridge" && "$STATE/venv/bin/python" owner_ctl.py issue-code)"
      printf 'BRIDGE URL:   https://%s\n' "$PUBLIC_HOST"
      printf 'PAIRING CODE: %s\n' "$owner_code"
      printf 'Enter both in the app under Settings > Connection. The code works once and expires in 10 minutes.\n'
    else
      gh codespace ports visibility 8000:private -c "$CODESPACE_NAME" >/dev/null 2>&1 || true
      fail "Public self-check failed (health=$live bad-token=$bad no-token=$none); port set back to private. If health is not 200, open the URL once in a browser and tap Continue, then rerun."
    fi
  else
    fail 'Could not set port 8000 public with gh. OWNER STEP: open the Ports tab, right-click port 8000, set Port Visibility to Public, then rerun start.sh.'
  fi
else
  fail 'Public exposure disabled: K3 hardening tests did not pass. Port stays private.'
fi
pass 'Foreground service processes are tracked in private Codespaces state.'
wait
