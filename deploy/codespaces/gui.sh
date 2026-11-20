#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
PROCESS_IDENTITY="$ROOT/deploy/codespaces/process_identity.py"
INSTALLER_IDENTITY="$RUNTIME/installer-gui.identity.json"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
[[ "$(uname -m)" == x86_64 ]] || fail 'The owner-assisted MT5 route requires x86_64.'
[[ -n "${CODESPACE_NAME:-}" ]] || fail 'CODESPACE_NAME is unavailable; run this inside GitHub Codespaces.'
[[ -x "$(command -v x11vnc || true)" && -x "$(command -v websockify || true)" ]] || fail 'x11vnc and websockify are missing; rebuild the devcontainer image.'
[[ -x "$STATE/venv/bin/python" ]] || fail 'Codespaces post-create has not completed; run it before gui.sh.'
if ! "$ROOT/scripts/linux-host/wine-guard.sh"; then
  fail 'Wine version allowed check failed; refusing to launch the installer GUI.'
  exit 1
fi
export DISPLAY=:99
bash "$ROOT/deploy/codespaces/vnc-up.sh"
python3 "$ROOT/deploy/codespaces/vnc-selfcheck.py"
if command -v gh >/dev/null 2>&1; then
  if ! gh codespace ports visibility 6080:private -c "$CODESPACE_NAME" >/dev/null 2>&1; then
    printf 'WARN: could not set port 6080 to Private; verify Codespaces forwarding before opening the URL.\n' >&2
  fi
else
  printf 'WARN: GitHub CLI is unavailable; port 6080 visibility could not be verified.\n' >&2
fi

if [[ -s "$INSTALLER_IDENTITY" ]]; then
  python3 "$PROCESS_IDENTITY" verify --record "$INSTALLER_IDENTITY" >/dev/null 2>&1 || fail 'an installer identity record exists but no longer matches; refusing duplicate launch and leaving all processes untouched'
  printf 'PASS: installer process matches the saved PID/start-time/UID/executable/argv identity; reusing the existing owner wizard.\n'
else
  # shellcheck disable=SC1091
  source "$ROOT/deploy/codespaces/installer-pins.env"
  (
    exec "$ROOT/scripts/linux-host/install-mt5.sh" --gui \
      --expected-sha256 "$MT5_LINUX_SCRIPT_SHA256" \
      --expected-setup-sha256 "$MT5_SETUP_SHA256"
  ) >>"$RUNTIME/installer-gui.log" 2>&1 &
  INSTALLER_PID="$!"
  printf '%s\n' "$INSTALLER_PID" >"$RUNTIME/installer-gui.pid"
  chmod 600 "$RUNTIME/installer-gui.pid"
  python3 "$PROCESS_IDENTITY" capture --pid "$INSTALLER_PID" \
    --record "$INSTALLER_IDENTITY" --expected-uid "$EUID" || fail 'could not record installer PID/start-time/UID/executable/argv; refusing unverified installer management'
  sleep 2
  if ! python3 "$PROCESS_IDENTITY" verify --record "$INSTALLER_IDENTITY" >/dev/null 2>&1; then
    printf 'WARN: installer process identity changed after launch (for example, during exec); it was not signalled and must not be relaunched until manually inspected.\n' >&2
  fi
fi

printf 'PASS: private installer GUI started.\n'
printf 'INFO: display=%s; installer log=%s\n' "$DISPLAY" "$RUNTIME/installer-gui.log"
printf 'OWNER STEP: open https://%s-6080.app.github.dev/vnc.html?autoconnect=true&reconnect=true&reconnect_delay=2000&cacheBust=%s in the signed-in phone browser.\n' "$CODESPACE_NAME" "$(date +%s)"
printf 'OWNER STEP: complete the MetaTrader wizard and close it with its normal Finish/Exit action.\n'\nprintf 'INFO: installer completion is recorded only after a successful wizard exit and terminal/MetaEditor hash verification; EA compilation is finalized automatically.\n'
printf 'OWNER STEP: run deploy/codespaces/start.sh only after the installer runner reports the shared finalizer PASS.\n'
printf 'INFO: MT5 terminal readiness remains unverified until separately reported by startup/self-check.\n'
printf 'INFO: this is a private owner-assisted install route, not public exposure.\n'
