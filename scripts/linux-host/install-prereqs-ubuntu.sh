#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit "${2:-1}"
}

run_install() {
  local label="$1"
  shift
  printf '\n==> %s\n' "$label"
  if "$@"; then
    printf 'PASS: %s\n' "$label"
  else
    local result=$?
    fail "$label failed (exit $result); no later install step was run." "$result"
  fi
}

[[ "$EUID" -eq 0 ]] || fail 'run with sudo: sudo bash scripts/linux-host/install-prereqs-ubuntu.sh' 2
for cmd in uname systemctl findmnt awk dpkg apt-get getconf; do
  command -v "$cmd" >/dev/null 2>&1 || fail "required preflight command missing: $cmd" 3
done

[[ "$(uname -m)" == "x86_64" ]] || fail "unsupported architecture $(uname -m); MT5 requires x86_64." 4
[[ -r /etc/os-release ]] || fail "/etc/os-release is missing." 4
. /etc/os-release
[[ "$ID" == "ubuntu" && "$VERSION_ID" == "24.04" ]] || fail "Ubuntu 24.04 is required; detected ${PRETTY_NAME:-unknown}." 4

SYSTEMD_VERSION="$(systemctl --version | awk 'NR==1 {print $2}')"
[[ "$SYSTEMD_VERSION" =~ ^[0-9]+$ ]] || fail "could not determine systemd version." 4
(( SYSTEMD_VERSION >= 250 )) || fail "systemd 250+ is required; detected $SYSTEMD_VERSION." 4
RUN_FS="$(findmnt -n -o FSTYPE --target /run 2>/dev/null || true)"
[[ "$RUN_FS" == "tmpfs" ]] || fail "/run must be tmpfs for transient bootstrap secrets; detected ${RUN_FS:-unknown}." 4
ROOT_FS="$(findmnt -n -o FSTYPE --target / 2>/dev/null || true)"
[[ -n "$ROOT_FS" && "$ROOT_FS" != "tmpfs" && "$ROOT_FS" != "ramfs" ]] || fail "root filesystem must be persistent storage." 4

CPU_COUNT="$(getconf _NPROCESSORS_ONLN)"
(( CPU_COUNT >= 2 )) || fail "at least 2 CPU cores are required; detected $CPU_COUNT." 4
MEM_KIB="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
[[ "$MEM_KIB" =~ ^[0-9]+$ ]] || fail "could not read physical RAM." 4
(( MEM_KIB >= 8 * 1024 * 1024 )) || fail "at least 8 GiB RAM is required; detected $((MEM_KIB / 1024)) MiB." 4

export DEBIAN_FRONTEND=noninteractive
run_install 'refresh Ubuntu package indexes' apt-get update
run_install 'install README baseline packages and repository tooling' apt-get install -y --no-install-recommends \
  apt-transport-https ca-certificates curl gnupg software-properties-common \
  build-essential git sudo python3 python3-venv python3-pip sqlite3 openssl \
  xvfb xauth x11-utils dbus-x11 fontconfig fonts-liberation fonts-dejavu-core \
  procps psmisc lsof iproute2 iputils-ping dnsutils jq unzip zip rsync \
  netcat-openbsd util-linux xz-utils tar gzip gawk sed grep findutils passwd

run_install 'enable Ubuntu universe and 32-bit Wine dependencies' add-apt-repository -y universe
run_install 'enable i386 architecture for MT5 Windows installer dependencies' dpkg --add-architecture i386

install -d -m 0755 /etc/apt/keyrings /usr/share/keyrings
run_install 'add Docker Engine repository signing key' bash -o pipefail -c \
  'curl --fail --silent --show-error --location --proto "=https" --tlsv1.2 https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg'
chmod 0644 /etc/apt/keyrings/docker.gpg
DOCKER_ARCH="$(dpkg --print-architecture)"
DOCKER_CODENAME="$VERSION_CODENAME"
printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu %s stable\n' \
  "$DOCKER_ARCH" "$DOCKER_CODENAME" > /etc/apt/sources.list.d/docker.list

run_install 'add Caddy stable repository signing key' bash -o pipefail -c \
  'curl --fail --silent --show-error --location --proto "=https" --tlsv1.2 https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg'
chmod 0644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg
printf 'deb [signed-by=/usr/share/keyrings/caddy-stable-archive-keyring.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main\n' \
  > /etc/apt/sources.list.d/caddy-stable.list

run_install 'refresh package indexes for Docker and Caddy' apt-get update
run_install 'install Docker Engine, Caddy, Ubuntu Wine and MT5 display packages' apt-get install -y --no-install-recommends \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
  caddy wine wine64 wine32:i386 winetricks

# The package-default unit must not bind ports 80/443 beside DestinedFX's
# separately sandboxed destinedfx-caddy.service.
if systemctl list-unit-files --no-legend caddy.service 2>/dev/null | grep -q 'caddy\.service'; then
  run_install 'disable package-default Caddy service; DestinedFX owns public ingress' systemctl disable --now caddy.service
fi
run_install 'enable Docker Engine' systemctl enable --now docker.service
run_install 'wait for Docker Engine API readiness' bash -o pipefail -c \
  'for attempt in $(seq 1 30); do docker info >/dev/null 2>&1 && exit 0; sleep 2; done; echo "Docker Engine did not become ready." >&2; exit 1'

for cmd in docker caddy wine xvfb-run Xvfb xauth git python3 sqlite3 curl visudo systemd-creds systemd-analyze; do
  command -v "$cmd" >/dev/null 2>&1 || fail "required command was not installed or is not on PATH: $cmd" 5
done

printf '\nInstalled versions:\n'
docker --version
caddy version
wine --version
python3 --version

printf '\nWine compatibility report:\n'
bash "$SCRIPT_DIR/wine-guard.sh" --report || fail \
  'Wine must be 9.x, 10.0-10.2, or 11.21. The host setup will not continue with an unsupported build.' 6

printf '\nPASS: Ubuntu 24.04 prerequisites installed and verified.\n'
