#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
for f in "$ROOT/.devcontainer/postCreate.sh" "$ROOT/deploy/codespaces/post-create.sh" "$ROOT/deploy/codespaces/start.sh" "$ROOT/deploy/codespaces/stop.sh" "$ROOT/deploy/codespaces/verify-public-ready.sh" "$ROOT/deploy/codespaces/gui.sh" "$ROOT/deploy/codespaces/first-run-check.sh" "$ROOT/scripts/linux-host/install-mt5.sh" "$ROOT/scripts/linux-host/launch-mt5.sh" "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" "$ROOT/scripts/linux-host/wine-guard.sh" "$ROOT/scripts/linux-host/wine-probe.sh" "$ROOT/scripts/linux-host/verify-mt5-signature.sh" "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh"; do bash -n "$f" || fail "bash syntax: ${f#$ROOT/}"; done
[[ -x "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" ]] || fail 'Wine MT5 compatibility gate missing or not executable'
[[ -x "$ROOT/scripts/linux-host/wine-guard.sh" ]] || fail 'Wine version guard missing or not executable'
[[ -x "$ROOT/scripts/linux-host/wine-probe.sh" ]] || fail 'Wine startup probe missing or not executable'
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
grep -Fq 'wine wine64 winetricks' "$ROOT/.devcontainer/Dockerfile" || fail 'Ubuntu Wine packages missing'
grep -Eq '(^|[[:space:]])gh([[:space:]\\]|$)' "$ROOT/.devcontainer/Dockerfile" || fail 'GitHub CLI package missing from Codespaces image'
grep -Fq '99-destinedfx-ubuntu-wine' "$ROOT/.devcontainer/Dockerfile" || fail 'Ubuntu Wine apt preferences missing'
grep -Fq 'apt-mark hold wine wine64 libwine' "$ROOT/.devcontainer/Dockerfile" || fail 'Ubuntu Wine package hold missing'
grep -Fq 'WINEDEBUG=-all' "$ROOT/.devcontainer/Dockerfile" || fail 'non-interactive Wine debug environment missing'
if grep -Fq 'WINE_PACKAGE_VERSION=' "$ROOT/.devcontainer/Dockerfile"; then fail 'Wine must not be pinned without a validated MT5 build combination'; fi
grep -Fq 'no Wine version is established as compatible' "$ROOT/scripts/linux-host/check-wine-mt5-compat.sh" || fail 'Wine compatibility status must remain unverified'
grep -Fq 'MT5_TERMINAL_EXIT_CODE' "$ROOT/scripts/linux-host/launch-mt5.sh" || fail 'terminal exit code is not reported'
grep -Fq 'WINEDEBUG=-all' "$ROOT/scripts/linux-host/launch-mt5.sh" || fail 'terminal launch must clear Wine debug channels'
grep -Fq 'MT5 terminal readiness remains unverified' "$ROOT/deploy/codespaces/gui.sh" || fail 'VNC readiness must not imply terminal readiness'
grep -Fq 'mt5_terminal_ready NOT_VERIFIED' "$ROOT/deploy/codespaces/first-run-check.sh" || fail 'terminal readiness must remain unverified absent owner evidence'
grep -Fq -- 'installer exited before the wizard appeared' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI installer liveness diagnostic missing'
grep -Fq -- 'wait_alive' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI display readiness checks missing'
grep -Fq -- '-noxdamage' "$ROOT/deploy/codespaces/gui.sh" || fail 'X11 damage buffering must be disabled for live VNC updates'
grep -Fq -- '--heartbeat=30' "$ROOT/deploy/codespaces/gui.sh" || fail 'noVNC websocket heartbeat missing'
grep -Fq -- 'cacheBust=' "$ROOT/deploy/codespaces/gui.sh" || fail 'noVNC cache-busting URL missing'
bash "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh" || fail 'invalid installer signature fixtures were accepted'
grep -Fq -- 'installer-verified' "$ROOT/.devcontainer/postCreate.sh" || fail 'installer verification marker missing'
bash "$ROOT/deploy/codespaces/first-run-check.sh" >/tmp/destinedfx-first-run-check.out 2>&1 || true
grep -Fq -- 'SUMMARY:' /tmp/destinedfx-first-run-check.out || fail 'first-run-check summary missing'
grep -Fq -- 'gh codespace ports visibility 6080:private' "$ROOT/deploy/codespaces/gui.sh" || fail 'private GUI forwarding missing'
grep -Fq 'cannot enforce private port 6080 visibility' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI route must fail closed without GitHub CLI'
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
grep -Fq 'HOST_GENERATION' "$ROOT/deploy/codespaces/start.sh" || fail 'fresh host generation must reject stale queued commands'
pass 'Codespaces launcher wires EPHEMERAL profile and idle countdown metadata'
grep -Fq 'chmod 700 "$STATE" "$RUNTIME"' "$ROOT/deploy/codespaces/start.sh" || fail 'private state dir mode missing'
grep -Fq 'chmod 600 "$STATE/bridge.env"' "$ROOT/deploy/codespaces/start.sh" || fail 'private credentials file mode missing'
pass 'generated credentials are constrained to private state modes'
grep -Fq 'linux-worker.py" --loop' "$ROOT/deploy/codespaces/start.sh" || fail 'start.sh must run the Linux connect-job runner'
grep -Fq 'DESTINEDFX_ALLOW_LOOPBACK_CONTROL_PLANE=true' "$ROOT/deploy/codespaces/start.sh" || fail 'loopback control plane opt-in missing'
grep -Fq 'MT5_JOURNAL_DIR="$TERMINAL_ROOT/Logs"' "$ROOT/deploy/codespaces/start.sh" || fail 'journal must be the platform Logs folder, not MQL5/Logs'
grep -Fq '.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe' "$ROOT/deploy/codespaces/start.sh" || fail 'MetaQuotes default terminal path missing'
if grep -Fq 'MQL5/Logs' "$ROOT/deploy/codespaces/start.sh"; then fail 'start.sh must not point the journal at MQL5/Logs'; fi
pass 'T1 path scheme: one terminal location, platform Logs journal, runner and process control wired'
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
mkdir -p "$TMP/gui-precondition-bin/wine-tools" "$TMP/gui-precondition-bin/allowed-wine" \
	"$TMP/gui-precondition-state/venv/bin" "$TMP/no-gh-state/venv/bin"
cat > "$TMP/no-gh-state/venv/bin/python" <<'MOCK_PYTHON'
#!/usr/bin/env bash
exit 0
MOCK_PYTHON
chmod +x "$TMP/no-gh-state/venv/bin/python"
cat > "$TMP/gui-precondition-bin/allowed-wine/wine" <<'MOCK_ALLOWED_WINE'
#!/usr/bin/env bash
printf '%s\n' 'wine-9.0'
MOCK_ALLOWED_WINE
chmod +x "$TMP/gui-precondition-bin/allowed-wine/wine"

# Satisfy the GUI's earlier VNC-tool precondition so this case specifically
# exercises the missing-GitHub-CLI guard without relying on host-installed tools.
for tool in x11vnc websockify; do
	printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/gui-precondition-bin/wine-tools/$tool"
	chmod +x "$TMP/gui-precondition-bin/wine-tools/$tool"
done
if output="$(PATH="$TMP/gui-precondition-bin/wine-tools:$TMP/gui-precondition-bin/allowed-wine:$TMP/gui-precondition-bin" \
	WINE_BIN="$TMP/gui-precondition-bin/allowed-wine/wine" \
	CODESPACE_NAME=fixture-codespace HOME="$TMP/gui-precondition-home" \
	DESTINEDFX_CODESPACES_STATE_DIR="$TMP/no-gh-state" \
	bash "$ROOT/deploy/codespaces/gui.sh" 2>&1)"; then
	fail 'GUI launcher must reject runs that cannot verify private port visibility'
fi
grep -Fq 'GitHub CLI is unavailable' <<< "$output" || fail 'GUI launcher must require GitHub CLI for private forwarding'
[[ ! -e "$TMP/no-gh-state/runtime/xvfb.pid" ]] || fail 'GUI launcher must not start Xvfb before confirming private forwarding'
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
grep -Fq -- 'installer-gui.pid' "$ROOT/deploy/codespaces/stop.sh" || fail 'shutdown must clean stale GUI installer process'
grep -Fq -- 'x11vnc.pid' "$ROOT/deploy/codespaces/stop.sh" || fail 'shutdown must clean stale VNC process'
mkdir -p "$TMP/wineserver-bin" "$TMP/gui-state/runtime"
cat > "$TMP/wineserver-bin/wineserver" <<'MOCK_WINESERVER'
#!/usr/bin/env bash
printf '%s|%s\n' "$WINEPREFIX" "$*" >> "$WINESERVER_CALLS"
MOCK_WINESERVER
chmod +x "$TMP/wineserver-bin/wineserver"
printf '99999999\n' > "$TMP/gui-state/runtime/installer-gui.pid"
WINESERVER_CALLS="$TMP/wineserver.calls" PATH="$TMP/wineserver-bin:$PATH" \
	DESTINEDFX_CODESPACES_STATE_DIR="$TMP/gui-state" DESTINEDFX_MT5_GUI_WINEPREFIX="$TMP/private-prefix" \
	"$ROOT/deploy/codespaces/stop.sh" >/dev/null || fail 'stop failed for tracked GUI Wine state'
grep -Fq "$TMP/private-prefix|-k" "$TMP/wineserver.calls" || fail 'stop must shut down Wine only in the tracked private GUI prefix'
pass 'stop shuts down orphaned Wine processes in the tracked private GUI prefix'
printf 'PASS: Codespaces K1 shell tests completed.\n'
