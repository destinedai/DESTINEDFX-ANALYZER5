# Safe staged deployment guide

**Checked:** 2026-11-06. **Status:** preparation only. This document is not a deployment attestation. No broker account, Windows host, VPS, tailnet, or live order was accessed from the sandbox. Do not treat HTTP reachability, a pairing code, process presence, or a build as broker connectivity. `CONNECTED` requires a fresh authenticated EA heartbeat that matches the expected login, exact server, broker identity, and DEMO or LIVE mode.

## Selected network path

This guide selects private Tailscale Serve for the initial owner-operated Windows host. It is the only remote path described for the first stages. Do not also configure Cloudflare Tunnel, Funnel, public reverse proxying, or an internet-facing listener. The owner must first confirm that the existing Tailscale plan is permitted for the intended use. Tailscale states that Personal is for non-commercial use and lists Standard at $8 per user per month. No plan was bought or changed. If the existing plan is ineligible, stop and obtain the owner's written choice and approval before any paid option is used.[1]

The selected design is: Android phone and Windows host join the same approved tailnet; Tailscale Serve exposes a private HTTPS hostname to that tailnet; Serve proxies to the bridge at `http://127.0.0.1:8000`; the bridge itself binds only to loopback. The Windows host must not accept public inbound bridge traffic. RDP must not be exposed to the internet. Reach administration through the private tailnet only, with a tightly scoped ACL and NLA enabled. CISA recommends removing unnecessary remote access and securing necessary access through controls such as patching, a jump host, MFA, and monitoring. Microsoft recommends NLA for most environments and notes that Remote Desktop opens a port.[2] [3]

## Control checklist

### A. Network boundary

The bridge listener must be `127.0.0.1:8000`, never `0.0.0.0`, a LAN address, or a public interface. Verify on the Windows host with `Get-NetTCPConnection -LocalPort 8000 -State Listen`. Keep Windows Firewall enabled with its default inbound block behavior. Do not add an inbound rule for port 8000 or public TCP 3389. Use the owner's private Tailscale Serve route for the bridge and RDP. Use a distinct, non-admin trading account for routine terminal and worker execution. Keep a separately protected administrator account for maintenance. The supplied setup scripts have not been exercised on the owner's host, so the owner must independently check effective firewall rules, active listener addresses, Tailscale ACLs, and NLA before proceeding.[3] [4]

### B. Authentication and transport

Retain per-account bearer tokens and the bridge's existing account-bound authentication. The Android client has finite connect, read, write, and total call timeouts. The bridge and all reverse-proxy layers must enforce request-size limits, request timeouts, per-token rate limits, and authorization before parsing or acting on command payloads. Reject missing, invalid, expired, or mismatched identity with a non-success response. Never turn a service health response into account `CONNECTED` state.

Optional Cloudflare Access support is implemented but is not configured in this release. Android stores the service-token Client ID and Client Secret in encrypted preferences and attaches `CF-Access-Client-Id` and `CF-Access-Client-Secret` to bridge calls only when both values are present. At the origin, setting both `CF_ACCESS_TEAM_DOMAIN` (for example, `https://your-team.cloudflareaccess.com`) and `CF_ACCESS_AUD` enables mandatory `Cf-Access-Jwt-Assertion` validation on every request. The bridge checks the Cloudflare JWKS signature using RS256, the exact issuer, the application audience, and expiry. A partial environment configuration returns 503; a missing or invalid assertion returns 401. These paths have local regression tests.[6] [7]

Private Tailscale Serve remains the only selected path for this rollout. Leave the Cloudflare fields and origin variables unset for this path. Do not also expose a Cloudflare hostname, public reverse proxy, or internet-facing listener. If the owner later selects Cloudflare instead, stop and reconfigure the system as one path only, protect the hostname with a Service Auth policy, enter the service token on the phone, set and verify the origin variables, and prove that direct-origin bypass, missing assertions, invalid signatures, wrong audience, and expiry are rejected before sending broker credentials.[6] [7]

### C. Windows host

Before installation, create a separate standard Windows user for trading and worker tasks. Reserve local administrator membership for maintenance. Enable automatic security updates, Microsoft Defender, account lockout policy, NLA, Windows event auditing, and time synchronization. Install only the genuine broker-issued MT5 desktop terminal and the signed release artifact. Patch Windows, Python, MT5, and the app before each rollout stage. Configure the worker and terminal to start only after the host is patched and the owner has signed in; confirm that reboot recovery does not create duplicate terminals or duplicate orders. Configure an external uptime check for `/health/live` and an alert route that does not depend on Android remaining open.

The Windows installer and worker startup/recovery behavior have not been exercised on the owner's machine. Treat auto-start, watchdog behavior, updates, account separation, and event logging as owner verification items, not completed deployment evidence.

### D. Secrets and backup handling

Android account passwords and app bearer tokens must remain in Android encrypted storage. The bridge's Fernet key and worker token must remain in the Windows DPAPI-protected secret file and must never be committed, placed in logs, command-line arguments, or this chat. Store only redacted logs. Keep the EA WebRequest allow-list to the exact private HTTPS bridge origin. Do not use wildcard origins. Rotate the worker token and Fernet key only through a documented migration that preserves access to encrypted credentials; do not delete the DPAPI file as a recovery shortcut.

Use the existing SQLite online backup mechanism only after confirming backup files are encrypted at rest by the owner-approved host volume or encrypted backup target. Keep backup copies off the trading user's writable path. Run SQLite integrity checks. Before any upgrade, perform a real restore into a separate test directory, compare expected pairing and ledger state, and record the restore evidence. The sandbox has not performed a restore of the owner's database.

### E. Monitoring and readiness

An external monitor may check `/health/live` for process liveness. It must separately page the owner when `/health/ready` is not ready, the EA heartbeat exceeds the 15-second freshness threshold, broker identity differs, the worker is not `READY`, or a daily loss lock or emergency stop is active. Configure a notification channel that is independent of the app process. Android background work is not guaranteed to execute on time, so phone polling is not the sole monitor.

Only a fresh EA heartbeat with exact account login, broker server, broker identity, and mode can contribute to `CONNECTED`. The current app and bridge also require the verified pairing, worker readiness, terminal online state, and a fresh heartbeat. A successful HTTP request, a pairing token, or `/health/ready` response by itself is not proof.

### F. Rollback and stop controls

The app Emergency Stop requests `EMERGENCY_STOP` and stops new entries. It does not close existing positions. Verify a fresh EA acknowledgement and stop state; if the phone, bridge, worker, or EA is unavailable, stop the EA and close or manage positions manually in the genuine terminal according to the owner's broker instructions. A lost heartbeat must stop new EA entries within 15 seconds. The daily loss lock, duplicate prevention, one-percent maximum risk, net two-R after costs, news block, LIVE lease, and emergency stop must remain enabled. Do not clear a lock or resume after restart without reconciling broker history and current positions.

### G. Staged rollout and evidence gates

Every stage produces a dated evidence folder, for example `evidence/deployment/2026-11-06/stage-0/`. Redact account IDs and secrets. Record commit SHA, app SHA-256, logs, test output, decisions, and the operator's name. A stage is `PASS` only when every listed item is evidenced. Otherwise mark `NO-GO` and do not advance.

| Stage | Required checks and evidence | GO gate |
| --- | --- | --- |
| 0. Local tests and CI | Python bridge, managed-worker and EA tests; Android JVM tests; genuine MetaEditor compile; secret scan; APK checksum; final-commit GitHub Actions URLs. The Kotlin 60-candle fixture parity task remains open. | All required tests pass. EA headless runtime parity remains `BLOCKED_EXTERNAL` until genuine external MetaTrader evidence exists. |
| 1. Host, no broker | Windows patch/Defender/NLA/firewall audit; listener bound only to loopback; authorized tailnet and private Serve hostname; access from approved phone only; backup job and health monitor; reboot recovery with no broker configured. | No public inbound port, no account credentials, no broker terminal provisioned. |
| 2. One broker, investor-only | One broker DEMO account; read-only investor password; exact server and login; paired terminal; doctor report; app status card; fresh heartbeat; identity and mode match; no order permission. | Account identity and worker/EA freshness independently reconciled. Any mismatch is NO-GO. |
| 3. One owner-approved DEMO order | Owner's written order approval retained outside chat; one minimum-size DEMO order; capture accepted request, broker ticket, fill, SL/TP, history and reconciliation; verify STOP. | Full order lifecycle reconciles and risk gates are confirmed. |
| 4. DEMO soak | Five to seven consecutive days of DEMO telemetry; daily reconciliation; news and daily-loss lock evidence; duplicate tests; controlled service and host restart tests; backup and actual restore evidence; alerts tested. | No unexplained position, duplicate, stale heartbeat, missing alert, or reconciliation gap. Produce a broker-specific DEMO PASS file. |
| 5. LIVE, one broker only | Only after that broker's DEMO evidence is PASS. Owner confirms in writing, selects one broker, minimum lot, and starts a 15-minute LIVE lease. Verify account identity and mode immediately before action. Lease is not persisted across app, worker, EA, or host restart. | Owner approval, fresh identity match, DEMO PASS artifact, risk gates and manual monitoring all present. Otherwise LIVE stays disabled. |
| Next broker | Repeat stages 2 through 5 for the next broker using a separate isolated terminal and profile. | No broker inherits another broker's PASS. |

The app's LIVE selector is disabled until the current app process imports a broker-matching DEMO PASS JSON file. The file must include `broker`, `mode: DEMO`, `status: PASS`, `account_login`, `server`, `soak_days` from 5 through 7, `order_lifecycle_pass: true`, `daily_reconciliation_pass: true`, `restart_test_pass: true`, `risk_controls_pass: true`, and a non-empty `evidence_paths` array. The broker, login, and server must exactly match the saved DEMO profile. The app displays the imported file's SHA-256. This is a fail-closed format and identity check, not a digital signature or independent proof that the evidence paths exist. The owner and reviewer must validate the source evidence before marking the stage PASS. Import state is memory-only and clears when the app process restarts.

No live order was placed as part of this work. The sandbox cannot create the required real terminal, investor login, broker heartbeat, soak evidence, or owner approval. The owner must not skip or combine gates.

### Cloudflare Access service-token owner steps

Cloudflare Access is optional and remains disabled for the selected private Tailscale Serve deployment. If the owner separately chooses Cloudflare Access, create distinct Service Auth credentials for the phone, Windows worker/doctor, and EA. Configure an Access Service Auth policy for the bridge application and use each client's own Client ID and Client Secret. Cloudflare validates the client credentials at its edge and forwards a `Cf-Access-Jwt-Assertion`; the bridge validates that assertion. Never reuse or paste these values into chat, source control, command-line arguments, or logs.

For the Windows worker and doctor, run `managed-worker/windows/set-access-service-token.ps1` as the same Windows user that installed the stack. It updates only the optional `cf_access_client_id` and `cf_access_client_secret` fields in the DPAPI bundle. Do not edit or replace the Fernet key or worker token. Restart the DestinedFX owner-stack scheduled task after updating the bundle. The worker reads these values when the DPAPI-aware owner-stack launcher starts it; `windows/doctor.ps1` reads them from the same bundle when run. Neither prints them. The phone keeps its own token in Android encrypted preferences. Enter the separate EA token into EA inputs `InpCloudflareAccessClientId` and `InpCloudflareAccessClientSecret`; these are sensitive terminal inputs and must not be included in screenshots or shared logs.

Keep `/health/live` excluded from the Access application policy or allow it as an unauthenticated liveness path for the external uptime check. It reports process liveness only and does not report account readiness. All other bridge requests require a valid Access assertion when both origin variables are configured. Setting neither variable preserves the prior behavior. Setting only one remains a 503 configuration error. Missing or invalid assertions remain a 401. Validate missing, valid, invalid, expired, and wrong-audience cases before sending broker credentials.

## MetaQuotes Virtual Hosting alternative

MetaQuotes Virtual Hosting is not offered by this design. It runs a terminal and EA, but it does not host this bridge or managed worker. Its own migration documentation forbids DLL calls and explains that automated trading is always allowed after migration. The documentation describes configuring permitted WebRequest URLs but does not establish compatibility with this product's remote worker, external secret store, identity handshake, and emergency monitoring. Do not rent or migrate an account there until a separate EA-direct protocol, security review, and tests exist. Any MetaQuotes trial or purchase would require the owner's written approval first.[5]

## References

[1]: https://tailscale.com/pricing "Tailscale pricing and plan use"
[2]: https://www.cisa.gov/resources-tools/resources/exposure-reduction "CISA Internet Exposure Reduction Guidance, revised 2026-08-21"
[3]: https://learn.microsoft.com/en-us/windows-server/remote/remote-desktop-services/clients/remote-desktop-allow-access "Microsoft Remote Desktop access and NLA guidance"
[4]: https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/ "Microsoft Windows Firewall overview"
[5]: https://www.metatrader5.com/en/terminal/help/virtual_hosting/virtual_hosting_migration "MetaTrader 5 Virtual Hosting migration"
[6]: https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/ "Cloudflare Access Service Tokens, headers and rotation"
[7]: https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/ "Cloudflare Access JWT assertion verification, JWKS, issuer, and audience"
