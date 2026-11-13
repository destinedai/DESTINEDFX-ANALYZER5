# Linux MT5 host runtime

These owner-operated scripts add a one-terminal Linux host path. They do not prove that MetaTrader 5 or the DestinedFX EA works on any free VM shape. They do not create a broker account or submit an order. All log examples in tests are synthetic fixtures and are labelled as such.

## Host health and journal evidence

`host-agent.py` exposes sanitized JSON on `127.0.0.1:8787` at `/health` and `/health/ready`. It reports terminal-process liveness, memory, swap, disk, uptime, a bounded journal failure code, and a sanitized EA heartbeat identity. It never returns journal text, passwords, tokens, keys, or secrets. Journal parsing accepts BOM-marked UTF-16LE/UTF-16BE and UTF-8 fallback, recognizes the real `Network '<login>': authorization on <server> failed (<reason>)` shape, and uses only records newer than the active connect-attempt timestamp. With no active timestamp marker it reports `NO_ACTIVE_CONNECT_JOB`, so a previous login failure cannot be attributed to a new attempt.

`/health/ready` is healthy only when the terminal is alive, memory is above the configured floor, and the EA heartbeat is fresh and terminal-connected. The bridge uses this evidence in `BRIDGE_HOST_MODE=linux-host`; unhealthy or stale evidence refuses readiness and managed commands. The legacy `EA_HEARTBEAT_FILE` gate is not used by this Linux bridge mode.

## Linux connect-job runner

`linux-worker.py` runs as a one-shot service under the non-root `destinedfx` user. Its systemd timer polls every 15 seconds. It claims at most one provisioning job, writes a mode-0600 startup INI and EA preset, restarts only `destinedfx-mt5.service`, and waits for the bridge job-status endpoint. The bridge promotes the job to `READY` only after a fresh authenticated EA heartbeat matches the pairing identity and account mode. A local process launch or an unverified heartbeat is not enough.

The runner loads its bearer token from systemd's encrypted credential named `worker-token`, not from `worker.env`. The encrypted source path is `/etc/credstore.encrypted/destinedfx-worker-token.cred`; `/etc/destinedfx/worker.env` is for non-secret settings such as the bridge URL and stable worker ID. Generate the encrypted credential on the VM with `systemd-creds` and the host key. Never put the bearer token in a command argument, repository file, ordinary environment file, log, or Terraform variable.

The MT5 unit calls `launch-mt5.sh`, which passes a path to the private startup INI and never passes a password in process arguments. After the bridge reports identity-matched `READY`, or after the terminal is confirmed stopped on failure, the runner best-effort overwrites and removes the startup INI and short-lived EA preset. Its one-shot process then exits and releases its in-memory references. Python cannot promise cryptographic zeroization of immutable strings, and overwriting a file cannot guarantee erasure from SSD flash translation layers. These are residual limits, not claims of secure physical erasure. If the terminal cannot be stopped, the private configuration is preserved for operator review and the worker does not claim cleanup succeeded.

The worker service uses a sudoers rule restricted to start, stop, and restart of `destinedfx-mt5.service`. Install the sudoers drop-in with mode `0440` and validate it with `visudo`. The worker service must not receive general sudo rights.

## Host maintenance

`backup.sh [DATABASE] [BACKUP_DIR]` creates an atomic SQLite backup and a SHA-256 sidecar. Defaults are `/var/lib/destinedfx/bridge.sqlite3` and `/var/lib/destinedfx/backups`. The backup contains only the database. It does not copy environment files, encrypted credential files, host keys, or other secrets. The database may contain ciphertext that still depends on a separately retained encryption key. Backups stored on the same VM do not survive loss of that VM.

`restore-test.sh BACKUP.sqlite3` verifies the sidecar, restores into a private temporary directory, runs SQLite integrity checking, then deletes the test copy. It never replaces the live database. `rebuild.sh [all|terminal|bridge]` installs the packaged runtime and units without deleting application data or owner credentials. `rebuild.sh --check` performs non-root syntax and package checks only.

`setup-unattended-upgrades.sh` installs Ubuntu unattended-upgrades and configures security-origin updates. Automatic reboot is disabled. This does not upgrade every application or remove the provider's idle-reclamation risk.

`download-ea-release.sh URL SHA256 DESTINATION.ex5` accepts only a direct HTTPS GitHub release asset URL and requires an owner-reviewed 64-character SHA-256. It refuses mismatches before installing the compiled EA. No release is created by this repository task; supply a real published asset URL and checksum when one exists.

## Services and platform boundary

The existing units run Xvfb, MT5, the host agent, and the loopback-only FastAPI bridge under `destinedfx`. The additional worker service and timer perform connect jobs. `ensure-swap.sh` provisions the configured swap file idempotently, and `logrotate.d/destinedfx` bounds logs. No trading port is opened to the public Internet.

`install-mt5.sh --print-script-sha256` downloads the current Linux script from the official MetaTrader 5 help page and prints its SHA-256. Review the hash, then run `install-mt5.sh --expected-sha256 REVIEWED_HASH`. The official page says the platform runs on Linux through Wine. ARM is **UNVERIFIED** for MT5 with an EA; the installer never runs the x86 script on ARM.

Official MetaTrader sources:

- https://www.metatrader5.com/en/terminal/help/start_advanced/install_linux
- https://download.terminal.free/cdn/web/metaquotes.software.corp/mt5/mt5linux.sh
