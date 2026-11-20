#!/usr/bin/env bash
set -euo pipefail

APT_ROOT="${DESTINEDFX_APT_ROOT:-/etc/apt/apt.conf.d}"
CODENAME="${DESTINEDFX_CODENAME:-}"
SYSTEMCTL_BIN="${DESTINEDFX_SYSTEMCTL_BIN:-/usr/bin/systemctl}"
if [[ -z "$CODENAME" ]]; then
  . /etc/os-release
  CODENAME="${VERSION_CODENAME:-}"
fi
if [[ ! "$CODENAME" =~ ^[a-z][a-z0-9.-]{1,31}$ ]]; then
  echo "BLOCKED: could not determine a valid Ubuntu release codename." >&2
  exit 2
fi
if [[ "${DESTINEDFX_SKIP_PACKAGE_INSTALL:-0}" != "1" ]]; then
  if [[ "$EUID" -ne 0 ]]; then
    echo "BLOCKED: run this helper as root to install unattended-upgrades." >&2
    exit 2
  fi
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y unattended-upgrades
fi
if [[ "$EUID" -ne 0 && "$APT_ROOT" == "/etc/apt/apt.conf.d" ]]; then
  echo "BLOCKED: run this helper as root to configure APT." >&2
  exit 2
fi
install -d -m 755 "$APT_ROOT"
write_config() {
  local name="$1" tmp
  tmp="$(mktemp "$APT_ROOT/.${name}.XXXXXX")"
  cat > "$tmp"
  chmod 644 "$tmp"
  if [[ -f "$APT_ROOT/$name" ]] && cmp -s "$tmp" "$APT_ROOT/$name"; then
    rm -f "$tmp"
  else
    mv -f -- "$tmp" "$APT_ROOT/$name"
  fi
}
write_config 20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
printf 'Unattended-Upgrade::Origins-Pattern {\n    "origin=Ubuntu,codename=%s-security";\n};\nUnattended-Upgrade::Automatic-Reboot "false";\nUnattended-Upgrade::Remove-Unused-Dependencies "true";\nUnattended-Upgrade::Remove-New-Unused-Dependencies "true";\n' "$CODENAME" | write_config 50unattended-upgrades
if [[ "${DESTINEDFX_SKIP_PACKAGE_INSTALL:-0}" != "1" ]]; then
  "$SYSTEMCTL_BIN" enable --now unattended-upgrades.service
fi
printf 'UNATTENDED_SECURITY_UPDATES_CONFIGURED codename=%s automatic_reboot=false\n' "$CODENAME"
