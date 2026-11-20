#!/usr/bin/env bash
set -euo pipefail

STATE="$HOME/.local/share/destinedfx-codespaces"
RUNTIME="$STATE/runtime"
RUN_DIR="$HOME/mt5-wine11-runtime-test"
TEST_PREFIX="$HOME/.mt5-wine11-test"
WEBVIEW="$HOME/mt5-wine11-installers/webview2-fixed/Microsoft.WebView2.FixedVersionRuntime.156.0.4314.58.x64"
WINE=/opt/wine-staging/bin/wine
WINEPATH=/opt/wine-staging/bin/winepath

mkdir -p "$RUNTIME"
chmod 700 "$STATE" "$RUNTIME"
exec 9>"$RUNTIME/resume.lock"
flock -n 9 || exit 0

LOG="$RUNTIME/resume.log"
echo "===== Desktop recovery $(date -Is) =====" | tee -a "$LOG"

[[ -n "${CODESPACE_NAME:-}" ]] || { echo "Not in Codespaces"; exit 1; }
gh codespace ports visibility 6080:private -c "$CODESPACE_NAME" >/dev/null

if ! DISPLAY=:99 xdpyinfo >/dev/null 2>&1; then
  if ! pgrep -f '^Xvfb :99 ' >/dev/null; then
    nohup Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >>"$RUNTIME/xvfb.log" 2>&1 < /dev/null 9>&- &
  fi
  for i in {1..20}; do
    DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && break
    sleep 1
  done
fi
DISPLAY=:99 xdpyinfo >/dev/null 2>&1 || { echo "X display failed"; exit 1; }

pgrep -x openbox >/dev/null || nohup env DISPLAY=:99 openbox --sm-disable >>"$RUNTIME/openbox.log" 2>&1 < /dev/null 9>&- &

if ! ss -lnt | grep -q ':5900 '; then
  nohup x11vnc -display :99 -localhost -forever -shared -nopw -rfbport 5900 -noxdamage >>"$RUNTIME/x11vnc.log" 2>&1 < /dev/null 9>&- &
fi

if ! ss -lnt | grep -q ':6080 '; then
  nohup websockify --web=/usr/share/novnc --heartbeat=30 --idle-timeout=0 6080 127.0.0.1:5900 >>"$RUNTIME/novnc.log" 2>&1 < /dev/null 9>&- &
fi

test -s "$RUN_DIR/terminal64.exe" || { echo "MT5 executable missing"; exit 1; }
test -f "$TEST_PREFIX/system.reg" || { echo "Test prefix missing"; exit 1; }
test -f "$WEBVIEW/msedgewebview2.exe" || { echo "WebView2 missing"; exit 1; }

if ! ps -eo stat,args | awk '$1 !~ /^Z/ && /terminal64\.exe/ {found=1} END {exit !found}'; then
  WEBVIEW_WIN="$("$WINEPATH" -w "$WEBVIEW")"
  (cd "$RUN_DIR" && nohup env DISPLAY=:99 WINEPREFIX="$TEST_PREFIX" WINEARCH=win64 WINEDEBUG=-all WEBVIEW2_BROWSER_EXECUTABLE_FOLDER="$WEBVIEW_WIN" PATH="/opt/wine-staging/bin:$PATH" "$WINE" ./terminal64.exe /portable >>"$RUNTIME/mt5-resume.log" 2>&1 < /dev/null 9>&- &)
  echo "MT5 launch requested" | tee -a "$LOG"
else
  echo "MT5 already running; no duplicate launch" | tee -a "$LOG"
fi

sleep 3
ss -lnt | grep -E ':(5900|6080)\b' | tee -a "$LOG" || true
ps -eo pid,stat,args | grep -E '[t]erminal64.exe|[w]ebsockify' | tee -a "$LOG" || true
echo "===== Recovery check completed =====" | tee -a "$LOG"
