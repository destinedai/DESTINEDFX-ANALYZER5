#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROCESS_IDENTITY="$ROOT/deploy/codespaces/process_identity.py"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
BRIDGE_DB="$STATE/bridge.sqlite3"
SLOT_ROOT="${DESTINEDFX_SLOT_ROOT:-/home/destinedfx-slots}"
SLOT_RUNTIME="$RUNTIME/slot-runtime"
PASS=0
MODE="${1:-full}"
if [[ "$MODE" == "--preflight" ]]; then
  [[ "$#" -eq 1 ]] || { printf 'FAIL: stop preflight takes no additional arguments.\n' >&2; exit 2; }
  if ! python3 "$ROOT/scripts/linux-host/linux-slot-worker.py" --check-stop; then
    printf 'FAIL: local stop preflight did not verify broker state and isolated-slot cleanup prerequisites.\n' >&2
    exit 2
  fi
  printf 'PASS: STOP PREFLIGHT acknowledged by stop.sh; no services or trading processes were stopped.\n'
  exit 0
fi
[[ "$MODE" == "full" && "$#" -le 1 ]] || { printf 'FAIL: unsupported stop.sh mode.\n' >&2; exit 2; }

STOP_FAILURES=0
stop_recorded() {
  local file="$1" expected="$2" pid record
  [[ -s "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ && -n "$expected" ]] || {
    printf 'WARN: invalid PID record for %s; no process was signalled.\n' "$expected" >&2
    return 1
  }
  record="$RUNTIME/process-$pid.identity.json"
  [[ -s "$record" ]] || {
    printf 'WARN: no complete identity record for %s PID %s; leaving it untouched.\n' "$expected" "$pid" >&2
    return 1
  }
  sudo -n python3 "$PROCESS_IDENTITY" stop --record "$record" --timeout 5 || return 1
  rm -f "$file"
  return 0
}

# First stop per-pairing MT5 processes. This runner rechecks the durable
# Bridge broker state and refuses cleanup if any account is unsafe or unknown.
ROOT="$ROOT"
if [[ -d "$STATE/isolated-slots" ]]; then
  shopt -s nullglob
  SLOT_MARKERS=("$STATE/isolated-slots"/*.json)
  shopt -u nullglob
  if (( ${#SLOT_MARKERS[@]} > 0 )); then
    if [[ ! -s "$BRIDGE_DB" ]]; then
      printf 'FAIL: isolated MT5 slots exist but the Bridge database is unavailable; refusing host shutdown.\n' >&2
      exit 2
    fi
    export BRIDGE_DB_PATH="$BRIDGE_DB" DESTINEDFX_SLOT_ROOT="$SLOT_ROOT" DESTINEDFX_SLOT_RUNTIME="$SLOT_RUNTIME"
    if ! python3 "$ROOT/scripts/linux-host/linux-slot-worker.py" --stop-all; then
      printf 'FAIL: isolated MT5 sessions could not be safely stopped; refusing host shutdown.\n' >&2
      exit 2
    fi
    printf 'PASS: isolated MT5 slots stopped after broker-state verification.\n'
  fi
fi

# Root host-agent is included in the same full-identity ledger as all services.
# Stop the private VNC helpers only through complete process identity records.
for spec in "x11vnc.pid|x11vnc" "novnc.pid|websockify"; do
  IFS='|' read -r pidfile expected <<< "$spec"
  if ! stop_recorded "$RUNTIME/$pidfile" "$expected"; then
    STOP_FAILURES=$((STOP_FAILURES + 1))
  fi
done

# Xvfb adopted from the devcontainer is borrowed. A service-owned Xvfb is
# stopped only by its complete record; PID-only legacy ownership is not trusted.
XVFB_OWNERSHIP="$(cat "$RUNTIME/xvfb.ownership" 2>/dev/null || true)"
case "$XVFB_OWNERSHIP" in
  started)
    if ! stop_recorded "$RUNTIME/xvfb.pid" "Xvfb :99"; then
      STOP_FAILURES=$((STOP_FAILURES + 1))
    fi
    ;;
  adopted)
    if [[ -s "$RUNTIME/xvfb.pid" ]]; then
      python3 "$ROOT/deploy/codespaces/display_processes.py" check-xvfb "$(cat "$RUNTIME/xvfb.pid")" >/dev/null 2>&1 || {
        printf 'WARN: adopted Xvfb identity no longer matches; process was not signalled.\n' >&2
        STOP_FAILURES=$((STOP_FAILURES + 1))
      }
    fi
    rm -f "$RUNTIME/xvfb.pid"
    printf 'PASS: left pre-existing adopted Xvfb running; this service did not own it.\n'
    ;;
  "")
    if [[ -s "$RUNTIME/xvfb.pid" ]]; then
      printf 'WARN: legacy Xvfb PID has no ownership proof; left untouched.\n' >&2
      STOP_FAILURES=$((STOP_FAILURES + 1))
    fi
    ;;
  *)
    printf 'WARN: unknown Xvfb ownership marker; left display process untouched.\n' >&2
    STOP_FAILURES=$((STOP_FAILURES + 1))
    ;;
esac

if [[ ! -f "$RUNTIME/pids" ]]; then
  if (( STOP_FAILURES > 0 )); then
    printf 'FAIL: one or more tracked processes lacked verified shutdown identity.\n' >&2
    exit 2
  fi
  printf 'PASS: no tracked Codespaces processes to stop.\n'
  exit 0
fi

while IFS='|' read -r pid identity_file; do
  if [[ ! "$pid" =~ ^[0-9]+$ || "$identity_file" != "process-$pid.identity.json" ]]; then
    printf 'WARN: legacy PID-only record found; no signal was sent.\n' >&2
    STOP_FAILURES=$((STOP_FAILURES + 1))
    continue
  fi
  record="$RUNTIME/$identity_file"
  [[ -s "$record" ]] || continue
  if ! sudo -n python3 "$PROCESS_IDENTITY" stop --record "$record" --timeout 5; then
    STOP_FAILURES=$((STOP_FAILURES + 1))
    continue
  fi
  for pidfile in x11vnc.pid novnc.pid xvfb.pid; do
    [[ -s "$RUNTIME/$pidfile" ]] || continue
    [[ "$(cat "$RUNTIME/$pidfile" 2>/dev/null || true)" == "$pid" ]] && rm -f "$RUNTIME/$pidfile"
  done
  PASS=$((PASS + 1))
done < "$RUNTIME/pids"

if (( STOP_FAILURES > 0 )); then
  printf 'FAIL: %s process identity check(s) prevented complete shutdown; records were retained for review.\n' "$STOP_FAILURES" >&2
  exit 2
fi

rm -f "$RUNTIME/pids" "$RUNTIME/xvfb.ownership"
printf 'PASS: stop completed using recorded process identities; private credentials were retained in the Codespaces-only state directory.\n'
