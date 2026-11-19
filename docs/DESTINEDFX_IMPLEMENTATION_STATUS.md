# DestinedFX implementation status

Updated: 2026-11-19

## Bridge and account security

- **Implemented:** per-pairing encrypted credentials, broker/login/server checks, DEMO/LIVE mode checks, fail-closed commands, fresh EA heartbeat requirements, signed worker attestation, nonce replay protection, expiry, host-generation binding, single-use job linkage, process/EA finalization, and READY gating.
- **Tested:** pending enrollment to worker reservation, signed claim, finalization, and READY transition; cross-authentication and replay rejection.
- **Implemented in this stage:** workers now use independently provisioned credentials keyed to a stable worker ID, with one-time issuance, short expiry, rotation, revocation, last-seen tracking, and claim/attestation/finalization/status worker-ID binding. The legacy shared-token path is disabled by default and is enabled only by explicit test fixtures.
- **Implemented in this stage:** the Windows supervisor now verifies the stored credential against the local Bridge before starting the worker, provisions the exact stable identity when absent, rotates only when rejected or near expiry, and persists the new credential through an atomic DPAPI rewrite. Legacy bundles receive missing fields through the same validated migration path.
- **Not production-proven:** this lifecycle has not yet been exercised on a persistent owner host.

## Execution workers

- **Windows worker:** has per-job terminal copies, per-job data directories, process ownership checks, restart/revocation handling, signed reservation claims, and final process-evidence finalization integrated with the bridge.
- **Linux worker:** retains a shared service-oriented terminal architecture. It refuses pending or attested managed jobs rather than running them through a shared terminal. Genuine Linux per-slot users, Wine prefixes, terminal copies, and resource allocation remain unimplemented; Linux managed MT5 execution must remain blocked.
- **Real verification:** no owner-controlled Wine/MT5 terminal or genuine broker account was available for runtime acceptance in this review.

## Android

- **Implemented:** phone-first managed broker flow, encrypted profile storage, explicit broker/mode identity, Bridge state polling, verified account/heartbeat gating, safe command authorization, Deriv and cTrader-specific flows, and Codespaces host discovery/Bridge verification UI.
- **Fixed in this stage:** MT5 registration sends the selected account mode to the Bridge; missing mode metadata previously caused the server to treat the request as unverified. Broker login switching also resets the login form scroll position to its header.
- **CI-tested:** Android JVM tests, debug APK build/signature/metadata/ABI/checksum checks, and clean API 34 emulator instrumentation all passed for the source commit recorded below.
- **Unverified:** installation and interaction on the owner's physical Android device, live Codespaces-to-phone pairing, real broker DEMO authentication, live quotes/orders, and isolated per-user noVNC sessions.

## Hosting and deployment

- **Implemented:** persistent Bridge Docker Compose templates, durable SQLite volume, Caddy/TLS owner stack, systemd host/worker units, backup/restore scripts, health/readiness endpoints, and Codespaces noVNC diagnostics.
- **Not deployed:** no paid VPS or persistent production worker has been provisioned or accepted. Codespaces remains an ephemeral DEMO/test host and is unsuitable as the sole always-on execution host.
- **Codespaces terminal:** CI diagnostics do not establish that the real Wine/MT5 terminal starts successfully. The owner must run the documented start and first-run checks in the actual Codespace and return redacted diagnostics; the MT5 debugger-warning observation must not be bypassed.

## Brokers and EA

- **Code paths:** HFM, Exness, FBS, and Deriv mappings exist; Deriv Options uses its separate API path; cTrader is assessed separately and remains read-only for trading.
- **Synthetic/automated verification:** broker mapping, identity, authorization, command, EA safety, adapter contracts, Bridge/worker regressions, Windows worker packaging, and genuine MetaEditor compilation are covered by CI.
- **Real verification pending:** broker-server authentication, terminal-reported account data, fresh EA heartbeat, one controlled DEMO order, attached SL/TP, close/history reconciliation, duplicate suppression, reconnect/restart, and LIVE behavior for every broker.

## Current release gates

- **DEMO:** implementation is not accepted until the owner-side host is running, per-user terminal isolation is independently attested, and a genuine broker DEMO lifecycle is completed with redacted evidence.
- **Linux isolation:** the current Linux worker remains fail-closed for managed MT5 jobs. The 3 GiB per-slot memory floor and Azure Students constraints remain enforced; a passing IaC workflow does not mean the Azure host has been deployed or can satisfy the runtime requirements.
- **LIVE:** server-enforced and blocked on ephemeral hosts; not enabled. No LIVE order or account acceptance is claimed.
- **APK:** the Build APK workflow completed successfully on source commit `7dfc23fafde566f7b91738af6286b47e5da9040d` (run [44322619785](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/44322619785)). The downloaded `DestinedFX-debug-apk` artifact contains `app-debug.apk`. Verified package: `com.aistudio.destinedfx.qwvzxp`; version `1.0.13` (code `13`); min SDK 24; target SDK 36; configured ABIs `arm64-v8a` and `x86_64`; APK v2 signature verified. The signing certificate SHA-256 is `28801d097eb0c208f8721a2ceaa107b8d4888cb08c2c77a1da094fcad7ad109b`. The downloaded APK SHA-256 is `47bf8631d06f9b10dc623914fc45bd8a30a18ef18a8c32cfa84ee2ac7c414420`. This is a debug APK, not a production-signed release.
- **CI evidence for that code commit:** Python/Bridge/managed-worker/EA tests, backend API and PostgreSQL migration/readiness tests, Azure IaC validation, secret scanning, genuine MetaEditor EA compilation, Windows worker-kit packaging, Android JVM tests, APK assembly/verification, and Android API 34 emulator instrumentation passed. The emulator job executed 7 instrumentation tests with 0 failures. Separate Codespaces deployment diagnostics, Wine guard regression tests, and official MetaQuotes installer-pin verification also completed successfully. These checks prove only the tested source and CI environment, not a live host or broker session.
- **Artifact evidence:** APK artifact ID `14435816756`, workflow-run ZIP digest `ca9fac5b20e3d2e3aa7696b770320d896bed3debc62373298111e26437aea4f7`; artifact metadata reported expiry at 2027-02-16 14:17:23 UTC when inspected on 2026-11-19.

## Owner-side acceptance still required

1. Install the debug APK on the physical Android phone and verify startup, encrypted storage, broker navigation/login switching, Bot workspace layout, and connection/error states. Report only redacted diagnostics.
2. Start the actual 2-core Codespace on `free/phone-only-v1`; run `deploy/codespaces/start.sh`, `first-run-check.sh`, and the documented public-readiness checks. Confirm the real MT5 terminal opens without the debugger warning. Do not expose the VNC port or share URLs/tokens.
3. Complete the Android Bot UI review against MT5 Android feature-by-feature: Quotes, Charts, Trade, History, account metrics, positions and pending orders, modification/closure, indicators/timeframes, Market Depth where supported, news, alerts, messages, and settings. Appearance alone is not parity evidence.
4. Implement and independently verify Linux per-user OS identities, Wine prefixes, terminal copies, resource allocation, host-agent/systemd integration, and signed root attestation before enabling any Linux broker registration or job execution.
5. On an owner-controlled DEMO account, complete broker login, identity-matched fresh EA heartbeat, quote verification, one explicitly approved DEMO order, broker deal/ticket evidence, SL/TP, close, history reconciliation, restart/reconnect, and duplicate-suppression checks. No broker credentials are requested in chat or stored in the repository.
6. Keep production deployment, persistent-host acceptance, management-to-Bridge trust configuration, production signing, and any reconsideration of LIVE separate from this DEMO/test work. Do not merge or mark the PR ready solely because CI is green.
