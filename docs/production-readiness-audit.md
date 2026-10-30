# DESTINEDFX production-readiness audit

**Repository:** `destinedai/DESTINEDFX-ANALYZER5`
**Starting point:** `f1bde8c7417ce45b3eb8dfebf171424eceb36be9`
**Audit type:** source review plus local Python tests; no broker or terminal activity
**Status:** repaired items below are code-level only. This project is **not yet production- or DEMO-acceptance-verified**.

## Summary

The starting commit already contained a native Kotlin/Compose app, a deterministic multi-strategy analyzer, Deriv WebSocket/Multiplier execution, account-and-mode-scoped Room execution records, a managed MT5 bridge/Windows worker, and a safety-oriented MT5 EA. The source includes pre-purchase intent persistence, explicit handling of uncertain purchase responses, account checks, proposal-before-purchase flow, terminal-sourced MT5 data, broker-side risk checks, and command reconciliation. The most important confirmed duplicate-entry defect was in the Deriv bot: the analyzer had a stable ID, but the bot generated a second key from rounded scan time and direction. A direction flip during the same candle could therefore bypass the database duplicate guard. The EA had a parallel direction-inclusive journal key.

The repair branch now reuses the analyzer's `signal.id` for Deriv persistence, removes the redundant Deriv key generator, removes direction from the MT5 persistent signal key, adds persistence/idempotency regressions, makes migrated executions with unknown account ownership block new entries, and requires complete per-ticket destructive acknowledgements at the bridge.

## Verified repairs in this branch

| Area | Root cause | Repair |
|---|---|---|
| Deriv duplicate key | `DerivDemoBotService` rebuilt a key from `signal.timestamp`, timeframe rounding, and direction instead of using the analyzer's closed-candle ID. | The service passes `signal.id` into `DerivMultiplierIntent`; the direction-dependent helper was removed. |
| Deriv persistence regression coverage | No test proved uniqueness across repeated/opposite-direction scans while preserving account/mode scoping. | Added a Robolectric/Room test using the analyzer ID and composite `(accountId, accountMode, signalId)` key. |
| Legacy execution migration | v5 records lack account identity and migrate as `LEGACY_UNKNOWN`; normal reconciliation filters by current login/mode and silently ignored these rows. | Added a DAO query and a global fail-closed check before new Deriv entries. Existing legacy rows now require explicit operator reconciliation; the app does not automatically assign them to an account. |
| MT5 duplicate key | EA `SignalKey` included `BUY`/`SELL` in its persisted identity. | Removed direction from the key and added a source-contract regression; the existing persistent signal journal remains in place. |
| MT5 bridge acknowledgements | The route accepted a subset of target results and implicitly filled missing targets as `UNKNOWN`. | The bridge now rejects acknowledgements unless they contain exactly one unique result for every immutable requested target; tests cover missing, duplicate, and unrequested targets. |

**Changed source/test files:** `DerivDemoBotService.kt`, `DerivMultiplierModels.kt`, `DerivExecutionPersistence.kt`, `DerivMultiplierExecutor.kt`, `DerivStableSignalKeyTest.kt`, `DerivSignalIdentityPersistenceTest.kt`, `bridge/app.py`, `bridge/test_app.py`, `DestinedFXTraderEA.mq5`, and `test_ea_source.py`.

## Remaining verified blockers

1. **Deriv reconnect and process lifecycle:** the service now uses `START_REDELIVER_INTENT`, bounded reauthentication, typed retryable-vs-manual-reconciliation outcomes, and a fresh account-matched portfolio before scans. This is code-level behavior only. Android force-stop/OS process recovery still needs device acceptance; a permanent unresolved execution correctly requires manual review. Do not claim uninterrupted autonomous uptime.
2. **Deriv aggregate exposure and portfolio integrity:** the executor now requires a fresh successful portfolio snapshot matching account ID, DEMO mode and currency, rejects malformed/unsupported contracts, requires a fresh positive balance, blocks any broker-reported open contract, and applies the 1% aggregate exposure ceiling. The Deriv API schema still needs validation against a genuine authenticated DEMO response before this path can be accepted operationally.
3. **Strategy-specific final validation:** `TradeValidationPipeline` now maps all eight strategy IDs to strategy-specific required evidence, score thresholds and direction checks. SMC alone requires complete direction-aligned MTF by default; ICT has its own killzone, sweep, FVG, structure and target evidence. Breakout requires source-backed REAL/TICK volume, and Liquidity Stop Hunts requires genuine EQH/EQL levels. Helper-level behavioral tests cover positive/missing evidence and source mismatches; full live engine/broker acceptance remains outstanding.
4. **Operational infrastructure is not verified:** the repository Render blueprint is intentionally Free and non-durable. It is diagnostics/liveness-only; `/health/ready` must remain `503 NON_DURABLE_STORAGE`, and it must not be treated as authoritative execution storage. No paid upgrade, VPS, or new subscription was authorized or created.
5. **MT5 acceptance is absent:** source-contract tests are not an MQL5 compile. MetaEditor compilation, a genuine MT5 terminal, a matching authenticated DEMO heartbeat, and broker DEMO order/fill/settlement/restart tests remain necessary. The worker kit supplies source/setup material and expects an operator-compiled EA.
6. **Credential and telemetry boundaries:** the worker temporarily writes startup credentials into a terminal INI before deletion; host ACLs, encryption at rest, and MT5 credential retention must be verified on the operator's Windows machine. EA closed-deal telemetry is bounded (30 records) and must be described as a recent sample, not complete history.
7. **No LIVE authorization:** this assignment does not authorize LIVE orders. No live execution was attempted or verified.

## Verification performed

- `python3 -m pytest -q bridge mt5-ea/test_ea_source.py mt5-ea/test_ea_safety_math.py`: **71 passed**, one third-party deprecation warning.
- `python3 -m unittest discover -s managed-worker -p 'test_*.py' -v`: **21 passed**.
- `git diff --check`: passed.
- `bash gradlew testDebugUnitTest --no-daemon`: **blocked locally before compilation** because this sandbox has no Android SDK configured (`ANDROID_HOME`/`sdk.dir` missing).
- Earlier repair commit `99044641e93460e9345e6db562e300fe99932ce3` passed Android `testDebugUnitTest` and `assembleDebug`, plus Python/worker/EA checks in [Actions run 41068032318](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41068032318). This is historical CI evidence for that exact commit only, not proof for a later branch head. The latest branch-head verification must be checked against the exact commit and Actions run linked from PR #6.
- The earlier successful main-branch run is [run 41057301322](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41057301322), at the starting SHA `f1bde8c`; it is baseline evidence only.
- No MetaEditor compile, MT5 Strategy Tester, actual terminal connection, DEMO proposal/purchase/fill/settlement, or LIVE action was performed.

## Safe DEMO acceptance procedure

Use the existing [`demo-acceptance-runbook.md`](demo-acceptance-runbook.md) and [`managed-execution-operator-setup.md`](managed-execution-operator-setup.md). In brief: deploy only an explicitly authorized HTTPS bridge with durable storage; provision a dedicated Windows host with genuine MT5 and MetaEditor; compile and install the EA; use a broker DEMO account and exact account/server identity; allowlist the bridge URL in terminal WebRequest settings; verify a fresh matching heartbeat and DEMO mode before START; confirm proposal, order, fill, protective SL/TP, subsequent position and settlement data from the broker; then test restart, disconnect, duplicate signal, stale heartbeat, partial/unknown result reconciliation, and emergency stop. Keep LIVE disabled. A queued command, green CI run, running process, or healthy bridge is not proof of broker execution.

## Remaining manual requirements

- An authorized persistent bridge/database and backups, with no surprise spend; otherwise use operator-owned durable infrastructure and a securely configured HTTPS tunnel.
- Dedicated, secured Windows host, genuine MT5 terminal and MetaEditor; compile the EA and verify worker/terminal identity and storage ACLs.
- Broker DEMO credentials entered through the app's consent flow, verified account/server/mode/currency/balance, terminal WebRequest allowlist, and controlled DEMO acceptance evidence.
- Obtain the PR-head APK from the `DestinedFX-debug-apk` artifact and the Windows kit from `DestinedFX-Windows-Worker-Kit` on the linked successful Actions run; these artifacts are code-built but do not constitute broker acceptance.

## Follow-up repairs on `repair/production-readiness-audit`

The follow-up implementation adds a fail-closed `DerivPortfolioSnapshot` containing success, account identity, mode, retrieval time, active contracts, and exposure. Deriv execution now requires a fresh account-matched snapshot before every purchase and rejects aggregate open risk above the conservative 1% ceiling. Portfolio API failure is no longer equivalent to an empty portfolio.

The Deriv foreground service now uses `START_REDELIVER_INTENT`, persists only non-secret bot configuration, differentiates retryable failures from manual reconciliation, uses bounded reconnect with fresh authentication, and validates portfolio and balance before scanning. Retry exhaustion disables the service safely. Android force-stop and OS restart behavior remain platform/operator acceptance items; no uninterrupted-operation promise is made.

The final validation pipeline now maps all eight registered strategy IDs to explicit evidence names and thresholds. An unknown strategy or missing strategy-specific evidence fails closed; breakout validation additionally requires actual volume evidence. Existing market-data, plan, 2R, and risk gates remain in force.

The Render blueprint is explicitly free-plan and non-durable. `/health` is liveness only; `/health/ready` returns `503 NON_DURABLE_STORAGE`, and execution-sensitive routes are blocked with the same code. A durable operator-owned HTTPS bridge remains required for DEMO acceptance.
