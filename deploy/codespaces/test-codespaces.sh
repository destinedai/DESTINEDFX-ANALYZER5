#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
for f in "$ROOT/.devcontainer/postCreate.sh" "$ROOT/deploy/codespaces/post-create.sh" "$ROOT/deploy/codespaces/start.sh" "$ROOT/deploy/codespaces/stop.sh"; do bash -n "$f" || fail "bash syntax: ${f#$ROOT/}"; done
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
grep -Fq 'f9418ed388beba4aa9e28cc552da5e1627a6812734887c89f284d0b76c101698' "$ROOT/.devcontainer/postCreate.sh" || fail 'reviewed installer hash missing'
grep -Fq -- '--expected-sha256 "$EXPECTED_MT5_SHA256"' "$ROOT/.devcontainer/postCreate.sh" || fail 'installer check not delegated to existing guarded script'
pass 'official installer uses reviewed SHA-256 via existing pre-execution verifier'
grep -Fq -- '--host 127.0.0.1' "$ROOT/deploy/codespaces/start.sh" || fail 'bridge bind must be loopback-only'
grep -Fq 'public-code-ready' "$ROOT/deploy/codespaces/start.sh" || fail 'missing public K3 readiness guard'
if grep -Eq '^[[:space:]]*gh codespace ports visibility .*public' "$ROOT/deploy/codespaces/start.sh"; then fail 'start must not run public visibility command before K3'; fi
pass 'public exposure remains blocked and bridge binds loopback'
grep -Fq 'export BRIDGE_HOST_PROFILE=EPHEMERAL' "$ROOT/deploy/codespaces/start.sh" || fail 'Codespaces bridge must announce EPHEMERAL'
grep -Fq 'HOST_IDLE_TIMEOUT_SECONDS' "$ROOT/deploy/codespaces/start.sh" || fail 'configured idle timeout must reach bridge'
grep -Fq 'HOST_STARTED_AT_EPOCH_SECONDS' "$ROOT/deploy/codespaces/start.sh" || fail 'host start epoch must reach bridge for countdown estimate'
grep -Fq 'HOST_GENERATION' "$ROOT/deploy/codespaces/start.sh" || fail 'fresh host generation must reject stale queued commands'
pass 'Codespaces launcher wires EPHEMERAL profile and idle countdown metadata'
grep -Fq 'chmod 700 "$STATE" "$RUNTIME"' "$ROOT/deploy/codespaces/start.sh" || fail 'private state dir mode missing'
grep -Fq 'chmod 600 "$STATE/bridge.env"' "$ROOT/deploy/codespaces/start.sh" || fail 'private credentials file mode missing'
pass 'generated credentials are constrained to private state modes'
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
DESTINEDFX_CODESPACES_STATE_DIR="$TMP/state" "$ROOT/deploy/codespaces/stop.sh" | grep -q '^PASS:' || fail 'stop without state failed'
pass 'stop safely handles empty private state'
printf 'PASS: Codespaces K1 shell tests completed.\n'
