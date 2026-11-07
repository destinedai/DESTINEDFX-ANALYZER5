# DestinedFX Analyzer (phone-only, read-only conversion in progress)

DestinedFX Analyzer is an Android market-analysis application. The intended product boundary is read-only: public market data, charting, deterministic educational analysis, and transparent rejection when data is missing, stale, malformed, or inconsistent. Analysis is informational and is not financial advice.

## Safety status

This branch has begun a read-only conversion, but it is **not yet verified as a fully read-only repository or release**. The Android order-submission use case now rejects every request with `READ_ONLY_PRODUCT`; the Bot workspace is no longer in primary navigation and renders a read-only disclosure if restored through an old navigation state; and manifest declarations for broker OAuth callbacks, foreground trading services, and boot recovery have been removed. These measures constrain the Android app's intended runtime surface.

The repository still contains legacy broker adapters, bot/service source code, and a separately runnable `bridge/` backend, worker, and MT5 EA with execution and trade-management routes. Those components have **not** been decommissioned or proven unreachable in all deployment configurations. Do not deploy the legacy bridge/worker or treat the current repository as a production-safe read-only release until the remaining conversion is completed and verified.

## Status by area

### Implemented in this conversion slice

- Trading requests routed through the Android `TradeUseCase` fail closed before broker access, persistence, or order submission.
- Android primary navigation no longer advertises the Bot workstation; the prior destination displays the read-only product boundary.
- Android manifest no longer declares the bot/session foreground services, boot receiver, cTrader callback route, or trade-only foreground/boot permissions.
- Regression tests cover the read-only policy and primary navigation contract.

### Implemented but not fully verified

- Existing market-data validation and stale-data safeguards remain in the Android source and have prior unit-test coverage, but tests/build could not run in this environment because the Android SDK is not configured and `pytest` is unavailable.
- Android layout, rotation, accessibility, persistence/recovery, and the full analysis result/evidence path have not been revalidated as part of this conversion.

### Not implemented / remaining defects

- Decommission or remove legacy broker, autonomous bot, worker, and MT5 execution code from the repository.
- Disable or remove all transaction-capable routes from the standalone bridge, including live authorization, order commands, close/modify operations, and managed-account setup.
- Remove execution-oriented deployment artifacts and workflow packaging, then add tests proving they cannot be enabled or reached.
- Complete phone-first UI review for data source/timestamp, analysis method/progress, evidence, rejection reasons, DATA UNAVAILABLE, accessibility, rotation, persistence, and recovery.
- Run the complete Android, Python, backend, worker, EA, build, and CI suites on a correctly provisioned runner and inspect all failures/warnings.
- Produce and inspect a release build only after the above items are complete.

### Deliberately excluded from the intended product

Broker account connections; credential-based broker access; live/demo order submission; automated trading; position/order management; live authorization; financial transactions; and fabricated market data, performance, or results.

## Build and tests

The repository includes a Gradle Android project and Python bridge tests. The exact local test commands and required environment are documented in the workflow at `.github/workflows/build-apk.yml`. The bridge/smoke Python suite passed 160 tests with one deprecation warning in the conversion environment. Android tests/build and full CI could not be verified because the sandbox has no configured Android SDK; see `docs/read-only-scope.md` for exact results and remaining limitations.
