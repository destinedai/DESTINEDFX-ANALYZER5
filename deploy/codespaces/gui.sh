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
if command -v gh >/dev/null 2>&1; then
  if ! gh codespace ports visibility 6080:private -c "$CODESPACE_NAME" >/dev/null 2>&1; then
    printf 'WARN: could not set port 6080 to Private; verify Codespaces forwarding before opening the URL.\n' >&2
  fi
else
  printf 'WARN: GitHub CLI is unavailable; port 6080 visibility could not be verified.\n' >&2
fi

export DISPLAY=:99
bash "$ROOT/deploy/codespaces/vnc-up.sh"
python3 "$ROOT/deploy/codespaces/vnc-selfcheck.py"

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
