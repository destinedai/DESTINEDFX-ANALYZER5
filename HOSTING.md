# DestinedFX hosting and execution policy

## Production architecture and current status

The production execution target is an **owner-operated, persistent Ubuntu Linux host** using the self-hosted per-pairing runtime in [scripts/linux-host/README.md](scripts/linux-host/README.md). That implementation uses a root-attested host agent, a dedicated non-login Unix identity for each authenticated pairing, a separate Wine prefix and MT5 terminal tree per pairing, encrypted systemd credentials, persistent local storage, a local PostgreSQL management database, and Caddy as the only public ingress.

This is an implemented deployment path, **not a claim that an owner host has already been installed, deployed, or accepted**. Repository CI validates code and fixtures; it does not create the operator's host, configure the operator's DNS, authenticate a broker account, or prove a broker order.

**Owner-reported operating window:** the owner reports that the separately created persistent host is available for at least two hours per day. This is operator-provided information, not CI-verified uptime. At each session verify Bridge /health, durable database readiness, actual MT5 account identity and a fresh terminal heartbeat. Two hours per day is not continuous availability.

The production target does not rely on Azure, Oracle, Railway, Render, Cloudflare Workers, Codespaces, or a third-party hosted MT5 execution vendor. PostgreSQL runs locally on the same operator-controlled Linux machine.

## Supported hosting paths

### Persistent self-hosted Linux runtime — production target

The currently supported baseline is Ubuntu 22.04 or 24.04 on x86-64, systemd 250+, a genuine x86-64 MT5 terminal and MetaEditor under the reviewed Wine setup, Xvfb/xauth, Caddy, Python, Docker Engine for the local PostgreSQL container, and persistent storage. ARM MT5/EA execution is not accepted by the installer.

The machine must have two operator-controlled DNS names: one for the Bridge and a separate one for the Management API. Both point to the same host. Caddy terminates public HTTPS on ports 80/443; do not expose the Bridge loopback listener, host agent, database, Wine display, or MT5 terminal ports publicly. See the Linux host guide for exact sizing, installation, endpoint manifest, systemd credential, backup, recovery, and two-user acceptance commands.

Each pairing is provisioned under its own locked Unix identity and private terminal runtime. LIVE is not enabled by host selection or login; it remains subject to the existing account authentication, exact broker identity, DEMO/LIVE pairing, fresh host-attestation, risk/news/session, explicit confirmation, and order-reconciliation gates. A persistent host configuration or passing CI test is not proof that these gates have been satisfied.

### Windows owner-operated worker — limited path

The repository also contains an owner-operated Windows Bridge/worker kit. Follow [managed-worker/windows/OWNER-SETUP.md](managed-worker/windows/OWNER-SETUP.md) only when using that Windows-specific path. It runs under the Windows owner's identity and protects its owner state with DPAPI. Separate MT5 `/portable` directories do **not** establish separate Windows OS identities or prove per-user process isolation.

Until per-account Windows process SIDs/tokens and account-specific ACLs are independently verified, this Windows path must not be represented as a multi-user isolated LIVE host. Windows LIVE remains blocked by that isolation limitation. Do not describe the Windows kit as the primary production topology or as equivalent to the Linux per-pairing runtime.

### GitHub Codespaces — development and test only

Codespaces is **EPHEMERAL and DEVELOPMENT ONLY**. Use it for source changes, CI, fixtures, and supervised DEMO experiments. A DEMO order is accepted only when `EXECUTION_STORAGE_DURABLE=true` and durable database readiness is verified, alongside MASTER access, broker identity, fresh heartbeat, risk, quote, protective-order and reconciliation checks. If storage is non-durable, execution remains blocked. LIVE is never available on EPHEMERAL; Codespaces is not the production trading host or persistent multi-user runtime.

## Linux host setup

1. Review [scripts/linux-host/README.md](scripts/linux-host/README.md) at the exact commit intended for deployment.
2. Prepare the supported Ubuntu x86-64 machine, persistent disk, DNS names, firewall, and operator access.
3. Run the documented bootstrap and self-hosted installation scripts on that host. Use the documented owner-only interactive process to handle setup credentials. Never put passwords, tokens, private keys, signing material, or broker credentials in Git, ordinary environment files, CI logs, chat, or APK resources.
4. Install the genuine MT5 terminal and MetaEditor into the clean shared template prefix without logging a broker account into that template. Real pairing terminals are created separately under each pairing's private runtime.
5. Configure the Bridge and Management API hostnames in the documented host configuration. Keep internal listeners and the database loopback-only; Caddy is the only public ingress.
6. Build a deployment APK from the host-generated public endpoint manifest using `scripts/linux-host/build-self-hosted-apk.sh`. That build verifies the public endpoints and Management Ed25519 key and locks the verified origins into the APK. The normal CI debug APK is a DEMO-test artifact and is not evidence of a deployed or reachable service.
7. Complete the real-host two-user acceptance command in the Linux host guide and follow docs/deployment/LINUX_HOST_ACCEPTANCE.md. It requires a live Bridge profile of persistent Linux plus root-attested slot capacity, distinct UIDs, slot homes, terminal trees, Wine prefixes, Xvfb displays, Xauthority files and terminal process ownership. A CI fixture is not a substitute.
8. Separately complete owner-side broker DEMO acceptance: authenticate each user's own DEMO account, verify terminal-reported identity and fresh EA heartbeats, and reconcile an explicitly supervised DEMO order lifecycle.

The installer preserves existing configuration and refuses unsafe migration conditions. Review its documented checks and recovery boundaries rather than disabling them to force installation.

## Connection and operational evidence

Keep these states separate:

- Android application built and installed.
- Management API configured and reachable.
- Bridge configured and reachable.
- Persistent host supervisor available and attested.
- Per-pairing MT5 terminal available.
- EA heartbeat fresh and authenticated.
- Broker login authenticated and broker identity/mode verified.
- DEMO order accepted, filled, reconciled, and safely closed.

A DNS lookup, TCP 443 check, HTTP 200, running process, saved credential, APK build, EA compile, or fixture test is not proof of broker authentication or an executed order. A pairing is not READY until the authenticated terminal and broker evidence matches the exact user, account, server, and mode.

## Safety and recovery

Begin with an explicitly authorized DEMO account. Login does not arm trading. Keep the existing risk, protective-order, news freshness, quote freshness, session, authentication, isolation, and explicit-confirmation checks.

Emergency STOP is an execution-control stop; **it does not liquidate or close existing open positions**. Manage and reconcile those positions through the separately confirmed position-management path. Never describe STOP as an automatic liquidation safeguard.

Keep encrypted systemd credentials, host state, terminal data, and local PostgreSQL backups protected. Follow the Linux guide's independent recovery-copy and restore-test procedures. A backup timer on the same disk does not protect against host or disk loss.

## Release boundary

CI artifacts named **DEBUG APK - FOR DEMO TEST ONLY - LIVE LOCKED FOR SAFETY** are for controlled DEMO validation, not production release. Exact-source APK metadata, certificate verification and stable debug-key provisioning are documented in docs/deployment/APK_SIGNING.md. GitHub Release/prerelease publishing remains disabled. Do not tag or publish a release until the exact candidate has passed Android JVM tests, emulator instrumentation, physical phone-to-Bridge pairing, authenticated MT5 DEMO telemetry, and supervised DEMO order/position acceptance. LIVE remains independently gated and is not authorized by these steps.
