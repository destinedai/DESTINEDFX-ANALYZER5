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
- **Fixed in this stage:** MT5 registration sends the selected account mode to the Bridge; missing mode metadata previously caused the server to treat the request as unverified. Broker login switching also resets the login form scroll position to its header. The Bot Accounts heading is now constrained to one line on narrow phone widths following the 320dp screenshot review.
- **Test maintenance:** storage tests now assert the encrypted-preferences fail-closed policy when Robolectric has no Android Keystore; MT5 login tests match the current explicit verification copy and scroll to off-screen verification text/DEMO controls before interaction. No production safety gate was weakened.
- **Current source validation:** commit `f63a9f5980340d4d3dad917e50527e12fec76687` passed 402 Android JVM tests and produced a signature/metadata/ABI/checksum-verified debug APK in Build APK run [44509927948](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/44509927948). Clean API 34 emulator instrumentation for that same commit was still in progress when this document was refreshed.
- **Feature parity record:** see [Android vs MT5 Android feature parity audit](android-mt5-parity-audit.md) for verified implementation status, gaps and next engineering steps.
- **Unverified owner runtime:** installation and interaction on the owner's physical Android device, live Codespaces-to-phone pairing, real broker DEMO authentication, live quotes/orders, and isolated per-user noVNC sessions.

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
- **APK:** the current branch app source commit `f63a9f5980340d4d3dad917e50527e12fec76687` completed Build APK run [44509927948](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/44509927948). Artifact `DestinedFX-debug-apk` ID `14521437968` contains `app-debug.apk`. Verified package: `com.aistudio.destinedfx.qwvzxp`; version `1.0.13` (code `13`); min SDK 24; target SDK 36; ABIs `arm64-v8a` and `x86_64`; APK v2 signature verified. Signing certificate SHA-256: `82fc416ba463d7c4a174094d00b2131fbdaa3c82b5220b4c94f8970f124d338a`. Extracted APK SHA-256: `9ed5922a6881f563228d337b96a1990e4044f97efbe226c2359d581df51e55e2`. Artifact ZIP SHA-256: `96c10614dbcef299675e6a8db6b3642bd51c6ad0760b13276b18859e3de33d99`. This is a debug APK, not a production-signed release.
- **CI evidence for current app source commit:** 402 Android JVM tests executed with 0 failures; APK assembly, signature, package/version, target/min SDK, ABI and checksum verification passed. Python/Bridge/managed-worker/EA tests, backend API and PostgreSQL migration/readiness tests, Azure IaC validation, secret scanning, genuine MetaEditor EA compilation and Windows worker-kit packaging also passed. The clean API 34 emulator instrumentation job was still in progress when this document was refreshed, so the current source has not yet been credited with that final runtime check. Separate Codespaces deployment diagnostics, Wine guard regression tests and official MetaQuotes installer-pin verification for the same head passed. These CI checks do not prove physical-phone installation, a live host or broker session.

## Owner-side acceptance still required

1. Install the debug APK on the physical Android phone and verify startup, encrypted storage, broker navigation/login switching, Bot workspace layout, and connection/error states. Report only redacted diagnostics.
2. Start the actual 2-core Codespace on `free/phone-only-v1`; run `deploy/codespaces/start.sh`, `first-run-check.sh`, and the documented public-readiness checks. Confirm the real MT5 terminal opens without the debugger warning. Do not expose the VNC port or share URLs/tokens.
3. Complete the Android Bot UI review against MT5 Android feature-by-feature: Quotes, Charts, Trade, History, account metrics, positions and pending orders, modification/closure, indicators/timeframes, Market Depth where supported, news, alerts, messages, and settings. Appearance alone is not parity evidence.
4. Implement and independently verify Linux per-user OS identities, Wine prefixes, terminal copies, resource allocation, host-agent/systemd integration, and signed root attestation before enabling any Linux broker registration or job execution.
5. On an owner-controlled DEMO account, complete broker login, identity-matched fresh EA heartbeat, quote verification, one explicitly approved DEMO order, broker deal/ticket evidence, SL/TP, close, history reconciliation, restart/reconnect, and duplicate-suppression checks. No broker credentials are requested in chat or stored in the repository.
6. Keep production deployment, persistent-host acceptance, management-to-Bridge trust configuration, production signing, and any reconsideration of LIVE separate from this DEMO/test work. Do not merge or mark the PR ready solely because CI is green.
