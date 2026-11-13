#!/usr/bin/env bash
set -euo pipefail

MAX_HEARTBEAT_AGE_SECONDS="${MAX_HEARTBEAT_AGE_SECONDS:-15}"
HOST_AGENT_HEALTH_COMMAND="${HOST_AGENT_HEALTH_COMMAND:-}"
EA_HEARTBEAT_FILE="${EA_HEARTBEAT_FILE:-}"
START_TRADING=0

usage() {
  cat <<'EOF'
Usage: host-doctor.sh [--start-trading]

Environment:
  HOST_AGENT_HEALTH_COMMAND  Read-only command that exits 0 only for a healthy host agent.
  EA_HEARTBEAT_FILE           File containing the latest heartbeat epoch seconds.
  MAX_HEARTBEAT_AGE_SECONDS   Freshness limit, default 15 seconds.

Without --start-trading, this reports the evidence needed for a host decision.
With --start-trading, every check is mandatory. Any missing, stale, or failed check
returns BLOCKED and trading must not start.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start-trading) START_TRADING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

arch="$(uname -m)"
now="$(date +%s)"
arch_status="PASS"
if [[ "$arch" == "aarch64" || "$arch" == "arm64" ]]; then
  arch_status="EXPERIMENTAL_ARM"
elif [[ "$arch" != "x86_64" ]]; then
  arch_status="UNSUPPORTED"
fi

health_status="MISSING"
if [[ -n "$HOST_AGENT_HEALTH_COMMAND" ]]; then
  if bash -c "$HOST_AGENT_HEALTH_COMMAND" >/dev/null 2>&1; then health_status="PASS"; else health_status="FAILED"; fi
fi

heartbeat_status="MISSING"
heartbeat_age="unknown"
if [[ -n "$EA_HEARTBEAT_FILE" && -r "$EA_HEARTBEAT_FILE" ]]; then
  heartbeat="$(tr -d '[:space:]' < "$EA_HEARTBEAT_FILE")"
  if [[ "$heartbeat" =~ ^[0-9]+$ ]]; then
    heartbeat_age=$((now - heartbeat))
    if (( heartbeat_age >= 0 && heartbeat_age <= MAX_HEARTBEAT_AGE_SECONDS )); then heartbeat_status="PASS"; else heartbeat_status="STALE"; fi
  else
    heartbeat_status="INVALID"
  fi
fi

printf 'architecture=%s\n' "$arch"
printf 'architecture_status=%s\n' "$arch_status"
printf 'host_agent_health=%s\n' "$health_status"
printf 'ea_heartbeat=%s\n' "$heartbeat_status"
printf 'ea_heartbeat_age_seconds=%s\n' "$heartbeat_age"

if [[ "$START_TRADING" == "1" ]]; then
  if [[ "$arch_status" == "UNSUPPORTED" ]]; then
    echo "BLOCKED: unsupported architecture."; exit 30
  fi
  if [[ "$health_status" != "PASS" ]]; then
    echo "BLOCKED: host-agent health is not fresh and passing."; exit 31
  fi
  if [[ "$heartbeat_status" != "PASS" ]]; then
    echo "BLOCKED: fresh EA heartbeat is not available."; exit 32
  fi
  if [[ "$arch_status" == "EXPERIMENTAL_ARM" ]]; then
    echo "BLOCKED: ARM MT5 and EA operation remains UNVERIFIED."; exit 33
  fi
  echo "PASS: x86_64 host-agent health and fresh EA heartbeat are present."
else
  echo "diagnostic_only=true"
fi
