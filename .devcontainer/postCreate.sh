#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/deploy/codespaces/installer-pins.env"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; }

if [[ "$(uname -m)" != x86_64 ]]; then
  fail 'Codespaces MT5 setup requires x86_64; no installer was executed.'
  exit 1
fi

if ! "$ROOT/scripts/linux-host/wine-guard.sh"; then
  fail 'Wine version allowed check failed; refusing MT5 installation.'
  exit 1
fi

mkdir -p "$HOME/.local/share/destinedfx-codespaces"
chmod 700 "$HOME/.local/share/destinedfx-codespaces"
python3 -m venv "$HOME/.local/share/destinedfx-codespaces/venv"
"$HOME/.local/share/destinedfx-codespaces/venv/bin/pip" install --disable-pip-version-check -r "$ROOT/bridge/requirements.txt"
pass 'Bridge Python dependencies installed.'

if "$ROOT/scripts/linux-host/install-mt5.sh" \
    --headless \
    --expected-sha256 "$MT5_LINUX_SCRIPT_SHA256" \
    --expected-setup-sha256 "$MT5_SETUP_SHA256"; then
  if "$ROOT/scripts/linux-host/finalize-mt5-install.sh" headless; then
    pass 'Official MetaQuotes setup and shared terminal/MetaEditor/EA-compile finalization passed.'
  else
    fail 'Installer finalization failed; no verified installation marker was created.'
    fail 'OWNER STEP: inspect the redacted finalizer output, then use deploy/codespaces/gui.sh only if owner assistance is needed.'
    exit 1
  fi
else
  fail 'Headless MetaQuotes setup failed; exact installer exit code/stage is in the redacted post-create log.'
  fail 'OWNER STEP: run deploy/codespaces/gui.sh for the private installer wizard fallback.'
  exit 1
fi

if [[ -x /usr/bin/Xvfb && -x /usr/bin/wine && -x /usr/bin/python3 ]]; then
  pass 'Xvfb, Wine, and Python runtime available.'
else
  fail 'One or more required host runtime binaries are unavailable.'
  exit 1
fi
pass 'Codespaces post-create completed; no prebuild is configured.'
