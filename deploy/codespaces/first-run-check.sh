#!/usr/bin/env bash
set -u -o pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
RUNTIME="$STATE/runtime"
RESULTS="$STATE/first-run-check.tsv"
mkdir -p "$STATE"
chmod 700 "$STATE"
: > "$RESULTS"
chmod 600 "$RESULTS" 2>/dev/null || true

record() {
  local name="$1" status="$2" detail="$3" fix="$4"
  printf '%s\t%s\t%s\t%s\n' "$name" "$status" "$detail" "$fix" >> "$RESULTS"
}
check_file() {
  local name="$1" file="$2" fix="$3"
  if [[ -s "$file" ]]; then record "$name" PASS "present" "$fix"; else record "$name" FAIL "missing" "$fix"; fi
}
check_cmd() {
  local name="$1" cmd="$2" fix="$3" detail
  if detail=$(bash -c "$cmd" 2>&1); then record "$name" PASS "${detail//$'\n'/ }" "$fix"; else record "$name" FAIL "${detail//$'\n'/ }" "$fix"; fi
}
pidfile_alive() {
  local pid
  [[ -s "$1" ]] || return 1
  pid="$(cat "$1" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

check_file installer_verified "$STATE/installer-verified" 'Run post-create again, or run gui.sh only when the installer requests owner assistance.'
check_cmd terminal_installed 'test -f "$HOME/.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe" || test -f "$HOME/.wine/drive_c/Program Files/MetaTrader 5/terminal64.exe"' 'Install MT5 through postCreate.sh; use gui.sh only for the private owner-assisted fallback.'
check_cmd wine_prefix 'test -d "$HOME/.mt5" && test -d "$HOME/.mt5/drive_c"' 'Run postCreate.sh and do not change the single ~/.mt5 path scheme.'
if pidfile_alive "$RUNTIME/xvfb.pid" && pidfile_alive "$RUNTIME/x11vnc.pid" && pidfile_alive "$RUNTIME/novnc.pid"; then
  record vnc_stack PASS 'Xvfb, x11vnc, and websockify processes are running' 'This confirms VNC only; it does not verify MT5 startup.'
else
  record vnc_stack NOT_VERIFIED 'private GUI stack is not running' 'Run deploy/codespaces/gui.sh only when the owner needs the installer GUI.'
fi
check_cmd xvfb 'command -v Xvfb >/dev/null && Xvfb -help >/dev/null 2>&1' 'Rebuild the Codespaces environment with Xvfb installed.'
check_cmd host_agent 'curl -fsS --max-time 5 http://127.0.0.1:8787/health/ready >/dev/null' 'Run bash deploy/codespaces/start.sh and inspect runtime/host-agent.log.'
check_cmd bridge_loopback 'curl -fsS --max-time 5 http://127.0.0.1:8000/health/live >/dev/null' 'Run bash deploy/codespaces/start.sh and inspect runtime/bridge.log.'
check_cmd runner_loop 'pgrep -f "linux-worker.py --loop" >/dev/null' 'Run bash deploy/codespaces/start.sh and inspect runtime/linux-worker.log.'

diagnostic_output="$(DESTINEDFX_CODESPACES_STATE_DIR="$STATE" DESTINEDFX_RUNTIME_DIR="$RUNTIME" \
  python3 "$ROOT/scripts/linux-host/mt5_diagnostics.py" 2>&1 || true)"
printf '%s\n' "$diagnostic_output"
if grep -Eq '^MT5_TERMINAL_PROCESS state=EXITED exit_code=-?[1-9][0-9]*$' <<< "$diagnostic_output"; then
  record mt5_terminal_process FAIL 'terminal process exited nonzero; see redacted diagnostics above' 'Check the reported exit code and redacted terminal startup lines; preserve the private prefix and installer provenance.'
else
  record mt5_terminal_process NOT_VERIFIED 'process status does not establish a usable terminal' 'Owner must confirm the terminal opens without the debugger dialog; do not infer readiness from VNC or a live process.'
fi
record mt5_terminal_ready NOT_VERIFIED 'no owner-returned evidence of successful terminal startup' 'Complete a real owner-run Codespaces check and return redacted evidence before marking terminal ready.'

if [[ -n "${CODESPACE_NAME:-}" ]] && command -v gh >/dev/null 2>&1; then
  visibility="$(gh codespace ports -c "$CODESPACE_NAME" --json port,visibility --jq '.[] | select(.port==8000) | .visibility' 2>/dev/null || true)"
  if [[ "$visibility" == public ]]; then record port_8000_public PASS 'visibility=public' 'Leave port protocol HTTP; changing it to HTTPS makes it private.'
  else record port_8000_public FAIL "visibility=${visibility:-unknown}" 'Run gh codespace ports visibility 8000:public -c "$CODESPACE_NAME", then rerun this check.'; fi
else
  record port_8000_public NOT_VERIFIED 'CODESPACE_NAME or gh unavailable' 'Run this command inside the owner Codespace.'
fi

PUBLIC_HOST="${CODESPACE_NAME:-unknown}-8000.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
if [[ "${CODESPACE_NAME:-}" != '' ]]; then
  live="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "https://$PUBLIC_HOST/health/live" 2>/dev/null || true)"
  invalid_test_token=invalid-test-token
  bad="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' -H 'X-Tunnel-Skip-AntiPhishing-Page: true' -H "Authorization: Bearer $invalid_test_token" "https://$PUBLIC_HOST/v1/app/state" 2>/dev/null || true)"
  none="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' -H 'X-Tunnel-Skip-AntiPhishing-Page: true' "https://$PUBLIC_HOST/v1/app/state" 2>/dev/null || true)"
  [[ "$live" == 200 ]] && record public_health PASS "status=200" 'If this was the first browser visit, open the URL once and tap Continue.' || record public_health FAIL "status=$live" 'Open the bridge URL once in a phone browser and tap Continue, then rerun this check.'
  [[ "$bad" == 401 && "$none" == 401 ]] && record auth_rejection PASS 'bad_token=401 no_token=401' 'No action.' || record auth_rejection FAIL "bad_token=$bad no_token=$none" 'Keep the port public and inspect bridge authentication and forwarding headers.'
  if [[ -n "${DESTINEDFX_APP_TOKEN:-}" ]]; then
    good="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H 'Accept: application/json' -H 'X-Tunnel-Skip-AntiPhishing-Page: true' -H "Authorization: Bearer $DESTINEDFX_APP_TOKEN" "https://$PUBLIC_HOST/v1/app/state" 2>/dev/null || true)"
    [[ "$good" == 200 ]] && record authenticated_state PASS 'status=200' 'Token value was not recorded.' || record authenticated_state FAIL "status=$good" 'Pair the app again and provide its current app token only through the process environment.'
  else
    record authenticated_state NOT_VERIFIED 'owner app token not provided' 'Pair the Android app and use its current token for the owner-run check; never paste it into reports.'
  fi
else
  record public_health NOT_VERIFIED 'CODESPACE_NAME unavailable' 'Run inside the owner Codespace after public forwarding is enabled.'
  record auth_rejection NOT_VERIFIED 'CODESPACE_NAME unavailable' 'Run inside the owner Codespace after public forwarding is enabled.'
  record authenticated_state NOT_VERIFIED 'owner app token not provided' 'Pair the Android app and rerun inside Codespaces.'
fi

if [[ -f "$ROOT/bridge/bridge.sqlite3" || -f "$STATE/bridge.sqlite3" ]]; then
  db="${BRIDGE_DB_PATH:-$STATE/bridge.sqlite3}"
  if [[ -f "$db" ]]; then
    pairing_state="$(python3 - "$db" <<'PY'
import sqlite3, sys, time
try:
    con=sqlite3.connect(sys.argv[1])
    row=con.execute('select count(*) from pairings where consumed=0 and expires>?', (time.time(),)).fetchone()
    print('present' if row and row[0] else 'absent')
except Exception:
    print('unknown')
PY
)"
    [[ "$pairing_state" == present ]] && record pairing_code PASS 'unexpired owner pairing record exists' 'Do not print or store the code in reports.' || record pairing_code NOT_VERIFIED "state=$pairing_state" 'Run owner_ctl.py issue-code and use the code within 10 minutes.'
  else record pairing_code NOT_VERIFIED 'database path unavailable' 'Run start.sh, issue a fresh owner code, and rerun this check.'; fi
else
  record pairing_code NOT_VERIFIED 'database not found' 'Run start.sh, issue a fresh owner code, and rerun this check.'
fi

free_disk="$(df -Pk "$STATE" 2>/dev/null | awk 'NR==2 {print $4}')"
[[ "${free_disk:-0}" =~ ^[0-9]+$ && "$free_disk" -gt 1048576 ]] && record free_disk PASS "available_kib=$free_disk" 'Stop the Codespace or remove owner-approved temporary files if low.' || record free_disk FAIL "available_kib=${free_disk:-unknown}" 'Stop the Codespace and free disk before installing or pairing.'
mem="$(awk '/MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)"
[[ "${mem:-0}" =~ ^[0-9]+$ && "$mem" -gt 524288 ]] && record free_ram PASS "available_kib=$mem" 'Stop other processes if memory is low.' || record free_ram FAIL "available_kib=${mem:-unknown}" 'Stop other processes or recreate the Codespace.'
record idle_timeout PASS "configured_minutes=${DESTINEDFX_IDLE_TIMEOUT_MINUTES:-240}" 'Codespaces maximum is 240 minutes; stop the Codespace when finished.'
record quota_hint NOT_VERIFIED 'GitHub billing usage is account-specific' 'Owner checks github.com/settings/billing; no quota claim is made here.'

python3 - "$RESULTS" <<'PY'
import json, sys
rows=[]
for line in open(sys.argv[1], encoding='utf-8'):
    name,status,detail,fix=line.rstrip('\n').split('\t',3)
    rows.append({'check':name,'status':status,'detail':detail,'fix':fix})
print(json.dumps({'checks':rows}, separators=(',',':')))
for row in rows:
    print(f"{row['status']}: {row['check']} ({row['detail']})")
failed=[r for r in rows if r['status']=='FAIL']
not_verified=[r for r in rows if r['status']=='NOT_VERIFIED']
print(f"SUMMARY: {'FAIL' if failed else 'PASS'} checks={len(rows)} failed={len(failed)} not_verified={len(not_verified)}")
PY
[[ ! -s <(grep -E $'\tFAIL\t' "$RESULTS") ]]
