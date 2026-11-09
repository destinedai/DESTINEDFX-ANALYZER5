#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
[[ "$(uname -m)" == x86_64 ]] || fail 'The owner-assisted MT5 route requires x86_64.'
[[ -n "${CODESPACE_NAME:-}" ]] || fail 'CODESPACE_NAME is unavailable; run this inside GitHub Codespaces.'
[[ -x "$(command -v x11vnc || true)" && -x "$(command -v websockify || true)" ]] || fail 'x11vnc and websockify are missing; rebuild the devcontainer image.'
[[ -x "$STATE/venv/bin/python" ]] || fail 'Codespaces post-create has not completed; run it before gui.sh.'

if ! pgrep -f 'Xvfb :99' >/dev/null 2>&1; then
  Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 &
  echo $! > "$RUNTIME/xvfb.pid"
fi
export DISPLAY=:99

if ! pgrep -f 'x11vnc.*-rfbport 5900' >/dev/null 2>&1; then
  x11vnc -display :99 -localhost -forever -nopw -rfbport 5900 >>"$RUNTIME/x11vnc.log" 2>&1 &
  echo $! > "$RUNTIME/x11vnc.pid"
fi
if ! pgrep -f 'websockify.*6080.*5900' >/dev/null 2>&1; then
  websockify --web=/usr/share/novnc 6080 127.0.0.1:5900 >>"$RUNTIME/novnc.log" 2>&1 &
  echo $! > "$RUNTIME/novnc.pid"
fi

# Codespaces private forwarding requires the owner's authenticated browser session.
if command -v gh >/dev/null 2>&1; then
  gh codespace ports visibility 6080:private -c "$CODESPACE_NAME" >/dev/null 2>&1 ||
    printf 'WARN: Could not set port visibility; verify Codespaces shows port 6080 as Private.\n' >&2
fi

if ! pgrep -f 'install-mt5.sh --gui' >/dev/null 2>&1; then
  # shellcheck disable=SC1091
  source "$ROOT/deploy/codespaces/installer-pins.env"
  (
    exec "$ROOT/scripts/linux-host/install-mt5.sh" --gui \
      --expected-sha256 "$MT5_LINUX_SCRIPT_SHA256" \
      --expected-setup-sha256 "$MT5_SETUP_SHA256"
  ) >>"$RUNTIME/installer-gui.log" 2>&1 &
  echo $! > "$RUNTIME/installer-gui.pid"
fi

printf 'PASS: private installer GUI started.\n'
printf 'OWNER STEP: open https://%s-6080.app.github.dev/vnc.html?autoconnect=true in the signed-in phone browser.\n' "$CODESPACE_NAME"
printf 'OWNER STEP: complete the MetaTrader wizard once, then run deploy/codespaces/start.sh.\n'
printf 'FAIL: this is a private owner-assisted install route, not public exposure.\n'
