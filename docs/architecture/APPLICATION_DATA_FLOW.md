# Application data flow

This describes repository code paths, not verified external service behavior.

## Request and state path

```mermaid
sequenceDiagram
  actor User
  participant Screen as Compose screen
  participant VM as ViewModel
  participant UC as Domain use case
  participant Repo as Repository
  participant Provider as Selected provider adapter
  participant Source as Public/configured upstream
  participant Room as Local Room storage
  User->>Screen: request analysis / open data view
  Screen->>VM: UI event
  VM->>UC: invoke domain operation
  UC->>Repo: request normalized market data
  Repo->>Provider: select supported source and fetch
  Provider->>Source: HTTP/WebSocket request
  Source-->>Provider: provider payload or failure
  Provider-->>Repo: parsed values + available source/time metadata
  Repo->>Repo: validate bars/quotes; check cache and freshness
  Repo-->>UC: data, empty/unavailable, or failure state
  UC-->>VM: analysis/result state
  VM-->>Screen: immutable loading/success/empty/error state
  VM->>Room: persist analysis history where applicable
```

`MainActivity` gates Compose workspace creation on `MyApplication` initialization. Compose state and ViewModels are Android presentation components; `MarketDataRepository` and related repository contracts form the access boundary, although object construction and some legacy screen/service interactions are not fully isolated.

## Market-data source selection

`MarketDataRepositoryImpl` selects a feed path and caches candles by symbol/timeframe. The default quote path is TradingView scanner polling. Source-specific code can use OANDA for configured instruments, Binance REST klines for supported crypto symbols, and a Deriv path when explicitly selected. These are not silently interchangeable: keep source identity and symbol mapping visible, and do not merge unlike quote/OHLC sources as though they were one feed.

Some provider adapters record local retrieval time rather than the upstream event timestamp. In particular, retrieval time only indicates when this client received or stamped the value; it is not proof that the underlying quote is current. The normalized market model also has provider/source timestamp fields, but the meaning varies by adapter and must be interpreted with the source.

## Validation and missing data

- Quotes are checked for valid numeric values, future timestamps and provider consistency through market-safety and arbitration code.
- OHLC bars are checked for ordering, positive values and coherent high/low/close relationships. Invalid candles are not repaired into plausible bars.
- Missing values remain unavailable; the app must not substitute zero, fabricate OHLC from a quote, or relabel delayed data as real-time.
- A missing/failed provider may lead to an empty candle result and diagnostics, not a successful analysis based on made-up data.
- Calendar/news failure states must remain distinct from a verified empty-event window. Cached information after an error is not evidence of current provider coverage.

## Local persistence

Analysis history is written through `AnalysisRepository` and Room. The database also includes retained trade, user, and Deriv execution entities, which are legacy/product-scope concerns requiring further privacy and migration review; their presence does not establish supported trading. App storage and provider caches are device-local unless an explicit remote feature is implemented and configured. Android Supabase cloud-sync methods are stubs.

## Read-only request flow

`TradeUseCaseImpl.executeTrade` calls `ReadOnlyExecutionPolicy` before broker lookup, market retrieval, journaling, or submission and rejects the request. Do not assume that every legacy gateway, service, bridge route, worker, or EA is disabled because this use case rejects.

## Relevant code

- `app/src/main/java/com/example/domain/repository/MarketDataRepository.kt`
- `app/src/main/java/com/example/data/market/MarketDataRepositoryImpl.kt`
- `app/src/main/java/com/example/data/market/MarketDataSafety.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/MarketModels.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/QuoteArbiter.kt`
- `app/src/main/java/com/example/domain/usecase/impl/ReadOnlyExecutionPolicy.kt`
- `app/src/main/java/com/example/data/AppDatabase.kt`

See [provider capability matrix](../providers/PROVIDER_CAPABILITY_MATRIX.md) and [data provenance](../security/DATA_PROVENANCE.md).