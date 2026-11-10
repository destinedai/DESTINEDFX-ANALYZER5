#!/usr/bin/env bash
set -euo pipefail

MT5_TERMINAL_EXE="${MT5_TERMINAL_EXE:-/var/lib/destinedfx/terminal/drive_c/Program Files/MetaTrader 5/terminal64.exe}"
MT5_STARTUP_CONFIG="${DESTINEDFX_TERMINAL_STARTUP_CONFIG:-/var/lib/destinedfx/terminal/startup.ini}"
WINE_BIN="${WINE_BIN:-/usr/bin/wine}"
WINEPATH_BIN="${WINEPATH_BIN:-/usr/bin/winepath}"
WINEPREFIX="${WINEPREFIX:-$HOME/.wine}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -x "$WINE_BIN" || ! -f "$MT5_TERMINAL_EXE" ]]; then
  echo "BLOCKED: Wine or the configured MT5 terminal executable is missing." >&2
  exit 2
fi
if ! WINE_BIN="$WINE_BIN" "$SCRIPT_DIR/wine-guard.sh"; then
  echo "BLOCKED: Wine version is unsupported; refusing to launch MetaTrader." >&2
  exit 2
fi

terminal_args=("$MT5_TERMINAL_EXE" /portable)
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
  terminal_args+=("/config:$windows_config")
fi

clean_wine_env=(env
  -u WINEDEBUG -u WINEDLLOVERRIDES -u LD_PRELOAD -u LD_AUDIT
  -u PROTON_DEBUG -u WINEESYNC -u WINEFSYNC
  WINEDEBUG=-all WINEDLLOVERRIDES='' WINEPREFIX="$WINEPREFIX")
set +e
"${clean_wine_env[@]}" "$WINE_BIN" "${terminal_args[@]}"
exit_code=$?
set -e

if [[ -n "${DESTINEDFX_TERMINAL_EXIT_CODE_FILE:-}" ]]; then
  exit_file="$DESTINEDFX_TERMINAL_EXIT_CODE_FILE"
  mkdir -p "$(dirname "$exit_file")"
  temporary="${exit_file}.tmp.$$"
  (umask 077; printf '%s\n' "$exit_code" > "$temporary")
  mv -f "$temporary" "$exit_file"
fi
printf 'MT5_TERMINAL_EXIT_CODE=%s\n' "$exit_code"
exit "$exit_code"
