# DestinedFX owner-operated Windows stack

This kit runs the bridge and MT5 worker on the owner's Windows computer. It is not a hosted service: keep the PC powered on, awake, connected to the network, and signed in to the Windows owner account for the scheduled tasks to run. A sleep, sign-out, shutdown, network outage, or PC restart can make the service unavailable. No VPS or public hosting is provisioned.

## Private network and setup

1. Use an owner-authorized Windows PC. Install Python 3.11+, the genuine broker-issued MT5 desktop terminal(s), MetaEditor, and Tailscale. Confirm the intended tailnet is authorized for your use; do not assume a Tailscale Personal plan permits commercial use.
2. Sign this PC and the Android phone into the same authorized tailnet. On the PC, configure **Tailscale Serve** to forward its private HTTPS hostname to `http://127.0.0.1:8000`. The installer accepts only the owner's `https://*.ts.net` Serve origin. Do not use Funnel, a public unauthenticated endpoint, or a LAN/public bind. Android must be connected to the same tailnet to use the private hostname.
3. From an elevated PowerShell prompt in the extracted kit, run `managed-worker/windows/install-owner-stack.ps1 -ProjectRoot <extracted-kit-root> -TailnetOrigin https://<exact-tailnet-host>.ts.net`, using the exact hostname shown by `tailscale serve status`. Review prompts and supply the actual installation paths. In Android, enter the exact broker-issued account server. Do not guess paths or server names. Start with an authorized DEMO account only.
4. The installer creates persistent owner configuration under `%ProgramData%\DestinedFX`, protects its owner secrets with Windows DPAPI, registers scheduled tasks, binds Uvicorn only to `127.0.0.1:8000`, and checks `/health/ready`. Keep the directory, database, backups, worker ID, and encrypted secrets intact. If recovery reports unsafe/inconsistent state, stop and preserve the directory for operator review; do not delete secrets or rotate tokens to force setup.
5. Add the exact private HTTPS Serve origin manually to the relevant MT5 terminal's **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**. A running process or queued job is not proof of readiness.
6. In Android, enter the same private hostname while the phone is on the tailnet. Verify exact broker identity, account login, account-issued server, DEMO mode, fresh terminal heartbeat, and worker READY separately. A slot or successful HTTP response alone is not connected readiness.
7. Perform DEMO acceptance only with permission: verify account identity and live terminal heartbeat; submit only a controlled DEMO order; confirm broker fill, SL/TP and trade history; then verify STOP at the broker. Do not use LIVE orders for setup or acceptance.

## Accounts and terminals

The installer currently provisions one account/terminal at a time and sets `max_terminals=1`. Never enter a guessed path, guessed server name, or credentials for another account in the current pairing. Use separate account identity, login, server, mode, pairing, and credential state. `worker.example.toml` includes a commented broker-terminal schema example; its sample paths are not verified installations, and this artifact does not claim that a second account has been provisioned or that a terminal is ready. Keep concurrency at one unless terminal isolation and available host resources have been explicitly verified.

## Recovery and backups

The scheduled backup copies the SQLite database and validates the backup integrity. Before upgrades or recovery, stop the stack and preserve `%ProgramData%\DestinedFX` including `data`, `accounts`, `config`, and `backups`. Restore only from a verified backup using the supplied recovery procedure; do not replace the active database or owner secrets blindly. Never share the DPAPI file, Fernet key, worker token, account passwords, or logs containing sensitive data.

This kit does not establish a production release signature, commercial Tailscale rights, a live broker connection, or genuine DEMO acceptance. Verify the actual Windows host and broker account locally before relying on it.
