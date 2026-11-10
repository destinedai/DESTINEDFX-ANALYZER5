#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
for f in "$ROOT/.devcontainer/postCreate.sh" "$ROOT/deploy/codespaces/post-create.sh" "$ROOT/deploy/codespaces/start.sh" "$ROOT/deploy/codespaces/stop.sh" "$ROOT/deploy/codespaces/verify-public-ready.sh" "$ROOT/deploy/codespaces/gui.sh" "$ROOT/deploy/codespaces/first-run-check.sh" "$ROOT/scripts/linux-host/install-mt5.sh" "$ROOT/scripts/linux-host/verify-mt5-signature.sh" "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh"; do bash -n "$f" || fail "bash syntax: ${f#$ROOT/}"; done
pass 'all Codespaces shell scripts pass bash -n'
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
grep -Fq -- 'installer exited before the wizard appeared' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI installer liveness diagnostic missing'
grep -Fq -- 'wait_alive' "$ROOT/deploy/codespaces/gui.sh" || fail 'GUI display readiness checks missing'
bash "$ROOT/scripts/linux-host/test-verify-mt5-signature.sh" || fail 'invalid installer signature fixtures were accepted'
grep -Fq -- 'installer-verified' "$ROOT/.devcontainer/postCreate.sh" || fail 'installer verification marker missing'
bash "$ROOT/deploy/codespaces/first-run-check.sh" >/tmp/destinedfx-first-run-check.out 2>&1 || true
grep -Fq -- 'SUMMARY:' /tmp/destinedfx-first-run-check.out || fail 'first-run-check summary missing'
grep -Fq -- 'gh codespace ports visibility 6080:private' "$ROOT/deploy/codespaces/gui.sh" || fail 'private GUI forwarding missing'
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
DESTINEDFX_CODESPACES_STATE_DIR="$TMP/state" "$ROOT/deploy/codespaces/stop.sh" | grep -q '^PASS:' || fail 'stop without state failed'
pass 'stop safely handles empty private state'
printf 'PASS: Codespaces K1 shell tests completed.\n'
