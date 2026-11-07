# Provider architecture

## Categories

1. **Market and event feeds** in `app/src/main/java/com/example/data/market/`, calendar and news packages retrieve quotes, candles and event data. Public market data is not proof of a broker account connection.
2. **Authenticated account gateways** in `data/broker/`, `data/deriv/`, `data/ctrader/`, and `data/mt5/` manage provider-specific sessions and account evidence. Source presence does not prove authentication, entitlement or operational availability.
3. **Unavailable and preview gateways** report unsupported operations explicitly or isolate non-account preview behavior. Preview data must never be presented as genuine account data.
4. **Managed MT5 components** in `bridge/`, `managed-worker/`, and `mt5-ea/` support a separate owner-operated terminal path. A repository checkout does not establish that this path is deployed or healthy.

## Android abstractions

- `BrokerGateway` exposes account data and order methods. `executeTradeWhenArmed` obtains a mode decision before invoking an order method. The default decision denies. An implementation that allows a request must evaluate the shared `ModeArmingPolicy` using fresh account evidence.
- `BrokerManager` selects between injected gateways. Current routing includes Deriv and MT5; OANDA account operations are unavailable.
- `BrokerCapabilityMatrix` is a source-level capability declaration, not a live connection check.
- `BrokerDirectory` supports brand selection and does not verify an account or server.
- `UniversalConnectorDispatcher` checks mode and connection evidence for connector operations. Fixtures do not establish a real API session.
- `AccountDataProvenance` retains origin, connection state, timestamps, currency, nullable values and displayability. Missing values stay null.

## Source-specific status

- **cTrader:** OAuth and account-data classes exist. The current inspected account gateway does not place orders. Spotware approval and live account verification remain owner steps.
- **Deriv:** authenticated account/session and Multiplier execution code exists. The Multiplier path checks account mode, fresh evidence, acceptance or LIVE lease, symbol support and product-specific risk before submission. The generic Forex/CFD adapter does not translate plans into Multiplier contracts. No genuine account or fill was verified.
- **MT5:** direct Android connection and order methods fail closed. The remote bridge, worker and EA form a separate managed route with account-mode checks. Broker-specific behavior and real host operation are unverified.
- **OANDA:** market-data adapters are separate from account access. The broker manager's account gateway is unavailable; do not infer account or trading support from a feed.
- **FBS, Exness, HFM and Deriv MT5:** brand selection can route through a shared managed MT5 path. This is not a separate provider API implementation for every brand.

## Rules for changes

- Define supported instruments, data types, intervals, authentication and implementation evidence explicitly.
- Distinguish unsupported, unavailable, stale and empty-success states.
- Keep preview values isolated from genuine provider data.
- Preserve provider event time and local retrieval time as distinct concepts.
- Do not infer real-time status from recent local retrieval.
- Do not silently merge providers. Make source changes visible and validate compatibility.
- Keep every order request behind account-specific `ModeArmingPolicy` authorization and the existing risk checks. Tests must use fake transports and must never submit real orders.

See the [public market-data capability matrix](../providers/PROVIDER_CAPABILITY_MATRIX.md), [broker capability matrix](../broker-capability-matrix.md), and [data provenance](../security/DATA_PROVENANCE.md).
