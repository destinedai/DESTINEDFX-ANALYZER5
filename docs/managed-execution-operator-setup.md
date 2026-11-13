# Managed MT5 execution: operator setup required

The Android app can control an account only after a DestinedFX-operated service and MT5 terminal worker are deployed. This repository does not include cloud-provider credentials, a production HTTPS hostname, an operator worker token, a credentials-encryption key, a Windows host, or MetaEditor. Nothing here is currently deployed as a hosted trading service, and no broker login or order has been tested.

## One-time deployment actions

1. **Do not buy hosting for this repository's free setup.** The repository Render Blueprint is deliberately Free and non-durable and cannot run managed execution. The app, Analyzer, strategies and direct Deriv client do not require a paid Render service; direct Deriv has its own [acceptance runbook](deriv-acceptance-runbook.md) and is not the managed MT5 bridge. With no hosting budget, do not provision a Windows VPS or paid pool. MT5 managed DEMO acceptance must be deferred until an authorized durable bridge and genuine Windows MT5/MetaEditor host are available. An operator may use an already-owned Windows PC for local EA experiments, but that is not the managed bridge deployment and is not continuous cloud hosting.
2. **Confirm platform and broker permissions.** Install the genuine broker-specific MT5 terminal and MetaEditor on the host. Confirm with MetaQuotes and each supported broker that the proposed hosted, automated account access and terminal distribution are permitted. Some brokers require OTP/extended authentication that cannot be unattended. Investor access is read-only and must not enable the trading EA.
3. **Compile and demo-verify the EA.** Compile `mt5-ea/DestinedFXTraderEA.mq5` using MetaEditor for the intended MT5 build. Configure the terminal worker to launch the terminal with the documented startup configuration. Validate broker login, fresh heartbeat, START/STOP, an explicitly authorized demo order, SL/TP, reconciliation, and restart/revocation behavior before any live account is enabled.
4. **Deploy the HTTPS control plane.** Publish `bridge/` behind a trusted TLS endpoint with persistent database storage, backups, access logs that exclude request bodies and authorization headers, rate limits, and network isolation. Keep port 8000 private. Set `TRUSTED_PROXY_IPS` to the exact TLS ingress IP/CIDR; never use `*`, since an untrusted `X-Forwarded-Proto` must not turn plain HTTP into trusted HTTPS. Verify `GET /health` for liveness and `GET /health/ready` for database/configuration readiness. Set production `BRIDGE_DB_PATH`, `CREDENTIALS_FERNET_KEY`, `WORKER_TOKEN`, and public service URL as server-side secrets. Never put the worker token or encryption key in the APK or repository.
5. **Install the managed Windows worker.** Configure it with the service URL, worker token, and a stable unique `DESTINEDFX_WORKER_ID` in the Windows service environment; startup fails if the identity is absent rather than silently changing identity after a restart. Configure the terminal installation, compiled EA and capacity limits. Use per-account terminal data directories, Windows ACLs, encrypted disks, process supervision and cleanup. The worker must redact credentials, remove transient startup INI files after MT5 has consumed them, and stop new trades on stale/mismatched state. Set `HEARTBEAT_RECOVERY_GRACE_SECONDS` on the bridge to the tested stale-reconnection window; it starts when a READY heartbeat first becomes invalid, not from original provisioning.
6. **Configure and release the Android app.** Set `MT5_MANAGED_SERVICE_URL` to the deployed public HTTPS origin. Debug and release builds no longer embed a temporary tunnel: with no build-time value, the Bot screen stays `CONFIGURATION_REQUIRED` until an operator-approved origin is entered in the app. Release builds fail if the URL is blank, malformed, non-HTTPS, or an obvious placeholder. Verify credentials are sent only to that origin. Users should then need only broker login, master password and exact server once; there is no per-user API token or external-service setup.
7. **Operate leases and revocation.** Keep the Windows worker supervised and monitor failed/expired jobs. Worker leases renew during status polling and expired jobs may be reclaimed. Process inspection distinguishes an owned running process, definitely dead process, identity mismatch, and unknown inspection result; only definitely dead or successfully terminated owned processes may be restarted, quarantined, or deleted. Revocation is only marked complete after the recorded terminal process identity and owned directory cleanup are verified; missing ownership markers fail safely and require operator review. Do not report such failures as successful deprovisioning.

## Broker-specific terminal installations and worker capacity

The managed MT5 worker uses `broker_brand` as the sole canonical broker identity and accepts only FBS, EXNESS, HFM, and DERIV (the last means a genuine Deriv MT5 account, not Options/Multipliers). Brand values are normalized to uppercase at registration/claim and worker validation; there is no independent legacy `broker` field to reconcile. New bridge registrations with UNKNOWN are rejected. The terminal claim must match the registered broker company, exact user-supplied server, login, and DEMO/LIVE mode; a brand label alone is not verification.

For staged owner-PC installation, choose exactly one broker and supply only that broker's genuine MT5 and MetaEditor paths; the other FBS/Exness/HFM/Deriv terminals are not prerequisites. The owner-stack installer writes one **[broker_terminals.BRAND]** mapping. Later, use its explicit `-AddBroker` path to append another brand mapping only: it refuses duplicate brands, preserves the worker ID, DPAPI secrets, database, account directories, existing mapping and tailnet origin, and leaves existing scheduled tasks and running connections untouched. The added mapping takes effect on the next normal stack start; adding a brand does not create or migrate account profiles. Never copy one broker's installation path into another brand's entry or infer company/server identity from the selected label.

Set **max_terminals** to an integer from 1 to 16 that the host can actually support. Each account is provisioned into a separate portable terminal tree, so disk, RAM, broker policy, and Windows session limits matter. The worker reports its current active-terminal count; the bridge also counts in-flight provisioning leases for that worker ID to prevent simultaneous claim races. At capacity, new PROVISION jobs remain queued while revocation jobs are still allowed. Begin with one DEMO terminal, then increase the limit only after measuring the PC and validating simultaneous broker sessions.

The Android foreground account monitor reads each saved broker/mode profile independently and reports per-profile heartbeat freshness and risk warnings. It does not replace the Windows EA or host: autonomous MT5 trading still requires the dedicated Windows terminal to remain running and connected. Android notifications require the user to allow the app's notification/foreground-service operation. The generic Android BrokerManager is not evidence of concurrent broker execution.

## MT5 daily-loss baseline capture

The MT5 EA calculates daily account loss from **current account equity minus the persisted account equity captured for that broker-server day**. This is account-wide, not limited to DestinedFX's magic number, so manual trades and other EAs affect the daily loss measurement. At the 100% limit, the EA blocks new entries and attempts to close only DestinedFX-owned positions and pending orders; manual and other EA exposure is intentionally not closed.

The EA can initialize a missing baseline only during the first **120 seconds after the broker server's midnight**. It does not silently treat a midday equity observation as the start-of-day balance. If the EA was not running or could not safely capture equity in that window, daily-risk data is unavailable and new entries remain blocked until the next broker-server day. Keep the EA attached and its terminal connected through the broker's daily rollover. This is deliberately fail-closed; it is not a historical statement reconstruction.

## Hosting cost constraint

No paid VPS, MetaQuotes VPS trial, Render upgrade, persistent disk, or cloud subscription is required to install and inspect the Android APK or use local Analyzer functionality. The current free Render Blueprint is diagnostics/liveness-only and blocks execution-sensitive operations. A durable managed MT5 bridge and genuine Windows terminal are separate infrastructure requirements; under a no-spend constraint, leave managed MT5 in CONFIGURATION REQUIRED / NOT READY rather than creating a paid dependency or claiming it is active.

MetaQuotes documents a desktop-terminal startup INI that can contain login, server and password and can auto-start an EA. That makes the worker technically feasible, but the password appears in a plaintext transient configuration file. Production deployment therefore requires restrictive host ACLs, encrypted storage, isolated terminal data and process directories, careful file cleanup, and operational security review. See [terminal startup configuration](https://www.metatrader5.com/en/terminal/help/start_advanced/start).


## Quick deployment

1. Do not use the repository Render Blueprint for execution. It is a Free, sleeping, non-durable diagnostics service; `/health` is liveness-only, `/health/ready` returns `503 NON_DURABLE_STORAGE`, and execution-sensitive routes are blocked. No paid Render resource is implied or created.
2. For DEMO acceptance, deploy `bridge/` on separately authorized durable HTTPS infrastructure with persistent SQLite/WAL storage, backups, generated secrets, and the configured calendar provider.
3. On that durable deployment, open the exact `/health/ready` URL. It must return `{"status":"ready","database":"ok","managed_execution":"configured"}`. If not, do not submit broker credentials.
4. In DestinedFX → Bot → Remote MT5 EA Bridge, enter the public HTTPS origin in **Managed bridge HTTPS URL**, tap **SAVE BRIDGE URL**, then **CHECK CONNECTION**.
5. Download DestinedFX-Windows-Worker-Kit from GitHub Actions, extract it on Windows with desktop MT5 and MetaEditor installed, run launch-worker.ps1, enter the generated worker token at the hidden prompt, and add the bridge origin to the managed MT5 terminal's WebRequest allowlist.

A worker is READY only after the genuine EA sends a fresh authenticated heartbeat matching broker login, exact server, company, currency and account mode; the selected broker label alone is not evidence. HTTP reachability or a configured terminal path is not READY. Start with a dedicated DEMO MASTER profile. LIVE remains separate and requires the existing explicit 15-minute authorization.

## First one-broker DEMO checklist

Do not configure FBS, Exness, HFM and Deriv MT5 together for the first acceptance. The operator must choose one broker and fill in the broker-issued values below from the genuine terminal login screen; this repository deliberately does not guess server names, company identity or credentials.

- Selected broker brand: `FBS` / `EXNESS` / `HFM` / `DERIV` (choose exactly one)
- Exact broker company shown by the terminal: `________________`
- Exact DEMO server string: `________________`
- DEMO master login: entered directly into the app/terminal, never committed or pasted into chat
- Isolated portable terminal directory: `________________`
- Worker ID: stable unique `DESTINEDFX_WORKER_ID`, persisted across restarts
- Bridge origin: real public HTTPS origin, with `/health/ready` returning `status=ready`, `database=ok`, and `managed_execution=configured`

The scheduled task uses interactive logon and is **not a 24/7 service**. The owner must be signed in; the PC must remain powered on, awake (disable sleep/hibernation as appropriate), and connected to a working network. Logout, sleep, shutdown, Windows updates/reboots, or internet/tailnet loss interrupts execution. No Funnel, public exposure, paid VPS, or paid service is part of this setup.

Acceptance order: install the selected broker's genuine MT5 terminal and MetaEditor on an already-owned Windows PC; create the isolated portable terminal tree; compile and attach the EA; add only the bridge origin to the EA WebRequest allowlist; start the worker with the server-side worker token; verify the authenticated heartbeat shows the exact company, server, login, currency and `DEMO` mode; issue `START`; authorize one smallest-broker-accepted DEMO order; verify broker-confirmed order and SL/TP protection; issue `STOP`; and reconcile the order, position and history from the broker terminal. If any identity, heartbeat, protection, STOP or history check is ambiguous, leave the profile stopped and do not retry the order.

The first checklist can be completed on an already-owned Windows PC for local acceptance. Durable public HTTPS, persistent database/WAL storage, backups and a continuously reachable worker require separately authorized durable infrastructure. Do not use the free Render profile for execution and do not create paid infrastructure without explicit approval.

The 15-minute authorization above is specific to the managed MT5 path. Direct Deriv uses a separate, account/session-bound, process-memory REAL lease that expires after 60 minutes; see the Deriv runbook. Neither authorization policy means a REAL order has been broker-tested.

## Broker support boundaries

`BrokerDirectory` entries are name/server presets, not proof that a broker has a production execution adapter. Do not describe all listed brokers as supported or execution-ready. OANDA currently has no genuine broker execution adapter in this application; do not connect it or report it as supported. The direct Deriv Options path and managed MT5 path have separate setup and acceptance procedures.
