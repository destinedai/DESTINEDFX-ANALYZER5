# DestinedFX phone-to-MT5 remote controller

The Android app is the remote controller. The actual MT5 terminal and broker execution remain on the user's separately provisioned Windows host. Android sends authenticated, account-bound commands through the Bridge; it never talks to the MT5 terminal process directly and does not calculate or supply lot size for managed market orders.

## Implemented managed MT5 capabilities

| MT5 mobile workflow | Current DestinedFX path | Status |
|---|---|---|
| Account identity and mode | Per-broker DEMO/LIVE profile, exact login/server/company/currency, MASTER/INVESTOR permissions | Implemented with verification gates |
| Live terminal quotes | Fresh bid/ask snapshots from the paired terminal and exact quote timestamp | Implemented |
| Account metrics | Balance, equity, margin, free margin, margin level, leverage | Implemented where the broker/terminal reports them |
| Positions | Fresh position snapshot, selected close, explicit close-all confirmation | Implemented |
| Pending orders | Snapshot and selected/EA-owned cancel | Implemented |
| Manual market BUY/SELL | Phone confirmation, SL/TP and risk input, exact quote binding, Bridge validation, Windows EA risk sizing and submission | Implemented for first configured EA symbol |
| Automated strategy | Start, pause, resume, stop, strategy configuration, risk guards and EA acknowledgements | Implemented for registered managed strategies |
| History | Broker closed-deal snapshots and trade-report synchronization | Implemented, subject to broker history returned by terminal |
| Analyzer charts | Separate analysis/provider market-data path | Available, but not claimed as the same broker-native MT5 chart |
| Pending order placement | Limit/stop order entry from phone | Not yet implemented |
| Modify position SL/TP | Remote broker-side position protection edits | Not yet implemented |
| Modify pending order | Remote pending entry/SL/TP/expiry edits | Not yet implemented |
| Full MT5 chart workflow | Broker OHLC/timeframe switching and terminal-native chart object/indicator settings | Not yet verified |
| Market Depth | Broker DOM subscriptions and level snapshots | Not yet implemented/verified |
| MT5 news and mailbox | Terminal-native news/messages/mailbox | Not yet implemented; economic-calendar entry guard is separate |

A screen that looks like an MT5 control is not evidence of feature parity. The unimplemented items above must remain visibly unavailable until they are wired to fresh broker data, a defined Bridge command and an EA-side implementation with tests.

## Manual market order contract

The phone sends `PLACE_MARKET_ORDER` with:
- selected account login, account-issued server and DEMO/LIVE mode;
- the first EA-configured symbol and BUY/SELL side;
- the exact fresh MT5 bid/ask and quote timestamp;
- stop-loss, take-profit and requested risk percentage.

The phone does **not** send lot size. The Bridge checks the authenticated MASTER profile, persistent host, current account identity, quote freshness/match, economic-calendar verdict, daily risk telemetry, risk cap, concurrency cap and (for LIVE) the account-bound 15-minute LIVE lease. The command expires after 20 seconds.

The Windows EA rechecks terminal/account identity, terminal/broker trade permission, quote deviation, news, daily risk, existing unresolved order state, broker contract data, commission/spread/slippage cost, stop distance, margin and the configured lot cap. It sizes the order using the EA's existing `OrderCalcProfit` and `OrderCalcMargin` risk path. The user-supplied TP must satisfy the existing cost-adjusted net 1:2 minimum reward/risk; it is not silently moved.

The EA records a durable order intent before calling the broker. The Bridge returns command acknowledgement separately from order fill. The app keeps the exact request ID until a fresh EA heartbeat carries the matching `signal_key` and a reconciled execution state. Timeouts and UNKNOWN results are not resent automatically.

### Current symbol limitation

The EA heartbeat currently carries one news verdict at a time, for the first configured symbol. Until per-symbol news telemetry is implemented, manual market orders are limited to that same first configured symbol. The app does not reuse a verdict for another symbol.

## DEMO and LIVE

DEMO and LIVE are both selectable profile modes. A DEMO market order needs the exact authenticated DEMO MASTER profile, persistent terminal, fresh quotes, clear news/risk evidence and an explicit order review. A prior DEMO soak acceptance is not required merely to place a controlled DEMO test order; it remains required for LIVE association/authorization.

LIVE requires a separately paired LIVE account, same-user/same-broker accepted DEMO profile, verified persistent Windows host, fresh MT5 LIVE heartbeat, MASTER trade permission, fresh risk/news evidence, management password reauthentication and exact typed confirmation. The lease is account-bound and expires. An expired/absent lease blocks new LIVE orders; there is no global or build-time LIVE bypass.

Multi-user operation additionally requires independent per-user Windows process/terminal isolation. A terminal-capacity number is not proof of OS-level isolation. Do not claim concurrent multi-user readiness until the preflight reports isolation VERIFIED and the owner host has been capacity-tested.

## Fast Python verification

From the repository root:

```bash
python scripts/mt5_remote_controller_preflight.py
```

This performs offline source-contract checks only. To add read-only runtime checks after deployment:

```bash
python scripts/mt5_remote_controller_preflight.py \
  --management-url https://<management-api-origin> \
  --bridge-url https://<private-windows-bridge-origin>
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
