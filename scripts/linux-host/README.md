# ZERO-COST SETUP - NO VPS PURCHASE REQUIRED - Use old laptop + free DuckDNS

**₦0 new hosting fees. ₦0 domain purchase. Use hardware and Internet you already have.**

DestinedFX has two supported zero-cost operating modes:

- **Option A — GitHub Codespaces (DEMO ONLY):** use the existing Codespaces build/runtime for DEMO testing. It remains EPHEMERAL and must never unlock LIVE trading. GitHub personal Free accounts currently include 120 core-hours and 15 GB-month of storage. A 2-core Codespace uses two core-hours per running hour, so its compute allowance is up to 60 hours monthly when storage is also within quota. When included usage is exhausted, GitHub blocks additional usage if no payment method is configured; do not add a card or enable spending. This is a monthly development allowance, not an always-on host.
- **Option B — your existing old laptop or desktop at home (persistent host):** install Ubuntu 24.04 LTS on an existing x86-64 computer, use two free DuckDNS subdomains, and keep the computer and home Internet connection on. No VPS, purchased domain, static IP or paid hosting account is required. The existing computer must meet the application capacity gate: at least 2 CPU cores and 8 GiB RAM for two isolated terminals, with persistent storage. Around 80 GiB available is a practical target. An existing HDD can run; an existing SSD is preferable, but do not buy one for this setup.

**Free DuckDNS hostnames:** create two subdomains under one free DuckDNS account, for example bridge.mydomain.duckdns.org and api.mydomain.duckdns.org. The setup script stores the DuckDNS token in a root-only file and installs a systemd timer that refreshes both records every five minutes. DuckDNS supports updating multiple subdomains in one API request. The script does not print or place the token in the repository, normal environment files or service command arguments.

**No-IP Free is not a two-hostname substitute:** its current Free account is limited to one hostname, while DestinedFX needs two separate hostnames. It also asks free users to confirm the hostname every 30 days. Do not upgrade or pay for No-IP; use DuckDNS for this setup.

**Cloud free tiers are not offered as Option C:** Oracle Cloud requires payment-card verification, and Google Cloud requires an active billing account. Neither qualifies as a guaranteed, no-card, ₦0 persistent host for this project. Codespaces remains DEMO-only.

## Option A — GitHub Codespaces, DEMO only

1. Open this repository on GitHub and create a Codespace on feat/self-hosted-linux-host-runtime.
2. Use the existing Codespaces start flow and phone pairing for DEMO testing only.
3. Keep its EPHEMERAL host profile. Do not attempt to enable LIVE on Codespaces.
4. Watch your personal GitHub Codespaces usage page. The included Free allowance is limited by both monthly compute and storage. When the quota ends, stop until it renews; do not add a payment method just to continue.

## Option B — turn your existing laptop/PC into the persistent host

### Hardware and home-network checklist

- Existing Intel/AMD 64-bit computer (x86_64), at least 2 CPU cores and 8 GiB RAM.
- Existing disk with room for Ubuntu, two isolated MT5 terminal trees, Wine prefixes, PostgreSQL and backups. No new disk purchase is required if the current disk has enough room.
- A stable home router and an Internet connection that provides a reachable **public dynamic IPv4 address**. A static IP is not required.
- Ethernet is recommended. Keep the computer powered on, connected to the router and ventilated. Configure the BIOS/UEFI to restore power after an outage where that option exists.

**Important network limitation:** DuckDNS updates a changing public address; it does not open router ports and cannot bypass ISP CGNAT. You must forward inbound TCP 80 and 443 on your home router to the computer's reserved LAN address. If the router's WAN address is private/shared (including 100.64.0.0/10) or differs from the public address, your ISP may be using CGNAT. Port forwarding will not work through CGNAT. First ask whether a public dynamic IP is already included in your existing Internet plan at no extra cost. If not, the home-host LIVE endpoint cannot be made publicly reachable through this direct free setup; keep using Codespaces for DEMO only. Do not buy a static IP or service for this project.

### Install Ubuntu 24.04 on the existing computer

1. Back up personal files first. Installing Ubuntu can erase the selected disk.
2. Download the free official Ubuntu Server 24.04 LTS AMD64 installer from [Ubuntu 24.04 releases](https://releases.ubuntu.com/24.04/). Create a bootable USB using an existing 8 GB or larger flash drive.
3. Boot the old laptop/PC from the USB and install Ubuntu Server 24.04 on the existing disk. Choose the whole-disk option only after confirming the correct disk is backed up. Create your normal administrator account and enable OpenSSH only if you need LAN administration.
4. After first login, verify the machine:

~~~sh
uname -m
nproc
free -h
df -h /
. /etc/os-release && echo "$PRETTY_NAME"
~~~

The expected architecture is x86_64, with at least two CPU cores and 8 GiB RAM. The one-command installer checks the OS, CPU, RAM, systemd and /run tmpfs itself.

### Create the free DuckDNS names

1. Open [DuckDNS](https://www.duckdns.org/) and sign in using one of its supported free login methods.
2. Create two different free subdomains under DuckDNS, such as bridge.mydomain.duckdns.org and api.mydomain.duckdns.org. Use the exact names shown in your DuckDNS account.
3. Copy the DuckDNS account token. The installer will ask for it with hidden input and save it with root-only permissions.
4. No static IP is needed. The installed host timer will update both DuckDNS A records from the home connection every five minutes.

### Router and Ubuntu firewall

1. In your router, reserve one LAN address for the old computer (DHCP reservation), so its internal address does not change.
2. Forward **TCP 80** and **TCP 443** from the Internet to that reserved LAN address, ports 80 and 443 respectively. Do not forward ports 8000, 8010, 8787, 5433, VNC or Wine.
3. On Ubuntu, allow only the required public web ports and your own SSH management access. For example, when using OpenSSH:

~~~sh
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
~~~

4. Check the router's WAN address against the public address shown by an IP-check page. If you are behind CGNAT and cannot obtain a public address at no extra cost, direct inbound HTTPS cannot work. Do not proceed expecting DuckDNS to bypass that restriction.

### Clone and run the one-command setup

On the Ubuntu home computer:

~~~sh
git clone --branch feat/self-hosted-linux-host-runtime https://github.com/destinedai/DESTINEDFX-ANALYZER5.git
cd DESTINEDFX-ANALYZER5
git checkout --detach "$(git rev-parse refs/remotes/origin/feat/self-hosted-linux-host-runtime)"
sudo bash scripts/linux-host/setup-persistent-host.sh
~~~

The setup script installs the free Ubuntu packages, Docker Engine, Caddy and supported Wine dependencies; verifies the computer; bootstraps the encrypted host; asks for your two DuckDNS names, email addresses and DuckDNS token; pushes the current home IP to both DNS records; and confirms that they resolve to the same address. It then prints the current MetaQuotes installer SHA-256 for review, requests you type that exact hash, installs MT5 under the restricted DestinedFX service account, runs the self-hosted source check and installation, enables the persistent services and both daily backup timers plus the DuckDNS refresh timer, and probes the public HTTPS readiness endpoints.

Some home routers do not support NAT loopback. To avoid a false local failure, installer HTTPS probes map each DuckDNS hostname to the local Caddy listener while retaining the correct TLS hostname. After setup succeeds, turn Wi-Fi off on your phone and test the public Bridge and API URLs over mobile data; this confirms that the router forwards the public Internet traffic to the home computer.


At the end, reveal the owner-setup token only on the computer's local terminal:

~~~sh
sudo /opt/destinedfx/scripts/linux-host/show-owner-setup-token.sh
~~~

Keep it private and enter it only in DestinedFX first-owner registration. Never put the token in the repository, chat, an ordinary environment file or a log.

### Keep the laptop awake

Keep its charger connected, do not close the lid while installing, and use the following command if this computer is dedicated to hosting and you want it to stay awake:

~~~sh
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
~~~

This disables system suspend until you reverse it with sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target. Keep ventilation clear and use a stable wired network where possible.

### What “persistent host ready” does and does not mean

The host installation and two-user isolation acceptance verify the Linux host, private per-user terminal isolation, service health and network readiness. They do not prove that two real broker accounts have logged in, that a DEMO order filled, or that LIVE trading is authorized. LIVE remains locked until the existing separate account, terminal, EA heartbeat, explicit confirmation, balance, risk and trade-reconciliation requirements pass.

## APK endpoint binding\n\nThe phone app uses the compiled Bridge and Management API origins in a production-locked build. The only source of truth is `/etc/destinedfx/bridge.env` on the host, validated during installation and projected into `/etc/destinedfx/public-endpoints.json` after external HTTPS readiness succeeds. The manifest contains only those two public HTTPS origins; it does not contain credentials, owner tokens, signing keys or worker secrets. Use `build-self-hosted-apk.sh` to verify both endpoints and the EdDSA management key, run Android tests/build, verify the APK and output the installable file plus SHA-256. The APK intentionally ignores old URL values saved in Android preferences and disables phone-side URL overrides.\n\n## Runtime and service boundaries

| Component | Runtime identity | Purpose |
|---|---|---|
| `destinedfx-host-agent.service` | root | Sanitized health, root-attested slot reservation and terminal-process identity |
| `destinedfx-postgres.service` | root-managed local Docker container | PostgreSQL 16.16 Debian image for app-management only; data is stored on this machine with the image's documented postgres UID/GID 999 and the port is loopback-only |
| `destinedfx-management-api.service` | `destinedfx-api` | Account/session/preferences API and Ed25519 LIVE step-up issuer; local loopback port 8010 |
| `destinedfx-bridge.service` | `destinedfx` | FastAPI pairing/session control, encrypted broker credential store, health and order-control APIs |
| `destinedfx-provision-linux-worker.service` | root, oneshot | Uses the owner-only bootstrap credential to issue/rotate the named worker token and encrypt it |
| `destinedfx-linux-slot-worker.service` | root | Claims work and provisions isolated Unix identities, Xvfb displays, Wine prefixes and MT5 trees |
| `destinedfx-caddy.service` | `caddy` | Local TLS termination and reverse proxy; only binds the public web ports |

Each authenticated pairing receives its own private Wine prefix, separate MT5 terminal tree, Xauthority cookie and display; no pairing shares another pairing's Wine state.

The worker runs as root only because it must create and attest separate locked Unix identities. Its systemd filesystem sandbox makes `/etc` writable only to this dedicated slot-worker service so the `useradd`/`userdel` atomic lock-and-replace sequence can update local account records; the Bridge, management API, host agent and Caddy do not receive `/etc` write access. This is an explicit privileged boundary, not a generic application process. The MT5 terminal itself is launched under each pairing's dedicated non-login UID, never under the shared Bridge identity. Root-agent validation independently checks the reservation, UID, Wine prefix, display, Xauthority and actual terminal process. The Bridge cannot promote a terminal to READY merely because a local process exists.

Systemd encrypted credentials are used for the host-agent token, Bridge Fernet key, worker bootstrap credential, per-host worker credential, PostgreSQL password and management signing key. Each service receives its own read-only credential file for its lifetime. Secret values are not written to ordinary environment files or command-line arguments. See the upstream [systemd-creds manual](https://www.freedesktop.org/software/systemd/man/latest/systemd-creds.html) and [systemd.exec credential documentation](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html).

## Two-user acceptance and trading acceptance

After two different authenticated DEMO pairings have been added through the app and fresh terminal heartbeats are present, run the persistent-host-only acceptance command. It first checks the live Bridge configuration for a persistent Linux profile, root-attested multi-user isolation and sufficient slot capacity:

```sh
sudo /opt/destinedfx/venv/bin/python \
  /opt/destinedfx/scripts/linux-host/accept_two_user_runtime.py \
  --expected-users 2 \
  --owner-uid "$(id -u destinedfx)" \
  --state-dir /var/lib/destinedfx/host-runtime \
  --slots-root /home/destinedfx-slots \
  --bridge-url http://127.0.0.1:8000 \
  --host-agent-url http://127.0.0.1:8787/health/ready
```

The acceptance must prove that the live Bridge reports host_profile=PERSISTENT, linux_host_mode=true, root-attested per-user and multi-user isolation, and sufficient active/configured slots, in addition to distinct Unix UIDs, separate slot homes, terminal copies, Wine prefixes, Xvfb displays and process ownership. It must fail rather than count the same terminal twice. Capture the sanitized result and retain it with the host release evidence.

The acceptance CLI prints **ACCEPTED: true - LIVE UNLOCKED** on a successful persistent-host/isolation check, or **FAILED: <exact reason> - fix: <command>** before its JSON evidence. This is the host-readiness label only. It does not waive the existing per-account LIVE checks (authentication, broker account identity, broker connection, balance/equity availability, explicit LIVE confirmation, terminal/EA heartbeat, risk validation and order reconciliation), and it does not submit an order. Failure commands are safe diagnostics; they do not restart or interrupt a running terminal.
 This is a real-host acceptance command; a CI fixture is not a substitute. See docs/deployment/LINUX_HOST_ACCEPTANCE.md.

Actual DEMO acceptance additionally requires two genuine broker DEMO profiles, successful authorization heartbeats, symbol/account identity matches, market-data availability, one approved DEMO market order, broker-reported fill, order reconciliation and safe close. LIVE acceptance is separate and must only occur after the user explicitly authorizes a specific LIVE test under their own authenticated broker profile. No source-code build, EA compile, readiness endpoint or simulated fixture proves a broker fill.

## Maintenance and recovery

- The installer enables `destinedfx-bridge-backup.timer` for a persistent daily 02:45 UTC backup of Bridge pairing/job state. It runs as the non-root `destinedfx` service owner, creates an atomic SQLite backup and SHA-256 sidecar under `/var/lib/destinedfx/backups`, integrity-checks the copy, and retains 14 days. Verify it with `systemctl list-timers destinedfx-bridge-backup.timer` and test a selected backup using `restore-test.sh`.
- The installer enables `destinedfx-management-postgres-backup.timer` for a persistent daily 02:25 UTC backup. `/opt/destinedfx/scripts/linux-host/backup-management-postgres.sh` runs `pg_dump` locally as the host administrator, writes a custom-format dump plus SHA-256 sidecar atomically into `/var/backups/destinedfx-management`, and retains 14 days. Verify with `systemctl list-timers destinedfx-management-postgres-backup.timer` and inspect only sanitized service status. Test restores into a separate scratch database before relying on a backup. The PostgreSQL container, data volume and backup files are all on this same machine.
- The local timer protects against logical mistakes and supports recovery from recent database corruption; it does **not** protect against host/disk loss. Keep encrypted copies of both databases, the `/etc/credstore.encrypted` files, and the systemd credential host key in an operator-controlled recovery location. That external recovery copy is not an execution host dependency. Never upload plaintext databases or credentials into the repository.
- `/opt/destinedfx/scripts/linux-host/restore-test.sh BACKUP.sqlite3` checks the sidecar and SQLite integrity in a temporary directory. It never overwrites the live database.
- `/opt/destinedfx/scripts/linux-host/setup-unattended-upgrades.sh` enables security-origin updates and leaves automatic reboot disabled.
- `/opt/destinedfx/scripts/linux-host/host-doctor.sh` and the host-agent health endpoints provide diagnostics; health output must remain sanitized.
- `rebuild.sh self-hosted --check` validates source and units without touching services. Reinstall only after reviewing host-service changes and confirming the active sessions will not be interrupted.

The host-agent never returns account passwords, broker login credentials, tokens, signing keys or raw journal content. It only exposes sanitized health and terminal identity evidence. Preserve database backups, credential recovery, restore drills, disk monitoring, and security patching as ongoing operations.

## Legacy tooling

The legacy single-terminal Linux path is a compatibility path. The self-hosted installer refuses to migrate while those legacy services or an unowned MT5 process is active, then disables inactive shared-terminal units. Do not start the legacy worker on a host running the isolated slot worker. The deploy/codespaces launcher is a separate EPHEMERAL test harness which reuses the isolated slot-worker code; its state and credentials are not production persistence or owner-host acceptance.

## Official MetaTrader reference

MetaQuotes documents installing the desktop terminal on Linux using Wine: [MetaTrader 5 for Linux](https://www.metatrader5.com/en/terminal/help/start_advanced/install_linux). ARM is not accepted for managed MT5+EA execution until terminal, MetaEditor, EA heartbeat and broker DEMO acceptance are verified.
