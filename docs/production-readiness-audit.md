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

1. **Not end-to-end autonomous:** the Deriv foreground service starts from explicit user action but is `START_NOT_STICKY`; there is no demonstrated process-death recovery or reconnect/reauthorization supervisor. After a disconnect the service can continue retrying without restoring a fresh authenticated connection. Do not claim continuous autonomous operation after network/process loss.
2. **No aggregate Deriv exposure limit:** per-entry stake is capped, and the bot is intended to be single-contract-at-a-time, but the selected service/executor path needs a broker-snapshot freshness contract and enforced aggregate open-risk ceiling before every purchase. A failed portfolio fetch must not be indistinguishable from an empty portfolio.
3. **Strategy-to-final-validation mismatch:** the final `TradeValidationPipeline` looks up generic SMC component names (`Structure`, `Liquidity`, `Volume`, `Momentum`) for every strategy. Specialized strategies emit different components. This can reject valid non-SMC setups. Do not claim all eight strategies are approved by the final execution pipeline until strategy-aware validation and per-strategy approval tests are added.
4. **Operational infrastructure is not verified:** the Render blueprint specifies a paid Starter web service and persistent disk, not a no-cost persistent database. The existing Free service must not be treated as authoritative durable execution storage. At audit time, both public `/health` and `/health/ready` URLs returned HTTP 404 with `x-render-routing: no-server`. No paid upgrade, VPS, or new subscription was authorized or created.
5. **MT5 acceptance is absent:** source-contract tests are not an MQL5 compile. MetaEditor compilation, a genuine MT5 terminal, a matching authenticated DEMO heartbeat, and broker DEMO order/fill/settlement/restart tests remain necessary. The worker kit supplies source/setup material and expects an operator-compiled EA.
6. **Credential and telemetry boundaries:** the worker temporarily writes startup credentials into a terminal INI before deletion; host ACLs, encryption at rest, and MT5 credential retention must be verified on the operator's Windows machine. EA closed-deal telemetry is bounded (30 records) and must be described as a recent sample, not complete history.
7. **No LIVE authorization:** this assignment does not authorize LIVE orders. No live execution was attempted or verified.

## Verification performed

- `python3 -m pytest -q bridge mt5-ea/test_ea_source.py mt5-ea/test_ea_safety_math.py`: **71 passed**, one third-party deprecation warning.
- `python3 -m unittest discover -s managed-worker -p 'test_*.py' -v`: **21 passed**.
- `git diff --check`: passed.
- `bash gradlew testDebugUnitTest --no-daemon`: **blocked locally before compilation** because this sandbox has no Android SDK configured (`ANDROID_HOME`/`sdk.dir` missing).
- Current repair commit `99044641e93460e9345e6db562e300fe99932ce3` passed the Android `testDebugUnitTest` and `assembleDebug` workflow, the Python/worker/EA workflow, and the PR and push checks in [Actions run 41068032318](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41068032318) (all checks successful). The run published `DestinedFX-debug-apk` and `DestinedFX-Windows-Worker-Kit` artifacts. The APK artifact is 45,545,447 bytes; the worker-kit artifact is 38,002 bytes.
- The earlier successful main-branch run is [run 41057301322](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41057301322), at the starting SHA `f1bde8c`; it is baseline evidence only.
- No MetaEditor compile, MT5 Strategy Tester, actual terminal connection, DEMO proposal/purchase/fill/settlement, or LIVE action was performed.

## Safe DEMO acceptance procedure

Use the existing [`demo-acceptance-runbook.md`](demo-acceptance-runbook.md) and [`managed-execution-operator-setup.md`](managed-execution-operator-setup.md). In brief: deploy only an explicitly authorized HTTPS bridge with durable storage; provision a dedicated Windows host with genuine MT5 and MetaEditor; compile and install the EA; use a broker DEMO account and exact account/server identity; allowlist the bridge URL in terminal WebRequest settings; verify a fresh matching heartbeat and DEMO mode before START; confirm proposal, order, fill, protective SL/TP, subsequent position and settlement data from the broker; then test restart, disconnect, duplicate signal, stale heartbeat, partial/unknown result reconciliation, and emergency stop. Keep LIVE disabled. A queued command, green CI run, running process, or healthy bridge is not proof of broker execution.

## Remaining manual requirements

- An authorized persistent bridge/database and backups, with no surprise spend; otherwise use operator-owned durable infrastructure and a securely configured HTTPS tunnel.
- Dedicated, secured Windows host, genuine MT5 terminal and MetaEditor; compile the EA and verify worker/terminal identity and storage ACLs.
- Broker DEMO credentials entered through the app's consent flow, verified account/server/mode/currency/balance, terminal WebRequest allowlist, and controlled DEMO acceptance evidence.
- Obtain the PR-head APK from the `DestinedFX-debug-apk` artifact and the Windows kit from `DestinedFX-Windows-Worker-Kit` on the linked successful Actions run; these artifacts are code-built but do not constitute broker acceptance.
