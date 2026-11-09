#!/usr/bin/env bash
set -euo pipefail

# MetaQuotes documents mt5linux.sh as a GUI wrapper and documents /auto /path for
# mt5setup.exe. This script pins both official artifacts, then runs setup.exe directly.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIN_FILE="${DESTINEDFX_INSTALLER_PINS_FILE:-$SCRIPT_DIR/../../deploy/codespaces/installer-pins.env}"
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

usage() {
  cat <<'EOF'
Usage: install-mt5.sh [options]

Options:
  --expected-sha256 HEX          Required x86_64 Linux-wrapper pin.
  --expected-setup-sha256 HEX    Required x86_64 setup.exe pin.
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
printf 'official_linux_script_url=%s\nofficial_linux_script_sha256=%s\nofficial_setup_url=%s\nofficial_setup_sha256=%s\n' \
  "$MT5_INSTALLER_URL" "$script_sha256" "$MT5_SETUP_URL" "$setup_sha256"
if [[ "$PRINT_ONLY" == "1" ]]; then exit 0; fi
if [[ "$PRINT_ONLY" == "2" ]]; then exit 0; fi

if [[ ! "$EXPECTED_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: supply --expected-sha256 with the reviewed Linux-wrapper hash." >&2
  exit 23
fi
if [[ ! "$EXPECTED_SETUP_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: supply --expected-setup-sha256 with the reviewed setup.exe hash." >&2
  exit 24
fi
if [[ "${EXPECTED_SHA256,,}" != "${script_sha256,,}" ]]; then
  echo "BLOCKED: Linux-wrapper SHA-256 mismatch; review the newly printed value before changing the pin." >&2
  exit 25
fi
if [[ "${EXPECTED_SETUP_SHA256,,}" != "${setup_sha256,,}" ]]; then
  echo "BLOCKED: setup.exe SHA-256 mismatch; review the newly printed value before changing the pin." >&2
  exit 26
fi

if [[ ! -x "$(command -v wine || true)" || ! -x "$(command -v xvfb-run || true)" ]]; then
  echo "BLOCKED: Wine and xvfb-run are required. Rebuild the Ubuntu Codespaces image with its repository packages." >&2
  exit 27
fi
mkdir -p "$WINEPREFIX_VALUE"
chmod 700 "$WINEPREFIX_VALUE"
export WINEPREFIX="$WINEPREFIX_VALUE"

if [[ "$GUI" == "1" ]]; then
  [[ -n "${DISPLAY:-}" ]] || { echo "BLOCKED: GUI mode needs DISPLAY. Run deploy/codespaces/gui.sh." >&2; exit 28; }
  echo "OWNER STEP: complete the MetaTrader setup wizard in the private Codespaces noVNC window."
  exec wine "$setup"
fi

# MetaQuotes documents this exact switch pair for setup.exe. Xvfb supplies the display
# required by the Windows web installer without exposing a public port.
xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' \
  env WINEPREFIX="$WINEPREFIX" wineboot -u
xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24 -nolisten tcp' \
  env WINEPREFIX="$WINEPREFIX" wine "$setup" /auto /path:"$INSTALL_PATH"
terminal="$WINEPREFIX_VALUE/drive_c/Program Files/MetaTrader 5/terminal64.exe"
if [[ ! -f "$terminal" ]]; then
  echo "BLOCKED: /auto completed without terminal64.exe at the standard path." >&2
  echo "OWNER STEP: run deploy/codespaces/gui.sh and complete the private setup wizard once." >&2
  exit 29
fi
chmod 700 "$terminal"
echo "PASS: headless MetaTrader setup completed at $terminal"
