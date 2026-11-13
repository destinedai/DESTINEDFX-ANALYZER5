#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEST_ROOT="${DESTINEDFX_ROOT:-/opt/destinedfx}"
UNIT_DIR="${DESTINEDFX_SYSTEMD_DIR:-/etc/systemd/system}"
SUDOERS_DIR="${DESTINEDFX_SUDOERS_DIR:-/etc/sudoers.d}"
LOGROTATE_DIR="${DESTINEDFX_LOGROTATE_DIR:-/etc/logrotate.d}"
ROLE="${1:-all}"
MODE="${2:-install}"
if [[ "$ROLE" == "--check" ]]; then ROLE="all"; MODE="--check"; fi
if [[ "$ROLE" != "all" && "$ROLE" != "terminal" && "$ROLE" != "bridge" ]]; then
  echo "Usage: rebuild.sh [all|terminal|bridge] [install|--check]" >&2
  exit 2
fi

required=(
  scripts/linux-host/host-agent.py
  scripts/linux-host/linux-worker.py
  scripts/linux-host/launch-mt5.sh
  scripts/linux-host/backup.sh
  scripts/linux-host/restore-test.sh
  scripts/linux-host/download-ea-release.sh
  scripts/linux-host/setup-unattended-upgrades.sh
  scripts/linux-host/ensure-swap.sh
  scripts/linux-host/install-mt5.sh
  scripts/linux-host/host-doctor.sh
  scripts/linux-host/logrotate.d/destinedfx
  managed-worker/worker.py
  managed-worker/mt5_journal.py
  scripts/linux-host/systemd/destinedfx-xvfb.service
  scripts/linux-host/systemd/destinedfx-mt5.service
  scripts/linux-host/systemd/destinedfx-host-agent.service
  scripts/linux-host/systemd/destinedfx-linux-worker.service
  scripts/linux-host/systemd/destinedfx-linux-worker.timer
  scripts/linux-host/sudoers.d/destinedfx-mt5-control
)
if [[ "$ROLE" == "all" || "$ROLE" == "bridge" ]]; then
  required+=(scripts/linux-host/systemd/destinedfx-bridge.service)
fi
for rel in "${required[@]}"; do
  [[ -f "$REPO_ROOT/$rel" ]] || { echo "BLOCKED: required source file missing: $rel" >&2; exit 3; }
done
if [[ "$MODE" == "--check" ]]; then
  for file in "$SCRIPT_DIR"/*.sh; do bash -n "$file"; done
  python3 - "$REPO_ROOT" <<'PY'
import ast, pathlib, sys
root = pathlib.Path(sys.argv[1])
for path in (root / 'managed-worker/worker.py', root / 'managed-worker/mt5_journal.py', root / 'scripts/linux-host/host-agent.py', root / 'scripts/linux-host/linux-worker.py'):
    ast.parse(path.read_text(encoding='utf-8'), filename=str(path))
print('REBUILD_CHECK_OK')
PY
  exit 0
fi
if [[ "$MODE" != "install" || "$EUID" -ne 0 ]]; then
  echo "BLOCKED: use --check for validation or run rebuild.sh as root for installation." >&2
  exit 4
fi
if [[ "$ROLE" == "terminal" || "$ROLE" == "all" ]]; then
  worker_credential="${DESTINEDFX_WORKER_CREDENTIAL:-/etc/credstore.encrypted/destinedfx-worker-token.cred}"
  if [[ ! -f "$worker_credential" || ! -f /etc/destinedfx/worker.env ]]; then
    echo "BLOCKED: worker settings or encrypted systemd credential are missing; complete the owner setup step first." >&2
    exit 5
  fi
  terminal_exe="${MT5_TERMINAL_EXE:-/var/lib/destinedfx/terminal/drive_c/Program Files/MetaTrader 5/terminal64.exe}"
  if [[ ! -f "$terminal_exe" ]]; then
    echo "BLOCKED: MT5 is not installed at the configured terminal path; install and hash-check it first." >&2
    exit 5
  fi
fi
if [[ "$ROLE" == "bridge" || "$ROLE" == "all" ]]; then
  if [[ ! -f /etc/destinedfx/bridge.env ]]; then
    echo "BLOCKED: bridge runtime settings are missing; complete the owner setup step first." >&2
    exit 5
  fi
fi
install -d -o root -g root -m 755 "$DEST_ROOT/scripts/linux-host" "$DEST_ROOT/managed-worker" "$UNIT_DIR" "$LOGROTATE_DIR"
install -d -o root -g root -m 755 "$SUDOERS_DIR"
install -m 755 "$SCRIPT_DIR/host-agent.py" "$SCRIPT_DIR/linux-worker.py" "$SCRIPT_DIR/launch-mt5.sh" "$SCRIPT_DIR/backup.sh" "$SCRIPT_DIR/restore-test.sh" "$SCRIPT_DIR/download-ea-release.sh" "$SCRIPT_DIR/setup-unattended-upgrades.sh" "$SCRIPT_DIR/ensure-swap.sh" "$SCRIPT_DIR/install-mt5.sh" "$SCRIPT_DIR/host-doctor.sh" "$DEST_ROOT/scripts/linux-host/"
install -m 644 "$REPO_ROOT/managed-worker/worker.py" "$REPO_ROOT/managed-worker/mt5_journal.py" "$DEST_ROOT/managed-worker/"
install -m 644 "$SCRIPT_DIR/logrotate.d/destinedfx" "$LOGROTATE_DIR/destinedfx"
units=(destinedfx-xvfb.service destinedfx-mt5.service destinedfx-host-agent.service destinedfx-linux-worker.service destinedfx-linux-worker.timer)
if [[ "$ROLE" == "all" || "$ROLE" == "bridge" ]]; then units+=(destinedfx-bridge.service); fi
for unit in "${units[@]}"; do
  install -m 644 "$SCRIPT_DIR/systemd/$unit" "$UNIT_DIR/$unit"
done
install -m 440 "$SCRIPT_DIR/sudoers.d/destinedfx-mt5-control" "$SUDOERS_DIR/destinedfx-mt5-control"
visudo -cf "$SUDOERS_DIR/destinedfx-mt5-control" >/dev/null
systemctl daemon-reload
if [[ "$ROLE" == "terminal" || "$ROLE" == "all" ]]; then
  systemctl enable --now destinedfx-xvfb.service destinedfx-mt5.service destinedfx-host-agent.service destinedfx-linux-worker.timer
fi
if [[ "$ROLE" == "bridge" || "$ROLE" == "all" ]]; then
  systemctl enable --now destinedfx-bridge.service
fi
printf 'REBUILD_OK role=%s; persistent data and owner secrets were not modified.\n' "$ROLE"
