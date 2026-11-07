# Provider architecture

## Categories

1. **Public market/event data adapters** (`app/src/main/java/com/example/data/market/`, calendar and news packages) retrieve public or runtime-keyed quotes, candles and event feeds. A public market feed is not a broker account connection.
2. **Authenticated provider/account gateways** (`data/broker/`, `data/deriv/`, `data/ctrader/`, `data/mt5/`) deal with account/session state under provider-specific conditions. Their code-level presence does not prove successful authentication, entitlement, account acceptance, or operational availability.
3. **Unavailable gateway** (`UnavailableBrokerGateway`) reports unsupported account operations explicitly and fails closed.
4. **Preview gateway** (`PreviewBrokerGateway`) is isolated non-account preview functionality. It does not supply genuine account values and refuses account/transaction operations.
5. **Legacy execution systems** (`bridge/`, `managed-worker/`, `mt5-ea/`, selected broker adapter methods) contain transaction-capable routes and are outside the intended phone-only analysis product. They must not be represented as disabled merely because the Android trade use case rejects requests.

## Android abstractions

- `BrokerGateway` exposes both data and transaction methods; it is therefore not a read-only-only interface.
- `BrokerManager` selects an injected gateway; current routing includes DERIV and MT5, while OANDA account operations use an unavailable gateway.
- `BrokerCapabilityMatrix` is a static capability declaration and should be read alongside implementation status and evidence; a row is not a live connection check.
- `BrokerDirectory` is primarily a brand-selection directory and does not verify an account/server.
- `UniversalConnectorDispatcher` checks connector evidence/identity/freshness, but unit fixtures do not verify a real API session.
- `AccountDataProvenance` models origin, connection state, timestamp, currency, nullable values and displayability. Missing values must remain null and values are displayable only when the predicate passes.

## Source-specific observations

- **cTrader:** a separate read-only OAuth/Open API path exists and lacks order methods in the inspected class; no genuine account authorization was verified. It is not automatically equivalent to a BrokerManager gateway.
- **Deriv:** authenticated account/session paths exist. Generic trade-plan execution is rejected in the adapter, but a contract-close path remains transactional. It is not wholly read-only.
- **MT5:** direct Android connection/order paths fail closed in the adapter; the managed bridge/worker/EA remains. The inspected MT5 gateway snapshot path does not currently reach a connected/displayable state.
- **OANDA:** market-data adapters are separate from account access. BrokerManager uses an unavailable OANDA account gateway; do not infer authenticated account/trading support from an OANDA feed.
- **Other broker brands:** FBS, Exness, HFM, and Deriv MT5 brand selection can route to a shared managed MT5 path; this is not a provider-specific API implementation for each brand.

## Rules for change

- Define capabilities explicitly with supported instruments/data types, intervals, authentication mode and implementation evidence.
- Treat “unsupported” and “unavailable” as distinct from an empty successful response.
- Keep preview values physically and logically isolated from real provider data.
- Attribute provider/source, local retrieval time and upstream event time separately whenever available.
- Do not infer real-time status from a recent local retrieval timestamp.
- Do not combine providers silently. A fallback needs compatibility checks and a visible source change.
- Keep every transaction-capable path outside the analysis-only product boundary or remove it; test the reachable call graph and built artifact, not only a policy helper.

See the [public market-data capability matrix](../providers/PROVIDER_CAPABILITY_MATRIX.md), [broker capability matrix](../broker-capability-matrix.md), and [data provenance](../security/DATA_PROVENANCE.md).