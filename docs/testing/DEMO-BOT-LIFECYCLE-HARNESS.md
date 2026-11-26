# DEMO Bot lifecycle acceptance harness

## What runs

\`DerivBotLifecycleInstrumentedTest\` executes on a clean Android API 34 emulator or an attached Android test device. It starts the real Android foreground service, uses the real \`DerivMultiplierExecutor\` and Room execution ledger, and substitutes only broker I/O and Bot-owned candles with an in-process fake DEMO gateway.

It records UTC timestamps, state before/after each action, fake broker gateway events, purchase IDs and sell IDs for:

1. Start and Run, including repeated Start while the loop is active.
2. Pause, with no new purchase and an existing position left under broker monitoring.
3. Resume, with one new closed-candle setup identity.
4. Stop, closing only contracts owned by the Bot execution ledger.
5. Emergency-Stop, running the existing disarm + Stop + REAL-authorization invalidation sequence.
6. No automatic restart after Stop or Emergency-Stop, followed by an explicit manual restart.

Before Stop and Emergency-Stop, the fake broker injects an unowned open contract. The test requires the Bot to leave that contract untouched.

## Safety boundary

- The fake identity is a DEMO account; the fake gateway rejects non-DEMO connect requests.
- The PAT and App ID written to secure storage are CI-only fake values. The test never opens a broker socket.
- The actual Android service, mode verification, execution ledger, executor and close-out logic are used.
- Only a debuggable build can use the injected test gateway. Release builds remain on the production Deriv gateway and candle source.
- The existing STOP and Emergency-Stop implementation is not changed.
- No LIVE authorization is created or enabled.

## Run

On an Android API 34 emulator or attached Android test device with ADB:

\`\`\`bash
adb devices -l
./gradlew :app:connectedDebugAndroidTest --stacktrace
adb logcat -s DestinedFXBotLifecycle
\`\`\`

CI also retrieves and validates:

\`app/build/reports/bot-lifecycle/bot-lifecycle-evidence.json\`

The JSON is uploaded inside the \`Android-emulator-test-reports\` artifact. Each transition contains \`timestamp_utc\`, \`state_before\`, \`state_after\`, \`broker_gateway_log\`, \`account_mode=DEMO\`, \`fake_gateway_only=true\`, and \`live_enabled=false\`.

## Review

- Start: one service loop and one \`PURCHASE_CONFIRMED\`; repeated Start creates neither a second loop nor another order.
- Pause: \`PAUSED\`, no added purchase, existing position stays open until broker settlement.
- Resume: the same service loop resumes and sends one new setup with a unique signal identity.
- Stop: broker confirms close of the Bot-owned contract; injected unowned contract is not sold.
- Emergency-Stop: existing disarm sequence runs; Bot-owned DEMO contract closes and unowned contract remains.
- Recovery: null-intent service restart after either stop sends no purchase; explicit owner Start does.

## What this does not prove

This is an Android service test on an emulator/test device with a fake broker. It is not a connection to a real Deriv broker, a physical MT5 terminal, or a persistent Linux/Windows trading host. It does not prove live host availability or authorize LIVE trading. Owner-host acceptance remains separate.
