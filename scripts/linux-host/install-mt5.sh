#!/usr/bin/env bash
set -euo pipefail

# Official MetaQuotes Linux installer referenced by the MetaTrader 5 help page.
MT5_INSTALLER_URL="https://download.terminal.free/cdn/web/metaquotes.software.corp/mt5/mt5linux.sh"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECORD_DIR="${DESTINEDFX_LINUX_RECORD_DIR:-$SCRIPT_DIR/records}"
EXPECTED_SHA256=""
ALLOW_EXPERIMENTAL_ARM=0
PRINT_ONLY=0

usage() {
  cat <<'EOF'
Usage: install-mt5.sh [options]

Options:
  --expected-sha256 HEX       Required on x86_64 before the official script runs.
  --print-script-sha256       Download and print the current script hash, then stop.
  --experimental-arm          Record the ARM emulation experiment and stop before install.
  --record-dir PATH           Store the ARM experiment record in PATH.

The installer never downloads or runs the x86 MT5 script on aarch64. It never claims
that MT5 or an EA is supported on ARM emulation. It does not log credentials.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-sha256) EXPECTED_SHA256="${2:-}"; shift 2 ;;
    --print-script-sha256) PRINT_ONLY=1; shift ;;
    --experimental-arm) ALLOW_EXPERIMENTAL_ARM=1; shift ;;
    --record-dir) RECORD_DIR="${2:-}"; shift 2 ;;
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
    echo "risks=Android or Linux process termination, battery or thermal limits, emulator overhead, and unverified MT5 EA behavior."
    echo "next_step=Owner-run DEMO experiment only; record exact emulator, Wine, terminal, EA, heartbeat, and 24-hour result."
  } > "$record"
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
trap 'rm -rf "$workdir"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$MT5_INSTALLER_URL" --output "$script"
sha256="$(sha256sum "$script" | awk '{print $1}')"
echo "official_installer_url=$MT5_INSTALLER_URL"
echo "official_installer_sha256=$sha256"

if [[ "$PRINT_ONLY" == "1" ]]; then
  exit 0
fi

if [[ ! "$EXPECTED_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "BLOCKED: supply --expected-sha256 with the reviewed hash before running the official installer." >&2
  exit 23
fi
if [[ "${EXPECTED_SHA256,,}" != "${sha256,,}" ]]; then
  echo "BLOCKED: downloaded script SHA-256 does not match the reviewed hash." >&2
  exit 24
fi

chmod 700 "$script"
echo "x86_64 verified. Running the owner-reviewed official installer without sudo."
exec "$script"
