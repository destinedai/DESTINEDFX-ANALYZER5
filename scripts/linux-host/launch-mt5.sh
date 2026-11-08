#!/usr/bin/env bash
set -euo pipefail

MT5_TERMINAL_EXE="${MT5_TERMINAL_EXE:-/var/lib/destinedfx/terminal/drive_c/Program Files/MetaTrader 5/terminal64.exe}"
MT5_STARTUP_CONFIG="${DESTINEDFX_TERMINAL_STARTUP_CONFIG:-/var/lib/destinedfx/terminal/startup.ini}"
WINE_BIN="${WINE_BIN:-/usr/bin/wine}"
WINEPATH_BIN="${WINEPATH_BIN:-/usr/bin/winepath}"

if [[ ! -x "$WINE_BIN" || ! -f "$MT5_TERMINAL_EXE" ]]; then
  echo "BLOCKED: Wine or the configured MT5 terminal executable is missing." >&2
  exit 2
fi

if [[ -f "$MT5_STARTUP_CONFIG" ]]; then
  if [[ ! -x "$WINEPATH_BIN" ]]; then
    echo "BLOCKED: winepath is required to pass the private startup config safely." >&2
    exit 2
  fi
  windows_config="$($WINEPATH_BIN -w "$MT5_STARTUP_CONFIG")"
  if [[ -z "$windows_config" ]]; then
    echo "BLOCKED: could not translate the private startup config path." >&2
    exit 2
  fi
  exec "$WINE_BIN" "$MT5_TERMINAL_EXE" /portable "/config:$windows_config"
fi

exec "$WINE_BIN" "$MT5_TERMINAL_EXE" /portable
