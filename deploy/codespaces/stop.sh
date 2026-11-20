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
  python3 "$PROCESS_IDENTITY" stop --record "$record" --timeout 5 || return 1
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

# The host agent runs as root. Stop only the process whose effective UID,
# exact script path, arguments and start-time match this Codespaces instance.
if [[ -f "$ROOT/scripts/linux-host/host-agent.py" ]]; then
  python3 - "$ROOT/scripts/linux-host/host-agent.py" <<'PY'
import os, signal, sys, time
from pathlib import Path
script=os.path.realpath(sys.argv[1])
proc=Path("/proc")
def identity(pid):
    base=proc/str(pid)
    try:
        status=(base/"status").read_text(encoding="ascii")
        uid_line=next(line for line in status.splitlines() if line.startswith("Uid:"))
        effective_uid=int(uid_line.split()[2])
        raw=(base/"stat").read_text(encoding="ascii")
        tail=raw[raw.rfind(")")+2:].split()
        ticks=int(tail[19])
        args=[x.decode("utf-8","replace") for x in (base/"cmdline").read_bytes().split(b"\0") if x]
        exe=os.path.realpath(base/"exe")
        state=tail[0]
        return effective_uid,ticks,exe,args,state
    except (OSError,ValueError,IndexError,StopIteration):
        return None
for entry in proc.glob("[0-9]*"):
    try: pid=int(entry.name)
    except ValueError: continue
    row=identity(pid)
    if not row: continue
    uid,ticks,exe,args,state=row
    if (uid!=0 or exe!=os.path.realpath("/usr/bin/python3") or
        len(args)<2 or os.path.realpath(args[1])!=script or
        args[2:] != ["--host","127.0.0.1","--port","8787"]):
        continue
    try: os.kill(pid,signal.SIGTERM)
    except ProcessLookupError: continue
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        current=identity(pid)
        if not current or current[1]!=ticks or current[4] in {"Z","X"}:
            break
        if current[0]!=0 or current[2]!=exe or current[3]!=args:
            raise SystemExit("FAIL: root host-agent identity changed after SIGTERM; no further signal sent")
        time.sleep(.1)
    current=identity(pid)
    if current and current[1]==ticks and current[4] not in {"Z","X"}:
        if current[0]!=0 or current[2]!=exe or current[3]!=args:
            raise SystemExit("FAIL: root host-agent identity changed; process left untouched")
        raise SystemExit("FAIL: root host agent did not stop after SIGTERM; refusing SIGKILL escalation")
PY
fi

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
  if ! python3 "$PROCESS_IDENTITY" stop --record "$record" --timeout 5; then
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
