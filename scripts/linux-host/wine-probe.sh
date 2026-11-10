#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WINEPREFIX="${WINEPREFIX:-$HOME/.mt5}"
WINE64_BIN="${WINE64_BIN:-$(command -v wine64 || true)}"
TERMINAL="${MT5_TERMINAL_EXE:-$WINEPREFIX/drive_c/Program Files/MetaTrader 5/terminal64.exe}"

if ! WINE_BIN="${WINE_BIN:-wine}" "$SCRIPT_DIR/wine-guard.sh"; then
  printf 'BLOCKED: unsupported Wine version; run sudo bash scripts/linux-host/wine-guard.sh --fix\n' >&2
  exit 1
fi

if [[ ! -f "$TERMINAL" ]]; then
  printf 'NOT_INSTALLED: MetaTrader terminal was not found in the selected Wine prefix.\n'
  exit 0
fi
if [[ -z "$WINE64_BIN" || ! -x "$WINE64_BIN" ]]; then
  printf 'BLOCKED: wine64 is not installed; rebuild with Ubuntu wine64.\n' >&2
  exit 1
fi

temporary="$(mktemp -d)"
xvfb_pid=''
terminal_pid=''
cleanup() {
  if [[ -n "$terminal_pid" ]] && kill -0 "$terminal_pid" 2>/dev/null; then
    kill "$terminal_pid" 2>/dev/null || true
  fi
  if [[ -n "$xvfb_pid" ]] && kill -0 "$xvfb_pid" 2>/dev/null; then
    kill "$xvfb_pid" 2>/dev/null || true
  fi
  rm -rf -- "$temporary"
}
trap cleanup EXIT

display_file="$temporary/display"
Xvfb -displayfd 3 -screen 0 1280x800x24 -nolisten tcp 3>"$display_file" \
  >"$temporary/xvfb.log" 2>&1 &
xvfb_pid=$!
for _ in {1..50}; do
  [[ -s "$display_file" ]] && break
  kill -0 "$xvfb_pid" 2>/dev/null || {
    printf 'FAIL: Xvfb did not start.\n' >&2
    exit 1
  }
  sleep 0.1
done
if [[ ! -s "$display_file" ]]; then
  printf 'FAIL: Xvfb did not publish a display number.\n' >&2
  exit 1
fi
export DISPLAY=":$(cat "$display_file")" WINEPREFIX

if ! "$WINE64_BIN" cmd /c ver; then
  printf 'FAIL: wine64 cmd /c ver did not run successfully.\n' >&2
  exit 1
fi

logs_dir="$(dirname "$TERMINAL")/Logs"
mkdir -p "$logs_dir"
probe_started="$(date +%s.%N)"
"$WINE64_BIN" "$TERMINAL" /portable >"$temporary/terminal.log" 2>&1 &
terminal_pid=$!
for _ in {1..25}; do
  if ! kill -0 "$terminal_pid" 2>/dev/null; then
    if ! find "$logs_dir" -maxdepth 1 -type f -newermt "@$probe_started" -print -quit | grep -q .; then
      printf 'EXITED_EARLY: terminal exited within 25 seconds with no journal; possible debugger check; run wine-guard.sh --report.\n'
    else
      printf 'EXITED_EARLY: terminal exited within 25 seconds after creating a journal.\n'
    fi
    exit 1
  fi
  sleep 1
done

if find "$logs_dir" -maxdepth 1 -type f -newermt "@$probe_started" -print -quit | grep -q .; then
  printf 'STARTED: terminal process is alive and a journal was created in %s.\n' "$logs_dir"
else
  printf 'STARTUP_UNCONFIRMED: terminal process is alive, but no journal was created within 25 seconds.\n'
  exit 1
fi
