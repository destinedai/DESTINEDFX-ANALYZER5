# DestinedFX Python quantitative Android integration — final report

## Branch and delivery

The implementation commit is `192c88bfb424b5ff7f96e516e72f1a51d0becd53`. The later documentation commit was `80e0e095392cca97df3733241e3d32706103e6a3`. The final branch tip for this continuation is `5b884f54776ea9eb0606266892d53c534173d5c4` on `feat/complete-demo-readiness`, pushed to `origin/feat/complete-demo-readiness`; `main` was not modified and no force-push was used.

Changed files are `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/java/com/example/MyApplication.kt`, `app/src/main/java/com/example/domain/engine/PythonQuantitativeSignalEngine.kt`, `app/src/main/java/com/example/domain/model/TradeSignal.kt`, `app/src/main/java/com/example/domain/usecase/impl/SignalGenerationUseCaseImpl.kt`, `bridge/quantitative.py`, `bridge/test_quantitative.py`, `MANUS_HANDOFF.md`, and `docs/python-android-quantitative-readiness.md`.

## Architecture and runtime

**VERIFIED:** The application remains native Android Kotlin/Jetpack Compose. Chaquopy 17.2.0 is applied only to the Android app module. Python 3.12 is configured with `arm64-v8a` and `x86_64` ABIs. The shared `bridge/quantitative.py` module is packaged as the Python source; Python tests are excluded from the APK source set.

**VERIFIED by CI packaging:** `PythonQuantitativeSignalEngine` initializes the Chaquopy runtime once per Android process, serializes typed candle data to JSON, invokes Python on `Dispatchers.Default`, applies a bounded calculation wait, parses a typed candidate, and returns `DATA_UNAVAILABLE` with `executable=false` on errors. Cancellation is not swallowed. Python has no broker, credential, order, sizing, or execution API. Chaquopy startup is scheduled off the Android main thread and remains guarded against duplicate initialization.

**NOT VERIFIED:** The instrumentation test was added and CI reached `connectedDebugAndroidTest`, but the 56 MB APK timed out during installation on the x86_64 emulator after 360 seconds; zero tests ran. The successful APK build proves compilation, packaging, and APK generation, not runtime execution on a device.

## Python engine and strategies

The Python ruleset currently implements closed-candle Smart Money Concepts BOS analysis with EMA20, ATR, structural stop proposal, and a minimum 2R target proposal. Its 100-point value is a documented confluence score, not a win probability: BOS confirmation contributes 40 points, EMA20 directional confirmation 20, closed-candle data integrity 20, and the 2R target proposal 20.

Python returns `NOT_IMPLEMENTED` for strategy IDs that it does not implement. It does not route unsupported strategies to SMC or manufacture UI signals. The existing Kotlin registry still contains the broader strategy definitions, but Kotlin/Python parity has not been approved. Therefore Python is currently a shadow candidate, not the canonical execution signal.

**Tested:** SMC positive and negative fixtures, insufficient history, stale and incomplete data, duplicate and unordered candles, gaps, non-finite values, impossible OHLCV, future timestamps, invalid retrieval timestamps, mixed-provider source identity, unsupported strategy dispatch, and missing-candle JSON requests.

**NOT VERIFIED:** Wyckoff, ICT Silver Bullet, supply/demand, breakout momentum, mean reversion, and trend-following rules are not implemented in Python in this change and have no Python backtest results.

## Data source and TradingView truthfulness

Python consumes the same Kotlin-validated closed-candle snapshot that the existing signal use case loads. The repository records the actual OHLC source and the provider receipt time separately from the candle timestamp and Android analysis time. Depending on configuration and symbol support, that source can be OANDA, Binance Spot OHLC, or Deriv WebSocket candles. The Python boundary requires a non-empty source and rejects candle-level source mismatches. FX/session gaps are accepted only through an explicit bounded non-crypto session-gap rule; crypto remains strict.

TradingView remains the visible chart interface. The official widget is not treated as an OHLC API. Its documentation says widget data cannot be downloaded/exported and that widgets do not provide a data API. Advanced Charts requires an application-owned or third-party datafeed. No cross-origin scraping, private endpoint inspection, pixel reconstruction, or new market-data API key was added. Consequently, chart candles and Python candles are **NOT VERIFIED as the same feed**; the UI must show the recorded candle source and must not call the analysis a TradingView-data signal.

## Kotlin, parity, and risk

**VERIFIED by design and CI compilation:** Kotlin still owns lifecycle, Compose UI, navigation, selection state, chart lifecycle, persistence, notifications, network communication, and execution gates. The existing Kotlin `SignalEngine`, `SignalGenerationUseCaseImpl`, `TradeSignal`, and `SignalPresentationSafety` remain present.

Python output is stored on `TradeSignal` as shadow metadata with `pythonParityStatus=NOT_VERIFIED`. It cannot set the Kotlin `executable` field. Kotlin `RiskEngine` remains the final authority for the 1% risk ceiling, minimum 2R requirement, broker constraints, spread and commission checks, news blocking, and other existing execution validation.

**NOT VERIFIED:** Exact Python/Kotlin parity has not been established. No automatic order submission is connected to Python. No broker authorization or trade was attempted.

## Backtesting and performance

**NOT VERIFIED / NO CLAIM:** This change adds no new backtest dataset, walk-forward evaluation, out-of-sample sample, trade count, win rate, expectancy, profit factor, drawdown, or cost-sensitivity result. No profitability or accuracy claim is made.

## Test and CI results

Local Python bridge and quantitative tests: **VERIFIED — 76 passed**. Python syntax compilation: **VERIFIED**. A real Android instrumentation test has been added and CI is configured to run it on an x86_64 API 35 emulator; runtime evidence remains **NOT VERIFIED** until that job executes successfully. Local Android compilation: **BLOCKED** because the sandbox has no Android SDK configured.

GitHub Actions run [41467861528](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41467861528) passed for implementation commit `192c88bfb424b5ff7f96e516e72f1a51d0becd53`. Run [41468988507](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41468988507) passed for documentation commit `80e0e095392cca97df3733241e3d32706103e6a3`. Final run [41481731986](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41481731986) for `5b884f54776ea9eb0606266892d53c534173d5c4` passed Python, MetaEditor, Android unit tests, APK build/signature/package verification and artifact packaging, but its emulator job failed at APK installation timeout with zero tests executed. Installed-APK Python runtime therefore remains **NOT VERIFIED**.

The debug APK artifact is [DestinedFX-debug-apk, artifact 13153836953](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/41467861528/artifacts/13153836953). The uploaded artifact size was 56,344,518 bytes; the individual APK byte size was not recorded in the retained log. CI verified package `com.aistudio.destinedfx.qwvzxp`, version `1.0.9`, minSdk 24, native ABIs `arm64-v8a` and `x86_64`, and a valid APK Signature Scheme v2 signature. No keystore or private key was committed. Debug-signing upgrade continuity across installations is **NOT VERIFIED**.

## Final readiness decision

Embedded Python packaging and the native Kotlin integration are **VERIFIED by successful CI compilation and APK packaging**. Python runtime execution inside an installed APK is **NOT VERIFIED**. TradingView-to-Python OHLC identity is **BLOCKED / NOT CLAIMED**. Python/Kotlin parity and out-of-sample performance are **NOT VERIFIED**. DEMO broker acceptance, fills, reconciliation, restart recovery, and LIVE trading remain **NO-GO** and were not attempted.

### References

- [1]: https://chaquo.com/chaquopy/doc/current/android.html "Chaquopy Android documentation"
- [2]: https://chaquo.com/chaquopy/license/ "Chaquopy licensing"
- [3]: https://www.tradingview.com/widget-docs/faq/data/ "TradingView widget data FAQ"
- [4]: https://www.tradingview.com/charting-library-docs/latest/connecting_data/datafeed-api/ "TradingView Charting Library datafeed API"
