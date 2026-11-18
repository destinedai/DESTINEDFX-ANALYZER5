# DestinedFX Analyzer 5

DestinedFX is a native Android application built with Kotlin and Jetpack Compose. It combines market-data analysis, account and risk monitoring, and broker-specific automated execution paths. The Android app, Python analysis module, FastAPI Bridge, managed worker, and MT5 Expert Advisor are separate parts of the system. A file or CI test in this repository does not mean that a public Bridge, terminal, broker account, or trading session is running.

## What the Android app does

The primary workspaces are **Home, Analyzer, Bot, Watchlist, and More**. Settings contains the DestinedFX management account and provider/connection preferences. The Bot workspace contains broker login and account profiles, the remote MT5 Bridge panel, account telemetry, EA controls, and existing-order/position management.

| Workspace | Actual responsibility |
|---|---|
| Home | Account/market overview and current app state |
| Analyzer | Provider quotes and OHLC candles, deterministic Kotlin signal analysis, multi-timeframe context, strategy evidence, blockers, and chart display |
| Bot | Broker selection and login flows, account-mode controls, managed MT5 Bridge/EA monitoring, Deriv multiplier service controls, and supported position/order management |
| Watchlist | Selected-market and feed monitoring |
| More / Settings | Management account, preferences, diagnostics, and supporting app workspaces |

The app must distinguish market analysis from broker evidence. A public quote, TradingView chart, local signal, HTTP 200, or queued Bridge command is not proof of a logged-in broker account or a filled order.

## Analyzer: how market data becomes a signal

### Data sources

The selected public analysis source defaults to DERIV_PUBLIC. The Deriv market-data gateway connects to the public WebSocket, reads the current active-symbol directory, and requests provider OHLC history for the exact instrument it has verified. The app does not construct substitute instrument codes or manufacture candles when a provider request fails.

OANDA is an optional alternative source when the owner configures the required API key and account ID. A selected source is not silently replaced with another venue. Quote and candle provenance is compared before the result can become executable. The chart uses TradingView for display; TradingView is not treated as the application's OHLC API or broker execution feed.

### Analysis sequence

1. The repository refreshes the selected provider quote and requests OHLC history for the selected symbol and timeframe.
2. Invalid, duplicate, future-dated, zero-price, or malformed candles are discarded. The analysis path uses closed candles and validates timestamp order and interval gaps.
3. The Kotlin deterministic SignalEngine evaluates the strategy, market structure, selected higher-timeframe context, spread, session, and authenticated news verdict.
4. A trade plan is displayed only when the engine returns a valid plan with correctly sided entry, stop and target and at least the configured minimum reward/risk. A WAIT, BLOCKED, or incomplete result is not converted into BUY/SELL.
5. The packaged Python quantitative module runs as a secondary, analysis-only check on the same identified candle snapshot. It does not submit orders and does not assign a win probability. Python/Kotlin disagreement is surfaced and blocks execution; Python is not used as a replacement for a missing Kotlin result.

The Analyzer status card reports quote, candle, chart, analysis and trading readiness. It also shows the provider's stored OHLC failure reason and has a **REFRESH VERIFIED QUOTE & OHLC DATA** action. If there is no fresh quote or validated history, the app reports the reason and keeps the signal non-actionable. It does not substitute demo prices or sample candles.

### Provider wait budgets

Deriv symbol discovery can require a WebSocket connection followed by an active-symbol response; candle history can then require another request. The repository's outer timeouts now allow these nested provider operations to complete rather than cancelling them at the previous 5-second symbol-resolution or 10-second candle boundary. Current source budgets are 30 seconds for active-symbol discovery, 32 seconds for repository symbol resolution, 30 seconds for candle history, and 65 seconds for the complete manual OHLC/MTF load path. These are request budgets, not a guarantee that an upstream provider will return data.

DATA_UNAVAILABLE remains a valid result when the feed is disconnected, the symbol is unsupported, the provider returns no usable history, the candle series fails integrity checks, or the snapshot is stale. The reason should be visible in the Analyzer diagnostics. It must never be hidden by fabricated prices or an unsafe fallback.

## Bot: login, pairing, orders and controls

### Account and Bridge login

The MT5 managed-account path uses two separate identities:

- The **DestinedFX management account** signs a short-lived management identity assertion. The app stores the management session in encrypted Android storage when secure storage is available.
- The **broker account** is selected separately by broker brand and DEMO/LIVE profile. The owner enters the broker login number, exact broker-issued server, password and MASTER or INVESTOR access type, and explicitly consents to the managed connection. Broker credentials are not management-account credentials.

For initial MT5 managed registration, the app checks secure storage, a valid HTTPS Bridge origin, Bridge configuration, and host per-user terminal isolation before submitting broker credentials. It saves each profile separately and binds the request to the selected broker/mode and credential generation.

The app then pairs the selected profile with the Bridge. When the public Bridge reports public_exposure, MT5RemoteBridgeClient requests a one-use, ten-minute owner bootstrap code over HTTPS using the signed management identity. The raw bootstrap code is not saved on the phone. The Bot's bootstrap-code field is a manual recovery fallback; leave it blank for automatic issuance. The resulting app token is stored in encrypted app storage. A pairing token is not a broker password and does not itself verify that MT5 is logged in.

The Bridge provisions an isolated per-pairing worker/terminal slot. The app treats the account as verified only after the terminal reports the exact login, server, company/broker identity and account mode and sends fresh EA heartbeat evidence. A missing or stale heartbeat is OFFLINE/UNVERIFIED, not connected.

### MT5 automated entry path

The app does **not** expose a phone-side arbitrary BUY, SELL, or PLACE MARKET ORDER Bridge command. Its managed MT5 command contract is:

- START, STOP, PAUSE, RESUME, and EMERGENCY_STOP for EA lifecycle control.
- CANCEL_PENDING_SELECTED, CANCEL_EA_PENDING, CLOSE_POSITION_SELECTED, and CLOSE_ALL for supported management of already-existing orders or positions.

When the owner starts the EA, the phone queues a profile-scoped command with a UUID, selected managed strategy, symbol/timeframe, and risk configuration. The server returns a command ID and queue state. The app does not call a queued command a fill. It polls the Bridge and waits for the matching terminal acknowledgement and fresh heartbeat.

The **MT5 EA** owns new entries. After START, its scan evaluates configured symbols/timeframes and closed-candle setups. For an eligible setup the EA performs its own broker-side trade.Buy() or trade.Sell() call. Before submitting, the EA rechecks account identity and mode, the authenticated Bridge heartbeat (15-second stale boundary), terminal and broker permissions, daily risk, concurrent-position cap, symbol selection, fresh quote, stop/target side and distance, verified commission model, spread, slippage allowance, net reward/risk, available margin, news verdict, signal de-duplication, and persisted order-intent state. It reconciles the broker return code/deal/order and reports FILLED, PARTIALLY_FILLED, ORDER_SUBMITTED, REJECTED, or UNKNOWN as supported by the actual response.

This is EA-managed automated execution, not an Android manual ticket. An EA strategy being present in the registry does not prove that it is profitable or has been tested on a real broker.

### Existing position and pending-order management

The Bot only enables position/order actions from a fresh identity-matched terminal snapshot. It checks exact ticket/position identifiers and EA ownership before sending a change. CLOSE_ALL requires a server-issued one-use confirmation token. After a command, the UI reconciles the server command, EA per-ticket acknowledgement and later terminal snapshot; an uncertain result remains UNKNOWN and is not blindly resent.

STOP and EMERGENCY_STOP block new entries; they are not a command to close every open position. Closing positions and cancelling pending orders are separate, explicit actions. The EA has its own daily-risk emergency exit path; that is separate from the ordinary Bot STOP control.

### Other broker execution paths

- **Deriv Options:** a separate Android foreground service implements supported MULTUP/MULTDOWN multiplier flows for supported synthetic underlyings. It requires a verified selected Deriv account, current DEMO acceptance, fresh quotes, strategy/product support and Deriv-specific risk/session checks. The Bot blocks Forex/CFD symbols in this multiplier route. The current service recovery path is DEMO-only; do not treat a saved REAL setting as a restarted LIVE session.
- **cTrader:** OAuth/account and market-data integration is read-only. cTrader order placement is not implemented.
- **Generic MT5 direct adapter / generic Forex/CFD route:** does not bypass the managed EA Bridge path or the common mode/risk policy. The app's generic direct MT5 adapter is not a substitute for the managed MT5 EA order route.

## DEMO, LIVE and host profile safety

ModeArmingPolicy is the shared mode gate. DEMO acceptance is account- and credential-generation-bound. LIVE requires a separate verified LIVE profile, fresh account/terminal evidence, explicit management reauthentication, exact typed confirmation, risk checks and a short-lived process-local lease. Restarting the app or expiring the lease does not silently restore LIVE authorization.

**LIVE requires a persistent host.** The Bridge reports PERSISTENT or EPHEMERAL; missing or unknown host metadata is treated as EPHEMERAL. The app and EA fail closed when the host is not independently verified as persistent. The Bot now displays the observed host profile and the reason LIVE is blocked. This policy has intentionally not been removed.

The repository's GitHub Codespaces MT5 host is an ephemeral development/DEMO test host, not a persistent production host. It has a finite lifetime and GitHub idle shutdown behavior. It cannot be used to claim LIVE readiness. The owner-operated Windows/Tailscale deployment recipe uses durable Bridge storage and a trusted TLS proxy, but it still needs the actual owner host, certificates, secret configuration, worker and terminal to be deployed and verified. The Render Free blueprint is deliberately liveness-only and non-durable; it is not a trading Bridge.

The phone's Codespaces controls can authorize GitHub using the GitHub device flow and start/stop the repository Codespace. The Bridge origin still has to be configured on the phone; automatic discovery and verification of the forwarded port is not yet a completed integration. The public Codespaces pairing bootstrap can be obtained automatically after the origin is configured and the signed management identity is available.

## Read-only repository and Bridge audit

Run the project audit from the repository root:

~~~sh
python scripts/destinedfx_readiness_audit.py
python scripts/test_destinedfx_readiness_audit.py
~~~

The audit checks source contracts for the Analyzer timeout hierarchy and provider diagnostics, Bot pairing and command paths, EA order safeguards, the persistent-host LIVE gate, and README coverage. It is read-only. It does not deploy or start a host.

To inspect an already-running Bridge, pass its bare HTTPS origin:

~~~sh
python scripts/destinedfx_readiness_audit.py --bridge-url https://your-bridge.example
~~~

This makes read-only GET requests to /health, /health/config, and /health/ready. To also request /v1/app/state, set DESTINEDFX_AUDIT_APP_TOKEN in the local environment and run:

~~~sh
DESTINEDFX_AUDIT_APP_TOKEN='your-app-token' \
  python scripts/destinedfx_readiness_audit.py --bridge-url https://your-bridge.example --authenticated-state
~~~

The token is read from the environment, sent only to /v1/app/state, and is never printed or written to disk. The report omits account login, server, password and token values. A successful health/readiness probe does not prove an MT5 terminal, EA heartbeat, broker login, or accepted broker order.

## Build and test

Requirements depend on the task: a compatible JDK and Android SDK for Android builds, Python 3.12 for the packaged/offline analysis and Python tests, and Windows with MetaEditor for genuine .ex5 EA compilation.

~~~sh
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
./gradlew connectedDebugAndroidTest --stacktrace  # connected device or emulator
python -m pytest -q bridge scripts/test_deriv_demo_smoke.py
python scripts/test_destinedfx_readiness_audit.py
python scripts/destinedfx_readiness_audit.py
python -m pytest -q managed-worker mt5-ea
~~~

Install bridge/requirements.txt before running the Bridge tests. See [Android setup](docs/development/ANDROID_SETUP.md), [build and test details](docs/development/BUILD_AND_TEST.md), [system architecture](docs/architecture/SYSTEM_ARCHITECTURE.md), and [data provenance](docs/security/DATA_PROVENANCE.md).

## Deployment and verification boundary

The workflows under .github/workflows/ validate source, build artifacts and run offline/unit/emulator tests. Each result applies only to the exact commit and completed jobs. A debug APK is not a production-signed release.

The following require owner-side runtime evidence and are not established by a green CI run:

- A live Codespace or persistent Bridge host running and externally reachable.
- A compatible Wine/MT5 terminal starting cleanly, with the real EA attached and authenticated.
- A broker-owned DEMO login, verified company/server identity, fresh heartbeat and successful demo order/fill/reconciliation.
- Full Android Bot UI comparison against MT5 Android and physical-device operation.
- Persistent-host deployment, production management-key configuration, provider credentials and independent LIVE verification.

Never add broker credentials, management assertions, pairing tokens, GitHub tokens, TLS private keys, or API secrets to source control, APK resources, issue comments, CI output, audit reports or this README. Do not enable LIVE or remove a safety gate merely to make the UI appear ready.

## Repository map

- app/: Android UI, domain/data layers, services, Python module and tests.
- bridge/: FastAPI relay, pairing, managed-account, worker and command API.
- managed-worker/: Windows managed-worker package and diagnostics.
- mt5-ea/: MQL5 EA source, order journal, strategy scan and tests.
- scripts/: host, build, smoke, deployment-contract and readiness-audit scripts.
- docs/: architecture, security, provider and deployment guides.

## License

No license has been selected. All rights and licensing choices remain with the repository owner.
