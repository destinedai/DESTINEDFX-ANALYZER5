# Security and Broker Readiness Register

**Assessment baseline:** `main` at `a7218911dc979e04d88ffedc296db060d09371bd` and PR #7 head `63cba1d3d323697994b1c5eeb28f55c14f4d8eaa`, both verified on 2026-11-02. The continuation branch `feat/trading-path-finalization` adds a DST-aware ICT Silver Bullet session gate and requires a closed-candle sweep, displacement, FVG and Consequent Encroachment. Candidate confidence is now explicitly unscored rather than hard-coded. This register is not a full penetration test, broker acceptance, or production approval.

**Overall disposition: NO-GO for production or automated LIVE trading.** Source controls exist in selected paths, but this environment lacks an Android SDK/device, genuine broker DEMO credentials, an operator-owned Windows MT5 terminal/MetaEditor, and a durable configured managed bridge. No order was sent.

## Broker readiness

| Broker / mode | Status | Evidence and remaining gate |
|---|---|---|
| Deriv DEMO | NOT VERIFIED | Android-native Options/Multiplier path has account selection, readiness checks, proposal-before-purchase, account/mode-scoped intents and ambiguous-outcome handling (`DerivBrokerGateway.kt`, `DerivMultiplierExecutor.kt`, `DerivDemoBotService.kt`). Unit/policy coverage exists, but no authenticated broker session, proposal/purchase/fill/settlement/reconciliation acceptance was run. Execute the steps in `docs/deriv-acceptance-runbook.md` using an operator-authorized DEMO account. |
| Deriv LIVE | BLOCKED | REAL lease is process-memory-only, account/session/currency/credential-generation-bound, expiring and checked at the purchase boundary. No REAL order is authorized or accepted here. Keep disabled pending broker DEMO acceptance, security review and separate, account-specific operator authorization. |
| MT5 DEMO | NOT VERIFIED | App/control-plane/worker/EA source and worker unit tests exist. No configured durable managed service, Windows terminal, MetaEditor compile, fresh terminal-authenticated heartbeat, or broker DEMO order acceptance was available. Worker hosting must be on an operator-controlled Windows machine or separately authorized durable infrastructure; the repository's free Render profile is non-durable diagnostics only. |
| MT5 LIVE | BLOCKED | Source contains live-profile/account gates, but full external execution-boundary review and genuine LIVE acceptance have not occurred. No REAL order was sent. Require a separate live account authorization, terminal/device verification and reviewed broker-confirmed acceptance plan. |
| OANDA DEMO | NO-GO | No genuine OANDA trade execution adapter is present. Market/chart provider references are not execution support. The broker manager routes OANDA to an unavailable gateway; keep execution disabled. |
| OANDA LIVE | NO-GO | No genuine OANDA trade execution adapter is present. Keep execution disabled. |
| Other broker directory entries | NOT VERIFIED | Display names/presets alone do not establish supported execution. Independently inventory each UI option and require an authenticated adapter plus broker-specific tests before enabling trade controls. |

## Security findings register

| ID | Severity | Component | Evidence / finding | Remediation | Status |
|---|---|---|---|---|---|
| S-01 | High | App authentication / sensitive-action authorization | `DestinedFXViewModel.kt` initializes `isAppLocked=false` and `lockApp()` is empty; the Compose gate only appears when that state is true. A biometric helper exists in `MainActivity.kt`, but reviewed call paths do not invoke it. Guest access is explicitly permitted by existing tests. | Decide guest/read-only product posture. Add lifecycle-aware app lock and mandatory re-authentication for sensitive actions, enforced again at the broker/backend boundary. Add lock, background/foreground, expiry and authorization tests. | NOT VERIFIED |
| S-02 | High | Credential persistence | `SecretsManager.kt` falls back to `InMemorySharedPreferences` if encrypted storage initialization fails. Deriv and bridge-token methods fail closed, but legacy MT5/OANDA/Telegram setters and managed MT5 password persistence do not consistently reject the unavailable-storage state or return an accurate persistence result. Their getters may expose values retained only in process memory. | Make all credential writes fail explicitly when secure storage is unavailable; prevent sensitive reads/operations in that state; surface persistence failure at every UI call site. Keep password-save opt-in and default-off. Add fallback-path tests. | NOT VERIFIED |
| S-03 | Medium | Android cryptography/dependency maintenance | Gradle catalog pins AndroidX Security Crypto `1.1.0-alpha06`. This review does not establish that the artifact is exploitable; its alpha status and maintenance age require a migration decision. | Review supported Android Keystore-backed storage alternatives and migration/rollback behavior. Do not migrate without compatibility tests and a credential recovery plan. | NOT VERIFIED |
| S-04 | High | Release build | `app/build.gradle.kts` release minification is disabled; current GitHub workflow builds/verifies debug APK rather than validating a signed release. No protected signing-key preflight or final release artifact verification was run. | Enable reviewed R8/minification with tested keep rules; add release assemble/signature verification in protected CI, secret/dependency/static scans, and artifact SHA-256. Store signing material only in protected CI secrets. | NOT VERIFIED |
| S-05 | High | Broker acceptance | No genuine Deriv or MT5 DEMO order lifecycle has been executed from app/terminal in this review. Code-level readiness is not execution evidence. | Complete the broker-specific DEMO runbooks and retain redacted IDs, account mode, fill, protection, close, settlement, history and STOP/restart evidence. | BLOCKED |
| S-06 | High | MT5 infrastructure | There is no verified durable bridge deployment, operator Windows host, actual terminal process, MetaEditor EA compilation or authenticated broker heartbeat in this sandbox. | Provision and secure an operator-owned Windows host and durable HTTPS bridge without unapproved spend; verify identity, ACLs, pairing/revocation and recovery there. | BLOCKED |
| S-07 | Medium | Android network/platform | Source config denies cleartext and trusts system certificate roots; reviewed app components mostly use non-exported defaults/explicit settings. This is source review only: no installed-app dynamic TLS, intent, backup, screenshot, notification, or device lifecycle testing was run. | Run manifest/static checks and installed-app testing on supported Android devices; verify exported entry points cannot initiate trading and sensitive screens are protected in recents/notifications. | NOT VERIFIED |
| S-08 | Medium | Backend/API/worker | Bridge and worker include tests for authentication/pairing, idempotency, worker identity, leases, validation and recovery. No staging penetration test, API fuzzing, replay/rate-limit campaign or external deployment configuration review was done. | Run authorized staging-only security tests; verify deployment TLS, token rotation/revocation, account isolation, rate limits, request limits and audit trail. | NOT VERIFIED |
| S-09 | Medium | Quantitative parity | Python candidate generation and Kotlin strategy validation are separate logic layers; reviewed contracts are not field-by-field equivalent for at least SMC. No shared golden candle snapshot parity suite was run. Python remains non-executable and candidates are not order instructions. | Define versioned semantics for all eight strategies; add identical candle fixtures and field-by-field Kotlin/Python comparison before any future execution integration. | NOT VERIFIED |
| S-10 | Low | Observability / logs | Redaction/crash-handling code exists, but this review did not inspect every telemetry path or dynamically assert that secrets are absent from logs, exception reports, clipboard or screenshots. | Add secret-canary tests over app logs/crash artifacts and review analytics/export paths. | NOT VERIFIED |

Severity labels here prioritize follow-up; they are not CVSS scores. No finding from this source review is represented as an exploit or confirmed vulnerability unless explicitly supported by the evidence above.

## Trading-specific readiness

| Area | Status | Evidence / gate |
|---|---|---|
| Kotlin RiskEngine | IMPLEMENTED | Existing app architecture retains Kotlin as final validation/risk authority; source includes conservative risk checks. Full invariant test suite could not be run here because Android SDK is missing. |
| 1% maximum risk / 2R minimum | IMPLEMENTED | Existing source/tests encode risk and reward-to-risk constraints. Broker product rules and actual execution risk are not accepted by source inspection alone. |
| Deriv product mapping | IMPLEMENTED | Multiplier adapter uses product-specific stake rules and rejects generic Forex/CFD plans instead of mapping lots into stake. Current live broker rule acceptance remains outstanding. |
| Python execution | BLOCKED | Candidates are non-executable; do not connect Python signals directly to orders. Keep disabled until strategy parity, runtime/device verification and Kotlin risk authorization are complete. |
| Eight strategy rulesets | NOT VERIFIED | Eight identifiers dispatch to versioned Python candidate logic. ICT Silver Bullet now enforces NY-local session windows (DST-aware) and closed-candle sweep/displacement/FVG/Consequent Encroachment evidence. This is not proof that all rule definitions are complete, that Kotlin/Python parity exists, or that any strategy is profitable. Candidate scores are not empirically calibrated. |
| Market-data provenance | NOT VERIFIED | Source validation exists in app/bridge. A complete end-to-end freshness/source audit against live data and actual broker execution was not performed. |
| Order, fill, SL/TP, close and reconciliation | NOT VERIFIED | Deriv source paths implement proposal, purchase, contract tracking and conservative reconciliation. No real broker response sequence or execution was run. MT5 genuine broker acknowledgement remains outstanding. |
| STOP / recovery | IMPLEMENTED | Code-level STOP, duplicate prevention and recovery policies exist with Python worker tests; actual Android process/network and terminal recovery require device/host acceptance. |

## Verification performed in this environment

- Repository fetched from `destinedai/DESTINEDFX-ANALYZER5`; verified `origin/main` SHA `a7218911dc979e04d88ffedc296db060d09371bd` and implementation branch SHA `63cba1d3d323697994b1c5eeb28f55c14f4d8eaa`.
- PR #7 inspected: open against `main`, head `63cba1d3d323697994b1c5eeb28f55c14f4d8eaa`; its existing GitHub checks were green when inspected. Continuation branch `feat/trading-path-finalization` is based on that head.
- `python3 -m pytest -q bridge managed-worker mt5-ea`: **142 passed**, one third-party Starlette deprecation warning.
- `python3 -m py_compile bridge/quantitative.py bridge/test_quantitative.py`: passed.
- Android `./gradlew testDebugUnitTest`: **BLOCKED before compilation** because no Android SDK is configured (`ANDROID_HOME`/`sdk.dir` absent). This is not a test failure in app source and is not a pass.
- GitHub Actions run [41515406776](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41515406776) on exact commit `a7a4731d7b252c39eb013ba054db1e18ea0a43f4` passed all three jobs: Python tests, Android unit tests/debug APK signature verification, and genuine MetaEditor EA compilation. The run published `DestinedFX-debug-apk`, `DestinedFX-Windows-Worker-Kit`, and `DestinedFX-MT5-EA-ex5` artifacts.
- No Deriv/OANDA/MT5 broker account login or order, Android install/device/instrumentation test, Chaquopy runtime test, staging penetration test, full static scan, dependency vulnerability scan, secret scan, or release signing verification was performed. A CI MetaEditor compile does not establish terminal or broker acceptance.

## Operator actions required before a go decision

1. Fix S-01 and S-02, then add regression tests for app lock and secure-storage-unavailable behavior.
2. Install/configure an Android SDK and supported emulator/device; run all unit, instrumentation and release checks on the exact final commit.
3. Complete a genuine authorized Deriv DEMO lifecycle acceptance from the runbook; do not use LIVE credentials for that test.
4. For MT5, provide a secured operator Windows host, MetaEditor, genuine terminal and broker DEMO account; compile and verify the EA and worker end-to-end.
5. Keep OANDA trade execution disabled until a separately tested official execution integration exists.
6. Add/execute branch CI for Python, Kotlin, release build/signing verification, manifests, dependencies, secrets, static analysis and artifact hashes. Retain full reports and exact commit SHA.
7. Do not arm or submit REAL orders in this project continuation. Any controlled LIVE test requires new, separate, exact account/broker authorization after review of DEMO and security evidence.
