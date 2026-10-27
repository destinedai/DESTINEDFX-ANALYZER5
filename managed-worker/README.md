# DestinedFX managed MT5 worker

This Windows-only worker consumes the managed execution API in `../docs/managed-execution-protocol.md`. It claims encrypted broker credentials using the operator-only bearer, stages and compiles the EA, writes a short-lived MT5 startup INI, and launches one isolated `/portable` terminal tree for that account. The worker never places trades itself or logs broker credentials. The control plane only reports READY after a fresh, account-matched EA heartbeat; MASTER additionally needs actual trade permission. INVESTOR sessions may be reported ready for read-only viewing but START is always rejected server-side.

## Prerequisites and configuration

Use Python 3.11 or later on a dedicated Windows Server host. Install a genuine broker-compatible MT5 terminal and MetaEditor. Configure `worker.example.toml` with the public HTTPS control-plane URL and absolute paths for the MT5 install, terminal executable, MetaEditor, approved EA source, and worker data root. Copy to a local `worker.toml`; it contains paths only and must not be committed. Supply `DESTINEDFX_WORKER_TOKEN` from the host secret store, never the command line or config file. Set a stable unique `DESTINEDFX_WORKER_ID` per worker instance. Keep capacity to one provisioned account per worker/host unless the operator has deliberately tested and sized a multi-terminal host.

The control plane must have `BRIDGE_BASE_URL` set to its real public HTTPS origin, plus a Fernet-compatible `CREDENTIALS_FERNET_KEY` and strong `WORKER_TOKEN`. The app APK must be built with `MT5_MANAGED_SERVICE_URL` set to that same HTTPS origin. No worker token or encryption key belongs in Android or Git.

## Operation and security

Test the worker's cross-platform validation/configuration suite on Linux or Windows with:

```sh
python -m unittest -v
```

On Windows, run one cycle with `python worker.py --config worker.toml --once` or as a supervised Windows service with `python worker.py --config worker.toml`. The worker polls for queued jobs, reports PROVISIONING and AUTHENTICATING, and waits for the backend to confirm actual terminal heartbeat readiness or timeout. It handles pairing revocation by stopping only the terminal PID recorded by the worker after verifying its executable path, then removes that account's local terminal tree. Do not run two copies with the same worker ID.

Each account gets a separately copied MT5 installation and portable data directory; this consumes disk and RAM. Use a dedicated non-administrator service account, restrictive Windows ACLs, BitLocker/equivalent encrypted volumes, encrypted backups, process supervision, and tested recovery procedures. Python's POSIX mode bits are not a substitute for Windows ACLs. The MT5 startup INI contains the broker password in plaintext briefly and is deleted after terminal launch; MT5 may persist account authorization in its own data directory, which remains sensitive. Do not capture HTTP bodies, authorization headers, process arguments, or raw terminal logs without redaction.

## Limits and go-live checks

This environment does not include Windows, an MT5 terminal, MetaEditor, a production HTTPS service, or host credentials. The worker has only been tested with fake terminal/compiler executables on Linux; the EA has not been compiled here and no real or demo account has been authenticated. The worker supervises initial provisioning and revocation, but a production deployment still needs host-level monitoring/restart policy for a terminal that exits after provisioning, capacity enforcement, alerting, key rotation and recovery drills. A stale terminal heartbeat disables new app START commands; it does not close existing positions. Test only with an authorized demo account until broker permissions, terminal lifecycle, order execution and failure recovery are independently verified.

MetaQuotes documents `/config` startup with account login, server, password, and EA auto-start parameters; the official terminal is therefore technically automatable on Windows. This is distinct from MetaQuotes Virtual Hosting, which is rented/migrated through an MT5/MQL5 user workflow and cannot be assumed to be a free, centrally provisioned DestinedFX fleet. See the platform references in `../docs/mql5-platform-notes.md` and cost/setup notes in `../docs/managed-execution-operator-setup.md`.
