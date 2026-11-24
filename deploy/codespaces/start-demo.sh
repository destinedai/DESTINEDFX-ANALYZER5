#!/usr/bin/env bash
# Start or verify the single supervised Codespaces DEMO host.
# Public visibility is controlled only by deploy/codespaces/start.sh after K3 checks.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
MODE="${1:-wait}"
PYTHON="${DESTINEDFX_PYTHON:-$STATE/venv/bin/python}"
[[ -x "$PYTHON" ]] || PYTHON=python3

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; }

if [[ "$#" -gt 1 || ( "$MODE" != "wait" && "$MODE" != "--background" ) ]]; then
  fail 'Usage: bash deploy/codespaces/start-demo.sh [--background]'
  exit 2
fi
if [[ "${CODESPACES:-}" != "true" || -z "${CODESPACE_NAME:-}" ]]; then
  fail 'Open this repository inside GitHub Codespaces before running the DEMO host command.'
  exit 1
fi
if [[ ! "$CODESPACE_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,127}$ ]]; then
  fail 'CODESPACE_NAME is malformed; no endpoint was constructed.'
  exit 1
fi
FORWARD_DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}"
case "$FORWARD_DOMAIN" in
  app.github.dev|preview.app.github.dev|githubpreview.dev) ;;
  *) fail 'GitHub did not provide a supported Codespaces forwarding domain.'; exit 1 ;;
esac

mkdir -p "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"
# The adapter is the single lifecycle entry point; it delegates to host_controller.py.
START_RESULT="$(python3 "$ROOT/scripts/host/destinedfx_trigger_host.py" start_all)"
pass "Codespaces host supervisor requested ($START_RESULT)."

if [[ "$MODE" == "--background" ]]; then
  pass 'Automatic Codespaces open/start hook accepted; readiness checks continue under the host supervisor.'
  exit 0
fi

TIMEOUT_SECONDS="${DESTINEDFX_CODESPACES_START_TIMEOUT_SECONDS:-900}"
if [[ ! "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || (( TIMEOUT_SECONDS < 60 || TIMEOUT_SECONDS > 1800 )); then
  fail 'DESTINEDFX_CODESPACES_START_TIMEOUT_SECONDS must be from 60 to 1800.'
  exit 2
fi
DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
MANIFEST="$RUNTIME/public-endpoints.json"
ACTIVITY="$RUNTIME/last-activity"
STATUS_FILE="$RUNTIME/host-status.json"

http_code() {
  local url="$1"
  curl --silent --output /dev/null --write-out '%{http_code}' --max-time 12 \
    -H 'Accept: application/json' -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "$url" 2>/dev/null || true
}

while (( $(date +%s) < DEADLINE )); do
  # The readiness command itself is host activity while the startup/forwarding checks run.
  touch "$ACTIVITY"
  chmod 600 "$ACTIVITY"

  HEALTH_JSON="$(python3 "$ROOT/scripts/host/destinedfx_trigger_host.py" check_health 2>/dev/null || true)"
  HEALTH_STATUS="$(python3 - "$HEALTH_JSON" <<'PY'
import json,sys
try:
    print(json.loads(sys.argv[1]).get("status","UNKNOWN"))
except Exception:
    print("UNKNOWN")
PY
)"
  CONTROLLER_STATUS="$(python3 - "$STATUS_FILE" <<'PY'
import json,sys
try:
    print(json.loads(open(sys.argv[1], encoding="utf-8").read()).get("status","UNKNOWN"))
except Exception:
    print("UNKNOWN")
PY
)"

  if [[ "$CONTROLLER_STATUS" == "FAILED" || "$CONTROLLER_STATUS" == "STOP_FAILED" || "$CONTROLLER_STATUS" == "STOP_BLOCKED" ]]; then
    fail "Codespaces startup stopped in state $CONTROLLER_STATUS. Private diagnostics are in $RUNTIME/supervisor-start.log and $RUNTIME/supervisor.log."
    exit 1
  fi

  if [[ -s "$MANIFEST" && "$HEALTH_STATUS" == "ACTIVE" ]] &&
     curl --fail --silent --max-time 3 http://127.0.0.1:8000/health/live >/dev/null 2>&1 &&
     curl --fail --silent --max-time 3 http://127.0.0.1:8010/ready >/dev/null 2>&1 &&
     curl --fail --silent --max-time 3 http://127.0.0.1:8787/health/ready >/dev/null 2>&1; then
    ENDPOINTS="$(python3 - "$MANIFEST" "$CODESPACE_NAME" "$FORWARD_DOMAIN" <<'PY'
import json,sys
try:
    d=json.loads(open(sys.argv[1], encoding="utf-8").read())
    name,domain=sys.argv[2:4]
    expected={
        "schema_version":1,
        "bridge_url":f"https://{name}-8000.{domain}",
        "management_api_origin":f"https://{name}-8010.{domain}",
    }
    if d != expected:
        raise ValueError("forwarded endpoints do not match this Codespace")
    print(d["bridge_url"])
    print(d["management_api_origin"])
except (OSError,ValueError,TypeError,KeyError):
    raise SystemExit(1)
PY
)" && {
      mapfile -t ORIGINS <<< "$ENDPOINTS"
      if [[ ${#ORIGINS[@]} -eq 2 ]]; then
        BRIDGE_PUBLIC="$(http_code "${ORIGINS[0]}/health/live")"
        API_PUBLIC="$(http_code "${ORIGINS[1]}/ready")"
        if [[ "$BRIDGE_PUBLIC" == "200" && "$API_PUBLIC" == "200" ]]; then
          pass 'Codespaces DEMO host is ready: Bridge, Management API/PostgreSQL and isolated host agent are healthy.'
          printf 'BRIDGE: %s\n' "${ORIGINS[0]}"
          printf 'MANAGEMENT API: %s\n' "${ORIGINS[1]}"
          printf 'PORTS: 8000 and 8010 were auto-forwarded and passed the guarded public-visibility checks. No manual port-forwarding is required.\n'
          printf 'PHONE: DestinedFX APK → Bot → Codespaces Host → AUTHORIZE GITHUB (once), then START HOST. The app discovers and saves the Bridge automatically.\n'
          printf 'DEMO: Sign in with your own broker DEMO account and wait for matching terminal identity and fresh EA heartbeat.\n'
          exit 0
        fi
      fi
    }
  fi
  sleep 5
done

fail "Timed out after ${TIMEOUT_SECONDS}s waiting for the verified public Bridge and Management API."
fail "No DEMO-ready result was reported. Check the private supervisor logs under $RUNTIME; do not make the ports public manually."
exit 1
