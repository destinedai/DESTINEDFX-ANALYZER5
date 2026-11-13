# Application data flow

This describes source paths. It does not establish provider availability or a verified account.

## Analysis and account data

```mermaid
sequenceDiagram
  actor User
  participant Screen as Compose screen
  participant VM as ViewModel
  participant UC as Use case
  participant Repo as Repository
  participant Provider as Provider adapter
  participant Source as Public or authenticated upstream
  participant Room as Local Room storage
  User->>Screen: request analysis or account view
  Screen->>VM: UI event
  VM->>UC: invoke operation
  UC->>Repo: request normalized data
  Repo->>Provider: select source and fetch
  Provider->>Source: HTTP or WebSocket request
  Source-->>Provider: payload or failure
  Provider-->>Repo: parsed values and available source/time metadata
  Repo->>Repo: validate, arbitrate, check cache and freshness
  Repo-->>UC: data, empty/unavailable state, or failure
  UC-->>VM: result state
  VM-->>Screen: loading, success, empty, blocked, or error state
  VM->>Room: persist selected local records
```

`MainActivity` gates workspace creation on application initialization. ViewModels and repository contracts separate some responsibilities, but the app retains global service lookup and legacy service interactions.

## Execution path and gate

```mermaid
sequenceDiagram
  actor Owner
  participant Bot as Bot or strategy caller
  participant UseCase as TradeUseCaseImpl
  participant Gateway as BrokerGateway
  participant Policy as ModeArmingPolicy
  participant Provider as Broker or managed host
  Owner->>Bot: request execution
  Bot->>UseCase: executeTrade(signal)
  UseCase->>UseCase: validate source, quote, news, session, risk, costs and duplicates
  UseCase->>Gateway: executeTradeWhenArmed(plan)
  Gateway->>Policy: evaluate account-bound mode decision
  alt not approved, missing or stale evidence
    Policy-->>Gateway: BLOCKED with reason
    Gateway-->>UseCase: failure, no order call
  else approved for exact account and mode
    Policy-->>Gateway: allowed
    Gateway->>Provider: provider-specific order request
    Provider-->>Gateway: acknowledgement or failure
    Gateway-->>UseCase: result
  end
```

`BrokerGateway` defaults to `MODE_ARMING_POLICY_UNAVAILABLE`. A gateway implementation must derive an allow decision from the shared policy and fresh identity-matched evidence; a missing override denies. Deriv Multiplier submission and MT5 managed commands also have their provider-specific checks. Direct MT5 app orders are disabled. cTrader trading is not implemented.

## Market source selection and provenance

`MarketDataRepositoryImpl` selects a configured or default feed and caches candles by symbol and timeframe. TradingView scanner, Binance REST, OANDA REST/stream, and Deriv paths are distinct. They must not be silently combined as one feed. Some adapters stamp local retrieval time rather than provider event time. Local receipt time does not prove upstream freshness.

Quotes and candles are validated before analysis. Missing values remain unavailable; malformed candles must not be repaired into plausible OHLC. A failed calendar request is not the same as a verified empty event window. Cached data after a provider failure does not prove current coverage.

## Persistence and records

Analysis and other selected records use local Room and preferences. Account and trade records contain sensitive or financial information, so retention, deletion, migration, backup and recovery need continued review. Android cloud-sync classes must be described according to their actual implementation, not as available remote storage.

## Relevant code

- `app/src/main/java/com/example/data/market/MarketDataRepositoryImpl.kt`
- `app/src/main/java/com/example/data/market/MarketDataSafety.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/MarketModels.kt`
- `app/src/main/java/com/destinedfx/core/marketdata/QuoteArbiter.kt`
- `app/src/main/java/com/example/domain/usecase/impl/TradeUseCaseImpl.kt`
- `app/src/main/java/com/example/data/broker/ModeArmingPolicy.kt`
- `app/src/main/java/com/example/data/AppDatabase.kt`

See [provider capability matrix](../providers/PROVIDER_CAPABILITY_MATRIX.md) and [data provenance](../security/DATA_PROVENANCE.md).
