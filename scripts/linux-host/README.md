# DestinedFX-owned Linux execution host

This directory contains two distinct Linux paths:

- **Self-hosted persistent host**: the production target for broker execution. It uses the repository's Bridge, root host-attestation agent, per-pairing Linux identities, isolated Wine prefixes, one MT5 terminal per authenticated pairing, encrypted systemd credentials, local Caddy HTTPS and persistent local storage. It runs on the operator-controlled Ubuntu machine; it does not use Azure, Oracle, Railway, Render, Cloudflare Workers, or another hosted execution vendor.
- **Legacy single-terminal/Codespaces tooling**: retained for compatibility and tests. It is not the production multi-user runtime and must not be started beside the self-hosted slot worker.

No installer creates broker accounts or submits orders. Every user supplies their own broker account through the authenticated app flow. DEMO and LIVE use the same isolated runtime; LIVE remains subject to explicit selection/confirmation, user authentication, account identity, broker validation, risk controls, and order reconciliation.

## Supported self-hosted baseline

The installer currently validates Ubuntu 22.04 or 24.04 on x86-64 with systemd 250+, an x86-64 MT5 terminal, Wine, Xvfb, xauth, Caddy, SQLite tools, Python 3/venv, sudo, and Docker Engine for the local PostgreSQL container. Docker is used only on this same machine for the management database; it is not a cloud runtime or remote host. The host must have persistent storage and a working system clock. ARM MT5 terminal/EA execution remains unverified and is rejected by the installer.

For two concurrent isolated broker accounts, plan at least 8 GiB RAM and two CPU cores. The installer enforces a static reserve of 2 GiB for the host plus 3 GiB per configured terminal and does not permit a configured slot count above the host's CPU/RAM capacity. The worker also checks *currently available* RAM before claiming work. This is a fail-closed capacity estimate, not a promise that every machine meeting the minimum will sustain continuous MT5 execution. SSD capacity should allow separate terminal copies, Wine prefixes, logs, database growth and backups.

The host needs two separate DNS names controlled by the operator: one for the Bridge and one for the app-management API. Both A/AAAA records must point to this same machine. Inbound TCP 80/443 is required for Caddy's TLS issuance/renewal, and outbound HTTPS is needed for OS/software updates, the local Postgres image pull and the news provider. Keep SSH restricted. Do not expose the host agent, Bridge loopback port, database, Wine display or MT5 terminal ports to the public Internet. Caddy is the only public ingress process.

## First installation

1. Check out the repository on the Ubuntu host and review the source at the exact commit you intend to deploy. Do not run an unreviewed branch or an installer copied from an untrusted location.
2. Install the Ubuntu runtime packages: Python 3 with its matching `python3-venv`, Docker Engine and its systemd service, `sudo`, `xvfb`, `xauth`, Wine with the supported version reported by `wine-guard.sh`, Caddy, `curl`, `sqlite3`, `procps`, `ca-certificates`, OpenSSL and `visudo`/sudo. Verify `/run` is tmpfs. The script does not silently add external repositories or open firewall ports.
3. Run `sudo bash scripts/linux-host/bootstrap-self-hosted-host.sh`. It creates the non-login `destinedfx` service identity, persistent directories, non-secret environment templates, and host-bound encrypted systemd credentials. Plaintext bootstrap material is created only under `/run` and is not printed. Existing configuration/credentials are preserved.
4. Install a genuine MT5 terminal and MetaEditor into a **clean, unpaired** Wine template prefix as the `destinedfx` service identity. First review the official Linux wrapper's SHA-256 with `install-mt5.sh --print-script-sha256`, then pass the reviewed value:
   
   ```sh
   sudo -u destinedfx -H bash scripts/linux-host/install-mt5.sh \
     --expected-sha256 REVIEWED_64_HEX_SHA256 \
     --wineprefix /var/lib/destinedfx/terminal-template-prefix \
     --headless
   ```

   The template must contain `drive_c/Program Files/MetaTrader 5/terminal64.exe`, `metaeditor64.exe`, and `MQL5/Include/Trade/Trade.mqh`. The host.env template points to that exact directory. Do not log into a broker in this shared template; each actual user session is created inside a separate per-pairing terminal tree and Wine prefix.
5. Edit `/etc/destinedfx/bridge.env` and set `BRIDGE_BASE_URL`, `BRIDGE_HOST`, and `EXPECTED_FORWARDED_HOST` to the same Bridge hostname. Set `APP_DOMAIN` to a different hostname for the app-management API, plus a real `CADDY_EMAIL` and the initial administrator address in `ADMIN_EMAILS`. Replace the example values. Point both DNS A/AAAA records to this machine and allow inbound TCP 80/443. The Caddyfile serves both APIs from this same host. Do not place tokens, passwords, private keys or database credentials in either env file.
6. The bootstrap creates a PostgreSQL password and Ed25519 signing key for management LIVE step-up as host-bound encrypted systemd credentials, and writes only the corresponding public key to `/etc/destinedfx/management-step-up-public.pem`. The PostgreSQL data is stored in `/var/lib/destinedfx-management/postgres`; its container listens only on `127.0.0.1:5433`. The management API listens on `127.0.0.1:8010`. The private signing key and PostgreSQL password are loaded into their specific services as read-only credentials. Back up the encrypted credential and the machine's systemd credential host key separately and securely; losing them prevents the Bridge from verifying new step-up assertions.
7. Run `sudo bash scripts/linux-host/rebuild.sh self-hosted --check` for static/source/unit validation. Once the MT5 template and DNS/configuration are ready, run `sudo bash scripts/linux-host/rebuild.sh self-hosted install`. Installation refuses to proceed if the old shared MT5 service is active, if encrypted credentials fail decryption, if either public hostname or the generated management verification key is invalid, if the local Postgres/API stack is not ready, or if the configured slots exceed CPU/RAM capacity. It does not stop a running trading process to force migration.
8. Verify the local and public endpoints: `https://BRIDGE_HOST/health/live`, `https://BRIDGE_HOST/health/ready`, `https://APP_DOMAIN/health`, `https://APP_DOMAIN/ready`, and `https://APP_DOMAIN/api/v1/auth/bridge-verification-key`. Also confirm `destinedfx-postgres`, `destinedfx-management-api`, Bridge, host agent, slot worker and Caddy service states. Readiness proves the host runtime is ready to accept authenticated work; it does not prove any user's broker login or an executed trade.

## Runtime and service boundaries

| Component | Runtime identity | Purpose |
|---|---|---|
| `destinedfx-host-agent.service` | root | Sanitized health, root-attested slot reservation and terminal-process identity |
| `destinedfx-postgres.service` | root-managed local Docker container | PostgreSQL 16 for app-management only; data is stored on this machine and the port is loopback-only |
| `destinedfx-management-api.service` | `destinedfx-api` | Account/session/preferences API and Ed25519 LIVE step-up issuer; local loopback port 8010 |
| `destinedfx-bridge.service` | `destinedfx` | FastAPI pairing/session control, encrypted broker credential store, health and order-control APIs |
| `destinedfx-provision-linux-worker.service` | root, oneshot | Uses the owner-only bootstrap credential to issue/rotate the named worker token and encrypt it |
| `destinedfx-linux-slot-worker.service` | root | Claims work and provisions isolated Unix identities, Xvfb displays, Wine prefixes and MT5 trees |
| `destinedfx-caddy.service` | `caddy` | Local TLS termination and reverse proxy; only binds the public web ports |

The worker runs as root only because it must create and attest separate locked Unix identities. The MT5 terminal itself is launched under each pairing's dedicated non-login UID, never under the shared Bridge identity. Root-agent validation independently checks the reservation, UID, Wine prefix, display, Xauthority and actual terminal process. The Bridge cannot promote a terminal to READY merely because a local process exists.

Systemd encrypted credentials are used for the host-agent token, Bridge Fernet key, worker bootstrap credential, per-host worker credential, PostgreSQL password and management signing key. Each service receives its own read-only credential file for its lifetime. Secret values are not written to ordinary environment files or command-line arguments. See the upstream [systemd-creds manual](https://www.freedesktop.org/software/systemd/man/latest/systemd-creds.html) and [systemd.exec credential documentation](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html).

## Two-user acceptance and trading acceptance

After two different authenticated DEMO pairings have been added through the app and fresh terminal heartbeats are present, run:

```sh
sudo /opt/destinedfx/venv/bin/python \
  /opt/destinedfx/scripts/linux-host/accept_two_user_runtime.py \
  --expected-users 2 \
  --owner-uid "$(id -u destinedfx)" \
  --state-dir /var/lib/destinedfx/host-runtime \
  --slots-root /home/destinedfx-slots \
  --host-agent-url http://127.0.0.1:8787/health/ready
```

The acceptance must prove distinct Unix UIDs, separate slot homes, terminal copies, Wine prefixes, Xvfb displays and process ownership. It must fail rather than count the same terminal twice. Capture the sanitized result and retain it with the host release evidence. This is a real-host acceptance command; a CI fixture is not a substitute.

Actual DEMO acceptance additionally requires two genuine broker DEMO profiles, successful authorization heartbeats, symbol/account identity matches, market-data availability, one approved DEMO market order, broker-reported fill, order reconciliation and safe close. LIVE acceptance is separate and must only occur after the user explicitly authorizes a specific LIVE test under their own authenticated broker profile. No source-code build, EA compile, readiness endpoint or simulated fixture proves a broker fill.

## Maintenance and recovery

- `/opt/destinedfx/scripts/linux-host/backup.sh [DATABASE] [BACKUP_DIR]` creates an atomic SQLite backup and SHA-256 sidecar for Bridge pairing/job state. Run it as the `destinedfx` service owner, for example with `sudo -u destinedfx`.
- Back up the management PostgreSQL database separately with `docker exec destinedfx-management-postgres pg_dump -U destinedfx_management -d app_management --format=custom`; store the dump on a protected persistent volume and test restore into a separate scratch database. The PostgreSQL container and data volume are on this same machine.
- Keep encrypted copies of both databases, the `/etc/credstore.encrypted` files, and the systemd credential host key in an operator-controlled recovery location. A same-disk backup does not survive host loss. Never upload plaintext databases or credentials into the repository.
- `/opt/destinedfx/scripts/linux-host/restore-test.sh BACKUP.sqlite3` checks the sidecar and SQLite integrity in a temporary directory. It never overwrites the live database.
- `/opt/destinedfx/scripts/linux-host/setup-unattended-upgrades.sh` enables security-origin updates and leaves automatic reboot disabled.
- `/opt/destinedfx/scripts/linux-host/host-doctor.sh` and the host-agent health endpoints provide diagnostics; health output must remain sanitized.
- `rebuild.sh self-hosted --check` validates source and units without touching services. Reinstall only after reviewing host-service changes and confirming the active sessions will not be interrupted.

The host-agent never returns account passwords, broker login credentials, tokens, signing keys or raw journal content. It only exposes sanitized health and terminal identity evidence. Preserve database backups, credential recovery, restore drills, disk monitoring, and security patching as ongoing operations.

## Legacy tooling

The legacy `linux-worker.py`, `destinedfx-linux-worker.timer`, `destinedfx-mt5.service`, and `destinedfx-xvfb.service` path is a **single-terminal** compatibility path. The self-hosted installer refuses to migrate while those legacy services or an unowned MT5 process is active, then disables the inactive shared-terminal units. Do not start the legacy worker on a host running the isolated slot worker. The Codespaces two-user scripts remain useful for isolated CI/sandbox tests, but Codespaces state is not production persistence.

## Official MetaTrader reference

MetaQuotes documents installing the desktop terminal on Linux using Wine: [MetaTrader 5 for Linux](https://www.metatrader5.com/en/terminal/help/start_advanced/install_linux). ARM is not accepted for managed MT5+EA execution until terminal, MetaEditor, EA heartbeat and broker DEMO acceptance are verified.
