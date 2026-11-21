# DestinedFX Android MT5 workflow parity audit

**Audit date:** 2026-11-21  
**Scope:** Android Bot workstation, MT5RemoteBridgeClient, Bridge command validation, and DestinedFXTraderEA.mq5. This is a source audit, not a claim of a completed broker-session acceptance test.

## Feature-by-feature status

| MT5 Android workflow area | DestinedFX implementation observed | Status / limitation |
|---|---|---|
| Quotes | The Bot workstation displays quotes returned by the authenticated account's MT5 terminal heartbeat. It verifies account/broker identity and heartbeat freshness, filters malformed/stale quotes, displays bid/ask and source/age. This branch adds stable symbol ordering and exact price spread. | **Implemented for terminal-reported symbols.** It does not replace missing broker quotes with analyzer/provider prices. |
| Charts | The app has chart/analyzer surfaces, but the Bot workstation does not receive a complete native MT5 chart stream directly from the authenticated terminal. | **Partial.** Do not describe the current external/analyzer chart feed as MT5 terminal candles. |
| Timeframes | Strategy configuration permits the EA-supported timeframes and checks that the terminal has fresh quotes and sufficient broker history before starting. | **Implemented for the validated strategy list, not full MT5 chart-timeframe parity.** Current EA ParseTF accepts M1, M5, M15, M30, H1, H4 and D1; the Bot strategy selector deliberately restricts its own primary scan set to M15, M30, H1, H4, D1 and W1. |
| Indicators | Managed strategies calculate their own strategy signals from broker history. There is no phone-side catalogue/parameter editor exposing the full native MT5 indicator set on terminal charts. | **Partial; not MT5 indicator parity.** |
| Trade / manual entry | Phone market-order command is account/profile-bound and validated by the Bridge and EA against fresh identity, quote, margin, news and risk state. The phone does not treat command acknowledgement as a fill. | **Implemented for configured symbol and guarded BUY/SELL market orders.** No arbitrary-symbol or unguarded order submission. |
| Account metrics | Balance, equity, margin, free margin, margin level and leverage are published in the account heartbeat and verified against the selected profile. | **Implemented for fresh, identity-verified terminal snapshots.** |
| Open positions | Positions are read from the broker heartbeat. A selected position can be closed by exact position_id; close-all requires the list to match a fresh snapshot and a one-use server confirmation. | **Implemented with broker reconciliation.** Close-all never includes pending orders and does not run from an unknown/stale snapshot. |
| Pending orders | Active broker orders are displayed. The phone can cancel one selected pending order or request cancellation of EA-owned pending orders. | **Read/cancel implemented.** Pending-order placement and edit/modify are not in the current command protocol. |
| Order modification | The phone/EA protocol supports a tighten-only stop-loss change for a selected position. | **Safety-limited partial implementation.** Widening/removing SL and editing TP are not supported by the phone command path. |
| History | The EA publishes closed broker deals; Bridge/client parse the closed-deal snapshot for reporting/reconciliation. | **Implemented for terminal-reported history, subject to broker history synchronization and freshness.** |
| Market Depth | No MarketBookAdd/MarketBookGet order-book payload is present in the current EA heartbeat protocol. | **Not integrated.** Do not invent depth from bid/ask or imply support for symbols/brokers with no depth feed. |
| News / alerts | The authenticated heartbeat carries a news verdict, and the EA gates new entries against its news/risk state. | **News guard integrated.** This is not a full replica of the native MT5 news/calendar screen or account mailbox. |
| Messages / mailbox | The phone shows DestinedFX bridge/command status and reconciliation messages. It does not expose an MT5 terminal mailbox/messages transport. | **Partial; native MT5 mailbox is not integrated.** |
| Settings | Broker profile, DEMO/LIVE mode, EA strategy, symbols, timeframe, risk caps and app-managed settings are available. LIVE activation is separate and requires the management identity step-up plus a fresh exact terminal/account identity. | **DestinedFX settings implemented; not a full clone of every MT5 Android setting.** |

## Guardrails that must remain

- A stale heartbeat or unverified broker login/server/company/account mode withholds terminal prices and disables broker operations.
- A queued/accepted phone command is not a confirmed fill. The EA persists operation intent, acknowledges outcomes, and waits for fresh broker positions/history/orders for reconciliation. Unknown outcomes are never blindly re-submitted.
- STOP, PAUSE and EMERGENCY STOP stop new entries; they do not silently close positions or alter broker SL/TP.
- Phone position management can tighten SL only. No widening or removal of protection is exposed by the reviewed phone path.
- LIVE remains separately authorized, time-limited and profile-bound. No LIVE trade was authorized or submitted as part of this audit.

## Changes in this branch

- Broker quotes are displayed in deterministic case-insensitive symbol order.
- Exact spread is calculated from that terminal quote's own ask minus bid, formatted using the quote's broker-reported digits. It is not converted into broker "points" because the EA heartbeat does not include symbol tick-size metadata.
- Invalid/non-finite/negative spread values are withheld.
- Tests cover ordering and spread validation.

## Acceptance still required

Source tests cannot prove a real MetaTrader Android session. The final release acceptance must compare the Bot screen on an Android device with MT5 Android and, against at least one real broker DEMO account, verify quotes, chart/history synchronization, trade tab metrics, position/order list, cancellation/closure, modify protection, timeframe changes, news state, reconnect behavior, Android rotation/background resume, and offline/stale-heartbeat handling.

Market Depth, pending-order creation/modification, wider order-management features, full native indicator/chart integration, and native mailbox/news should remain explicitly marked unsupported until the EA, Bridge, Android UI, and real broker acceptance all implement them end-to-end.
