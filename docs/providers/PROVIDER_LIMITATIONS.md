# Market-data provider limitations

This document records limitations observed in source; it does not establish live API behavior.

- The TradingView scanner adapter is a polling path. Several fields are unavailable/null if absent upstream. The repository stamps retrieval time locally; this must not be labeled as source event time or guaranteed real-time.
- Binance source mapping is deliberately limited to BTC/ETH/BNB/SOL/XRP USDT spot symbols. REST quote uses the 24-hour ticker and separately fetches book ticker, so those values are not atomic. A Binance WebSocket implementation exists but is not wired into the repository's live-stream startup in the audited source.
- OANDA adapters require account ID and bearer token configuration. In quote paths, a local time may be used rather than an upstream event timestamp. Market-data account setup must not be mistaken for broker order connectivity.
- FMP economic-calendar requests require a runtime API key, currently added to the query string. A plan quota, entitlement, response completeness and event timestamp accuracy are not established in source.
- Public calendar/news feed parsers and bridge provider clients are separate. A successful parser fixture is not provider acceptance. Cached data after an error must remain labelled stale/uncertain.
- Provider-specific retries, backoff and rate-limit response behavior are not comprehensive across all adapters. Do not add unbounded retries.
- Capability fallback should only occur for compatible symbol, instrument type, interval and data semantics; show a provider/source change to the user.

## Recommended follow-up

1. Represent `retrievedAt` separately from provider `eventTime` and prevent event-time claims when the upstream payload lacks a timestamp.
2. Add deterministic adapter tests for source identity, wrong-symbol payloads, invalid/future/expired timestamps, null values, unsupported intervals and API rate-limit/error mapping.
3. Wire streaming providers only when explicitly supported and add lifecycle/cancellation/reconnect tests.
4. Define source-compatible fallback rules and UI source-change labels before adding fallback behavior.
5. Document quotas, entitlement, geographic access and licensing only from current provider documentation and mark any values as date-stamped; source inspection alone is insufficient.