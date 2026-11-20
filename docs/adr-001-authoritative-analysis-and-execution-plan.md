# ADR-001: Authoritative analysis and execution-plan consistency

- **Status:** Accepted for DEMO integration; LIVE remains independently locked.
- **Date:** 2026-11-05
- **Scope:** Android Analyzer, Python shadow analysis, MT5 EA and managed worker.

## Context

The repository contains three strategy implementations: the Kotlin `SignalEngine`, the Python quantitative rulesets, and strategy logic in `mt5-ea/DestinedFXTraderEA.mq5`. They do not currently have an approved field-by-field parity contract. Treating any one output as executable without proving parity could show one plan in Android while the EA executes another.

The Android process is also not a safe availability dependency: the owner-controlled Windows worker must enforce its own safety gates when the phone is closed or offline.

## Decision

The Kotlin `SignalEngine` and its validated market-data pipeline are the **authoritative Android analysis path** for the current DEMO UI. It owns the Analyzer display, including source identity, closed-candle validation, quote freshness, symbol mapping, market-data health, setup eligibility, rejection reasons and the initial analysis plan.

The MT5 EA remains the **authoritative execution path**. It independently revalidates account identity, terminal/EA permissions, current quote, costs, margin, risk, news, duplicate identity and the final broker-valid plan immediately before submission. The EA may adjust entry/target levels for the actual quote and verified transaction costs; those final values must be reported as execution-validated values and are not interchangeable with Android's initial estimates.

Python remains **candidate/shadow-only** until deterministic parity is approved. It may provide evidence and diagnostics, but its result cannot authorize an order. The managed worker must block execution when a requested strategy/plan is unsupported or when a parity mismatch is reported.

This is a deterministic parity architecture rather than an attempt to make the phone a runtime dependency:

1. Feed identical closed candles, symbol metadata, timeframe and execution assumptions into available engines.
2. Compare direction, entry, stop, targets, reward/risk, invalidation, eligibility and rejection reason.
3. Record disagreements as non-executable evidence.
4. Only the EA's final broker-validated plan can cross the execution boundary.

## Consequences

- Analyzer levels are labelled **initial analysis estimates** unless a final EA validation response exists.
- A successful HTTP response, pairing state or Android signal is never presented as a fill.
- A parity mismatch blocks DEMO execution rather than silently selecting one implementation.
- The worker can continue safe monitoring with the phone closed, but must fail closed when required evidence is stale, missing or conflicting.
- Python strategy claims must match the actual registered ruleset; unsupported or candidate-only strategies remain visibly marked.

## Verification obligations

Golden fixtures are maintained in the Kotlin strategy behavioral tests and Python quantitative tests. The fixture set must cover SMC, ICT Silver Bullet, Wyckoff, Supply and Demand, Breakout Momentum, Mean Reversion, Trend Following, Liquidity/Stop Hunts and Volatility Breakout where supported. New parity approval requires deterministic outputs and an explicit record of any intentional semantic difference.
