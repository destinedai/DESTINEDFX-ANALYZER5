#!/usr/bin/env bash
set -euo pipefail

# Verify the embedded Authenticode signature without trusting a mutable SHA-256 pin.
# osslsigncode is used in Linux CI and on the Linux host. The Windows EA compiler
# performs the equivalent Get-AuthenticodeSignature check in compile-mt5-ea.ps1.
verify_mt5_authenticode() {
  local installer="$1"
  [[ -s "$installer" ]] || { echo "SIGNATURE_INVALID: installer is missing or empty" >&2; return 1; }
  command -v osslsigncode >/dev/null 2>&1 || {
    echo "SIGNATURE_CHECK_UNAVAILABLE: install osslsigncode before verifying mt5setup.exe" >&2
    return 1
  }

  local report
  if ! report="$(osslsigncode verify -in "$installer" 2>&1)"; then
    echo "SIGNATURE_INVALID: osslsigncode rejected the installer" >&2
    return 1
  fi
  printf '%s\n' "$report" | grep -Eiq 'signature verification[[:space:]]*:[[:space:]]*(ok|successful)|status[[:space:]]*:[[:space:]]*valid' || {
    echo "SIGNATURE_INVALID: Authenticode signature is not valid" >&2
    return 1
  }
  printf '%s\n' "$report" | grep -Eiq 'MetaQuotes[[:space:]]+Ltd\.?([[:space:]]|$)|MetaQuotes[[:space:]]+Limited' || {
    echo "SIGNATURE_INVALID: unexpected Authenticode signer (expected MetaQuotes Ltd)" >&2
    return 1
  }
  echo "PASS: setup.exe Authenticode signer is MetaQuotes Ltd"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  [[ $# -eq 1 ]] || { echo "Usage: verify-mt5-signature.sh PATH_TO_SETUP_EXE" >&2; exit 2; }
  verify_mt5_authenticode "$1"
fi
