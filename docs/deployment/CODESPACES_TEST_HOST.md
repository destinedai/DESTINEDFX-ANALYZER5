# Codespaces DEMO test host

This runbook is for the owner-operated, ephemeral GitHub Codespaces host on branch `free/phone-only-v1`. It is a DEMO test host, not a 24/7 service and not evidence that a broker, terminal, EA, or order works. No account is created by this repository.

## MT5 startup status: UNVERIFIED

No Wine/MT5 combination has been validated as a working Codespaces terminal. On 2026-11-10, the inspected Codespace reported Wine `9.0~repack-4build3` from Ubuntu 24.04 `noble/universe`; the existing prefix reported `win64` and `Microsoft Windows 10` (registry build `19043`). The terminal executable was at `~/.mt5/drive_c/Program Files/MetaTrader 5/terminal64.exe`. The owner reported that MT5 still displayed “A debugger has been found running in your system,” despite no matching debugger process being present. This is a failing observation, not evidence that Wine 9 works or that Wine 10/11 do not.

The installer pin file verifies the official MetaQuotes Linux wrapper and the downloaded `mt5setup.exe` SHA-256/Authenticode signer. `mt5setup.exe` is a web installer; it can download or update the terminal independently. Those installer hashes therefore do not pin the installed MT5 build. The current build was not recoverable from the inspected terminal logs or executable metadata and is reported as unavailable. Do not call a live process, visible desktop, VNC connection, wrapper checksum, or passing CI test “MT5 terminal ready.” Only an owner-run Codespaces test that confirms the real terminal opens without the warning can change this status; no such evidence is recorded here.

The container uses Ubuntu's repository `wine` and `wine64` packages without asserting a compatible major version. The installation flow configures the existing private prefix at `~/.mt5` with `winecfg -v win10`; diagnostics read its architecture and Windows product version when available. Do not downgrade or pin Wine based on version alone. Test candidate combinations against the actual installed terminal build before changing the image policy.

Safe recovery preserves the existing prefix and user data:

1. Run `bash deploy/codespaces/stop.sh` to stop only recorded Codespaces/MT5 processes, then `bash deploy/codespaces/start.sh` to retry the existing terminal path.
2. Read the `MT5_DIAGNOSTICS`, `MT5_TERMINAL_PROCESS`, and `MT5_TERMINAL_READY` output. `VNC READY` only describes Xvfb/x11vnc/websockify. A nonzero process exit is reported separately; `NOT_VERIFIED` remains the expected result until an owner confirms terminal startup.
3. Keep the existing `~/.mt5` prefix and its installed data. Do not delete it, remove Wine debugger registry entries, disable MetaTrader checks, or kill unrelated processes. If testing another Wine/MT5 combination, use an owner-approved copy of the prefix and record both Wine package origin and the terminal build.
4. Return only redacted diagnostics and owner confirmation. Never include a VNC URL, pairing code, token, password, account identity, or broker data in CI or issue logs.

## Requirements

The owner needs a GitHub account with Codespaces access, an Android phone signed in to the same GitHub account, and an owner-controlled DEMO broker account. Use a 2-core Codespace. The repository devcontainer requests x86_64, no prebuild, and private forwarding by default. MT5 on Wine in a container remains UNVERIFIED until the owner returns a redacted connection report.

## Create and start

1. On the phone, open `https://github.com/codespaces`.
2. Tap **New codespace**, choose `destinedai/DESTINEDFX-ANALYZER5`, branch `free/phone-only-v1`, machine type with 2 cores, then **Create**.
3. In GitHub Codespaces settings, set the default idle timeout to 240 minutes. This is the maximum requested timeout, not a promise of uninterrupted runtime.
4. Wait for post-create to finish. It installs the pinned Linux wrapper, verifies `mt5setup.exe` with an Authenticode signer check for MetaQuotes Ltd, installs dependencies, and writes a private installer marker. The recorded setup hash warns on mutation; it is not the trust decision.
5. In the terminal run:

```sh
bash deploy/codespaces/start.sh
bash deploy/codespaces/first-run-check.sh
```

Run `bash deploy/codespaces/gui.sh` only if headless MT5 setup reports the documented owner step. The GUI requires authenticated GitHub CLI access so it can set port 6080 to Private before starting Xvfb, x11vnc, noVNC, or the installer. If the CLI is unavailable or visibility cannot be set, the route fails closed; set the port to Private in the Codespaces Ports panel and retry with an authenticated `gh` CLI. The port must not be made public. Its `VNC READY` message does not establish that the MetaTrader installer or terminal is ready.

## Expected startup

`start.sh` creates private state under `~/.local/share/destinedfx-codespaces`, generates credentials with mode 600, binds the bridge and host agent to loopback, starts the Linux worker loop, and derives the MT5 terminal, journal, EA preset, and Wine paths from the single terminal location. It exposes port 8000 publicly only after the K3 marker exists and a deliberately invalid token returns 401.

The command prints a bridge URL like:

```text
BRIDGE URL:   https://<codespace-name>-8000.app.github.dev
PAIRING CODE: <one-time code>
```

The pairing code is valid once for 10 minutes. Never place it in source, logs, screenshots, issue reports, or a returned evidence file.

## Self-check results

`first-run-check.sh` prints redacted MT5 diagnostics followed by redacted JSON and one `PASS`, `FAIL`, or `NOT_VERIFIED` line per component. It reports Wine version/package origin, prefix path/architecture/Windows product version, resolved terminal path, obtainable MT5 build, installer checksum/signer provenance, process status/exit code, and selected redacted startup errors. It checks VNC processes separately from terminal readiness, which remains `NOT_VERIFIED` without owner evidence. It also checks installer verification, terminal path, Wine prefix, Xvfb, host agent readiness, loopback bridge health, worker loop, public visibility, public health, bad and missing token rejection, optional authenticated state, pairing-record expiry, disk, RAM, idle timeout, and quota reporting.

A `FAIL` includes the next fix. `NOT_VERIFIED` means the owner must supply an owner-side fact, such as an app token or GitHub billing view. The script never prints tokens, passwords, pairing codes, or forwarded header values.

## Pair the phone and verify DEMO

1. In the app open **Settings > Connection**.
2. Enter the complete HTTPS bridge URL and the one-time owner pairing code.
3. Open **Bot > Accounts > Add account**.
4. Search for the exact broker brand. Select the verified broker specification or create an explicitly unverified manual profile. Never guess a server.
5. Open the official DEMO account page only if required by the broker. The app does not create the account.
6. Enter the owner-issued login, password, and exact server. Start with DEMO. Do not enable LIVE.
7. Confirm the verified account card shows the terminal-reported account, server, company identity, account mode, worker `READY`, and a fresh identity-matched EA heartbeat.
8. Accept DEMO only after that fresh read-only verification. Record the complete owner-side lifecycle separately. No order is claimed by this runbook.
9. Run `bash deploy/codespaces/verify-public-ready.sh` and return its redacted forwarding diagnostic with the connection report.

The Android client sends `X-Tunnel-Skip-AntiPhishing-Page: true` and `Accept: application/json` on bridge calls. If the first browser visit shows the Dev Tunnels interstitial, open the URL once in a phone browser and tap **Continue**. The app reports `BRIDGE_NEEDS_BROWSER_APPROVAL` instead of treating HTML as a successful connection. A recreated Codespace changes the host URL and requires re-pairing.

## Stop and inspect usage

When finished, stop the Codespace from the Codespaces page or run:

```sh
gh codespace stop -c "$CODESPACE_NAME"
```

Check usage at `https://github.com/settings/billing`. The host is not 24/7. Do not generate artificial load to avoid idle reclamation. The owner is responsible for the account quota and any provider eligibility.

## Troubleshooting

| Result | Fix |
|---|---|
| Installer signature invalid or missing | Do not bypass the check. Re-run post-create, inspect the official artifact, or use the private GUI owner step. |
| Terminal not installed | Run `bash deploy/codespaces/gui.sh` only when requested, then rerun `start.sh`. |
| MT5 displays the debugger warning | Do not assume a debugger is running or remove Wine/MetaTrader security checks. Read the diagnostic process names, Wine package origin, actual prefix version, MT5 build (if available), and redacted startup errors. Keep the existing prefix. Treat Wine/MT5 compatibility as unverified until the owner confirms the actual terminal build opens successfully. |
| VNC works but MT5 startup is unverified | This is not a successful terminal check. Use the `MT5_TERMINAL_PROCESS` exit status and redacted startup logs; request owner confirmation before changing the runbook status. |
| Host agent not ready | Inspect `~/.local/share/destinedfx-codespaces/runtime/host-agent.log`. Restart with `start.sh`. |
| Bridge health is not 200 | Inspect `runtime/bridge.log`; keep the port private until health passes. |
| Public port remains private | Confirm the K3 marker, then run `gh codespace ports visibility 8000:public -c "$CODESPACE_NAME"`. Leave the protocol as HTTP. |
| Bad or missing token is not 401 | Set port 8000 private and stop. Do not use the host for pairing until authentication is fixed. |
| Browser interstitial appears | Open the bridge URL once in the phone browser and tap **Continue**, then retry in the app. |
| App reports URL changed | Copy the new URL from `start.sh` and re-pair. Never reuse an old Codespace URL. |
| No fresh heartbeat | Verify the owner terminal, EA, worker `READY`, exact server, account identity, and WebRequest configuration. No values may be fabricated. |
| LIVE is blocked | This is expected on an ephemeral Codespaces host. Use DEMO only and follow the existing `ModeArmingPolicy`. |

## Evidence boundary

Automated CI proves the exact source revision and generated artifacts only. A successful self-check proves host diagnostics only. Deployment, broker login, heartbeat, DEMO acceptance, fills, reconciliation, and LIVE readiness remain `NOT VERIFIED` until the owner returns a redacted report. No order of any kind is claimed by this repository or runbook.
