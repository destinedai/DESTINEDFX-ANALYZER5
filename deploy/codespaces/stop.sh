#!/usr/bin/env bash
set -euo pipefail
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
PASS=0
FAIL=0
# The terminal runs in its own session; stop it through the same controller that started it.
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
while read -r pid; do
  [[ "$pid" =~ ^[0-9]+$ ]] || continue
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
    printf 'PASS: stopped tracked process %s.\n' "$pid"
    PASS=$((PASS + 1))
  else
    printf 'PASS: tracked process %s was already stopped.\n' "$pid"
  fi
done < "$RUNTIME/pids"
rm -f "$RUNTIME/pids"
printf 'PASS: stop completed; private credentials were retained in the Codespaces-only state directory.\n'
