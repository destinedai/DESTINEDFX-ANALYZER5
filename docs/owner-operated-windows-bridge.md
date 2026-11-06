# Owner-operated Windows bridge with private Tailscale Serve

This is a prepared first-install path, not a deployed service. It binds the bridge to Windows loopback only, stores the Fernet key and worker token in a DPAPI-protected file for the installing Windows user, and stores SQLite outside temporary directories. The scripts have not been executed on the owner's Windows PC; do not enter broker credentials until that PC passes the readiness checks below.

## Cost and privacy conditions

Tailscale Serve routes a local service to devices on the same authorized tailnet; it is not the public Funnel feature. The HTTPS certificate requires MagicDNS and HTTPS certificates enabled for the tailnet. Tailscale certificate names are recorded in the public Certificate Transparency ledger, although access remains governed by the tailnet ACLs. Use a nonsensitive Windows device name before enabling certificates.[1] [2]

Tailscale's current Personal plan is free but is stated to be only for non-commercial use. Since this project may be used for business, do not assume Personal is eligible. This setup makes no purchase and does not enroll or upgrade an account. Proceed only if the existing tailnet/service is already eligible under its terms; otherwise, with the no-new-paid-service constraint, leave the bridge unconfigured.[3]

## Windows first install

Use the owner's existing Windows PC, signed in as the same Windows user who will run the scheduled task. Install Python 3.11 or later, the genuine broker MT5 desktop terminal, MetaEditor, Git, and Tailscale from their official sources. Clone this project to a stable local path and retain the active integration branch. Do not use a transient download folder as the project, data, or backup directory.

Join the Windows PC and Android phone to the same owner-authorized tailnet. In the Tailscale admin DNS settings enable MagicDNS and HTTPS certificates, review the certificate-transparency hostname disclosure, and apply ACLs so only the intended phone/user can reach the PC. On Windows, confirm `tailscale status` shows the PC connected. Do not configure Funnel.

In an elevated PowerShell window, configure private Serve to proxy HTTPS to the bridge's loopback listener, then copy the `https://<windows-device>.<tailnet>.ts.net` hostname shown in `tailscale serve status`:

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:8000
tailscale serve status
```

The `--bg` option stores Serve configuration persistently. Serve accepts an HTTP reverse-proxy target at `127.0.0.1`; it does not require opening port 8000 to the LAN or Internet. The bridge's own HTTPS middleware permits loopback HTTP only when `ALLOW_LOOPBACK_HTTP=true`; the supervisor binds Uvicorn to `127.0.0.1`, and the app does not trust arbitrary forwarded-protocol headers.[1] [4]

Run the project installer from the elevated PowerShell session. Provide the project path and exact tailnet origin; the installer prompts for the existing MT5 install, MetaEditor/EA source, first broker brand, and stable worker ID. It creates a local Python venv, writes a one-account worker profile, generates Fernet and worker secrets, protects them with Windows DPAPI for the current user, registers a logon task, configures a daily backup task, starts the service, and probes `/health/ready` from Windows Python through the tailnet HTTPS hostname.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\managed-worker\windows\install-owner-stack.ps1 `
  -ProjectRoot 'C:\DestinedFX\DESTINEDFX-ANALYZER5' `
  -TailnetOrigin 'https://<windows-device>.<tailnet>.ts.net'
```

The installer is a first-install operation and deliberately refuses to run when a DPAPI secret file already exists; it would otherwise rotate the Fernet key and make previously encrypted broker credentials unreadable. Do not delete that file to bypass the guard. Secret rotation or restoring another user profile requires a deliberate data migration, not a second first-install run.

The scheduled task runs at the owner's Windows logon, not as `SYSTEM`, because the DPAPI secret is tied to the installing user. It supervises and restarts the bridge and worker processes. This means the owner must sign in after a reboot. The PC must remain powered on, awake, network-connected, and connected to Tailscale for service availability. Configure Windows power settings so it does not sleep while this service is needed.

## Readiness and network checks

Before sending any MT5 credentials, require all of the following:

1. During installation, the Windows Python probe reaches the exact private HTTPS origin and accepts either HTTP 200 with `status=ready`, `database=ok`, and `managed_execution=configured`, or HTTP 503 with `NO_VERIFIED_TERMINAL_PAIRING`. The latter is the expected safe state before a real terminal is paired. Final acceptance requires HTTP 200 plus `verified_terminal_pairing=healthy` after the fresh authenticated EA heartbeat.
2. `tailscale serve status` shows HTTPS on the expected `*.ts.net` host proxying to `http://127.0.0.1:8000`; it must not show a Funnel/public endpoint.
3. On Windows, check `Get-NetTCPConnection -LocalPort 8000 -State Listen`. The local address must be `127.0.0.1`, never `0.0.0.0` or a LAN/Tailscale interface. Do not add a Windows Firewall inbound allow rule for port 8000.
4. On the Android phone, connect the Tailscale app to the same authorized tailnet and open `https://<windows-device>.<tailnet>.ts.net/health/ready`. A failed TLS, DNS, network, or health check means no broker credentials are submitted.
5. In the active DestinedFX Bot screen, save the exact HTTPS origin and run **CHECK CONNECTION**. A passing service probe is not broker or worker readiness. Continue only after the authenticated managed account state reports the correct worker status and a fresh terminal heartbeat that matches the broker, account, exact server, company, currency, and mode.

The worker's EA must have the exact HTTPS origin entered manually under MetaTrader **Tools → Options → Expert Advisors → Allow WebRequest for listed URL** in every isolated terminal. A process launch, queued job, pairing code, HTTP probe, or successful compilation is not a terminal heartbeat or proof of a broker fill. Begin with a dedicated DEMO account and the project's full acceptance checklist.

## Storage, backups, and recovery

Persistent files are under `%ProgramData%\DestinedFX`: the bridge database is `data\bridge.sqlite3`, worker account trees are under `accounts`, encrypted credentials are protected by the bridge Fernet key, and the Fernet key/worker token are stored in `config\owner-secrets.dpapi` encrypted with Windows DPAPI. Files are ACL-restricted to the installing user, SYSTEM, and local Administrators. The worker token is not written to worker.toml, process arguments, or repository files. Bridge/worker logs have no HTTP access-body logging configured; keep logs local and do not capture broker credentials or terminal password dialogs.

A scheduled task creates daily online SQLite backups in `%ProgramData%\DestinedFX\backups`, retaining the newest 14 snapshots. Each snapshot contains an integrity-checked SQLite backup, DPAPI ciphertext, stable worker ID, and worker config. The DPAPI ciphertext can be decrypted only under the original Windows user profile on that machine. These local backups do not protect against disk loss, ransomware, or theft; use an existing BitLocker-protected external drive or other already-authorized encrypted backup location and verify restore procedures before relying on it.

To check tasks and health, use `Get-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker'`, `Get-ScheduledTask -TaskName 'DestinedFX Bridge SQLite Backup'`, `tailscale serve status`, and the `/health/ready` URL. If readiness returns `NON_DURABLE_STORAGE` or any non-200 response, keep account forms unused. Do not change bridge durability flags to make a failing deployment appear ready.

Before planned maintenance or revocation, stop the active Bot and revoke its pairing/authorization through the app's existing account controls. Then use `Stop-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker'`. To remove only this dedicated Serve configuration, use `tailscale serve --https=443 http://127.0.0.1:8000 off`; do not run `tailscale serve reset` if the node hosts any other Serve routes. Windows removal of the scheduled task or data must be an explicit owner action after a verified backup. Do not delete the database or DPAPI file as a troubleshooting shortcut.

## Current limits

This sandbox did not have the owner's Windows PC, Tailscale tailnet, MT5 terminal, MetaEditor, signing key, or Android SDK/emulator attached. These scripts and this architecture are therefore prepared but not Windows-tested, not deployed, and not broker-accepted. The installer supports one selected broker brand and one concurrent terminal by default. Additional broker installations/capacity must be configured against the worker's supported multi-terminal config and tested on the real PC; do not change `max_terminals` without checking the PC's disk, memory, isolation, and account policy.

## References

[1]: https://tailscale.com/docs/features/tailscale-serve "Tailscale Serve"
[2]: https://tailscale.com/docs/how-to/set-up-https-certificates "Tailscale HTTPS certificates"
[3]: https://tailscale.com/pricing "Tailscale pricing and plan eligibility"
[4]: https://tailscale.com/docs/reference/tailscale-cli/serve "Tailscale Serve CLI reference"

## Read-only MT5 connection doctor

After the installer and terminal setup, run the doctor from the worker kit; it never places orders, changes terminal settings, or prints passwords:

```powershell
$env:DESTINEDFX_WORKER_TOKEN = '<worker token from the owner secret store>'
.\managed-worker\windows\doctor.ps1 -Config 'C:\ProgramData\DestinedFX\worker.toml' -Broker FBS -PythonCheck -Login '<login>' -Server '<exact broker server>'
```

Use `-Broker FBS`, `-Broker EXNESS`, `-Broker HFM`, or `-Broker DERIV` for the corresponding isolated terminal mapping. `-PythonCheck` is optional and read-only: it calls MetaTrader 5 `initialize`, reads `account_info`/`terminal_info`, and shuts down without submitting an order. The password is requested with hidden console input only when the wrapper is given the login and server; it is never written to output or configuration.

A `READY` result requires the terminal executable and MetaEditor, the EA source, a worker-owned pairing marker, the exact WebRequest allowlist declaration, a reachable bridge, and a current authenticated worker/EA state. `/health/ready` intentionally remains `503 NO_VERIFIED_TERMINAL_PAIRING` after installation until a real terminal has claimed the pairing and sent a fresh identity-matched heartbeat. HTTP reachability, a running process, compilation, or a queued job alone is not readiness evidence.

The exact WebRequest origin still has to be added manually in every isolated terminal under **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**. The installer cannot safely edit this MetaTrader security setting. Begin with DEMO, then use the acceptance checklist before any order authorization.

## Cost and service boundary

This implementation uses the owner's existing Windows computer and tailnet. It does not create a VPS, paid Tailscale plan, broker account, or subscription. A PC that sleeps or loses power is unavailable; a zero-cost always-on, PC-off execution service is not claimed. Tailscale plan eligibility and commercial-use terms must be checked by the owner. If an always-on alternative is later selected, obtain explicit approval for the provider, Windows licensing, storage/backups, and recurring monthly cost before deployment.
