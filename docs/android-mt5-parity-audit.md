# DestinedFX Android vs MetaTrader 5 Android — feature parity audit

Audit date: 2026-11-19  
Audited branch: `free/phone-only-v1`  
Scope: source-level review of the Android UI, MT5 Bridge client, Bridge command contract, chart container, calendar and notification paths. This is not a physical-device acceptance report.

## Status meanings

- **Implemented in source** means a corresponding code path exists. It does not mean a real broker, phone, market feed or host was exercised.
- **Partial** means a related DestinedFX feature exists but differs materially from the MT5 Android workflow or omits part of it.
- **Not implemented / not found** means no supported implementation was found in the audited Android and Bridge paths. Do not present it as supported.
- **Owner acceptance required** means real device, broker DEMO account, host or push-provider evidence is required.

## Feature-by-feature comparison

| MT5 Android area | Current DestinedFX implementation found | Parity assessment and remaining work |
|---|---|---|
| Quotes / Market Watch | Bot Workstation reads fresh, two-sided BID/ASK quotes from the account-bound MT5 terminal snapshot. It displays symbol, BID, ASK, quote age and source. The client rejects stale/future/invalid quote rows. | **Partial.** This is useful authenticated broker pricing, not a full MT5 Quotes/Market Watch screen. Verify symbol list management, full quote columns (including spread, daily change/high/low and last-time display), sorting, context menu, symbol properties and add/remove symbols. Do not substitute Analyzer provider prices for broker quotes. |
| Charts | Analyzer uses a TradingView Advanced Chart WebView when enabled, with a native candlestick fallback based on validated provider OHLC. The embed is configured with EMA and RSI studies and a structure overlay can show symbol-matched strategy levels. | **Partial.** This is not a broker-terminal MT5 chart. Chart display/source and quote execution source are separate. Verify mobile gestures, chart-type/tool controls, indicator configuration and persistence, drawing objects, crosshair, volumes and symbol mapping. External TradingView availability is a dependency; chart document loaded does not prove candles rendered. |
| Timeframes | Analyzer selector: M1, M5, M15, M30, H1, H4, D1 and W1. TradingView mapper supports those eight intervals. Bot strategy timeframe choices are M15, M30, H1, H4, D1 and W1. | **Partial.** Eight Analyzer intervals (six Bot intervals) are fewer than the 21 timeframes documented for MT5 Android. Add and test missing supported intervals only when source/data coverage is real; do not offer unsupported intervals as working. |
| Indicators | TradingView embed preconfigures EMA and RSI; no full native MT5 indicator catalogue/configuration workflow was found in the audited Compose chart code. | **Partial.** Two displayed studies are not MT5’s indicator-management parity. Confirm add/remove/configure indicators and per-chart persistence before claiming parity. |
| Trade / strategy control | Bot can START, STOP, PAUSE, RESUME and EMERGENCY STOP a managed EA strategy subject to reauthentication, exact broker/profile identity, mode, fresh heartbeat, news, cost and risk gates. The MT5 phone Bridge contract deliberately does not expose arbitrary phone-side market-order submission; the EA submits managed entries after its checks. | **Partial by design.** This is a managed strategy control surface, not the same as MT5's manual New Order ticket. Keep the distinction explicit. A STOP is not a position liquidation. |
| Account metrics | Verified account view exposes balance, equity, free margin, leverage and margin level. Bot cards show balance, equity, free margin and position count. Unavailable/stale metrics are not treated as verified. | **Implemented in source, pending owner runtime acceptance.** Confirm exact live terminal values and currency on each broker DEMO account; never use typed balance hints or stale snapshots as broker evidence. |
| Open positions | Trade view and Bot render fresh terminal positions with identifiers, symbol, direction, volume, floating P/L, SL, TP and EA ownership where supplied. | **Partial.** List and close operations exist. There is no Android Bridge action to modify a position's SL/TP. Until implemented and accepted, use MT5 terminal for modifications. |
| Pending orders | Trade view and Bot render current orders and can cancel one selected order or EA-owned pending orders after fresh snapshot checks. | **Partial.** List and cancellation paths exist. Modify pending-order price, SL/TP and expiry are not supported by the Android Bridge action contract found in this audit. |
| Close / cancel | Selected position closure, selected pending-order cancellation, EA-owned pending-order cancellation and close-all are supported with fresh account-bound state, exact identifiers, reauthentication and one-use close-all confirmation. UI waits for ticket-level results and fresh telemetry before displaying reconciliation. | **Implemented in source, high-risk owner DEMO acceptance required.** Test each action independently on each broker DEMO account, including partial close, rejection, timeout, restart, duplicate suppression and reconciliation. A command acknowledgement alone is not a broker fill/close. |
| History | Account flow contains Trade, History and Bot tabs; History renders verified deal history when supplied. Bot also pages authenticated Bridge trade records. | **Partial.** Verify pagination, date filters, deal/order/position identifiers, commissions, swap, fees, realized P/L and reconciliation against broker terminal history. |
| Market Depth / Depth of Market | No Android DOM/Level II screen, terminal depth model or supported Bridge DOM command/data route was found in the audited files. | **Not implemented / not verified.** Do not display simulated order-book levels. Add only if a supported broker terminal feed can supply genuine depth data and the backend can preserve its timestamp, source and instrument identity. |
| News / economic events | Calendar Workstation fetches a verified economic calendar, filters by date/currency/impact and calculates a symbol-relevant news verdict. Bot shows CLEAR, HIGH-IMPACT BLOCKED or UNKNOWN BLOCKED. | **Partial.** This is a calendar/risk gate, not full parity with the MT5 News feed or broker research/news stream. Unknown, stale or incomplete coverage must continue to block managed entries. |
| Alerts / push notifications | Settings includes Telegram connection, Chat ID detection, delivery test and automated Telegram alert controls. Android notification permission is requested for the Bot foreground service. | **Partial.** This is not MT5 MetaQuotes ID registration/device push or a complete MT5 alert rule manager. Implement and test push delivery separately if desired; do not claim MT5 push parity from Telegram integration. |
| Messages / mailbox | Chat Workstation is explicitly a deterministic, on-device DestinedFX app-help chat; it states no conversational AI provider is connected. No MT5 Mailbox/terminal message history or MetaQuotes push inbox was found in the audited paths. | **Not implemented as MT5 messaging.** Keep the DestinedFX help chat clearly distinguished from broker messages, MT5 Mailbox and MT5 push-message history. |
| Settings / Journal | Settings includes appearance, chart access, management connection and Telegram controls. | **Partial.** This is DestinedFX app settings, not the full MT5 Android settings, connection management, chart defaults, trading defaults, notifications, mailbox and Journal workflow. No MT5 terminal Journal UI parity was found. |
| Phone layout / navigation | Compose UI has a custom DestinedFX navigation and workstation design. A narrow 320dp emulator screenshot showed the broker-flow “Accounts” title wrapping; the current branch includes a compact one-line header fix and a test assertion in the Bot screenshot sweep. | **Source-level fix pending successful CI and physical-phone review.** Visual resemblance is not functional parity. Review small/large phone layouts, keyboard/scroll navigation, TalkBack, rotation and system bars on a real device. |

## Immediate Android backlog

1. **Add safe modification support (highest product gap):** define explicit `MODIFY_POSITION` and `MODIFY_PENDING_ORDER` request schemas only after the EA/Bridge contract is designed. Require current account identity/mode, fresh complete snapshots, exact canonical ticket/position IDs, ownership policy, broker stop/freeze-level validation, valid tick size/precision, margin/risk/news gates as applicable, unique idempotency key and per-ticket broker result reconciliation. Never infer modification success from HTTP acknowledgement. Add Bridge, EA, Android unit, integration and DEMO tests before enabling the controls.
2. **Implement a proper Quotes workspace:** show symbol, bid, ask, derived spread, quote timestamp/age, source and verified daily fields only when supplied by the terminal. Add watchlist search/add/remove/sort and symbol details without substituting third-party Analyzer prices.
3. **Expand chart/timeframe parity:** distinguish broker-origin MT5 chart data from TradingView/provider visualization. Add the missing timeframe set only where the genuine source supplies data. Add indicator configuration and chart tools with real tests rather than decorative controls.
4. **Assess Market Depth feasibility:** determine whether each supported MT5 broker terminal can provide actual DOM for the account/instrument. If not, disclose unsupported; never fabricate levels.
5. **Separate calendar from news and messages:** keep economic event gating; add a source-labelled News view only with a real authorized provider. Decide separately whether to implement MT5-style Mailbox/device push, and distinguish all notifications from Telegram.
6. **Expand parity tests:** add explicit navigation/UI tests for Quotes, Trade, History, positions, pending orders, account metrics, indicators, timeframes, calendar/news, alerts, messages and Settings; include screenshots across supported viewport sizes. A UI screenshot alone cannot assert broker behaviour.

## Runtime acceptance gates that remain external

- Install the current passing debug APK on the owner's physical Android phone and inspect startup, secure storage, login navigation, narrow layout, scrolling, keyboard behaviour, notifications and connection failure states.
- Pair with the owner's real Codespace and verify the exact port-8000 forwarded HTTPS origin and Bridge liveness/config/readiness. A public Bridge response does not prove MT5 terminal or EA readiness.
- Complete a real, owner-authorized DEMO lifecycle independently for FBS, Exness, HFM and Deriv MT5. Record only redacted, account-bound evidence.
- Keep Linux managed execution blocked until isolated per-user OS identities, Wine prefixes, terminal copies, the resource floor, host-agent/systemd integration and independently verified signed root attestation are present.
- Keep Codespaces EPHEMERAL / DEMO-only. A persistent production host, deployment trust, production signing and any LIVE reconsideration are separate release gates.

## Source map used for this audit

- `app/src/main/java/com/example/ui/workstations/BotWorkstation.kt`
- `app/src/main/java/com/example/ui/bot/MetaTraderBrokerFlow.kt`
- `app/src/main/java/com/example/data/trade/MT5RemoteBridgeClient.kt`
- `bridge/app.py`
- `app/src/main/java/com/example/ui/SimplifiedAnalyzerTab.kt`
- `app/src/main/java/com/example/ui/chart/TradingChartContainer.kt`
- `app/src/main/java/com/example/ui/workstations/CalendarWorkstation.kt`
- `app/src/main/java/com/example/ui/SettingsTab.kt`
- `app/src/main/java/com/example/ui/ChatWorkstationTab.kt`
- `app/src/main/java/com/example/data/NotificationService.kt`

## Official MetaTrader 5 Android comparison references

- [MetaTrader 5 Android Help — Getting started](https://www.metatrader5.com/en/mobile-trading/android/help/getting_started)
- [MetaTrader 5 Android Help — Quotes](https://www.metatrader5.com/en/mobile-trading/android/help/quotes)
- [MetaTrader 5 Android Help — Charts](https://www.metatrader5.com/en/mobile-trading/android/help/chart)
- [MetaTrader 5 Android Help — Trade](https://www.metatrader5.com/en/mobile-trading/android/help/trade)
- [MetaTrader 5 Android Help — Depth of Market](https://www.metatrader5.com/en/mobile-trading/android/help/trade/depth)
- [MetaTrader 5 Android Help — Messages / notifications](https://www.metatrader5.com/en/mobile-trading/android/help/settings/messages)
