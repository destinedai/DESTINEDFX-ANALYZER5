#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
PASSWORD_FILE="$RUNTIME/vnc.pass"
DISPLAY_HELPER="$ROOT/deploy/codespaces/display_processes.py"
mkdir -p "$STATE" "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
write_pid() {
  local name="$1" pid="$2"
  [[ "$pid" =~ ^[0-9]+$ ]] || fail "invalid $name PID"
  printf '%s\n' "$pid" >"$RUNTIME/$name.pid"
  chmod 600 "$RUNTIME/$name.pid"
}
track_started_pid() {
  local pid="$1" expected="$2"
  [[ -f "$RUNTIME/pids" ]] || return 0
  if ! grep -Fqx "$pid|$expected" "$RUNTIME/pids"; then
    printf '%s|%s\n' "$pid" "$expected" >>"$RUNTIME/pids"
    chmod 600 "$RUNTIME/pids"
  fi
}
stop_recorded() {
  local file="$1" expected="$2" pid cmdline owner
  [[ -s "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
  [[ -r "/proc/$pid/cmdline" && -r "/proc/$pid/status" ]] || return 1
  cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
  owner="$(awk '/^Uid:/ {print $3}' "/proc/$pid/status" 2>/dev/null || true)"
  [[ "$cmdline" == *"$expected"* && "$owner" == "$EUID" ]] || return 1
  kill -TERM "$pid" 2>/dev/null || return 1
  for _ in {1..50}; do
    kill -0 "$pid" 2>/dev/null || { rm -f "$file"; return 0; }
    cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
    owner="$(awk '/^Uid:/ {print $3}' "/proc/$pid/status" 2>/dev/null || true)"
    if [[ "$cmdline" != *"$expected"* || "$owner" != "$EUID" ]]; then
      rm -f "$file"
      return 0
    fi
    sleep 0.1
  done
  fail "recorded process $pid did not stop gracefully"
}
wait_alive() {
  local name="$1" file="$2" expected="$3" pid cmdline owner
  for _ in {1..30}; do
    if [[ -s "$file" ]]; then
      pid="$(cat "$file" 2>/dev/null || true)"
      if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
        owner="$(awk '/^Uid:/ {print $3}' "/proc/$pid/status" 2>/dev/null || true)"
        [[ "$cmdline" == *"$expected"* && "$owner" == "$EUID" ]] && return 0
      fi
    fi
    sleep 0.2
  done
  fail "$name did not remain running with the expected process identity"
}
wait_port() {
  local port="$1"
  for _ in {1..30}; do
    if python3 - "$port" <<'PY'
import socket, sys
try:
    with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.5):
        pass
except OSError:
    raise SystemExit(1)
PY
    then
      return 0
    fi
    sleep 0.2
  done
  fail "loopback port $port did not become ready"
}
require_port_free() {
  local port="$1"
  python3 - "$port" <<'PY' || exit 1
import socket, sys
port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    sock.bind(("127.0.0.1", port))
except OSError as exc:
    print(f"FAIL: port {port} is occupied by an untracked or unrelated listener: {exc}", file=sys.stderr)
    raise SystemExit(1)
finally:
    sock.close()
PY
}

[[ -x "$(command -v Xvfb || true)" ]] || fail 'Xvfb is missing'
[[ -x "$(command -v x11vnc || true)" ]] || fail 'x11vnc is missing'
[[ -x "$(command -v websockify || true)" ]] || fail 'websockify is missing'
[[ -f "$DISPLAY_HELPER" ]] || fail 'display process identity helper is missing'

if [[ ! -s "$PASSWORD_FILE" ]]; then
  umask 077
  if [[ -n "${DESTINEDFX_VNC_PASSWORD:-}" ]]; then
    printf '%s\n' "$DESTINEDFX_VNC_PASSWORD" >"$PASSWORD_FILE"
  else
    python3 -c 'import secrets; print(secrets.token_urlsafe(24))' >"$PASSWORD_FILE"
  fi
fi
chmod 600 "$PASSWORD_FILE"
[[ "$(stat -c '%a' "$PASSWORD_FILE")" == 600 ]] || fail 'VNC password file permissions are not private'

# Xvfb has exactly one owner: this script. A pre-existing display is adopted
# only after executable, effective UID, exact arguments, lock PID and socket
# ownership all agree. Adopted displays are never stopped by our cleanup.
mapfile -t XVFB_PIDS < <(pgrep -f '^Xvfb :99 ' || true)
if (( ${#XVFB_PIDS[@]} > 1 )); then
  fail 'multiple Xvfb :99 processes found; refusing ambiguous display ownership'
elif (( ${#XVFB_PIDS[@]} == 1 )); then
  XVFB_PID="${XVFB_PIDS[0]}"
  python3 "$DISPLAY_HELPER" check-xvfb "$XVFB_PID" || fail 'pre-existing Xvfb cannot be safely adopted'
  # Preserve ownership across repeated invocations. A process started by this
  # service must not become "adopted" merely because it is still running.
  prior_ownership="$(cat "$RUNTIME/xvfb.ownership" 2>/dev/null || true)"
  prior_pid="$(cat "$RUNTIME/xvfb.pid" 2>/dev/null || true)"
  if [[ "$prior_ownership" == started && "$prior_pid" == "$XVFB_PID" ]] &&
     grep -Fqx "$XVFB_PID|Xvfb :99" "$RUNTIME/pids" 2>/dev/null; then
    printf 'started\n' >"$RUNTIME/xvfb.ownership"
  else
    if [[ "$prior_ownership" == started && "$prior_pid" != "$XVFB_PID" ]]; then
      fail 'previously owned Xvfb PID changed; refusing to silently reclassify display ownership'
    fi
    printf 'adopted\n' >"$RUNTIME/xvfb.ownership"
  fi
  write_pid xvfb "$XVFB_PID"
  chmod 600 "$RUNTIME/xvfb.ownership"
else
  if [[ -e /tmp/.X11-unix/X99 || -e /tmp/.X99-lock ]]; then
    fail 'stale or foreign X display socket/lock exists without a matching Xvfb process; refusing to remove it'
  fi
  Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 &
  XVFB_PID="$!"
  write_pid xvfb "$XVFB_PID"
  printf 'started\n' >"$RUNTIME/xvfb.ownership"
  chmod 600 "$RUNTIME/xvfb.ownership"
  track_started_pid "$XVFB_PID" "Xvfb :99"
  for _ in {1..30}; do
    if kill -0 "$XVFB_PID" 2>/dev/null && python3 "$DISPLAY_HELPER" check-xvfb "$XVFB_PID" >/dev/null 2>&1; then
      break
    fi
    sleep 0.2
  done
  python3 "$DISPLAY_HELPER" check-xvfb "$XVFB_PID" || fail 'new Xvfb failed its process/lock/socket identity check'
fi
export DISPLAY=:99
wait_alive Xvfb "$RUNTIME/xvfb.pid" "Xvfb :99"

# Replace only the old unauthenticated DestinedFX VNC daemons. Unknown
# listeners are never killed: their ports are checked and startup fails closed.
stop_recorded "$RUNTIME/x11vnc.pid" "x11vnc"
stop_recorded "$RUNTIME/novnc.pid" "websockify"
python3 "$DISPLAY_HELPER" cleanup-legacy || fail 'legacy VNC process recovery was not safe'
require_port_free 5900
require_port_free 6080

x11vnc -display :99 -localhost -forever -shared -repeat -noxdamage -noxfixes -noxrecord \
  -wait 5 -defer 5 -passwdfile "$PASSWORD_FILE" -rfbport 5900 \
  >>"$RUNTIME/x11vnc.log" 2>&1 &
X11VNC_PID="$!"
write_pid x11vnc "$X11VNC_PID"
track_started_pid "$X11VNC_PID" "x11vnc"
websockify --listen 127.0.0.1 --web=/usr/share/novnc --heartbeat=30 --idle-timeout=0 \
  6080 127.0.0.1:5900 >>"$RUNTIME/novnc.log" 2>&1 &
NOVNC_PID="$!"
write_pid novnc "$NOVNC_PID"
track_started_pid "$NOVNC_PID" "websockify"

wait_alive Xvfb "$RUNTIME/xvfb.pid" "Xvfb :99"
wait_alive x11vnc "$RUNTIME/x11vnc.pid" "x11vnc"
wait_alive novnc "$RUNTIME/novnc.pid" "websockify"
wait_port 5900
wait_port 6080
printf 'PASS: VNC READY (verified Xvfb, authenticated loopback x11vnc, and loopback-only websockify); this does not verify MT5 terminal startup.\n'
