#!/usr/bin/env bash
set -euo pipefail
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
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

# The terminal is launched in its own session and is stopped through the same
# process controller that started it; do not kill arbitrary Wine processes.
if [[ -f "$RUNTIME/terminal.pid" ]]; then
  ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  export DESTINEDFX_TERMINAL_CONTROL=process DESTINEDFX_TERMINAL_PIDFILE="$RUNTIME/terminal.pid"
  export DESTINEDFX_TERMINAL_LAUNCHER="$ROOT/scripts/linux-host/launch-mt5.sh"
  if python3 "$ROOT/scripts/linux-host/linux-worker.py" --terminal stop >/dev/null 2>&1; then
    printf 'PASS: MT5 terminal stopped.\n'
  else
    printf 'FAIL: MT5 terminal did not stop cleanly; check the process list.\n' >&2
  fi
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
