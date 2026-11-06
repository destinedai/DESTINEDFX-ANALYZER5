# Owner quick start: Stages 1 and 2

Private Windows host and one read-only DEMO connection only. Do not place orders or select LIVE. `CONNECTED` requires a fresh EA heartbeat matching the exact account, server, broker identity, and mode.

## Stage 1: Windows host, no broker

1. Use an owner-authorized PC and a separate standard Windows account for worker/terminal tasks. Patch Windows; confirm Defender, Firewall, NLA, and time sync. Keep the PC awake and signed in. Do not expose ports 8000 or 3389 publicly.
2. Install Python 3.11+ and Tailscale. Join the PC and Android phone to the same approved tailnet. Configure private Tailscale Serve from the exact `https://<host>.ts.net` name to `http://127.0.0.1:8000`. No Funnel, public tunnel, or public bind.
3. From an elevated PowerShell window at the extracted owner-kit root, run with the exact hostname shown by `tailscale serve status`:

   ```powershell
   .\managed-worker\windows\install-owner-stack.ps1 -ProjectRoot (Resolve-Path .).Path -TailnetOrigin https://<exact-host>.ts.net
   ```

   Review prompts and supply real paths. Keep `%ProgramData%\DestinedFX`, DPAPI secrets, database, and backups intact. Stage 1 has no broker credentials or terminal.

## Stage 2: one broker, investor-only DEMO

4. Install one genuine broker-issued MT5 terminal and MetaEditor. Sign in to a DEMO account using the exact broker-issued server and read-only INVESTOR access. Do not guess server names.
5. In Android's Bot workstation, select that broker's DEMO profile. Enter the exact login, investor password, and server; select INVESTOR, review HTTPS credential consent, then tap **CONNECT**. Keep the phone on the tailnet. In MT5, allow only the exact Serve origin under **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**, attach the supplied EA, and verify a fresh heartbeat. Stop if identity or mode differs.
6. From the kit root, run the read-only doctor and save its JSON:

   ```powershell
   $cfg = Join-Path $env:ProgramData 'DestinedFX\config\worker.toml'
   .\managed-worker\windows\doctor.ps1 -Config $cfg -Broker FBS | Set-Content -Encoding UTF8 .\doctor-output.json
   ```

   Replace `FBS` with the selected broker. Redact account identifiers and local paths before sharing. `READY` is not permission to trade.

## Return and failure meanings

Send the **redacted** doctor JSON, a screenshot of the app's **VERIFIED CONNECTED ACCOUNT** card, and relevant MT5 **Experts** or **Journal** lines for EA startup/heartbeat. Mask identifiers and personal information. Never send passwords, pairing codes, bearer/Access tokens, DPAPI files, or raw logs.

| Result | Meaning / action |
| --- | --- |
| Bridge unreachable, `CONFIGURATION_REQUIRED`, or readiness `503` | Host, URL, storage, or service setup is incomplete. Check installer and private Serve before sending credentials. |
| `NO_LOCAL_TERMINAL_PAIRING` or `PAIRING_OR_AUTHENTICATION_REQUIRED` | No verified pairing. Finish the DEMO **CONNECT** step. |
| Doctor `BLOCKED`, worker not `READY`, or terminal offline | Provisioning, terminal/EA, WebRequest allow-list, or network is incomplete. Keep host awake; inspect sanitized logs. |
| Stale heartbeat or account/server/mode mismatch | Terminal identity is not verified. Correct the DEMO setup and wait for a fresh heartbeat. |

Stage 2 remains **NO-GO** until doctor, worker, heartbeat, and app identity agree. No LIVE authorization or order is part of this quick start.
