# Broker capability matrix

This matrix describes repository capability and evidence status. A capability marked implemented means code exists; it does not prove a genuine broker account, broker response, fill, reconciliation, or production readiness.

| Broker | Market data | Authentication/account verification | DEMO orders | LIVE orders | Stop/close | History/reconciliation |
|---|---|---|---|---|---|---|
| Deriv | **IMPLEMENTED** — WebSocket/provider paths exist | **IMPLEMENTED / NOT VERIFIED** — authorization code exists; genuine account evidence is not present in this environment | **NOT VERIFIED** — the generic Forex/CFD trade plan is deliberately rejected because Deriv Multiplier stake/proposal mapping is not validated | **BLOCKED** — no LIVE authorization is granted | **NOT VERIFIED** — close route exists, broker confirmation and recovery evidence are absent | **NOT VERIFIED** |
| MT5 managed bridge | **NOT VERIFIED** — depends on the managed terminal/provider | **NOT VERIFIED** — requires the authenticated bridge, worker, terminal and exact account identity | **NOT VERIFIED** — requires a genuine terminal, EA, worker and broker-confirmed order/fill | **BLOCKED** — separately gated and not authorized | **NOT VERIFIED** — commands exist, end-to-end broker/EA evidence is absent | **NOT VERIFIED** |
| OANDA | **IMPLEMENTED** for provider market data only | **NOT IMPLEMENTED** for trading | **NOT IMPLEMENTED** — routes to `UnavailableBrokerGateway` | **BLOCKED** — no execution route exists | **NOT IMPLEMENTED** | **NOT IMPLEMENTED** |
| Preview gateway | **NOT IMPLEMENTED** | **BLOCKED** | **BLOCKED** — simulated orders are disabled | **BLOCKED** | **BLOCKED** | **BLOCKED** |

The same matrix is available to Android code through `BrokerCapabilityMatrix`. Preview mode has no account balance, cannot authenticate, cannot return a quote, and fails closed for order, close, and margin operations. No preview identifier is a broker transaction identifier.

## Evidence still required

Deriv DEMO acceptance requires a user-authorized genuine DEMO account, exact account identity and mode, a minimum-risk product-specific proposal/purchase, broker-confirmed transaction and contract IDs, settlement, history reconciliation, duplicate prevention, reconnect, STOP and restart evidence. MT5 DEMO acceptance additionally requires the Windows worker, genuine terminal, compiled EA, exact login/server/mode, heartbeat, trade permission, broker-reported order/fill and recovery evidence. No LIVE order is authorized by this repository task.
