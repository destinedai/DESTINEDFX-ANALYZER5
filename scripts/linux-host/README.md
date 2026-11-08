# Linux MT5 host runtime

These scripts are additive host tooling. They do not prove that MetaTrader 5 or the DestinedFX EA works on any free VM shape. They do not create a broker account, submit an order, or print credentials.

## Host agent

`host-agent.py` is stdlib-only and exposes read-only JSON on `127.0.0.1:8787` at `/health` and `/health/ready`. It reports terminal process liveness, memory, swap, disk, uptime, sanitized terminal journal classification, and a sanitized EA heartbeat account identity. It never reads or emits passwords, tokens, keys, or secrets.

`/health/ready` is healthy only when the terminal process is alive, available memory is above the configured floor, and the EA heartbeat JSON is fresh and terminal-connected. The bridge uses this evidence in `BRIDGE_HOST_MODE=linux-host`; dead terminals, low memory, and missing or stale heartbeats refuse readiness and commands. The legacy `EA_HEARTBEAT_FILE` gate is not used by the Linux bridge.

## Services

The `systemd/` units run Xvfb, the MT5 terminal, the host agent, and the loopback-only FastAPI bridge under the non-root `destinedfx` user with restart policy and filesystem hardening. `ensure-swap.sh` provisions the required 2 GB swap file idempotently. Install `logrotate.d/destinedfx` for bounded logs. No unit opens a public trading port.

## x86_64 and ARM

`install-mt5.sh --print-script-sha256` downloads the current Linux script from the official MetaTrader 5 help page and prints its SHA-256. Review that hash, then run `install-mt5.sh --expected-sha256 REVIEWED_HASH`. The official page says the platform runs on Linux through Wine. ARM is **UNVERIFIED** for MT5 with an EA; the installer never runs the x86 script on ARM.

Official sources:

- https://www.metatrader5.com/en/terminal/help/start_advanced/install_linux
- https://download.terminal.free/cdn/web/metaquotes.software.corp/mt5/mt5linux.sh

No order was placed and no broker is verified by these scripts.
