#!/usr/bin/env bash
# Creates (or checks) the public-code-ready marker. The marker is written by this script only,
# and only after the K3 hardening tests pass against the exact bridge code that will run.
# Editing bridge/app.py or bridge/owner_ctl.py invalidates the marker.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MARKER="$ROOT/deploy/codespaces/public-code-ready"
STATE="${DESTINEDFX_CODESPACES_STATE_DIR:-$HOME/.local/share/destinedfx-codespaces}"
PYTHON="${DESTINEDFX_PYTHON:-$STATE/venv/bin/python}"
[[ -x "$PYTHON" ]] || PYTHON=python3
K3_TESTS='public or owner_code or bad_and_missing or forwarded_for or per_ip or rotation or oversized'

code_hash() { cat "$ROOT/bridge/app.py" "$ROOT/bridge/owner_ctl.py" | sha256sum | awk '{print $1}'; }

case "${1:-generate}" in
  --check)
    [[ -f "$MARKER" ]] && [[ "$(cat "$MARKER")" == "k3-tests-passed:$(code_hash)" ]]
    ;;
  generate)
    rm -f "$MARKER"
    if (cd "$ROOT/bridge" && "$PYTHON" -m pytest -q test_app.py -k "$K3_TESTS" >/dev/null 2>&1); then
      printf 'k3-tests-passed:%s' "$(code_hash)" > "$MARKER"
      printf 'PASS: K3 hardening tests passed; public-code-ready marker written.\n'
    else
      printf 'FAIL: K3 hardening tests did not pass; public exposure stays disabled.\n' >&2
      exit 1
    fi
    ;;
  *) echo 'Usage: verify-public-ready.sh [generate|--check]' >&2; exit 2 ;;
esac
