#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
BRIDGE_DB="$STATE/bridge.sqlite3"
SLOT_ROOT="${DESTINEDFX_SLOT_ROOT:-/home/destinedfx-slots}"
SLOT_RUNTIME="$RUNTIME/slot-runtime"
PASS=0

stop_recorded() {
  local file="$1" expected="$2" pid cmdline
  [[ -s "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ && -n "$expected" ]] || { rm -f "$file"; return 0; }
  kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
  cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
  if [[ "$cmdline" != *"$expected"* ]]; then
    printf 'WARN: left PID %s untouched because identity did not match %s.\n' "$pid" "$expected" >&2
    rm -f "$file"
    return 1
  fi
  kill "$pid" 2>/dev/null || return 1
  for _ in {1..30}; do
    kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
    cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
    [[ "$cmdline" == *"$expected"* ]] || { rm -f "$file"; return 0; }
    sleep 0.1
  done
  cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
  if [[ "$cmdline" == *"$expected"* ]]; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
  for _ in {1..10}; do
    kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
    sleep 0.1
  done
  return 1
}

# First stop per-pairing MT5 processes. This runner rechecks the durable
# Bridge broker state and refuses cleanup if any account is unsafe or unknown.
ROOT="$ROOT"
if [[ -d "$SLOT_ROOT" ]]; then
  shopt -s nullglob
  SLOT_MARKERS=("$SLOT_ROOT"/*/*/.destinedfx-worker.json)
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
        status=(base/"status").read_text()
        uid_line=next(line for line in status.splitlines() if line.startswith("Uid:"))
        effective_uid=int(uid_line.split()[2])
        raw=(base/"stat").read_text()
        tail=raw[raw.rfind(")")+2:].split()
        ticks=int(tail[19])
        args=[x.decode("utf-8","replace") for x in (base/"cmdline").read_bytes().split(b"\0") if x]
        return effective_uid,ticks,args
    except (OSError,ValueError,IndexError,StopIteration):
        return None
for entry in proc.glob("[0-9]*"):
    try: pid=int(entry.name)
    except ValueError: continue
    row=identity(pid)
    if not row: continue
    uid,ticks,args=row
    if uid!=0 or not args or os.path.realpath(args[0])!=script:
        continue
    if args[1:] != ["--host","127.0.0.1","--port","8787"]:
        continue
    try: os.kill(pid,signal.SIGTERM)
    except ProcessLookupError: continue
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        current=identity(pid)
        if not current or current[1]!=ticks: break
        time.sleep(.1)
    current=identity(pid)
    if current and current[1]==ticks:
        try: os.kill(pid,signal.SIGKILL)
        except ProcessLookupError: pass
        time.sleep(.2)
        current=identity(pid)
        if current and current[1]==ticks:
            raise SystemExit("FAIL: root host agent did not terminate")
PY
fi

# GUI and VNC helpers have PID files outside the main service list. Validate
# each command before signalling it; a stale/reused PID is never killed.
GUI_INSTALLER_WAS_TRACKED=0
[[ -s "$RUNTIME/installer-gui.pid" ]] && GUI_INSTALLER_WAS_TRACKED=1
stop_recorded "$RUNTIME/installer-gui.pid" "install-mt5.sh --gui" || true
stop_recorded "$RUNTIME/x11vnc.pid" "x11vnc" || true
stop_recorded "$RUNTIME/novnc.pid" "websockify" || true
stop_recorded "$RUNTIME/xvfb.pid" "Xvfb :99" || true

if (( GUI_INSTALLER_WAS_TRACKED )) && command -v wineserver >/dev/null 2>&1; then
  GUI_WINEPREFIX="${DESTINEDFX_MT5_GUI_WINEPREFIX:-${WINEPREFIX:-$HOME/.mt5}}"
  WINEPREFIX="$GUI_WINEPREFIX" wineserver -k >/dev/null 2>&1 || true
fi

if [[ ! -f "$RUNTIME/pids" ]]; then
  printf 'PASS: no tracked Codespaces processes to stop.\n'
  exit 0
fi

while IFS='|' read -r pid expected; do
  [[ "$pid" =~ ^[0-9]+$ && -n "$expected" ]] || {
    printf 'WARN: skipped legacy or unverified PID record.\n' >&2
    continue
  }
  if kill -0 "$pid" 2>/dev/null; then
    cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
    if [[ "$cmdline" != *"$expected"* ]]; then
      printf 'WARN: left PID %s untouched because process identity did not match.\n' "$pid" >&2
      continue
    fi
    kill "$pid" 2>/dev/null || true
    for _ in {1..30}; do
      kill -0 "$pid" 2>/dev/null || break
      cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
      [[ "$cmdline" == *"$expected"* ]] || break
      sleep 0.1
    done
    cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
    if kill -0 "$pid" 2>/dev/null && [[ "$cmdline" == *"$expected"* ]]; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
    printf 'PASS: stopped verified process %s.\n' "$pid"
    PASS=$((PASS + 1))
  else
    printf 'PASS: tracked process %s was already stopped.\n' "$pid"
  fi
done < "$RUNTIME/pids"

rm -f "$RUNTIME/pids"
printf 'PASS: stop completed; private credentials were retained in the Codespaces-only state directory.\n'
