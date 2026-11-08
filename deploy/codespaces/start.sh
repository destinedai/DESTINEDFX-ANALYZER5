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

# Local-only startup is safe for current K1. K3 must supply an explicit readiness guard
# before any public forwarding/visibility command is permitted.
if [[ ! -f "$ROOT/deploy/codespaces/public-code-ready" ]]; then
  fail 'K3 blocker: public bridge exposure is disabled until the code-ready hardening guard exists.'
  printf 'PASS: bridge binding remains restricted to 127.0.0.1.\n'
  printf 'INFO: After K3 guard review, the exact visibility command is: gh codespace ports visibility 8000:public -c %s\n' "$CODESPACE_NAME"
fi

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
export BRIDGE_DB_PATH="$STATE/bridge.sqlite3" ALLOW_LOOPBACK_HTTP=true BRIDGE_HOST_MODE=linux-host HOST_AGENT_URL=http://127.0.0.1:8787/health/ready
(
  cd "$ROOT/bridge"
  "$STATE/venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port 8000 --no-proxy-headers
) >>"$RUNTIME/bridge.log" 2>&1 & echo $! >>"$RUNTIME/pids"

TERMINAL="$HOME/.wine/drive_c/Program Files/MetaTrader 5/terminal64.exe"
if [[ -f "$TERMINAL" ]]; then
  "$ROOT/scripts/linux-host/launch-mt5.sh" >>"$RUNTIME/terminal.log" 2>&1 & echo $! >>"$RUNTIME/pids"
  pass 'MT5 terminal process started.'
else
  fail 'MT5 terminal is not installed at the default Wine path; host runtime is diagnostic-only.'
fi

printf 'PASS: local bridge URL https://%s-8000.app.github.dev (Codespaces private port; no token in URL).\n' "$CODESPACE_NAME"
printf 'FAIL: public URL is intentionally unavailable until K3 hardening is reviewed.\n'
pass 'Foreground service processes are tracked in private Codespaces state.'
wait
