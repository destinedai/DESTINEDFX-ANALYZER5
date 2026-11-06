# Exness Trader API contract review

**Reviewed:** 2026-11-05 against the linked official Exness Developer Portal and OpenAPI YAML.

This note records the source contracts found while assessing a separate Exness Trader API integration. It is not evidence of a live eligible account, a successful authentication, or an implemented integration.

## Verified documentation and key account distinction

Exness describes the Trader API as a distinct product from MetaTrader connectivity. Its help article says standard MetaTrader accounts cannot connect to the Trader API; those accounts must remain on the genuine MT5 terminal route. The same help article describes Exness trading accounts as eligible subject to verification and API access requirements. Do not interpret this as API eligibility for every MT account.[1]

The API overview instructs clients to use the Gateway host supplied by the API Key Management DNS widget and discover the account Access Point before making REST or WebSocket requests. It states private signing material must remain in a trusted server-side environment and not be placed in mobile apps, browser code, public repositories, or client-side scripts.[2]

## Verified current OpenAPI operations

The current linked Trader API OpenAPI YAML identifies these relevant routes:[3]

- Account details: `GET /v1/configuration/accounts/{account_id}/account`.
- Account-scoped instrument names: `GET /v1/configuration/accounts/{account_id}/instruments`.
- Instrument conditions: `GET /v1/configuration/accounts/{account_id}/instruments/{instrument}/conditions`.
- API limits: `GET /v1/configuration/accounts/{account_id}/limits`.
- Historical candles: `GET /v1/market-data/accounts/{account_id}/candles`.
- Current trading snapshot: `GET /v1/trading/accounts/{account_id}/snapshot`.
- Operation status: `GET /v1/trading/accounts/{account_id}/operations/{operation_id}`.
- Orders history and deals history: `GET /v1/history/accounts/{account_id}/orders` and `/deals`.
- Server-event subscriptions and tick subscriptions: `POST /v1/server-events/accounts/{account_id}/ws/events` and `/ws/ticks`.
- Pending-order placement/modification/cancellation: `POST /v1/trading/accounts/{account_id}/orders`, `PATCH /v1/trading/accounts/{account_id}/orders/{order_id}`, `DELETE /v1/trading/accounts/{account_id}/orders/{order_id}`.
- Position opening/closure: `POST /v1/trading/accounts/{account_id}/positions` and `DELETE /v1/trading/accounts/{account_id}/positions/{position_id}`.

Account, volume, and requested price fields use decimal strings where defined in the OpenAPI. Keep monetary precision and instrument-specific sizing/price constraints; do not infer them from account currency precision.[3]

Mutating operations return an asynchronous `202` ACK with an `operation_id`. An ACK means processing was accepted, not that a trade was filled or applied. The documented operation-status route has `pending`, `confirmed`, `rejected`, and `failed` states. Final results also flow through Server Events and history/state reads. The API describes idempotency keys, request-to-event correlation, and duplicate-key conflict behavior; clients must retain the same key for a safe retry of the same request and reconcile final operation/event/state before reporting application.[3]

The public reference documents REST request signing, but that does not by itself specify the WebSocket upgrade/handshake contract. Implementers must follow the operation-specific OpenAPI schemas rather than projecting REST headers onto a WebSocket connection.[3]

## Signing contract

For signed HTTP requests the official reference documents the headers `EXN-API-KEY`, `EXN-IDEMPOTENCY-KEY`, `EXN-TIMESTAMP`, `EXN-SIGN-VERSION`, `EXN-DATA`, and `EXN-SIGN`. The signed payload contains the API key identifier, idempotency key, Unix-millisecond timestamp, sign version `1`, uppercase HTTP method, exact path including query string, and base64url-without-padding SHA-256 hash of the exact body bytes. `EXN-SIGN` is an Ed25519 signature over the decoded `EXN-DATA` payload bytes. GET requests include an empty idempotency header and empty payload field; mutations require a nonempty 5–128 character idempotency key. Sign every request freshly and send the exact path/body bytes that were signed. Duplicate query parameters are rejected.[3] The official docs do not define timestamp skew tolerance or prescribe a particular JSON canonicalization of the signed payload; do not hard-code unsupported assumptions.

## Unresolved contract: Access Point discovery

The supplied legacy `/trading` reference URL currently returns Page Not Found. The current official reference is the linked API Reference page and OpenAPI YAML above. The official Access Point guide describes the Gateway-to-account-Access-Point discovery concept, but its example endpoint/path is conditional on a separate Access Point YAML contract. The account-access-point operation is not present in the retrieved Trader API OpenAPI paths. Therefore this review does **not** verify the discovery request method/path, exact query or response schema, signing requirements, cache lifetime, or WebSocket host/handshake. Do not implement or hard-code `/v1/trading/access-point?account_id=...` as a guaranteed route until Exness supplies the authoritative current schema.[4]

The operation schemas in the official OpenAPI are sufficient to begin isolated contract tests and a server-side signing layer, but not sufficient to declare the requested full integration complete while Access Point discovery/host selection and real account eligibility remain unverified. Do not place API-key identifiers or private keys in Android, APK resources, Git, screenshots, logs, or chat. No owner credentials were requested or used here.

## References

[1]: https://get.exness.help/hc/en-us/articles/27866287512476-Exness-API "Exness API access and eligibility"
[2]: https://www.exness-api.com/ "Exness API developer overview"
[3]: https://www.exness-api.com/reference/trading/exness-trader-api "Exness Trader API reference and linked OpenAPI"
[4]: https://www.exness-api.com/trading/get-started/access-point-host-discovery "Exness Access Point host discovery"
