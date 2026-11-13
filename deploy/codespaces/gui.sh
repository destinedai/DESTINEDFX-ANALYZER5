#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
stop_recorded() {
  local file="$1" pid
  [[ -s "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || { rm -f "$file"; return 0; }
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
}
wait_alive() {
  local name="$1" file="$2"
  for _ in {1..30}; do
    [[ -s "$file" ]] && kill -0 "$(cat "$file")" 2>/dev/null && return 0
    sleep 0.2
  done
  fail "$name did not remain running; inspect $RUNTIME/${name,,}.log"
}
restart_recorded() {
  stop_recorded "$1"
}
[[ "$(uname -m)" == x86_64 ]] || fail 'The owner-assisted MT5 route requires x86_64.'
[[ -n "${CODESPACE_NAME:-}" ]] || fail 'CODESPACE_NAME is unavailable; run this inside GitHub Codespaces.'
[[ -x "$(command -v x11vnc || true)" && -x "$(command -v websockify || true)" ]] || fail 'x11vnc and websockify are missing; rebuild the devcontainer image.'
[[ -x "$STATE/venv/bin/python" ]] || fail 'Codespaces post-create has not completed; run it before gui.sh.'
if ! "$ROOT/scripts/linux-host/wine-guard.sh"; then
  fail 'Wine version allowed check failed; refusing to launch the installer GUI.'
  exit 1
fi
command -v gh >/dev/null 2>&1 || fail 'GitHub CLI is unavailable; cannot enforce private port 6080 visibility.'
gh codespace ports visibility 6080:private -c "$CODESPACE_NAME" >/dev/null 2>&1 ||
  fail 'Could not set port 6080 to Private; refusing to launch the installer GUI.'

if ! pgrep -f '^Xvfb :99 ' >/dev/null 2>&1; then
  stop_recorded "$RUNTIME/xvfb.pid"
  Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 &
  echo $! > "$RUNTIME/xvfb.pid"
fi
export DISPLAY=:99

# Always replace the forwarding pair. Reusing an old websocket can leave the phone
# attached to a frozen framebuffer after the Codespace or Wine display is recreated.
restart_recorded "$RUNTIME/x11vnc.pid"
restart_recorded "$RUNTIME/novnc.pid"
x11vnc -display :99 -localhost -forever -shared -repeat -noxdamage -noxfixes -noxrecord \
  -wait 5 -defer 5 -rfbport 5900 >>"$RUNTIME/x11vnc.log" 2>&1 &
echo $! > "$RUNTIME/x11vnc.pid"
websockify --web=/usr/share/novnc --heartbeat=30 --idle-timeout=0 \
  6080 127.0.0.1:5900 >>"$RUNTIME/novnc.log" 2>&1 &
echo $! > "$RUNTIME/novnc.pid"
wait_alive Xvfb "$RUNTIME/xvfb.pid"
wait_alive x11vnc "$RUNTIME/x11vnc.pid"
wait_alive novnc "$RUNTIME/novnc.pid"
printf 'PASS: VNC READY (Xvfb, x11vnc, and websockify are running); this does not verify MT5 terminal startup.\n'

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
sleep 2
if [[ -s "$RUNTIME/installer-gui.pid" ]] && ! kill -0 "$(cat "$RUNTIME/installer-gui.pid")" 2>/dev/null; then
  printf 'FAIL: installer exited before the wizard appeared. Last log lines:\n' >&2
  tail -n 20 "$RUNTIME/installer-gui.log" >&2 || true
  exit 1
fi

printf 'PASS: private installer GUI started.\n'
printf 'INFO: display=%s; installer log=%s\n' "$DISPLAY" "$RUNTIME/installer-gui.log"
printf 'OWNER STEP: open https://%s-6080.app.github.dev/vnc.html?autoconnect=true&reconnect=true&reconnect_delay=2000&cacheBust=%s in the signed-in phone browser.\n' "$CODESPACE_NAME" "$(date +%s)"
printf 'OWNER STEP: complete the MetaTrader wizard once, then run deploy/codespaces/start.sh.\n'
printf 'INFO: MT5 terminal readiness remains unverified until separately reported by startup/self-check.\n'
printf 'INFO: this is a private owner-assisted install route, not public exposure.\n'
