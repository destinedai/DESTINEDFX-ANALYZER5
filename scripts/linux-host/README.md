# DestinedFX-owned Linux execution host

This directory contains three execution contexts which must not be conflated:

- **Self-hosted persistent host**: the production target for broker execution. Its systemd units run the Bridge, Management API, root host-attestation agent and linux-slot-worker.py with BRIDGE_HOST_PROFILE=PERSISTENT and BRIDGE_HOST_MODE=linux-host. Each authenticated pairing receives its own non-login Unix identity, private Wine prefix, terminal tree, Xauthority cookie and display, with encrypted systemd credentials and persistent local storage. It runs on the operator-controlled Ubuntu machine; it does not use Azure, Oracle, Railway, Render, Cloudflare Workers, or another hosted execution vendor.
- **Ephemeral Codespaces development/test host**: the Codespaces launcher also runs linux-slot-worker.py and the same isolation/host-agent code, but sets an EPHEMERAL host profile and finite lifecycle. It is DEMO-only development/test infrastructure, not a persistent production host and not evidence of real broker acceptance.
- **Legacy single-terminal Linux compatibility path**: linux-worker.py, destinedfx-linux-worker.timer, destinedfx-mt5.service and destinedfx-xvfb.service remain for compatibility. They do not provide per-user OS isolation and must not be started beside the persistent isolated slot worker.

No installer creates broker accounts or submits orders. Every user supplies their own broker account through the authenticated app flow. DEMO and LIVE use the same isolated runtime; LIVE remains subject to explicit selection/confirmation, user authentication, account identity, broker validation, risk controls, and order reconciliation.

## Supported self-hosted baseline

The supported one-command production target is **Ubuntu 24.04 LTS on x86-64** with systemd 250+, a persistent root filesystem, and /run mounted as tmpfs. The prerequisite installer checks those facts before it installs anything. It also enforces at least **2 CPU cores and 8 GiB RAM** for two concurrent isolated broker terminals. Use SSD storage with enough capacity for two private terminal trees and Wine prefixes, logs, PostgreSQL data and local backups; 80 GiB or more is a sensible starting disk allocation.

The install-prereqs-ubuntu.sh script installs the baseline packages (Git, Python 3/venv/pip, sudo, SQLite, OpenSSL, Xvfb, xauth, fonts, process/network diagnostics, curl, archive tools and supporting utilities), Docker Engine from Docker's official Ubuntu repository, Caddy from its stable repository, and Ubuntu's Wine packages with i386 support. It then checks command -v docker, command -v caddy, command -v wine and runs wine-guard.sh --report. Wine must report **9.x, 10.0-10.2, or 11.21**; the script stops if the distribution candidate is outside the supported range. Package installation is fail-fast: a failed apt, repository-key, service or verification command prevents later setup steps.

For two isolated users, the host needs two separate DNS hostnames controlled by the operator: one for the Bridge and another for the app-management API. Both A/AAAA records must point to this same VPS before setup starts. Inbound TCP **80 and 443** must be allowed for Caddy TLS issuance and renewal; outbound HTTPS is needed for package updates, the PostgreSQL image and the news provider. Caddy is the only public ingress process. Never expose the Bridge loopback port, management API loopback port, host agent, database, Wine display or MT5 terminal ports to the public Internet.

## First installation: one command

Use a clean Ubuntu 24.04 x86-64 VPS. Clone the requested production branch and detach at the exact branch commit you are installing:

~~~sh
git clone --branch feat/self-hosted-linux-host-runtime https://github.com/destinedai/DESTINEDFX-ANALYZER5.git
cd DESTINEDFX-ANALYZER5
git checkout --detach "$(git rev-parse refs/remotes/origin/feat/self-hosted-linux-host-runtime)"
~~~

Before running setup, create the two DNS A/AAAA records so both hostnames resolve to the same VPS, and allow inbound TCP 80/443 in the provider firewall and Ubuntu firewall. Then run this single host-setup command:

~~~sh
sudo bash scripts/linux-host/setup-persistent-host.sh
~~~

The command performs the prerequisite installation, validates and stages the exact checked-out source under /opt/destinedfx/source, bootstraps service accounts and encrypted credentials, prompts for the two domain names and owner email addresses, runs install-mt5.sh --print-script-sha256, and asks you to type the reviewed MetaQuotes wrapper hash before installing MT5 as the non-login destinedfx service account. It then runs rebuild.sh self-hosted --check, installs the persistent host, checks the enabled/active systemd services and both backup timers, and probes the public Bridge /health/ready and Management API /ready endpoints. It prints a PASS/FAIL summary and stops at the first failed step; rerun the same command after fixing the reported blocker.

The installer will not replace a partial MT5 template, log into a broker, create broker accounts or submit orders. Do not log into a broker in the shared template. Each authenticated app pairing receives its own per-user terminal copy and Wine prefix.

After setup succeeds, reveal the one-time first-owner registration token only in the VPS's interactive terminal:

~~~sh
sudo /opt/destinedfx/scripts/linux-host/show-owner-setup-token.sh
~~~

Enter it privately during first-owner registration. Never paste the token into chat, source control, an env file or a log. Other registrations remain normal non-admin accounts. The generated /etc/destinedfx/public-endpoints.json contains only the two public HTTPS origins; the host-bound APK still needs to be built on a trusted Android build workstation with that manifest. A generic DEMO-test APK is not automatically bound to your VPS domains.

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
