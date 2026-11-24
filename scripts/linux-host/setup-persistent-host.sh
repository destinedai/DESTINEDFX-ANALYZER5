#!/usr/bin/env bash
set -Eeuo pipefail

readonly EXPECTED_REPO='destinedai/DESTINEDFX-ANALYZER5'
readonly EXPECTED_BRANCH='feat/self-hosted-linux-host-runtime'
readonly PREFIX='/var/lib/destinedfx/terminal-template-prefix'
readonly MT5_STATE='/var/lib/destinedfx/host-runtime/mt5-installer-state'
readonly STAGED='/opt/destinedfx/source'
readonly BRIDGE_ENV='/etc/destinedfx/bridge.env'
readonly BRIDGE_TIMER='destinedfx-bridge-backup.timer'
readonly PG_TIMER='destinedfx-management-postgres-backup.timer'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR/../.." rev-parse --show-toplevel 2>/dev/null)" || {
  echo 'BLOCKED: run from a checked-out DestinedFX repository.' >&2
  exit 2
}
SOURCE_SHA="$(git -C "$SOURCE_ROOT" rev-parse HEAD)"

STEPS=(
  'Ubuntu prerequisites'
  'Repository checkout validation'
  'Pinned source staging'
  'Encrypted host bootstrap'
  'Two-domain and email configuration'
  'MetaQuotes MT5 install'
  'Self-hosted source validation'
  'Persistent host installation'
  'Systemd service checks'
  'Backup timer checks'
  'HTTPS readiness checks'
)
declare -A RESULTS=()
CURRENT_STEP=''
BRIDGE_DOMAIN=''
APP_DOMAIN=''
BRIDGE_BASE_URL=''

summary() {
  local rc=$?
  trap - EXIT
  [[ -z "$CURRENT_STEP" ]] || RESULTS["$CURRENT_STEP"]='FAIL'
  printf '\n================ DESTINEDFX HOST SETUP ================\n'
  printf 'Source commit: %s\n' "$SOURCE_SHA"
  local step
  for step in "${STEPS[@]}"; do
    printf '%-42s %s\n' "$step" "${RESULTS[$step]:-NOT RUN}"
  done
  if (( rc == 0 )); then
    printf '\nPASS: ALL HOST INSTALLATION CHECKS COMPLETED.\n'
    printf 'Bridge: %s\nManagement API: %s\n' "$BRIDGE_DOMAIN" "$APP_DOMAIN"
    printf 'Next: sudo /opt/destinedfx/scripts/linux-host/show-owner-setup-token.sh\n'
    printf 'Enter the one-time token privately in DestinedFX first-owner registration. Never paste it into chat, an env file, logs or source control.\n'
    printf 'The APK must be built with /etc/destinedfx/public-endpoints.json on the trusted Android build workstation; the existing generic demo APK is not automatically bound to these domains.\n'
  else
    printf '\nFAIL: setup stopped at the first failing step (exit %s). Fix that step and rerun the same command.\n' "$rc"
  fi
  printf 'DNS next step: keep two separate hostnames pointed at this VPS and allow inbound TCP 80/443.\n'
  printf '========================================================\n'
  exit "$rc"
}
trap summary EXIT

run_step() {
  local name="$1"
  shift
  CURRENT_STEP="$name"
  printf '\n========== %s ==========\n' "$name"
  if "$@"; then
    RESULTS["$name"]='PASS'
    CURRENT_STEP=''
    printf 'PASS: %s\n' "$name"
  else
    local rc=$?
    RESULTS["$name"]='FAIL'
    printf 'FAIL: %s (exit %s)\n' "$name" "$rc" >&2
    return "$rc"
  fi
}

require_terminal() {
  [[ "$EUID" -eq 0 ]] || { echo 'Run once with sudo: sudo bash scripts/linux-host/setup-persistent-host.sh' >&2; return 1; }
  [[ -r /dev/tty && -w /dev/tty ]] || { echo 'Interactive terminal required for DNS and MetaQuotes hash confirmation.' >&2; return 1; }
}

validate_checkout() {
  local origin active dirty
  origin="$(git -C "$SOURCE_ROOT" remote get-url origin 2>/dev/null)" || {
    echo 'BLOCKED: origin remote is missing.' >&2; return 1;
  }
  case "${origin,,}" in
    https://github.com/destinedai/destinedfx-analyzer5|https://github.com/destinedai/destinedfx-analyzer5.git|git@github.com:destinedai/destinedfx-analyzer5.git|ssh://git@github.com/destinedai/destinedfx-analyzer5.git) ;;
    *) echo "BLOCKED: expected origin $EXPECTED_REPO; got $origin." >&2; return 1 ;;
  esac
  active="$(git -C "$SOURCE_ROOT" branch --show-current)"
  [[ -z "$active" || "$active" == "$EXPECTED_BRANCH" ]] || {
    echo "BLOCKED: checkout $EXPECTED_BRANCH or detach at its reviewed commit; current branch is $active." >&2; return 1;
  }
  dirty="$(git -C "$SOURCE_ROOT" status --porcelain --untracked-files=normal)" || return 1
  [[ -z "$dirty" ]] || { echo 'BLOCKED: checkout must be clean before host installation.' >&2; return 1; }
  printf 'Validated %s at %s\n' "$EXPECTED_REPO" "$SOURCE_SHA"
}

stage_source() {
  local temp
  install -d -o root -g root -m 0755 /opt/destinedfx || return 1
  [[ ! -L "$STAGED" ]] || { echo "BLOCKED: source staging path is a symlink: $STAGED" >&2; return 1; }
  temp="$(mktemp -d /opt/destinedfx/.source.XXXXXX)" || return 1
  if ! git -C "$SOURCE_ROOT" archive --format=tar "$SOURCE_SHA" | tar -xf - -C "$temp"; then
    rm -rf -- "$temp"
    echo 'BLOCKED: could not stage the exact checked-out commit.' >&2
    return 1
  fi
  [[ ! -e "$STAGED" ]] || rm -rf -- "$STAGED"
  mv -- "$temp" "$STAGED" || return 1
  chown -R root:root "$STAGED" || return 1
  chmod 0755 "$STAGED" || return 1
  printf 'Staged reviewed source at %s\n' "$STAGED"
}

env_default() {
  local key="$1"
  python3 - "$BRIDGE_ENV" "$key" <<'PY'
import sys
from pathlib import Path
path, key = Path(sys.argv[1]), sys.argv[2]
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(key + "="):
            value = line.split("=", 1)[1].strip()
            if value and "example.com" not in value.lower():
                print(value)
                break
PY
}

configure_domains() {
  local default_bridge default_app default_caddy default_admin bridge_ips api_ips
  default_bridge="$(env_default BRIDGE_HOST)"
  default_app="$(env_default APP_DOMAIN)"
  default_caddy="$(env_default CADDY_EMAIL)"
  default_admin="$(env_default ADMIN_EMAILS)"
  printf 'Before continuing, create two different DNS A/AAAA records pointing to this same VPS.\n'
  read -r -p "Bridge hostname [${default_bridge:-bridge.your-domain.tld}]: " BRIDGE_DOMAIN </dev/tty || return 1
  read -r -p "Management API hostname [${default_app:-api.your-domain.tld}]: " APP_DOMAIN </dev/tty || return 1
  read -r -p "Caddy TLS contact email [${default_caddy:-owner@your-domain.tld}]: " CADDY_EMAIL </dev/tty || return 1
  read -r -p "Initial administrator email [${default_admin:-owner@your-domain.tld}]: " ADMIN_EMAILS </dev/tty || return 1

  python3 - "$BRIDGE_DOMAIN" "$APP_DOMAIN" "$CADDY_EMAIL" "$ADMIN_EMAILS" <<'PY' || return $?
import re, sys
bridge, api, caddy, admins = sys.argv[1:]
host = re.compile(r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
email = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
for label, value in (("Bridge", bridge), ("Management API", api)):
    if not host.fullmatch(value) or value.lower().endswith(".example.com") or value.lower() == "example.com":
        raise SystemExit(f"BLOCKED: enter a real public DNS hostname for {label}.")
if bridge.lower().rstrip(".") == api.lower().rstrip("."):
    raise SystemExit("BLOCKED: Bridge and Management API need separate hostnames.")
if not email.fullmatch(caddy) or any(not email.fullmatch(value.strip()) for value in admins.split(",")):
    raise SystemExit("BLOCKED: enter valid Caddy and administrator email addresses.")
PY

  bridge_ips="$(getent ahosts "$BRIDGE_DOMAIN" | awk '{print $1}' | sort -u)" || true
  api_ips="$(getent ahosts "$APP_DOMAIN" | awk '{print $1}' | sort -u)" || true
  [[ -n "$bridge_ips" ]] || { echo "BLOCKED: $BRIDGE_DOMAIN does not resolve. Add its DNS A/AAAA record and retry." >&2; return 1; }
  [[ -n "$api_ips" ]] || { echo "BLOCKED: $APP_DOMAIN does not resolve. Add its DNS A/AAAA record and retry." >&2; return 1; }
  [[ "$bridge_ips" == "$api_ips" ]] || { echo 'BLOCKED: both hostnames must resolve to the same VPS address set.' >&2; return 1; }

  python3 - "$BRIDGE_ENV" "$BRIDGE_DOMAIN" "$APP_DOMAIN" "$CADDY_EMAIL" "$ADMIN_EMAILS" <<'PY' || return $?
import os, sys, tempfile
from pathlib import Path
path = Path(sys.argv[1])
bridge, api, caddy, admins = sys.argv[2:]
values = {
    "BRIDGE_BASE_URL": "https://" + bridge,
    "BRIDGE_HOST": bridge,
    "EXPECTED_FORWARDED_HOST": bridge,
    "APP_DOMAIN": api,
    "CADDY_EMAIL": caddy,
    "ADMIN_EMAILS": admins,
}
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
seen, updated = set(), []
for line in lines:
    key = line.partition("=")[0]
    if key in values:
        if key not in seen:
            updated.append(key + "=" + values[key])
            seen.add(key)
    else:
        updated.append(line)
for key, value in values.items():
    if key not in seen:
        updated.append(key + "=" + value)
path.parent.mkdir(parents=True, exist_ok=True)
fd, temp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent, text=True)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write("\n".join(updated) + "\n")
    os.chmod(temp, 0o600)
    os.replace(temp, path)
finally:
    if os.path.exists(temp):
        os.unlink(temp)
PY
  chown root:caddy "$BRIDGE_ENV" || return 1
  chmod 0640 "$BRIDGE_ENV" || return 1
  BRIDGE_BASE_URL="https://$BRIDGE_DOMAIN"
  printf 'Configured two HTTPS origins: %s and https://%s\n' "$BRIDGE_BASE_URL" "$APP_DOMAIN"
}

install_mt5() {
  local terminal editor trade_library preview wrapper_hash typed_hash
  terminal="$PREFIX/drive_c/Program Files/MetaTrader 5/terminal64.exe"
  editor="$PREFIX/drive_c/Program Files/MetaTrader 5/metaeditor64.exe"
  trade_library="$PREFIX/drive_c/Program Files/MetaTrader 5/MQL5/Include/Trade/Trade.mqh"
  if [[ -f "$terminal" && -f "$editor" && -f "$trade_library" ]]; then
    echo 'PASS: MT5 terminal, MetaEditor and MQL5 Trade library already exist; preserving the existing template.'
    return 0
  fi
  if [[ -e "$PREFIX/drive_c/Program Files/MetaTrader 5" ]]; then
    echo 'BLOCKED: partial MT5 template exists. Refusing to delete or overwrite it.' >&2
    return 1
  fi
  install -d -o destinedfx -g destinedfx -m 0700 "$MT5_STATE" || return 1
  if ! preview="$(sudo -u destinedfx -- env HOME=/var/lib/destinedfx WINEPREFIX="$PREFIX" WINE_BIN=/usr/bin/wine \
      DESTINEDFX_CODESPACES_STATE_DIR="$MT5_STATE" bash "$STAGED/scripts/linux-host/install-mt5.sh" \
      --print-script-sha256 --wineprefix "$PREFIX" 2>&1)"; then
    printf '%s\n' "$preview" >&2
    echo 'BLOCKED: MetaQuotes Linux wrapper SHA-256 preview failed.' >&2
    return 1
  fi
  printf '\n%s\n' "$preview"
  wrapper_hash="$(printf '%s\n' "$preview" | sed -n 's/^official_linux_script_sha256=//p' | tail -n 1)"
  [[ "$wrapper_hash" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'BLOCKED: no valid 64-character wrapper SHA-256 was returned.' >&2; return 1; }
  printf '\nReview the MetaQuotes wrapper SHA-256 above, then type the exact 64-character value to authorize installation.\n'
  read -r -p 'Reviewed MetaQuotes wrapper SHA-256: ' typed_hash </dev/tty || return 1
  [[ "$typed_hash" =~ ^[0-9a-fA-F]{64}$ && "${typed_hash,,}" == "${wrapper_hash,,}" ]] || {
    echo 'BLOCKED: typed SHA-256 does not match the reviewed download; MT5 setup was not run.' >&2
    return 1
  }
  if ! sudo -u destinedfx -- env HOME=/var/lib/destinedfx WINEPREFIX="$PREFIX" WINE_BIN=/usr/bin/wine \
      DESTINEDFX_CODESPACES_STATE_DIR="$MT5_STATE" bash "$STAGED/scripts/linux-host/install-mt5.sh" \
      --expected-sha256 "$typed_hash" --wineprefix "$PREFIX" --headless; then
    echo 'BLOCKED: MetaQuotes MT5 installer failed.' >&2
    return 1
  fi
  [[ -f "$terminal" && -f "$editor" && -f "$trade_library" ]] || {
    echo 'BLOCKED: installer did not create terminal64.exe, MetaEditor and MQL5 Trade.mqh.' >&2
    return 1
  }
  chown -R destinedfx:destinedfx "$PREFIX" || return 1
  chmod 0750 "$PREFIX" || return 1
}

check_services() {
  local unit
  for unit in docker.service destinedfx-host-agent.service destinedfx-postgres.service \
    destinedfx-management-api.service destinedfx-bridge.service \
    destinedfx-linux-slot-worker.service destinedfx-caddy.service; do
    systemctl is-enabled --quiet "$unit" || { echo "FAIL: $unit is not enabled." >&2; return 1; }
    systemctl is-active --quiet "$unit" || {
      echo "FAIL: $unit is not active." >&2
      systemctl status "$unit" --no-pager --full >&2 || true
      return 1
    }
    printf 'PASS: %s enabled and active\n' "$unit"
  done
}

check_timers() {
  local timer
  for timer in "$BRIDGE_TIMER" "$PG_TIMER"; do
    systemctl is-enabled --quiet "$timer" || { echo "FAIL: $timer is not enabled." >&2; return 1; }
    systemctl is-active --quiet "$timer" || { echo "FAIL: $timer is not active." >&2; return 1; }
    printf 'PASS: %s enabled and active\n' "$timer"
  done
  systemctl list-timers --all --no-pager "$BRIDGE_TIMER" "$PG_TIMER"
}

check_health() {
  curl --fail --silent --show-error --max-time 15 "$BRIDGE_BASE_URL/health/ready" -o /dev/null || {
    echo "FAIL: $BRIDGE_BASE_URL/health/ready did not return HTTP 2xx; check DNS, ports 80/443 and Caddy/Bridge." >&2
    return 1
  }
  curl --fail --silent --show-error --max-time 15 "https://$APP_DOMAIN/ready" -o /dev/null || {
    echo "FAIL: https://$APP_DOMAIN/ready did not return HTTP 2xx; check Caddy, PostgreSQL and Management API." >&2
    return 1
  }
  echo 'PASS: Bridge /health/ready and Management API /ready returned HTTP 2xx.'
}

require_terminal
run_step 'Ubuntu prerequisites' bash "$SCRIPT_DIR/install-prereqs-ubuntu.sh"
run_step 'Repository checkout validation' validate_checkout
run_step 'Pinned source staging' stage_source
run_step 'Encrypted host bootstrap' bash "$STAGED/scripts/linux-host/bootstrap-self-hosted-host.sh"
run_step 'Two-domain and email configuration' configure_domains
run_step 'MetaQuotes MT5 install' install_mt5
run_step 'Self-hosted source validation' bash "$STAGED/scripts/linux-host/rebuild.sh" self-hosted --check
run_step 'Persistent host installation' bash "$STAGED/scripts/linux-host/rebuild.sh" self-hosted install
run_step 'Systemd service checks' check_services
run_step 'Backup timer checks' check_timers
run_step 'HTTPS readiness checks' check_health
