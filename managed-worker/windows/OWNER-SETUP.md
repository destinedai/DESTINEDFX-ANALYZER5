# DestinedFX owner-operated Windows stack

This kit runs the Bridge and MT5 worker on an owner-operated Windows computer. The installer registers password-logon Task Scheduler jobs for the same Windows owner identity, triggered at Windows boot and owner sign-in. This allows the Bridge supervisor and recovery jobs to run in a non-interactive background session while that owner is signed out; it preserves the owner-bound DPAPI profile rather than switching to LocalSystem or S4U. The installer asks once for the Windows account password so Task Scheduler can store its protected task credential. The password is not written to DestinedFX files. Keep the PC powered on, awake, connected to the network, and prevent sleep/hibernation. A powered-off, sleeping or network-disconnected PC remains unavailable. This is owner-operated hosting, not an independently managed VPS or cloud SLA.

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

The scheduled backup creates an integrity-checked SQLite snapshot, writes a checksum manifest, and wraps the complete snapshot in a Windows-user DPAPI protected archive. The unprotected staging directory and temporary archive are removed only after the protected file has been validated. Before upgrades or recovery, stop the stack and preserve `%ProgramData%\DestinedFX` including `data`, `accounts`, `config`, and `backups`.

Test a recovery into a separate directory before replacing live state:

```powershell
.\managed-worker\windows\restore-bridge.ps1 `
  -Backup 'C:\ProgramData\DestinedFX\backups\backup-YYYYMMDD-HHMMSS.dpapi' `
  -TargetRoot 'C:\ProgramData\DestinedFX-recovery-test'
```

The restore validates the DPAPI archive, every checksum, SQLite integrity, and the stable worker ID. It refuses to overwrite an existing target unless `-Replace` is explicitly supplied. Do not replace active state blindly. Never share the DPAPI file, Fernet key, worker token, account passwords, or logs containing sensitive data.

This restore is supported only on the same Windows host under the same Windows user profile that created the DPAPI backup. A new host or different user must use a separately designed owner-controlled migration envelope; copying the `.dpapi` ciphertext is not migration and is intentionally rejected as unsupported.

The non-interactive task configuration is source-tested but must still be verified on the owner PC after sign-out and reboot. The terminal/EA must independently prove it can remain connected and send a fresh heartbeat in that background session; a running scheduled task alone does not establish unattended MT5 readiness. This kit does not establish a production release signature, commercial Tailscale rights, a live broker connection, or genuine DEMO acceptance. Verify the actual Windows host and broker account locally before relying on it.
