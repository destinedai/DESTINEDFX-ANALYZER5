# Public provider capability matrix

**Evidence basis:** source inspection of `free/phone-only-v1`. This is not a live endpoint probe or account verification. Check the exact commit before relying on source claims.

| Provider/path | Public market data | Supported instruments | Supported intervals | Auth for public data | Limits/quotas | Protocol | Implementation status | Automated tests | External verification | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| TradingView scanner REST | Scanner quote snapshots, not broker account state | App scanner mapping and request universe | Quote polling; no OHLC guarantee | No key supplied by this adapter | Not verified here | HTTPS REST POST | Adapter exists on default quote path; provider contract is not established | Local normalization/safety tests | Not verified | Retrieval time may be locally stamped; nullable bid/ask; no guaranteed event timestamp or automation entitlement |
| Binance Spot REST | 24-hour ticker, book ticker and OHLC klines | BTC, ETH, BNB, SOL, XRP mapped against USDT | 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M | No key in public REST adapter | App does not enforce provider quota here | HTTPS REST | Adapter implemented; separate stream class is not wired into startup | Local model/repository safety tests | Not verified | Quote and book responses use separate requests; local timestamp does not prove event freshness |
| OANDA pricing/candles REST | Configured pricing and candles | `SymbolRegistry` instrument mappings; entitlement depends on account/provider | M1, M5, M15, M30, H1, H4, D, W, M mapping | Runtime bearer token and account ID | Not verified or uniformly enforced | HTTPS REST | Adapter exists; runtime wiring/configuration affects availability | Local adapter safety tests | Not verified | Feed access is not account trading; timestamp handling may use local receipt time |
| OANDA pricing stream | Stream messages and heartbeat parsing | Configured OANDA symbols | Stream quotes; candles are REST-specific | Runtime bearer token and account ID | Not verified | HTTPS stream | Parser/path exists; active lifecycle use must be confirmed | Parser/freshness tests | Not verified | Reconnect behavior and account access are not externally verified |
| FMP economic calendar | Calendar events, not market quotes | Currencies/events in API response | Date window, not bar intervals | Runtime API key | Plan limits not verified | HTTPS REST; key currently in query parameters | Adapter exists; deployment/configured key is not established | Parsing, error and empty-response tests | Not verified | Completeness, entitlement and freshness unknown; URL-level telemetry may expose query keys |
| ForexFactory/configured news | Calendar/news events | Upstream event payload | Event windows | Android path and configured bridge path differ | Not verified | Feed polling or server HTTPS | Android parser/cache and bridge client are separate paths | Local parser/cache and bridge tests | Not verified | Uptime, licensing, coverage and completeness not guaranteed |
| Deriv market data | Provider-specific quotes/candles | Provider symbols and app resolver | Provider-supported mapping | Some paths require authenticated session | Not established | Provider WebSocket/HTTP | Provider module exists; account and execution functions are separate | Local parser/selection tests | Not verified | Data access does not prove account authorization or order acceptance |

## Normalization and provenance

Shared models include nullable quote sides, source/provider identifiers, timestamps and candle validation. Timestamp semantics vary: some REST adapters use local receipt time, while stream parsers may preserve event time. The repository caches candles by symbol/timeframe and tracks source for display. Do not silently merge sources.

## Test boundary

Relevant sources include `MarketDataSafetyTest.kt`, `EconomicCalendarProviderTest.kt`, and `ForexNewsRepositoryTest.kt`. Fixtures test local logic only. They do not establish quotas, latency, regional access, licensing, API availability, or current provider behavior.

## Source references

- `app/src/main/java/com/example/data/market/MarketDataRepositoryImpl.kt`
- `app/src/main/java/com/example/data/market/PublicRestMarketFeed.kt`
- `app/src/main/java/com/example/data/market/BinanceProvider.kt`
- `app/src/main/java/com/example/data/market/OandaProvider.kt`
- `app/src/main/java/com/example/data/market/SymbolRegistry.kt`
- `app/src/main/java/com/example/data/market/BinanceSymbols.kt`
- `app/src/main/java/com/example/data/calendar/EconomicCalendarProvider.kt`
- `app/src/main/java/com/example/data/news/ForexNewsRepository.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/MarketModels.kt`
