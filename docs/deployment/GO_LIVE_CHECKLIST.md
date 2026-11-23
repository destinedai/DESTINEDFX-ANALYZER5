# R1 go-live checklist

This checklist is owner-operated. A green CI run proves only the exact commit's automated checks; it does not prove a broker login, terminal heartbeat, DEMO fill, LIVE readiness, or deployment.

## Supported host architecture

The production execution target is the persistent, owner-operated Ubuntu x86-64 runtime documented in [scripts/linux-host/README.md](../../scripts/linux-host/README.md). It uses root-attested per-pairing Linux identities and separate Wine prefixes, terminal trees, displays and Xauthority state. Codespaces is ephemeral and DEMO-only for development/CI. The Windows owner-worker remains a separate supported owner-operated path, but all accounts currently share the same Windows identity; separate `/portable` folders do not prove OS-level isolation, so Windows LIVE remains blocked until process identity and ACL isolation are independently verified.

The Linux deployment scripts and CI tests are implementation evidence only. No owner-operated persistent host, phone-to-Bridge pairing, or broker account is accepted by CI. Each must be independently verified by the owner.

## Phone-only Bot flow

- The default Bot path is Accounts, Brokers, Login, and the verified account workspace. Legacy bot tools are available under Settings > Advanced.
- MT5 mode selection is a login preference only. The terminal-reported mode is authoritative; a mismatch requires reconnecting under the reported mode.
- LIVE MT5 login defaults to Investor access. LIVE Trade access is blocked on an ephemeral host and is available only on a persistent host. Login never arms trading.
- Deriv Options and cTrader use their provider authorization and read-only verification paths. cTrader remains read-only in the app. A cTrader application that is not usable shows WAITING FOR SPOTWARE APPROVAL.
- Account values, positions, and history are shown only when returned by the verified provider or bridge. Missing fields are unavailable, not synthesized.
- Position close and pending-order cancellation require in-flow confirmation and the existing gated execution path. Emergency Stop remains persistent and does not liquidate open positions.
- Passwords and tokens are masked. Saving a credential requires encrypted storage and an owner-approved device credential. The cTrader client secret is not prefilled.
- Local fixture tests cover app behavior only. They are not broker authentication evidence. No broker connection is verified by this checklist, and no order was placed.
- Emulator screenshots for Accounts, Brokers, Login, and the verified account screen have not been captured in this environment. Do not substitute fixture renders for emulator evidence.

## Connection evidence matrix

The paths below are implemented in the app and covered by local fixture/state tests, but each remains BLOCKED BY OWNER STEP until the owner returns a redacted Connection report for that exact account and mode.

| Path | DEMO login | LIVE login | Current status |
|---|---|---|---|
| Deriv Options | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| Deriv cTrader | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| cTrader | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| MT5 FBS | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| MT5 Exness | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| MT5 HFM | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |
| Deriv MT5 | Implemented, fixture tested | Implemented, fixture tested | BLOCKED BY OWNER STEP |

## Before release

- Confirm the branch head and CI run are green for the exact commit.
- Review the Android debug APK artifact and SHA-256 from that run. It is a debug build, not a production signing release.
- Review `DestinedFX-MT5-EA-ex5` and its compiler evidence. Confirm the EA was compiled by genuine MetaEditor with zero errors.
- For the production Linux topology, follow the host install, local backup/recovery and APK endpoint-locking instructions in `scripts/linux-host/README.md`. Build the deployment APK from the host-generated public endpoint manifest. Do not expect the generic CI debug APK to contain a configured Management API or Bridge origin.
- Treat `DestinedFX-Windows-Worker-Kit` as the separate Windows owner-worker path only. It does not establish per-user Windows OS isolation; do not use it to claim multi-user LIVE readiness.
- Do not put credentials or tokens in source, CI variables, issue reports, or the APK.

## Owner-side DEMO proof

- Install the APK and use **Accounts > Add account**. Select the exact broker and enter the exact broker-issued server; never guess a server name.
- Begin with DEMO. Verify the terminal-reported login, server, company identity, account mode, worker `READY`, fresh EA heartbeat, and bridge identity match.
- Accept the exact DEMO account only after fresh evidence. Verify one complete lifecycle separately: entry, SL/TP, close, history/reconciliation, restart, and emergency STOP.
- Record a redacted evidence report containing commit SHA, APK/EA/kit checksums, broker brand, account mode, server, timestamps, heartbeat age, and result. Never include passwords, tokens, or private URLs.

## Python LIVE authorization tool

An optional owner-operated Python flow is available in `scripts/live_activation_preflight.py`. It uses the same existing Bridge challenge and Management password-step-up endpoints as the Android flow; it does not create a bypass.

After installing the repository's Python dependencies in a trusted owner-controlled environment, provide the exact user's LIVE profile app-pairing bearer token either through the hidden prompt or through the environment variable `DESTINEDFX_APP_BEARER_TOKEN` from an approved local secret store. Never put that token in the command line, source, shell history, issue reports, or chat. The tool does not extract secrets from Android's encrypted app storage; when the bearer cannot be supplied safely, use the in-app LIVE authorization flow instead.

Run:

```sh
python scripts/live_activation_preflight.py \
  --management-url https://<management-api-origin> \
  --bridge-url https://<private-windows-bridge-origin> \
  --arm-live
```

The script refuses to request credentials unless Management readiness, the Ed25519 public-key match, a persistent durable host, configured news provider, verified per-user terminal isolation, two simultaneously active isolated DEMO/LIVE terminal slots, and fresh authenticated MT5 readiness all pass.

A persistent Linux host may use the root-attested per-pairing slot runtime: each pairing is launched under its own non-login Unix identity, private Wine prefix, terminal tree, Xauthority cookie and display. The Bridge and slot worker allow LIVE only when `BRIDGE_HOST_PROFILE=PERSISTENT`, `BRIDGE_HOST_MODE=linux-host`, the root agent reports verified isolation, and the linked DEMO and LIVE terminals are both active. Codespaces remains explicitly `EPHEMERAL` and is DEMO-only. Do not change its host profile or bypass its expiry gate.

The current Windows owner-worker still creates separate `/portable` directories but launches all accounts under the same Windows identity; this does not prove OS-level isolation. Windows LIVE remains blocked until process SIDs/tokens and account-specific ACLs are independently verified. The script then asks for Management account email, hidden password reauthentication, and the exact account-specific `ARM LIVE <BROKER> <LOGIN>` phrase.

On success it reports only the 15-minute account-bound lease expiry and a Management-session logout result. Tokens, passwords and signed assertions remain in memory; authenticated redirects are refused. The script does not submit, retry, close, cancel, or modify any broker order. An unsuccessful Management logout is reported as a warning because the already-issued lease can remain active until its 15-minute expiry.

Do not mark LIVE production-ready merely because this command succeeds: the broker account's own acceptance, risk/news freshness, actual order lifecycle, and operational recovery still need owner verification.

## LIVE gate

- LIVE is not enabled by login. It requires the existing `ModeArmingPolicy`, fresh exact identity evidence, risk/news/session checks, typed confirmation, an active process-local lease, and a persistent owner host.
- Do not attempt LIVE on an ephemeral/test host. Confirm the owner host profile, backup/recovery path, terminal process identity, and emergency-stop procedure first.
- The owner must explicitly approve any live action; this repository does not create accounts, reset passwords, submit orders, or claim external connectivity.

## Prerelease and production release — HOLD

Do not create or push a `host-v*` tag and do not publish a GitHub Release at this stage. The prerelease job in `.github/workflows/build-apk.yml` is deliberately disabled with `if: false`. Keep it disabled until all seven acceptance stages are independently complete for the same candidate, including physical phone-to-Bridge pairing, authenticated MT5 DEMO telemetry, and supervised DEMO order/position acceptance.

For repository work, remain on `feat/self-hosted-linux-host-runtime` and verify its current remote HEAD before changing anything. `free/phone-only-v1` is a separate divergent branch: do not switch to it, merge it, or use it as the deployment source.

A debug APK, compiled EA, Windows worker archive, green CI run, or packaging job is not a production release and does not prove a deployed service or broker connection. Revisit release publication only after owner acceptance and a separate review of the production signing/release workflow.
