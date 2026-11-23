# DestinedFX phone-to-MT5 remote controller

The Android app is the remote controller. The production MT5 terminal and broker execution run on the user's persistent, owner-operated Ubuntu x86-64 Linux host. MetaTrader 5 terminal and EA binaries remain Windows applications and run in each isolated per-pairing Wine runtime; this is not native Linux MT5 support. Android sends authenticated, account-bound commands through the Bridge; it never talks to the terminal process directly and does not calculate or supply lot size for managed market orders.

## Host runtime architecture and support limits

The production target is the root-attested Linux slot runtime documented in [HOSTING.md](../HOSTING.md) and [the Linux host guide](../scripts/linux-host/README.md). Each authenticated pairing is provisioned under its own non-login Unix identity with a private Wine prefix, terminal tree, Xauthority file and display. LIVE remains gated by a persistent host profile, fresh root-agent isolation evidence, at least two simultaneously active isolated slots for the linked DEMO/LIVE profiles, account identity, risk/news/session checks and explicit authorization.

A separate owner-operated Windows Bridge/worker kit remains a compatibility path. It currently launches accounts under the same Windows identity; separate portable directories do not establish OS-level process isolation. Do not claim Windows multi-user LIVE support until distinct Windows process identities and account-specific ACLs are independently verified. Genuine MetaEditor compilation in CI runs on Windows; that compile does not prove a deployed Linux host or broker connection.

## Implemented managed MT5 capabilities

| MT5 mobile workflow | Current DestinedFX path | Status |
|---|---|---|
| Account identity and mode | Per-broker DEMO/LIVE profile, exact login/server/company/currency, MASTER/INVESTOR permissions | Implemented with verification gates |
| Live terminal quotes | Fresh bid/ask snapshots from the paired terminal and exact quote timestamp | Implemented |
| Account metrics | Balance, equity, margin, free margin, margin level, leverage | Implemented where the broker/terminal reports them |
| Positions | Fresh position snapshot, selected close, explicit close-all confirmation | Implemented |
| Pending orders | Snapshot and selected/EA-owned cancel | Implemented |
| Manual market BUY/SELL | Phone confirmation, SL/TP and risk input, exact quote binding, Bridge validation, MT5 EA risk sizing and submission | Implemented for first configured EA symbol |
| Automated strategy | Start, pause, resume, stop, strategy configuration, risk guards and EA acknowledgements | Implemented for registered managed strategies |
| History | Broker closed-deal snapshots and trade-report synchronization | Implemented, subject to broker history returned by terminal |
| Analyzer charts | Separate analysis/provider market-data path | Available, but not claimed as the same broker-native MT5 chart |
| Pending order placement | Limit/stop order entry from phone | Not yet implemented |
| Tighten position SL | Exact-ticket, account-bound, quote-bound stop tightening; EA checks broker stop/freeze distance and preserves the current TP | Implemented (tighten-only) |
| Widen/remove position SL or modify TP | Broader broker-side protection changes | Not implemented; loosening/removal is intentionally blocked |
| Modify pending order | Remote pending entry/SL/TP/expiry edits | Not yet implemented |
| Full MT5 chart workflow | Broker OHLC/timeframe switching and terminal-native chart object/indicator settings | Not yet verified |
| Market Depth | Broker DOM subscriptions and level snapshots | Not yet implemented/verified |
| MT5 news and mailbox | Terminal-native news/messages/mailbox | Not yet implemented; economic-calendar entry guard is separate |

A screen that looks like an MT5 control is not evidence of feature parity. The unimplemented items above must remain visibly unavailable until they are wired to fresh broker data, a defined Bridge command and an EA-side implementation with tests.


## Detailed parity and dependency audit

These capabilities are split between the Analyzer chart/market-data path and the broker-native MT5 terminal path. A similarly named screen, snapshot, or Analyzer feature does not establish MT5 Android parity.

| Requested capability | Existing implementation | Missing phone, Bridge and EA work | Regression coverage and dependencies |
|---|---|---|---|
| Pending orders | Authenticated active-order snapshots and selected/EA-owned pending-order cancellation | No pending-order creation form or PLACE_PENDING command; no general pending-order modify/expiry command or screen | Existing snapshot and cancellation tests cover read/cancel only. Placement needs typed parameters, broker tick/stop/freeze validation, expiry handling, EA CTrade submission, idempotency and per-ticket reconciliation. |
| Full TP/SL editing | Exact-ticket, account-bound and quote-bound monotonic SL tightening; existing TP is preserved | No TP edit, SL widening/removal, or unrestricted protection form | Client and EA safety tests cover tighten-only. Broader edits need a separate risk policy, fresh quotes, broker constraints, confirmation and EA reconciliation. Loosening/removal remain blocked. |
| Native MT5 chart | TradingChartContainer and Analyzer/provider OHLC are available; Bridge validates terminal-origin candles in the authenticated state snapshot | No complete remote MT5 terminal chart-object/template workflow or chart command proving actual terminal chart state | Analyzer chart and candle parser tests do not prove terminal parity. Requires broker-terminal history, symbol mapping, account-bound chart interface and stale-data validation. |
| Timeframe controls | Analyzer has its own market-analysis timeframe selection; the verified-candle parser accepts a bounded timeframe set | No verified command to change the actual MT5 terminal chart timeframe from the phone and retrieve matching terminal chart state | Analyzer timeframe and candle parser tests only. Requires terminal-side timeframe selection, history synchronization and selected-account/symbol checks. |
| Indicator controls | EA internal signal/risk calculations and Analyzer indicators exist | No remote add/remove/configure interface for indicators attached to an MT5 terminal chart; no Bridge contract or terminal chart-object implementation | Signal engine and EA calculation tests are not terminal indicator parity. Requires a supported-indicator list, parameter validation, chart object lifecycle and per-terminal persistence. |
| Market Depth / DOM | No supported broker DOM subscription or depth-level snapshot is exposed by the managed Bridge | No phone Depth screen, Bridge model, or EA MarketBookAdd/OnBookEvent collection path | No DOM acceptance test exists; it must remain unavailable rather than display simulated depth. Requires broker and symbol support. |
| MT5 native news/mailbox | An authenticated economic-calendar verdict is used as a separate entry/risk guard | No terminal-native MT5 news stream, mailbox/messages view, message cursor or per-account notification reconciliation | Calendar freshness and order-block tests cover only calendar gating, not native MT5 news/mailbox parity. Requires a distinct terminal-source contract and UI reconciliation. |

### Availability rules

- Implemented snapshot/cancel and tighten-only actions may appear only when the selected authenticated account snapshot is fresh and identity-matched.
- Pending placement, broad protection edits, native chart controls, terminal indicator controls, Depth and native mailbox/news must remain visibly unavailable until the phone UI, account-bound Bridge API, EA runtime and behavior-focused tests are all implemented.
- Analyzer/provider charts, timeframe selection and indicators must be labelled as Analyzer functionality, not terminal-native MT5 features.
- A placeholder screen, mock service, parser-only test or source-string assertion does not complete a capability.


## Manual market order contract

The phone sends `PLACE_MARKET_ORDER` with:
- selected account login, account-issued server and DEMO/LIVE mode;
- the first EA-configured symbol and BUY/SELL side;
- the exact fresh MT5 bid/ask and quote timestamp;
- stop-loss, take-profit and requested risk percentage.

The phone does **not** send lot size. The Bridge checks the authenticated MASTER profile, persistent host, current account identity, quote freshness/match, economic-calendar verdict, daily risk telemetry, risk cap, concurrency cap and (for LIVE) the account-bound 15-minute LIVE lease. The command expires after 20 seconds.

The MT5 EA rechecks terminal/account identity, terminal/broker trade permission, quote deviation, news, daily risk, existing unresolved order state, broker contract data, commission/spread/slippage cost, stop distance, margin and the configured lot cap. It sizes the order using the EA's existing `OrderCalcProfit` and `OrderCalcMargin` risk path. The user-supplied TP must satisfy the existing cost-adjusted net 1:2 minimum reward/risk; it is not silently moved.

The EA records a durable order intent before calling the broker. The Bridge returns command acknowledgement separately from order fill. The app keeps the exact request ID until a fresh EA heartbeat carries the matching `signal_key` and a reconciled execution state. Timeouts and UNKNOWN results are not resent automatically.

### Current symbol limitation

The EA heartbeat currently carries one news verdict at a time, for the first configured symbol. Until per-symbol news telemetry is implemented, manual market orders are limited to that same first configured symbol. The app does not reuse a verdict for another symbol.


### Position stop tightening

The phone can select a fresh open-position ticket and request a tighter SL. The Bridge checks the current ticket, direction, old SL, fresh bid/ask and exact account. The MT5 EA then rechecks the authenticated Bridge session, position ticket, old SL, current quote, broker tick size and stop/freeze levels before using `CTrade.PositionModify(ticket,newSL,currentTP)`. The existing TP is passed through unchanged. BUY stops can only move upward and remain below bid; SELL stops can only move downward and remain above ask. A wider or removed SL is rejected. The app only clears the pending request after a fresh heartbeat shows the same ticket with the new SL.

## DEMO and LIVE

DEMO and LIVE are both selectable profile modes. A DEMO market order needs the exact authenticated DEMO MASTER profile, persistent terminal, fresh quotes, clear news/risk evidence and an explicit order review. A prior DEMO soak acceptance is not required merely to place a controlled DEMO test order; it remains required for LIVE association/authorization.

LIVE requires a separately paired LIVE account, same-user/same-broker accepted DEMO profile, verified persistent Linux production host, fresh MT5 LIVE heartbeat, MASTER trade permission, fresh risk/news evidence, management password reauthentication and exact typed confirmation. The lease is account-bound and expires. An expired/absent lease blocks new LIVE orders; there is no global or build-time LIVE bypass.

Multi-user operation on the production Linux path requires root-attested independent Unix identities, private Wine prefixes, terminal trees, displays and Xauthority files for the simultaneously active DEMO and LIVE pairings. A terminal-capacity number alone is not proof of OS-level isolation. The Windows compatibility worker still lacks independently verified per-user process/ACL isolation and must not be presented as a multi-user LIVE host.

## Fast Python verification

From the repository root:

```bash
python scripts/mt5_remote_controller_preflight.py
```

This performs offline source-contract checks only. To add read-only runtime checks after deployment:

```bash
python scripts/mt5_remote_controller_preflight.py \
  --management-url https://<management-api-origin> \
  --bridge-url https://<private-linux-production-bridge-origin>
```

The runtime probe uses HTTPS GET only. It checks management readiness and public Ed25519 key publication, Bridge persistence/news configuration, key fingerprint and issuer/audience match, terminal readiness and multi-user isolation. It does not ask for broker credentials, read private signing keys, create a pairing, arm LIVE or submit an order.

Also run the dedicated suites:
```bash
python -m pytest -q bridge
python -m pytest -q managed-worker
python -m pytest -q scripts/test_mt5_remote_controller_preflight.py
python -m pytest -q mt5-ea/test_ea_source.py mt5-ea/test_ea_safety_math.py
```
Android JVM/APK and genuine MetaEditor/Windows packaging tests run in GitHub Actions.
