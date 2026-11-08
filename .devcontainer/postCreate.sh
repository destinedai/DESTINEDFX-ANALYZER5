#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_MT5_SHA256=f9418ed388beba4aa9e28cc552da5e1627a6812734887c89f284d0b76c101698

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; }

if [[ "$(uname -m)" != x86_64 ]]; then
  fail 'Codespaces MT5 setup requires x86_64; no installer was executed.'
  exit 1
fi

mkdir -p "$HOME/.local/share/destinedfx-codespaces"
chmod 700 "$HOME/.local/share/destinedfx-codespaces"
python3 -m venv "$HOME/.local/share/destinedfx-codespaces/venv"
"$HOME/.local/share/destinedfx-codespaces/venv/bin/pip" install --disable-pip-version-check -r "$ROOT/bridge/requirements.txt"
pass 'Bridge Python dependencies installed.'

# The existing installer downloads to a temporary file and compares this reviewed digest
# before chmod/exec. Its official installer output may not contain secrets.
if "$ROOT/scripts/linux-host/install-mt5.sh" --expected-sha256 "$EXPECTED_MT5_SHA256"; then
  pass 'Official MetaQuotes Linux installer completed after SHA-256 verification.'
else
  fail 'Official MetaQuotes installer failed or its checksum did not match; inspect the non-secret installer diagnostic.'
  exit 1
fi

if [[ -x /usr/bin/Xvfb && -x /usr/bin/wine && -x /usr/bin/python3 ]]; then
  pass 'Xvfb, Wine, and Python runtime available.'
else
  fail 'One or more required host runtime binaries are unavailable.'
  exit 1
fi
pass 'Codespaces post-create completed; no prebuild is configured.'
