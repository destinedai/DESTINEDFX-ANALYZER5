# Data provenance and freshness

## Market data

`MarketQuote` and related market models carry provider/source identity, timestamp fields, nullable bid/ask/last, and candle validity information. The precise timestamp meaning is adapter-specific. A locally assigned retrieval timestamp is not a provider event timestamp and does not prove the source is real-time. UI labels should identify the source and avoid unqualified “live” where event-time/latency evidence is absent.

Market candle normalization validates the source payload; it must not manufacture OHLC from an isolated quote or combine sources silently. Missing fields stay null/unavailable. Unsupported symbols/timeframes, invalid JSON, timeouts, future values and stale data should result in explicit unavailable/failure state, not zero-valued substitutes.

## Account data

`BrokerAccountSnapshot` in `AccountDataProvenance.kt` includes provider identity, origin, connection status, retrieval timestamp, environment, currency, nullable metrics, validity and safe error information. `isDisplayable()` is the boundary used to prevent unavailable, stale, wrong-origin or incomplete values from appearing as genuine account data. Existing strict full-snapshot checks should not be weakened. If independently valid metrics need separate presentation in future, implement separate validators while preserving nulls and provenance.

The code audit noted that the displayability predicate rejects non-finite and negative metric values; whether all negative equity/free-margin scenarios should be accepted is a domain decision requiring tests and provider evidence. The inspected MT5 path also does not currently produce a connected/displayable snapshot. No genuine authenticated account was used for this audit.

## Preview and fallback

Preview data must remain tagged and isolated from production/provider data. A fallback across feeds is permitted only when data types, instruments, intervals and semantics are compatible; the source change must be visible. Never use demo, cached-after-error, or stale values as genuine current values.

## Required tests

Cover missing/null values, NaN/infinity, negative-value policy, expired/stale/future timestamps, provider/source mismatch, provider switch, malformed response, cache invalidation, delayed data labels, preview isolation and unavailable-provider states. Existing tests cover parts of these boundaries but were not executed in this documentation pass unless recorded in final verification.

## Source

- `app/src/main/java/com/example/data/broker/AccountDataProvenance.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/MarketModels.kt`
- `app/src/main/java/com/example/data/market/MarketDataSafety.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/QuoteArbiter.kt`
- `app/src/test/java/com/example/BrokerAccountSnapshotTest.kt`
- `app/src/test/java/com/example/MarketDataSafetyTest.kt`