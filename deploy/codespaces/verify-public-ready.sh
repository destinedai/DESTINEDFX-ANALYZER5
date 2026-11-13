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
FORWARDING_DIAGNOSTIC="$STATE/public-forwarding-diagnostic.txt"

code_hash() { cat "$ROOT/bridge/app.py" "$ROOT/bridge/owner_ctl.py" | sha256sum | awk '{print $1}'; }

record_forwarding_diagnostic() {
  local url="${DESTINEDFX_PUBLIC_URL:-}"
  if [[ -z "$url" && -n "${CODESPACE_NAME:-}" ]]; then
    url="https://${CODESPACE_NAME}-8000.app.github.dev"
  fi
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  if [[ -z "$url" ]]; then
    {
      printf 'external_request=false\n'
      printf 'reason=OWNER_STEP: set DESTINEDFX_PUBLIC_URL or run inside Codespaces after public forwarding is enabled\n'
      printf 'x_forwarded_for_received=unknown\n'
      printf 'assumption=UNVERIFIED until the owner returns this diagnostic\n'
    } > "$FORWARDING_DIAGNOSTIC"
    chmod 600 "$FORWARDING_DIAGNOSTIC"
    printf 'INFO: external forwarding diagnostic not run; owner evidence remains UNVERIFIED.\n'
    return 0
  fi
  local headers status xff entries valid
  headers="$(mktemp)"
  trap 'rm -f "$headers"' RETURN
  status="$(curl --silent --show-error --location --max-time 15 --proto '=https' --tlsv1.2 \
    -D "$headers" -o /dev/null -w '%{http_code}' "$url/health/ready" 2>/dev/null || printf '000')"
  xff="$(grep -i '^x-forwarded-for:' "$headers" | tail -1 | cut -d: -f2- | tr -d '\r' || true)"
  if [[ -n "${xff//[[:space:]]/}" ]]; then
    entries="$(printf '%s' "$xff" | awk -F, '{print NF}')"
    valid="$(printf '%s' "$xff" | tr ',' '\n' | awk 'BEGIN{n=0} $0 ~ /^[[:space:]]*([0-9a-fA-F:.]+)[[:space:]]*$/ {n++} END{print n}')"
    {
      printf 'external_request=true\nstatus=%s\n' "$status"
      printf 'x_forwarded_for_received=true\nshape=entries:%s valid_ip_entries:%s\n' "$entries" "$valid"
      printf 'assumption=UNVERIFIED until the owner returns this diagnostic and confirms gateway behavior\n'
    } > "$FORWARDING_DIAGNOSTIC"
  else
    {
      printf 'external_request=true\nstatus=%s\n' "$status"
      printf 'x_forwarded_for_received=false\nshape=absent\n'
      printf 'assumption=UNVERIFIED until the owner returns this diagnostic and confirms gateway behavior\n'
    } > "$FORWARDING_DIAGNOSTIC"
  fi
  chmod 600 "$FORWARDING_DIAGNOSTIC"
  printf 'INFO: redacted external forwarding diagnostic recorded at %s; header values were not printed.\n' "$FORWARDING_DIAGNOSTIC"
}

case "${1:-generate}" in
  --check)
    [[ -f "$MARKER" ]] && [[ "$(cat "$MARKER")" == "k3-tests-passed:$(code_hash)" ]]
    ;;
  generate)
    rm -f "$MARKER"
    if (cd "$ROOT/bridge" && "$PYTHON" -m pytest -q test_app.py -k "$K3_TESTS" >/dev/null 2>&1); then
      record_forwarding_diagnostic
      printf 'k3-tests-passed:%s' "$(code_hash)" > "$MARKER"
      printf 'PASS: K3 hardening tests passed; public-code-ready marker written.\n'
    else
      printf 'FAIL: K3 hardening tests did not pass; public exposure stays disabled.\n' >&2
      exit 1
    fi
    ;;
  *) echo 'Usage: verify-public-ready.sh [generate|--check]' >&2; exit 2 ;;
esac
