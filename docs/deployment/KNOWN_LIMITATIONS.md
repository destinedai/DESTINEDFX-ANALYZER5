# Known limitations and remaining work

## Product and execution boundary

- Read-only conversion is incomplete: broker adapters, service source, FastAPI command routes, Windows worker and MT5 EA remain transaction-capable.
- The Android TradeUseCase entry guard does not establish that all indirect call sites/build variants/services are unreachable.
- No full-package/artifact review proves the Android deliverable or repository is transaction-free.

## Market data

- No provider endpoints were live-verified. Provider quota, geographic eligibility, plan, license and data delays are unknown unless individually evidenced elsewhere.
- Retrieval timestamps do not always equal provider event timestamps; quote recency is not necessarily source freshness.
- Binance quote and book calls are separate, and its defined WebSocket adapter is not wired into repository startup.
- Some provider paths need runtime credentials; timeout/retry/rate-limit handling and source fallback behavior are not uniform.
- Calendar empty responses, caches and provider errors require conservative UI semantics.

## Android and persistence

- Full Android pathway, accessibility, rotation, process recreation, privacy and retention audits remain incomplete.
- Room includes legacy user/trade/Deriv records; retention/deletion and migration behavior need review.
- Service locator/global state and direct service/preferences use make component boundaries porous.
- Instrumentation tests exist but are not in current CI.

## Backend and deployment

- Bridge/worker/EA path is legacy, separate, execution-capable, and not verified end-to-end.
- Root Render configuration uses ephemeral/non-durable Free storage and is not an always-on or execution-ready host.
- Owner-operated deployment recipes require operator hardware, secrets, secure HTTPS, backup and MT5 setup; no service deployment was confirmed.
- Android Supabase auth/cloud-sync classes are stubs.

## Release quality

- CI covers static verifier, Android JVM tests, debug assembly/signature check and artifact upload; it does not run instrumentation or Python/backend/worker/EA suites, lint or security scanning.
- A debug APK is not a production release; run/artifact/checksum must match exact source revision.
- Local compilation may be blocked by missing full JDK or Android SDK; an environment blocker must not be presented as a green build.

## Next steps

1. Remove/isolate all execution surfaces and add end-to-end boundary verification.
2. Complete market/account data provenance, adapter timestamp semantics and provider switching tests.
3. Audit local data retention, secret handling, logging, Android lifecycle and UI accessibility.
4. Add and run Python plus instrumentation CI tests as appropriate.
5. Validate the complete install/release and deployment documentation against actual artifact/service evidence.