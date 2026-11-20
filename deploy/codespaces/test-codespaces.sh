#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
for f in "$ROOT/.devcontainer/postCreate.sh" "$ROOT/deploy/codespaces/post-create.sh" "$ROOT/deploy/codespaces/start.sh" "$ROOT/deploy/codespaces/stop.sh" "$ROOT/deploy/codespaces/vnc-up.sh" "$ROOT/deploy/codespaces/verify-public-ready.sh" "$ROOT/deploy/codespaces/gui.sh" "$ROOT/deploy/codespaces/first-run-check.sh" "$ROOT/scripts/linux-host/install-mt5.sh" "$ROOT/scripts/linux-host/launch-mt5.sh" "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" "$ROOT/scripts/linux-host/wine-guard.sh" "$ROOT/scripts/linux-host/wine-probe.sh" "$ROOT/scripts/linux-host/verify-mt5-signature.sh" "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh" "$ROOT/scripts/linux-host/finalize-mt5-install.sh"; do bash -n "$f" || fail "bash syntax: ${f#$ROOT/}"; done
[[ -x "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" ]] || fail 'Wine MT5 compatibility gate missing or not executable'
[[ -x "$ROOT/scripts/linux-host/wine-guard.sh" ]] || fail 'Wine version guard missing or not executable'
[[ -x "$ROOT/scripts/linux-host/wine-probe.sh" ]] || fail 'Wine startup probe missing or not executable'
[[ -x "$(command -v Xvfb || true)" ]] || fail 'Xvfb binary missing'
[[ -x "$(command -v x11vnc || true)" ]] || fail 'x11vnc binary missing'
[[ -x "$(command -v websockify || true)" ]] || fail 'websockify binary missing'
[[ -f "$ROOT/deploy/codespaces/vnc-up.sh" ]] || fail 'VNC launcher missing'
[[ -f "$ROOT/deploy/codespaces/vnc-selfcheck.py" ]] || fail 'VNC self-check missing'
[[ -f "$ROOT/deploy/codespaces/vnc_rfb.py" ]] || fail 'RFB VNC authentication verifier missing'
grep -Fq 'authenticate_localhost(5900, vnc_password' "$ROOT/deploy/codespaces/vnc-selfcheck.py" || fail 'VNC self-check must perform a real RFB authentication handshake'
grep -Fq 'vnc-selfcheck.py' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'VNC launcher must require the authentication handshake before readiness'
python3 -m pytest -q "$ROOT/deploy/codespaces/test_vnc_rfb.py" || fail 'RFB VNC authentication regressions failed'
[[ -f "$ROOT/scripts/linux-host/installer_completion.py" ]] || fail 'installer completion verifier missing'
grep -Fq 'record_successful_installer_completion gui' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'GUI installer must record completion only after a successful wizard exit'
grep -Fq 'record_successful_installer_completion headless' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'headless installer must record completion after successful /auto exit'
grep -Fq 'rm -f "$VERIFIED_MARKER" "$COMPLETION_FILE"' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'new installer runs must invalidate old acceptance markers'
grep -Fq 'COMPLETION_HELPER" verify' "$ROOT/scripts/linux-host/finalize-mt5-install.sh" || fail 'finalizer must verify the successful installer completion manifest'
grep -Fq 'installer runner reports the shared finalizer PASS' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI fallback must require automatic finalizer PASS before host start'
if grep -Fq 'exec "${clean_wine_env[@]}" wine "$setup"' "$ROOT/scripts/linux-host/install-mt5.sh"; then fail 'GUI installer must observe wizard exit and record completion'; fi
python3 -m pytest -q "$ROOT/scripts/linux-host/test_installer_completion.py" || fail 'installer completion evidence regressions failed'
pass 'all Codespaces shell scripts pass bash -n'
python3 "$ROOT/scripts/test_mt5_diagnostics.py" || fail 'MT5 diagnostics regression tests failed'
python3 - "$ROOT/.devcontainer/devcontainer.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
assert x['hostRequirements']['cpus']==2
assert 'prebuild' not in str(x).lower()
assert x['portsAttributes']['8000']['visibility']=='private'
assert x['build']['dockerfile']=='Dockerfile'
PY
pass 'devcontainer requests two cores, no prebuild, and private forwarding'
grep -Fq 'f9418ed388beba4aa9e28cc552da5e1627a6812734887c89f284d0b76c101698' "$ROOT/deploy/codespaces/installer-pins.env" || fail 'reviewed Linux-wrapper hash missing'
grep -Fq 'a879492dd9d7b168d0538edd1c0dc5604ca43dc0951825b3501818e8b18f4c93' "$ROOT/deploy/codespaces/installer-pins.env" || fail 'reviewed setup.exe hash missing'
grep -Fq -- '--expected-sha256 "$MT5_LINUX_SCRIPT_SHA256"' "$ROOT/.devcontainer/postCreate.sh" || fail 'Linux-wrapper pin not wired'
grep -Fq -- '--expected-setup-sha256 "$MT5_SETUP_SHA256"' "$ROOT/.devcontainer/postCreate.sh" || fail 'setup.exe pin not wired'
grep -Fq -- 'wine "$setup" /auto /path:' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'documented setup.exe /auto route missing'
grep -Fq -- 'OWNER STEP: run deploy/codespaces/gui.sh' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'GUI fallback fix missing'
grep -Fq -- 'verify_mt5_authenticode' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'Authenticode verification not wired into installer'
grep -Fq -- 'MetaQuotes' "$ROOT/scripts/linux-host/verify-mt5-signature.sh" || fail 'MetaQuotes signer check missing'
grep -Fq -- 'wineboot -u' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'GUI Wine prefix initialization missing'
grep -Fq -- 'debugger process detected' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'debugger preflight missing'
grep -Fq -- 'WINEDEBUG=-all' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'clean Wine debug environment missing'
grep -Fq -- 'check-wine-mt5-compat.sh' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'Wine compatibility gate not wired'
grep -Fq -- 'wine-guard.sh' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'Wine version guard not wired into the installer'
grep -Fq -- 'wine-guard.sh' "$ROOT/deploy/codespaces/gui.sh" || fail 'Wine version guard not wired before the GUI'
grep -Fq -- 'wine-guard.sh' "$ROOT/deploy/codespaces/start.sh" || fail 'Wine version guard not wired before host startup'
grep -Fq -- 'Wine version allowed' "$ROOT/deploy/codespaces/start.sh" || fail 'Wine version status line missing'
grep -Fq -- 'wine64 cmd /c ver' "$ROOT/scripts/linux-host/wine-probe.sh" || fail 'Wine probe smoke command missing'
grep -Fq -- 'winecfg -v win10' "$ROOT/scripts/linux-host/install-mt5.sh" || fail 'Windows 10 Wine prefix not configured'
grep -Fq '"wine-staging=${WINE_STAGING_VERSION}"' "$ROOT/.devcontainer/Dockerfile" || fail 'Pinned Wine Staging package missing'
grep -Eq '(^|[[:space:]])gh([[:space:]\\]|$)' "$ROOT/.devcontainer/Dockerfile" || fail 'GitHub CLI package missing from Codespaces image'
grep -Fq '99-destinedfx-wine-staging' "$ROOT/.devcontainer/Dockerfile" || fail 'Wine Staging apt preferences missing'
grep -Fq 'apt-mark hold wine-staging' "$ROOT/.devcontainer/Dockerfile" || fail 'Wine Staging package hold missing'
grep -Fq 'WINEDEBUG=-all' "$ROOT/.devcontainer/Dockerfile" || fail 'non-interactive Wine debug environment missing'
if grep -Fq 'WINE_PACKAGE_VERSION=' "$ROOT/.devcontainer/Dockerfile"; then fail 'Wine must not be pinned without a validated MT5 build combination'; fi
grep -Fq 'no Wine version is established as compatible' "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" || fail 'Wine compatibility status must remain unverified'
grep -Fq 'MT5_TERMINAL_EXIT_CODE' "$ROOT/scripts/linux-host/launch-mt5.sh" || fail 'terminal exit code is not reported'
grep -Fq 'WINEDEBUG=-all' "$ROOT/scripts/linux-host/launch-mt5.sh" || fail 'terminal launch must clear Wine debug channels'
grep -Fq 'MT5 terminal readiness remains unverified' "$ROOT/deploy/codespaces/gui.sh" || fail 'VNC readiness must not imply terminal readiness'
grep -Fq 'mt5_terminal_ready NOT_VERIFIED' "$ROOT/deploy/codespaces/first-run-check.sh" || fail 'terminal readiness must remain unverified absent owner evidence'
grep -Fq -- 'installer identity record exists but no longer matches' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI launcher must refuse duplicate launch when process identity is stale'
grep -Fq -- 'process_identity.py' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI launcher must use exact process identity records'
if grep -Eq 'pgrep|pkill|killall|kill -KILL|wineserver -k' "$ROOT/deploy/codespaces/gui.sh"; then fail 'GUI launcher must not use name-only process control or global Wine shutdown'; fi
if grep -Fq 'Xvfb :99 -screen 0 1280x800x24' "$ROOT/deploy/codespaces/start.sh"; then fail 'start.sh must delegate Xvfb ownership to vnc-up.sh'; fi
grep -Fq 'DISPLAY_HELPER="$ROOT/deploy/codespaces/display_processes.py"' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'display process identity helper path missing'
grep -Fq 'check-xvfb "$XVFB_PID"' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'existing Xvfb must be identity-verified before adoption'
grep -Fq 'prior_ownership' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'repeated VNC startup must preserve verified Xvfb ownership'
grep -Fq 'previously owned Xvfb lacks a matching complete process identity record' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'incomplete prior Xvfb ownership must fail closed'
grep -Fq 'cleanup-legacy' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'legacy unauthenticated VNC services must be safely reconciled'
grep -Fq -- '--listen 127.0.0.1' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'noVNC websocket must bind to loopback only'
grep -Fq 'xvfb.ownership' "$ROOT/deploy/codespaces/stop.sh" || fail 'shutdown must distinguish owned and adopted Xvfb'
grep -Fq 'MetaEditor64.exe' "$ROOT/deploy/codespaces/start.sh" || fail 'MetaEditor filename case fallback missing'
grep -Fq 'not_loopback_only' "$ROOT/deploy/codespaces/vnc-selfcheck.py" || fail 'VNC self-check must reject wildcard noVNC listeners'
python3 -m pytest -q "$ROOT/deploy/codespaces/test_display_processes.py" || fail 'display process identity tests failed'
python3 -m pytest -q "$ROOT/scripts/linux-host/test_check_slot_worker.py" || fail 'slot worker process identity tests failed'
python3 -m pytest -q "$ROOT/scripts/host/test_destinedfx_trigger_host.py" || fail 'host trigger lifecycle tests failed'
grep -Fq 'destinedfx_trigger_host.py start_all' "$ROOT/.devcontainer/devcontainer.json" || fail 'postStart must use the shared host trigger adapter'
grep -Fq 'ensure_started()' "$ROOT/scripts/host/destinedfx_trigger_host.py" || fail 'host adapter must reuse the existing host supervisor'
grep -Fq 'STOP_SCRIPT' "$ROOT/deploy/codespaces/host_controller.py" || fail 'host supervisor must retain the existing stop.sh path'
grep -Fq -- '--preflight' "$ROOT/deploy/codespaces/stop.sh" || fail 'safe host shutdown must require local stop.sh preflight'
grep -Fq -- '--check-stop' "$ROOT/deploy/codespaces/stop.sh" || fail 'stop preflight must verify isolated-slot cleanup prerequisites'
grep -Fq 'stop_script_ack' "$ROOT/bridge/app.py" || fail 'Bridge host shutdown must return local stop.sh acknowledgement'
grep -Fq 'isSafeHostStopPreflight(preflight)' "$ROOT/app/src/main/java/com/example/ui/SimplifiedBotWorkstation.kt" || fail 'Android must validate the complete stop preflight before GitHub stop'
grep -Fq 'stop_script_status' "$ROOT/app/src/main/java/com/example/data/codespaces/HostStopPreflight.kt" || fail 'Android host preflight must require the stop.sh acknowledgement'
grep -Fq 'PREFLIGHT_PASSED' "$ROOT/app/src/main/java/com/example/data/codespaces/HostStopPreflight.kt" || fail 'Android must require the exact stop.sh preflight result'
if grep -Eq '(Popen|subprocess[.](run|call|check_call|check_output)|exec)[^#]*gui[.]sh' "$ROOT/scripts/host/destinedfx_trigger_host.py"; then fail 'normal host start must never invoke the GUI installer'; fi
grep -Fq 'check_slot_worker.py' "$ROOT/deploy/codespaces/first-run-check.sh" || fail 'first-run check must use the tested slot worker process checker'
grep -Fq -- '-noxdamage' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'X11 damage buffering must be disabled for live VNC updates'
grep -Fq -- '--heartbeat=30' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'noVNC websocket heartbeat missing'
grep -Fq -- 'cacheBust=' "$ROOT/deploy/codespaces/gui.sh" || fail 'noVNC cache-busting URL missing'
bash "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh" || fail 'invalid installer signature fixtures were accepted'
grep -Fq 'finalize-mt5-install.sh" headless' "$ROOT/.devcontainer/postCreate.sh" || fail 'headless install must invoke the shared finalizer before accepting the marker'
grep -Fq 'finalize-mt5-install.sh gui' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI installer must require shared finalization after owner wizard'
grep -Fq 'authenticode_signer=MetaQuotes Ltd' "$ROOT/scripts/linux-host/finalize-mt5-install.sh" || fail 'finalizer must verify installer provenance signer'
grep -Fq 'MetaEditor' "$ROOT/scripts/linux-host/finalize-mt5-install.sh" || fail 'finalizer must verify MetaEditor and compile the EA'
grep -Fq 'DestinedFXTraderEA.ex5' "$ROOT/scripts/linux-host/finalize-mt5-install.sh" || fail 'finalizer must require the compiled EA output'
bash "$ROOT/deploy/codespaces/first-run-check.sh" >/tmp/destinedfx-first-run-check.out 2>&1 || true
grep -Fq -- 'SUMMARY:' /tmp/destinedfx-first-run-check.out || fail 'first-run-check summary missing'
grep -Fq -- 'gh codespace ports visibility 6080:private' "$ROOT/deploy/codespaces/gui.sh" || fail 'private GUI forwarding missing'
grep -Fq 'port 6080 visibility could not be verified' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI route must warn when port visibility cannot be checked'
grep -Fq -- 'INFO: this is a private owner-assisted install route, not public exposure.' "$ROOT/deploy/codespaces/gui.sh" || fail 'private GUI route notice must not report a false failure'
if grep -Fq -- 'FAIL: this is a private owner-assisted install route, not public exposure.' "$ROOT/deploy/codespaces/gui.sh"; then fail 'private GUI route notice must not report a false failure'; fi
pass 'official Linux wrapper and setup.exe pins are verified and the headless route is guarded'
grep -Fq -- '--host 127.0.0.1' "$ROOT/deploy/codespaces/start.sh" || fail 'bridge bind must be loopback-only'
grep -Fq 'verify-public-ready.sh' "$ROOT/deploy/codespaces/start.sh" || fail 'missing public K3 readiness guard'
grep -Fq 'verify-public-ready.sh' "$ROOT/deploy/codespaces/start.sh" || fail 'public exposure must be gated by the code-created marker'
if grep -Eq '^gh codespace ports visibility .*public' "$ROOT/deploy/codespaces/start.sh"; then fail 'public visibility command must only live inside the guarded function'; fi
grep -Fq 'gh codespace ports visibility 8000:public -c "$CODESPACE_NAME"' "$ROOT/deploy/codespaces/start.sh" || fail 'guarded public visibility command missing'
grep -Fq 'BAD_AUTH' "$ROOT/deploy/codespaces/start.sh" || fail 'bad-token self-check missing'
grep -Fq 'X-Tunnel-Skip-AntiPhishing-Page: true' "$ROOT/deploy/codespaces/start.sh" || fail 'tunnel header missing from self-check'
grep -Fq 'EXPECTED_FORWARDED_HOST' "$ROOT/deploy/codespaces/start.sh" || fail 'host check env not exported'
if grep -Eq 'public-code-ready' <(git -C "$ROOT" ls-files 2>/dev/null); then fail 'marker must not be committed'; fi
pass 'public exposure is guarded by a code-created marker, self-checked, and bridge binds loopback'
grep -Fq 'export BRIDGE_HOST_PROFILE=EPHEMERAL' "$ROOT/deploy/codespaces/start.sh" || fail 'Codespaces bridge must announce EPHEMERAL'
grep -Fq 'HOST_IDLE_TIMEOUT_SECONDS' "$ROOT/deploy/codespaces/start.sh" || fail 'configured idle timeout must reach bridge'
grep -Fq 'HOST_STARTED_AT_EPOCH_SECONDS' "$ROOT/deploy/codespaces/start.sh" || fail 'host start epoch must reach bridge for countdown estimate'
grep -Fq 'HOST_MAX_LIFETIME_SECONDS=43200' "$ROOT/deploy/codespaces/start.sh" || fail '12-hour hard lifetime must reach bridge'
grep -Fq 'HOST_WARNING_SECONDS=3600' "$ROOT/deploy/codespaces/start.sh" || fail 'one-hour hard lifetime warning must reach bridge'
grep -Fq 'HOST_EMERGENCY_STOP_LEAD_SECONDS=600' "$ROOT/deploy/codespaces/start.sh" || fail 'ten-minute pre-expiry safety-stop lead must reach bridge'
grep -Fq 'HOST_GENERATION' "$ROOT/deploy/codespaces/start.sh" || fail 'fresh host generation must reject stale queued commands'
pass 'Codespaces launcher wires EPHEMERAL profile and idle countdown metadata'
grep -Fq 'chmod 700 "$STATE" "$RUNTIME"' "$ROOT/deploy/codespaces/start.sh" || fail 'private state dir mode missing'
grep -Fq 'chmod 600 "$STATE/bridge.env"' "$ROOT/deploy/codespaces/start.sh" || fail 'private credentials file mode missing'
pass 'generated credentials are constrained to private state modes'
grep -Fq 'linux-slot-worker.py" --loop --interval 3' "$ROOT/deploy/codespaces/start.sh" || fail 'start.sh must run the isolated Linux slot connect-job worker'
grep -Fq 'linux-slot-worker.py' "$ROOT/deploy/codespaces/first-run-check.sh" || fail 'first-run check must inspect the isolated Linux slot worker process identity'
if grep -Fq 'pgrep -f "linux-worker.py --loop"' "$ROOT/deploy/codespaces/first-run-check.sh"; then fail 'first-run check must not mistake legacy linux-worker for Codespaces slot worker'; fi
if grep -Fq 'pgrep -f "linux-worker.py --loop"' "$ROOT/deploy/codespaces/first-run-check.sh"; then fail 'first-run check must not mistake legacy linux-worker for Codespaces slot worker'; fi
grep -Fq 'scripts/linux-host/linux-slot-worker.py' "$ROOT/deploy/codespaces/first-run-check.sh" || fail 'first-run check must pass the isolated slot worker script to its process-identity checker'
grep -Fq 'args[2:5] == ["--loop", "--interval", "3"]' "$ROOT/scripts/linux-host/check_slot_worker.py" || fail 'slot-worker identity checker must verify the exact loop arguments'
if grep -Fq 'pgrep -f "linux-worker.py --loop"' "$ROOT/deploy/codespaces/first-run-check.sh"; then fail 'first-run check must not mistake legacy linux-worker for Codespaces slot worker'; fi
grep -Fq 'DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE=true' "$ROOT/deploy/codespaces/start.sh" || fail 'loopback control plane opt-in missing'
grep -Fq 'unset WINEPREFIX MT5_JOURNAL_DIR DESTINEDFX_EA_SET_PATH' "$ROOT/deploy/codespaces/start.sh" || fail 'shared Wine prefix, journal and preset overrides must remain unset'
grep -Fq 'export DESTINEDFX_ISOLATED_SLOTS=true' "$ROOT/deploy/codespaces/start.sh" || fail 'per-user isolated MT5 slots must be enabled'
grep -Fq '"/portable"' "$ROOT/scripts/linux-host/linux-slot-worker.py" || fail 'each MT5 session must use its private portable terminal data tree'
grep -Fq 'terminal-{pairing_key(reservation.pairing_id)}.log' "$ROOT/scripts/linux-host/linux-slot-worker.py" || fail 'each pairing must have a separate private runtime log'
grep -Fq '.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe' "$ROOT/deploy/codespaces/start.sh" || fail 'MetaQuotes default terminal template path missing'
pass 'Per-user runtime: isolated Wine prefix, portable terminal data and per-pairing logs wired'
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/wine-bin"
cat > "$TMP/wine-bin/wine" <<'MOCK_WINE'
#!/usr/bin/env bash
printf '%s\n' "${MOCK_WINE_VERSION:?}"
MOCK_WINE
chmod +x "$TMP/wine-bin/wine"
for version in wine-9.0 wine-9.22 wine-10.0 wine-10.2 "wine-11.21 (Staging)"; do
	output="$(WINE_BIN="$TMP/wine-bin/wine" MOCK_WINE_VERSION="$version" bash "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh")" || fail "$version must be allowed"
	grep -Fq "Wine version allowed: PASS" <<< "$output" || fail "$version must pass the guard"
done
for version in wine-10.3 wine-11.0 wine-11.20 wine-11.22 wine-8.21 garbage; do
	if output="$(WINE_BIN="$TMP/wine-bin/wine" MOCK_WINE_VERSION="$version" bash "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" 2>&1)"; then
		fail "$version must be blocked"
	fi
	grep -Fq 'Wine version allowed: FAIL' <<< "$output" || fail "$version must fail the guard"
	grep -Fq 'sudo bash scripts/linux-host/wine-guard.sh --fix' <<< "$output" || fail "$version must print the fix command"
done
if grep -Eq '(^|[;&|[:space:]])(kill|pkill|killall)([[:space:]]|$)' "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh"; then
	fail 'Wine compatibility gate must not kill processes'
fi
mkdir -p "$TMP/gui-precondition-bin" "$TMP/gui-precondition-home"
for tool in bash dirname mkdir chmod uname awk; do
	ln -s "$(command -v "$tool")" "$TMP/gui-precondition-bin/$tool"
done
if output="$(env -u CODESPACE_NAME HOME="$TMP/gui-precondition-home" \
	DESTINEDFX_CODESPACES_STATE_DIR="$TMP/no-codespace-state" \
	bash "$ROOT/deploy/codespaces/gui.sh" 2>&1)"; then
	fail 'GUI launcher must reject runs outside Codespaces'
fi
grep -Fq 'CODESPACE_NAME is unavailable' <<< "$output" || fail 'GUI launcher must report missing Codespaces context'
vnc_line="$(grep -n 'vnc-selfcheck.py' "$ROOT/deploy/codespaces/gui.sh" | head -n 1 | cut -d: -f1)"
visibility_line="$(grep -n 'codespace ports visibility 6080:private' "$ROOT/deploy/codespaces/gui.sh" | head -n 1 | cut -d: -f1)"
[[ "$vnc_line" =~ ^[0-9]+$ && "$visibility_line" =~ ^[0-9]+$ && "$vnc_line" -lt "$visibility_line" ]] ||
	fail 'GUI launcher must prove VNC before checking port visibility'
mkdir -p "$TMP/gui-precondition-bin/wine-tools" "$TMP/gui-precondition-bin/allowed-wine" \
	"$TMP/gui-precondition-state/venv/bin"
cat > "$TMP/gui-precondition-bin/allowed-wine/wine" <<'MOCK_ALLOWED_WINE'
#!/usr/bin/env bash
printf '%s\n' 'wine-9.0'
MOCK_ALLOWED_WINE
chmod +x "$TMP/gui-precondition-bin/allowed-wine/wine"

cat > "$TMP/gui-precondition-bin/wine-tools/wine" <<'MOCK_BLOCKED_WINE'
#!/usr/bin/env bash
printf '%s\n' 'wine-10.3'
MOCK_BLOCKED_WINE
cat > "$TMP/gui-precondition-bin/wine-tools/x11vnc" <<'MOCK_VNC'
#!/usr/bin/env bash
exit 0
MOCK_VNC
cat > "$TMP/gui-precondition-bin/wine-tools/websockify" <<'MOCK_NOVNC'
#!/usr/bin/env bash
exit 0
MOCK_NOVNC
cat > "$TMP/gui-precondition-bin/wine-tools/Xvfb" <<'MOCK_XVFB'
#!/usr/bin/env bash
printf 'started\n' >> "$XVFB_MARKER"
MOCK_XVFB
cat > "$TMP/gui-precondition-state/venv/bin/python" <<'MOCK_PYTHON'
#!/usr/bin/env bash
exit 0
MOCK_PYTHON
chmod +x "$TMP/gui-precondition-bin/wine-tools/"* "$TMP/gui-precondition-state/venv/bin/python"
if output="$(PATH="$TMP/gui-precondition-bin/wine-tools:$PATH" \
	WINE_BIN="$TMP/gui-precondition-bin/wine-tools/wine" \
	CODESPACE_NAME=fixture-codespace HOME="$TMP/gui-precondition-home" \
	XVFB_MARKER="$TMP/xvfb.started" DESTINEDFX_CODESPACES_STATE_DIR="$TMP/gui-precondition-state" \
	bash "$ROOT/deploy/codespaces/gui.sh" 2>&1)"; then
	fail 'GUI launcher must reject an unsupported Wine version'
fi
grep -Fq 'Wine version allowed check failed' <<< "$output" || fail 'GUI launcher must report unsupported Wine'
[[ ! -e "$TMP/xvfb.started" ]] || fail 'GUI launcher must reject unsupported Wine before starting Xvfb'
pass 'Wine allowlist and Codespaces GUI preconditions fail closed before startup'
DESTINEDFX_CODESPACES_STATE_DIR="$TMP/state" "$ROOT/deploy/codespaces/stop.sh" | grep -q '^PASS:' || fail 'stop without state failed'
pass 'stop safely handles empty private state'
grep -Fq -- 'x11vnc.pid' "$ROOT/deploy/codespaces/stop.sh" || fail 'shutdown must clean stale VNC process'
grep -Fq 'process_identity.py' "$ROOT/deploy/codespaces/start.sh" || fail 'startup must record complete service process identities'
grep -Fq 'process_identity.py' "$ROOT/deploy/codespaces/stop.sh" || fail 'shutdown must validate full process identity records'
grep -Fq 'process_identity.py' "$ROOT/deploy/codespaces/vnc-up.sh" || fail 'VNC lifecycle must use complete process identity records'
grep -Fq 'start_ticks' "$ROOT/deploy/codespaces/process_identity.py" || fail 'process identity must include kernel start ticks'
grep -Fq 'SIGTERM' "$ROOT/deploy/codespaces/process_identity.py" || fail 'process identity shutdown must use SIGTERM'
if grep -Eq 'signal[.]SIGKILL|kill -KILL|killall|pkill' "$ROOT/deploy/codespaces/process_identity.py" "$ROOT/deploy/codespaces/stop.sh" "$ROOT/deploy/codespaces/vnc-up.sh" "$ROOT/deploy/codespaces/gui.sh"; then fail 'managed process cleanup must never use forced or name-only termination'; fi
if grep -Fq 'killpg' "$ROOT/deploy/codespaces/host_controller.py"; then fail 'host controller must not signal unverified process groups'; fi
python3 -m pytest -q "$ROOT/deploy/codespaces/test_process_identity.py" || fail 'full process identity regression tests failed'
mkdir -p "$TMP/legacy-state/runtime"
sleep 30 &
LEGACY_PID="$!"
printf '%s|sleep\n' "$LEGACY_PID" > "$TMP/legacy-state/runtime/pids"
if DESTINEDFX_CODESPACES_STATE_DIR="$TMP/legacy-state" "$ROOT/deploy/codespaces/stop.sh" >"$TMP/legacy-stop.out" 2>&1; then
  kill "$LEGACY_PID" 2>/dev/null || true
  fail 'legacy PID-only shutdown record must fail closed'
fi
kill -0 "$LEGACY_PID" 2>/dev/null || fail 'legacy PID-only record caused an unrelated process to be terminated'
grep -Fq 'legacy PID-only record found; no signal was sent' "$TMP/legacy-stop.out" || fail 'legacy process record refusal must be explicit'
kill "$LEGACY_PID" 2>/dev/null || true
wait "$LEGACY_PID" 2>/dev/null || true
pass 'shutdown rejects legacy PID-only records without signalling unrelated processes'
printf 'PASS: Codespaces K1 shell tests completed.\n'
