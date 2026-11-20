#!/usr/bin/env bash
set -euo pipefail

# MetaQuotes documents mt5linux.sh as a GUI wrapper and documents /auto /path for
# mt5setup.exe. This script pins both official artifacts, then runs setup.exe directly.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIN_FILE="${DESTINEDFX_INSTALLER_PINS_FILE:-$SCRIPT_DIR/../../deploy/codespaces/installer-pins.env}"
# shellcheck source=verify-mt5-signature.sh
source "$SCRIPT_DIR/verify-mt5-signature.sh"
if [[ -f "$PIN_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$PIN_FILE"
fi
MT5_INSTALLER_URL="${MT5_LINUX_SCRIPT_URL:-https://download.terminal.free/cdn/web/metaquotes.software.corp/mt5/mt5linux.sh}"
MT5_SETUP_URL="${MT5_SETUP_URL:-https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe}"
EXPECTED_SHA256=""
EXPECTED_SETUP_SHA256=""
ALLOW_EXPERIMENTAL_ARM=0
PRINT_ONLY=0
GUI=0
WINEPREFIX_VALUE="${WINEPREFIX:-$HOME/.mt5}"
INSTALL_PATH='C:\Program Files\MetaTrader 5'
STATE_DIR="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
PROVENANCE_FILE="${DESTINEDFX_MT5_INSTALLER_PROVENANCE_FILE:-$STATE_DIR/mt5-installer-provenance.env}"
COMPLETION_FILE="$STATE_DIR/installer-completed.json"
VERIFIED_MARKER="$STATE_DIR/installer-verified"
METAEDITOR_PATH=""
BASE_TERMINAL_SHA256=""
BASE_METAEDITOR_SHA256=""
PREFIX_INFO_FILE="${DESTINEDFX_MT5_PREFIX_INFO_FILE:-$STATE_DIR/mt5-prefix.env}"

url_host() {
  python3 -c 'from urllib.parse import urlsplit; import sys; print(urlsplit(sys.argv[1]).hostname or "unavailable")' "$1"
}

record_private_metadata() {
  local target="$1" temporary="$2"
  mkdir -p "$(dirname "$target")"
  chmod 700 "$(dirname "$target")"
  chmod 600 "$temporary"
  mv -f "$temporary" "$target"
}

record_installer_provenance() {
  local temporary
  mkdir -p "$(dirname "$PROVENANCE_FILE")"
  temporary="$(mktemp "${PROVENANCE_FILE}.XXXXXX")"
  {
    printf 'verified_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'wrapper_host=%s\nwrapper_sha256=%s\nwrapper_expected_sha256=%s\n' \
      "$(url_host "$MT5_INSTALLER_URL")" "$script_sha256" "$EXPECTED_SHA256"
    printf 'setup_host=%s\nsetup_sha256=%s\nsetup_expected_sha256=%s\n' \
      "$(url_host "$MT5_SETUP_URL")" "$setup_sha256" "$EXPECTED_SETUP_SHA256"
    printf 'authenticode_status=verified\nauthenticode_signer=MetaQuotes Ltd\n'
  } > "$temporary"
  record_private_metadata "$PROVENANCE_FILE" "$temporary"
}

find_metaeditor() {
  local candidate
  METAEDITOR_PATH=""
  for candidate in \
    "$WINEPREFIX_VALUE/drive_c/Program Files/MetaTrader 5/metaeditor64.exe" \
    "$WINEPREFIX_VALUE/drive_c/Program Files/MetaTrader 5/MetaEditor64.exe" \
    "$WINEPREFIX_VALUE/drive_c/Program Files/MetaTrader 5/MetaEditor.exe"; do
    if [[ -f "$candidate" && ! -L "$candidate" ]]; then
      METAEDITOR_PATH="$candidate"
      return 0
    fi
  done
  return 1
}

file_sha256() {
  [[ -f "$1" && ! -L "$1" ]] || return 1
  sha256sum "$1" | awk '{print $1}'
}

capture_installer_baseline() {
  BASE_TERMINAL_SHA256=""
  BASE_METAEDITOR_SHA256=""
  if [[ -f "$TERMINAL_PATH" && ! -L "$TERMINAL_PATH" ]]; then
    BASE_TERMINAL_SHA256="$(file_sha256 "$TERMINAL_PATH")"
  fi
  if find_metaeditor; then
    BASE_METAEDITOR_SHA256="$(file_sha256 "$METAEDITOR_PATH")"
  fi
}

record_successful_installer_completion() {
  local mode="$1" current_terminal current_editor
  [[ -f "$TERMINAL_PATH" && ! -L "$TERMINAL_PATH" ]] || {
    echo "BLOCKED: successful installer exit did not produce the standard terminal binary." >&2
    return 1
  }
  find_metaeditor || {
    echo "BLOCKED: successful installer exit did not produce MetaEditor." >&2
    return 1
  }
  current_terminal="$(file_sha256 "$TERMINAL_PATH")"
  current_editor="$(file_sha256 "$METAEDITOR_PATH")"
  if [[ -n "$BASE_TERMINAL_SHA256" && "$BASE_TERMINAL_SHA256" == "$current_terminal" ]]; then
    echo "BLOCKED: terminal binary is unchanged from before this installer run; completion cannot be proven." >&2
    return 1
  fi
  if [[ -n "$BASE_METAEDITOR_SHA256" && "$BASE_METAEDITOR_SHA256" == "$current_editor" ]]; then
    echo "BLOCKED: MetaEditor binary is unchanged from before this installer run; completion cannot be proven." >&2
    return 1
  fi
  python3 "$SCRIPT_DIR/installer_completion.py" record \
    --manifest "$COMPLETION_FILE" \
    --provenance "$PROVENANCE_FILE" \
    --terminal "$TERMINAL_PATH" \
    --metaeditor "$METAEDITOR_PATH" \
    --mode "$mode" --exit-code 0
}

record_prefix_configuration() {
  local temporary prefix_arch=unknown
  mkdir -p "$(dirname "$PREFIX_INFO_FILE")"
  temporary="$(mktemp "${PREFIX_INFO_FILE}.XXXXXX")"
  if [[ -r "$WINEPREFIX_VALUE/system.reg" ]]; then
    prefix_arch="$(sed -nE 's/^#arch=(win32|win64)$/\1/p' "$WINEPREFIX_VALUE/system.reg" | head -n 1)"
    [[ -n "$prefix_arch" ]] || prefix_arch=unknown
  fi
  {
    printf 'prefix=%s\n' "$WINEPREFIX_VALUE"
    printf 'prefix_arch=%s\nwindows_version=win10\n' "$prefix_arch"
  } > "$temporary"
  record_private_metadata "$PREFIX_INFO_FILE" "$temporary"
}

usage() {
  cat <<'EOF'
Usage: install-mt5.sh [options]

Options:
  --expected-sha256 HEX          Required x86_64 Linux-wrapper pin.
  --expected-setup-sha256 HEX    Recorded setup.exe hash; mismatch warns after signature verification.
  --print-script-sha256          Download and print the Linux-wrapper hash, then stop.
  --print-setup-sha256           Download and print the setup.exe hash, then stop.
  --headless                     Run setup.exe with documented /auto /path under Xvfb.
  --gui                          Run the owner-assisted setup wizard on DISPLAY.
  --experimental-arm             Record the ARM experiment and stop before install.
  --record-dir PATH              Store the ARM experiment record in PATH.
  --wineprefix PATH              Use this private Wine prefix.

The default is --headless. The installer never logs credentials and never runs on ARM.
EOF
}

RECORD_DIR="${DESTINEDFX_LINUX_RECORD_DIR:-$SCRIPT_DIR/records}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-sha256) EXPECTED_SHA256="${2:-}"; shift 2 ;;
    --expected-setup-sha256) EXPECTED_SETUP_SHA256="${2:-}"; shift 2 ;;
    --print-script-sha256) PRINT_ONLY=1; shift ;;
    --print-setup-sha256) PRINT_ONLY=2; shift ;;
    --headless) GUI=0; shift ;;
    --gui) GUI=1; shift ;;
    --experimental-arm) ALLOW_EXPERIMENTAL_ARM=1; shift ;;
    --record-dir) RECORD_DIR="${2:-}"; shift 2 ;;
    --wineprefix) WINEPREFIX_VALUE="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# Derive Wine-prefix paths only after --wineprefix arguments have been applied.
TERMINAL_PATH="$WINEPREFIX_VALUE/drive_c/Program Files/MetaTrader 5/terminal64.exe"

arch="$(uname -m)"
mkdir -p "$RECORD_DIR"

if [[ "$arch" == "aarch64" || "$arch" == "arm64" ]]; then
  if [[ "$ALLOW_EXPERIMENTAL_ARM" != "1" ]]; then
    echo "BLOCKED: ARM host is experimental and unverified for MT5 with an EA." >&2
    echo "Re-run only with --experimental-arm to record an owner-run emulation experiment." >&2
    exit 20
  fi
  record="$RECORD_DIR/arm64-experiment-$(date -u +%Y%m%dT%H%M%SZ).log"
  {
    echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "architecture=$arch"
    echo "status=UNVERIFIED"
    echo "installer_url=$MT5_INSTALLER_URL"
    echo "route=ARM host with an x86 terminal through owner-selected emulation such as Box64, FEX, or Hangover"
    echo "attempted=The official x86 installer was not executed on ARM."
    echo "trading_gate=BLOCKED until host-agent health and a fresh identity-matched EA heartbeat both pass."
    echo "next_step=Owner-run DEMO experiment only; record exact emulator, Wine, terminal, EA, heartbeat, and 24-hour result."
  } > "$record"
  chmod 600 "$record"
  echo "ARM experiment recorded: $record"
  echo "UNVERIFIED: no MT5 installation or trading start was attempted."
  exit 21
fi

if [[ "$arch" != "x86_64" ]]; then
  echo "BLOCKED: unsupported host architecture '$arch'." >&2
  exit 22
fi

if [[ "$PRINT_ONLY" == "0" ]] && ! "$SCRIPT_DIR/wine-guard.sh"; then
  echo "BLOCKED: Wine version is unsupported; refusing to execute the MetaTrader installer." >&2
  exit 28
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
script="$workdir/mt5linux.sh"
setup="$workdir/mt5setup.exe"
fetch_and_hash() {
  local url="$1" output="$2"
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$url" --output "$output"
  sha256sum "$output" | awk '{print $1}'
}

script_sha256="$(fetch_and_hash "$MT5_INSTALLER_URL" "$script")"
setup_sha256="$(fetch_and_hash "$MT5_SETUP_URL" "$setup")"
printf 'official_linux_script_host=%s\nofficial_linux_script_sha256=%s\nofficial_setup_host=%s\nofficial_setup_sha256=%s\n' \
  "$(url_host "$MT5_INSTALLER_URL")" "$script_sha256" "$(url_host "$MT5_SETUP_URL")" "$setup_sha256"
if [[ "$PRINT_ONLY" == "1" ]]; then exit 0; fi
if [[ "$PRINT_ONLY" == "2" ]]; then exit 0; fi

if [[ ! "$EXPECTED_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: supply --expected-sha256 with the reviewed Linux-wrapper hash." >&2
  exit 23
fi
if [[ -n "$EXPECTED_SETUP_SHA256" && ! "$EXPECTED_SETUP_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: setup.exe recorded hash is malformed." >&2
  exit 24
fi
if [[ "${EXPECTED_SHA256,,}" != "${script_sha256,,}" ]]; then
  echo "BLOCKED: Linux-wrapper SHA-256 mismatch; review the newly printed value before changing the pin." >&2
  exit 25
fi
if [[ -n "$EXPECTED_SETUP_SHA256" && "${EXPECTED_SETUP_SHA256,,}" != "${setup_sha256,,}" ]]; then
  echo "WARNING: setup.exe SHA-256 changed; signature is authoritative, review and record the new last-known-good hash." >&2
fi

verify_mt5_authenticode "$setup"
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
# Invalidate every prior acceptance before a new installer attempt. A failed or
# canceled wizard must never inherit an earlier success marker.
rm -f "$VERIFIED_MARKER" "$COMPLETION_FILE"
record_installer_provenance

if [[ ! -x "$(command -v wine || true)" || ! -x "$(command -v xvfb-run || true)" ]]; then
  echo "BLOCKED: Wine and xvfb-run are required. Rebuild the Ubuntu Codespaces image with its repository packages." >&2
  exit 27
fi
WINE_GUARD_ALREADY_CHECKED=1 "$SCRIPT_DIR/check-wine-mt5-compat.sh"
mkdir -p "$WINEPREFIX_VALUE"
chmod 700 "$WINEPREFIX_VALUE"
export WINEPREFIX="$WINEPREFIX_VALUE"

# MetaQuotes setup refuses to start when a debugger or instrumentation hook is
# visible. Do not kill an owner's process silently: identify only debugger
# processes owned by this user and give the owner a precise fix.
debugger_pids="$(pgrep -u "$(id -u)" -f '(^|/)(winedbg|gdb|gdbserver|lldb|strace)([[:space:]]|$)' || true)"
if [[ -n "$debugger_pids" ]]; then
  echo "BLOCKED: debugger process detected for this owner: $debugger_pids" >&2
  echo "OWNER STEP: close that debugger, then rerun deploy/codespaces/gui.sh." >&2
  exit 26
fi

# Do not pass IDE tracing, debugger DLL overrides, preload shims, or Wine
# diagnostic channels into the vendor installer. The bridge remains separately
# observable through its own redacted logs.
clean_wine_env=(env
  -u WINEDEBUG -u WINEDLLOVERRIDES -u LD_PRELOAD -u LD_AUDIT
  -u PROTON_DEBUG -u WINEESYNC -u WINEFSYNC
  WINEDEBUG=-all WINEDLLOVERRIDES=''
  WINEPREFIX="$WINEPREFIX")

capture_installer_baseline

if [[ "$GUI" == "1" ]]; then
  [[ -n "${DISPLAY:-}" ]] || { echo "BLOCKED: GUI mode needs DISPLAY. Run deploy/codespaces/gui.sh." >&2; exit 28; }
  echo "INFO: initializing Wine prefix at $WINEPREFIX_VALUE before opening the installer."
  "${clean_wine_env[@]}" wineboot -u
  "${clean_wine_env[@]}" winecfg -v win10
  record_prefix_configuration
  echo "OWNER STEP: complete the MetaTrader setup wizard in the private Codespaces noVNC window."
  echo "INFO: installer completion is recorded only if the wizard exits successfully and the terminal/MetaEditor binaries are created or updated."
  if "${clean_wine_env[@]}" wine "$setup"; then
    installer_exit_code=0
  else
    installer_exit_code=$?
    echo "FAIL: MetaTrader GUI installer exited with status $installer_exit_code; no completion marker was written." >&2
    exit "$installer_exit_code"
  fi
  record_successful_installer_completion gui
  "$SCRIPT_DIR/finalize-mt5-install.sh" gui
  exit 0
fi

# MetaQuotes documents this exact switch pair for setup.exe. Xvfb supplies the display
# required by the Windows web installer without exposing a public port.
xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' \
  "${clean_wine_env[@]}" wineboot -u
xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' \
  "${clean_wine_env[@]}" winecfg -v win10
record_prefix_configuration
if xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' \
  "${clean_wine_env[@]}" wine "$setup" /auto /path:"$INSTALL_PATH"; then
  installer_exit_code=0
else
  installer_exit_code=$?
  echo "FAIL: MetaQuotes headless setup exited with status $installer_exit_code; no completion marker was written." >&2
  exit "$installer_exit_code"
fi
[[ -f "$TERMINAL_PATH" ]] || {
  echo "BLOCKED: /auto completed without terminal64.exe at the standard path." >&2
  echo "OWNER STEP: run deploy/codespaces/gui.sh only after reviewing the failed headless installation." >&2
  exit 29
}
record_successful_installer_completion headless
chmod 700 "$TERMINAL_PATH"
echo "PASS: headless MetaTrader setup completed and exact terminal/MetaEditor completion was recorded."
