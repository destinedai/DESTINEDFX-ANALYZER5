# DestinedFX Analyzer — AI Studio / Coding-Agent Context

## Project identity

This repository is the **native Android application** for DestinedFX Analyzer. It is Kotlin + Jetpack Compose, built with Gradle. It is not a web application. Keep it buildable as an Android Studio project and do not replace it with React, Next.js, Vite, Flutter, or a new web project.

- Android module: `app/`
- Application ID: `com.aistudio.destinedfx.qwvzxp`
- Kotlin package root: `app/src/main/java/com/example/`
- Shared deterministic trading core: `app/src/main/java/com/destinedfx/core/`
- Gradle wrapper: `./gradlew`
- CI workflow: `.github/workflows/build-apk.yml`
- Separate website reference: `destinedai/make-a-web-15`

## Repository boundaries (avoid conflicts)

1. Make changes in this Android repository's existing files and architecture; inspect current `main` first.
2. Use the website repository as a **visual/behavioral reference only** unless a change is specifically requested there. Do not merge its web framework, `package.json`, `src/` tree, web routes, or hosting configuration into this Android repository.
3. Do not create duplicate Activities, ViewModels, Compose screens, repositories, Gradle projects, or competing dependency-injection containers.
4. Do not overwrite `settings.gradle.kts`, `build.gradle.kts`, the version catalog, manifest, or source files wholesale merely to make a code assistant recognize the project.
5. Keep the app native; use Compose for UI and an Android `WebView` only where an embedded TradingView visualization is required.
6. Never commit credentials, access tokens, service-role keys, private keys, production account passwords, or real user data. `.env.example` contains placeholders only.

## Product requirements

- Preserve deterministic, testable analysis; do not add Gemini or another generative-AI provider to the production analysis path.
- Do not reintroduce TwelveData or Alpha Vantage.
- Never display demo/sample/mock quotes, candles, calendar events, bot connection states, order results, or trade signals as live or real.
- Keep data source and freshness visible. Stale, missing, unsupported, or inconsistent data must produce a clear unavailable/WAIT state, not a fabricated signal.
- The TradingView widget is a chart visualization. Do not scrape its cross-origin iframe or claim Kotlin can read its internal candles directly. Trading signals need an actual supported market-data feed with verifiable timestamps and instrument mapping; clearly disclose when the feed cannot be acquired.
- Keep the selected instrument and timeframe synchronized across the chart, analysis request, and trade plan. Supported chart intervals are M1, M5, M15, M30, H1, H4, D1, and W1 where the mapped TradingView symbol supports them.
- RiskEngine is the single sizing authority. Enforce a maximum risk of 1% per trade and minimum reward/risk of 2.0R. Do not shrink stops to force eligibility; unsafe or incomplete plans remain WAIT/non-executable.
- Bot status must reflect a verified broker session. Network loss, expired credentials, missing permissions, stale market data, or failed risk/news/session checks must prevent new live orders.
- Telegram must not show Connected or Delivered without an actual verified bot/chat result.

## Change and verification protocol

1. Trace the existing execution path before editing; fix the smallest safe set of files.
2. Preserve user data, Room migrations, sign-in behavior, navigation, and existing configuration.
3. Add or update focused tests for each behavioral fix.
4. Run:
   ```bash
   ./gradlew testDebugUnitTest --stacktrace
   ./gradlew assembleDebug --stacktrace
   ```
5. Review the actual CI result for the new commit. Do not report a passing build until the run finishes successfully.
6. A successful unit-test/build run is not proof that WebView, login, broker connectivity, Telegram delivery, or chart candles work on a real device. State which runtime checks were or were not performed.
7. Commit coherent, scoped changes directly to the existing repository/branch when authorized; report the commit SHA, changed paths, CI result, and remaining device-only checks.

## Current recovery principle

Dependency initialization must fail closed: set the app to Ready only after all required dependencies have been created. On a recoverable initialization failure, expose the error and a working retry path. Never bypass initialization with a timeout or instantiate dependency-backed ViewModels before the app is Ready.
