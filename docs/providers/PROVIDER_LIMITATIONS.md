# Provider limitations

This is a source-level summary. Provider behavior, regional eligibility, entitlements and current service availability have not been verified against external accounts.

## Market data

- The TradingView scanner is a polling path. Some fields remain null; its local receipt timestamp is not an upstream event timestamp or guaranteed real-time data.
- Binance mapping is limited to BTC, ETH, BNB, SOL and XRP against USDT. REST quote and book data come from separate calls. The WebSocket class is not wired into repository stream startup.
- OANDA adapters require account ID and bearer token configuration. Quote paths may use local receipt time. Market-data setup is not broker order connectivity.
- FMP requires a runtime key in a query parameter. Quota, entitlement, completeness and event-time accuracy are not established in source.
- Android feed parsers and bridge provider clients are separate. Cached data after failure must remain stale or uncertain.
- Retry, backoff and rate-limit behavior varies. Do not add unbounded retries.

## Account and execution

- Deriv Options has authenticated account/session and Multiplier submission code. Its path checks account mode, fresh evidence, acceptance or LIVE lease, symbol/product and risk. No account, purchase or fill was verified.
- Generic Deriv Forex/CFD trade plans do not map to Multiplier contracts and the adapter fails closed.
- cTrader OAuth/account data code exists, but cTrader order placement is not implemented. Spotware approval is an owner step.
- Direct Android MT5 connection and order methods fail closed. Managed MT5 requires the bridge, worker, terminal, EA, exact account/server and fresh identity-matched heartbeat.
- FBS, Exness, HFM and Deriv MT5 brand selection does not prove broker-specific symbol, company alias, commission, fill mode or terminal support.
- OANDA account/trading gateway operations are unavailable through the current broker manager.

## Validation

Use authorized DEMO accounts for owner-run checks and fake transports for tests. External verification needs redacted account identity, mode, fresh session/quote/heartbeat, order acknowledgement and reconciliation evidence. Do not call a path verified from source code, an APK build, or an OAuth/API response alone.
