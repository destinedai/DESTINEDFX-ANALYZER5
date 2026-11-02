# Python quantitative engine in the native Android app

**Status:** PARTIAL / NOT VERIFIED for APK runtime until the new GitHub Actions x86_64 emulator instrumentation job passes.

The Android app remains Kotlin/Jetpack Compose. Chaquopy 17.2.0 is applied only to the `app` module. Python 3.12 is configured with `arm64-v8a` and `x86_64` ABIs, and the shared `bridge/quantitative.py` module is the only Python source packaged into the APK. The module contains no broker access, credentials, order submission, sizing, or risk authorization.

`PythonQuantitativeSignalEngine` starts Chaquopy once per Android process, serializes the exact Kotlin-validated closed-candle snapshot to JSON, carries provider receipt time separately from Android analysis time, invokes `analyze_json` on `Dispatchers.Default` with a bounded timeout, and parses a typed non-executable candidate. App startup schedules Chaquopy initialization off the main thread. Exceptions and timeouts return `DATA_UNAVAILABLE` with `executable=false`; coroutine cancellation is rethrown. `SignalGenerationUseCaseImpl` invokes this result as a shadow-analysis stage. Its result is marked `pythonParityStatus=NOT_VERIFIED`, and the existing Kotlin `SignalEngine` and `RiskEngine` remain authoritative.

The Python ruleset currently implements only closed-candle SMC BOS analysis. Its score is a documented confluence score, not a probability: BOS confirmation 40 points, EMA20 directional confirmation 20 points, closed-candle data integrity 20 points, and a minimum 2R target proposal 20 points. Other strategy IDs return `NOT_IMPLEMENTED`; they are not routed to SMC and no signal is manufactured.

The Python input source is the repository's validated closed OHLC candle result, not the TradingView widget. The current repository can receive candles from OANDA when configured, Binance Spot OHLC for supported crypto, or Deriv WebSocket candles. It records the actual source and provider receipt time, rejects source-mismatched rows, applies an explicit bounded non-crypto session-gap rule, and does not treat TradingView's visible chart as an OHLC feed. The official TradingView widget documentation says its data cannot be downloaded/exported and that widgets do not provide a data API. Advanced Charts requires an application-owned or third-party datafeed; no such TradingView datafeed was added here.

## Verification

- Python bridge and quantitative tests: **VERIFIED — 76 passed locally**.
- Python syntax and JSON boundary smoke test: **VERIFIED**.
- Android compile/APK: **NOT VERIFIED** in this sandbox because the Android SDK location is unavailable (`ANDROID_HOME`/`sdk.dir` missing).
- Chaquopy runtime inside an APK: **NOT VERIFIED** until the new API 35 x86_64 emulator job executes the adapter test.
- Kotlin/Python parity: **NOT VERIFIED**. Python is shadow-only and cannot authorize execution.
- TradingView OHLC source match: **BLOCKED / NOT CLAIMED**. The visible official widget is display-only; Python uses the recorded repository candle source.
- DEMO/LIVE execution readiness: **NO-GO**. No orders, credentials, broker accounts, or paid infrastructure were used.

## References

- [1] Chaquopy Android documentation: https://chaquo.com/chaquopy/doc/current/android.html
- [2] Chaquopy licensing: https://chaquo.com/chaquopy/license/
- [3] TradingView widget data FAQ: https://www.tradingview.com/widget-docs/faq/data/
- [4] TradingView Charting Library datafeed API: https://www.tradingview.com/charting-library-docs/latest/connecting_data/datafeed-api/
