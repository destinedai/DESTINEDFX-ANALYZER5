#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
PASSWORD_FILE="$RUNTIME/vnc.pass"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
stop_recorded() {
  local file="$1" expected="$2" pid cmdline
  [[ -s "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
  cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
  [[ "$cmdline" == *"$expected"* ]] || return 1
  kill "$pid" 2>/dev/null || return 1
  for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }; sleep 0.1; done
  kill -KILL "$pid" 2>/dev/null || true
  for _ in {1..10}; do kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }; sleep 0.1; done
  return 1
}
wait_alive() {
  local name="$1" file="$2"
  for _ in {1..30}; do
    [[ -s "$file" ]] && kill -0 "$(cat "$file")" 2>/dev/null && return 0
    sleep 0.2
  done
  fail "$name did not remain running"
}

[[ -x "$(command -v Xvfb || true)" ]] || fail 'Xvfb is missing'
[[ -x "$(command -v x11vnc || true)" ]] || fail 'x11vnc is missing'
[[ -x "$(command -v websockify || true)" ]] || fail 'websockify is missing'
if [[ ! -s "$PASSWORD_FILE" ]]; then
  umask 077
  if [[ -n "${DESTINEDFX_VNC_PASSWORD:-}" ]]; then
    printf '%s\n' "$DESTINEDFX_VNC_PASSWORD" >"$PASSWORD_FILE"
  else
    python3 -c 'import secrets; print(secrets.token_urlsafe(24))' >"$PASSWORD_FILE"
  fi
fi
chmod 600 "$PASSWORD_FILE"

if ! pgrep -f '^Xvfb :99 ' >/dev/null 2>&1; then
  stop_recorded "$RUNTIME/xvfb.pid" "Xvfb :99" || fail 'refusing to stop an unrelated Xvfb process'
  Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 &
  echo $! >"$RUNTIME/xvfb.pid"
fi
export DISPLAY=:99
for _ in {1..30}; do
  [[ -S /tmp/.X11-unix/X99 ]] && break
  sleep 0.2
done
[[ -S /tmp/.X11-unix/X99 ]] || fail 'X display socket :99 did not appear'

stop_recorded "$RUNTIME/x11vnc.pid" "x11vnc" || fail 'refusing to stop an unrelated x11vnc process'
stop_recorded "$RUNTIME/novnc.pid" "websockify" || fail 'refusing to stop an unrelated websockify process'
x11vnc -display :99 -localhost -forever -shared -repeat -noxdamage -noxfixes -noxrecord \
  -wait 5 -defer 5 -passwdfile "$PASSWORD_FILE" -rfbport 5900 \
  >>"$RUNTIME/x11vnc.log" 2>&1 &
echo $! >"$RUNTIME/x11vnc.pid"
websockify --web=/usr/share/novnc --heartbeat=30 --idle-timeout=0 \
  6080 127.0.0.1:5900 >>"$RUNTIME/novnc.log" 2>&1 &
echo $! >"$RUNTIME/novnc.pid"
wait_alive Xvfb "$RUNTIME/xvfb.pid"
wait_alive x11vnc "$RUNTIME/x11vnc.pid"
wait_alive novnc "$RUNTIME/novnc.pid"
printf 'PASS: VNC READY (Xvfb, authenticated x11vnc, and websockify are running); this does not verify MT5 terminal startup.\n'
