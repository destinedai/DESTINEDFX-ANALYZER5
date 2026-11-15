# DestinedFX implementation status

Updated: 2026-11-15

## Bridge and account security

- **Implemented:** per-pairing encrypted credentials, broker/login/server checks, DEMO/LIVE mode checks, fail-closed commands, fresh EA heartbeat requirements, signed worker attestation, nonce replay protection, expiry, host-generation binding, single-use job linkage, process/EA finalization, and READY gating.
- **Tested:** pending enrollment to worker reservation, signed claim, finalization, and READY transition; cross-authentication and replay rejection.
- **Implemented in this stage:** workers now use independently provisioned credentials keyed to a stable worker ID, with one-time issuance, short expiry, rotation, revocation, last-seen tracking, and claim/attestation/finalization/status worker-ID binding. The legacy shared-token path is disabled by default and is enabled only by explicit test fixtures.
- **Implemented in this stage:** the Windows supervisor now verifies the stored credential against the local Bridge before starting the worker, provisions the exact stable identity when absent, rotates only when rejected or near expiry, and persists the new credential through an atomic DPAPI rewrite. Legacy bundles receive missing fields through the same validated migration path.
- **Not production-proven:** this lifecycle has not yet been exercised on a persistent owner host.

## Execution workers

- **Windows worker:** has per-job terminal copies, per-job data directories, process ownership checks, restart/revocation handling, signed reservation claims, and final process-evidence finalization integrated with the bridge.
- **Linux worker:** retains a shared service-oriented terminal architecture. It now refuses pending or attested managed jobs instead of running them through a shared terminal. Genuine Linux per-slot users, Wine prefixes, terminal copies, and resource allocation remain unimplemented.
- **Real verification:** no Wine/MT5 executable or broker account was available in this environment.

## Android

- **Implemented:** phone-first managed broker flow, encrypted profile storage, explicit broker/mode identity, bridge state polling, verified account/heartbeat gating, safe command authorization, Deriv and cTrader-specific flows.
- **Fixed in this stage:** MT5 registration now sends the selected account mode to the bridge; missing mode metadata previously caused the server to treat the request as unverified.
- **Unverified:** physical-device behavior, real broker DEMO authentication, live quotes/orders, and noVNC per-user sessions.

## Hosting and deployment

- **Implemented:** persistent bridge Docker compose templates, durable SQLite volume, Caddy/TLS owner stack, systemd host/worker units, backup/restore scripts, health/readiness endpoints, and Codespaces noVNC diagnostics.
- **Not deployed:** no paid VPS or persistent production worker has been provisioned in this environment. Codespaces remains unsuitable as the sole always-on execution host.

## Brokers and EA

- **Code paths:** HFM, Exness, FBS, and Deriv mappings exist; Deriv options uses its separate API path; cTrader is assessed separately.
- **Synthetic verification:** broker mapping, identity, authorization, command, EA safety, and adapter contract tests.
- **Real verification pending:** broker-server authentication, account data, order lifecycle, reconnect, and LIVE behavior for every broker.

## Current release gates

- **DEMO:** implementation is incomplete until a persistent isolated worker and a real broker DEMO lifecycle are verified.
- **LIVE:** server-enforced and blocked on ephemeral hosts; not enabled.
- **APK:** dependency resolution was attempted with Gradle 9.3.1/JDK 21, but the environment could not resolve Google Maven DNS; no fresh APK hash is claimed. GitHub Actions runs for `db9cd2a` were `action_required` with no jobs, so they are not build evidence.
