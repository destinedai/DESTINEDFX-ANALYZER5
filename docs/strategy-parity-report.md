# Strategy parity report

Date: 2026-11-06

## Golden-candle coverage

`bridge/test_strategy_parity.py` runs deterministic closed-candle fixtures through the canonical Python strategy engine and asserts exact state, direction, entry, stop, target, and evidence for the two managed strategies with shared golden fixtures:

- `smc-confluence-1.0.0`: `CANDIDATE`, `LONG`, entry `107.4`, stop `105.54464285714286`, target `111.11071428571425`, reward/risk `2.0`.
- `ict-silver-bullet`: `CANDIDATE`, `LONG`, entry `106.5`, stop `105.22142857142858`, target `109.05714285714285`, reward/risk `2.0`.

The test also asserts that the Android-bundled Python module is byte-identical to `bridge/quantitative.py`. Any change in the canonical result or bundled copy fails the test and prints the field-level disagreement.

## Disagreements found

The repository does **not** currently expose a callable Kotlin `SignalEngine` golden-fixture adapter or an MT5 EA headless execution adapter. Therefore this report does not claim Kotlin/EA numerical parity that was not executed.

The following known contract differences remain and must be resolved before calling all three engines numerically identical:

1. The bridge/Python engine returns versioned strategy identifiers such as `python-smc-confluence-1.0.0`; Kotlin `SignalEngine` currently emits the managed strategy ID as its `strategyVersion`. This is a provenance/version-label disagreement, not proof of signal-rule disagreement.
2. Kotlin requires at least 60 closed candles for `SignalEngine.analyse`, while the bridge fixture intentionally uses the strategy validator's 35-candle positive fixture. The same fixture cannot be submitted to Kotlin without an explicit fixture adapter or a 60-candle equivalent.
3. The MT5 EA computes broker-specific tick-size, spread, commission, slippage, stop-level, and account-risk adjustments at order time. Its final TP can therefore differ from the provider-only Python candidate target by design. Exact broker-side TP parity requires a MetaTrader 5 integration test against a demo terminal and a fixed account/symbol specification.

These differences are reported explicitly; no fallback engine is treated as authoritative. The Kotlin SignalEngine remains the app execution engine, the bridge Python module remains the canonical provider-analysis engine, and the EA remains authoritative for broker-side order validation and submitted protection telemetry.
