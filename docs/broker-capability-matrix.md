# Broker capability matrix

This matrix records repository implementation and evidence separately. A declared broker brand or an authenticated MT5 terminal is not proof of order acceptance, fill, reconciliation, or production readiness.

| Broker / connection method | Current implementation | DEMO acceptance | LIVE |
|---|---|---|---|
| FBS MT5 | Shared managed MT5 terminal/EA path; broker-brand claim check for terminal company, login, server and mode | NOT VERIFIED — no genuine FBS DEMO account acceptance evidence in this repository run | BLOCKED |
| Exness MT5 | Shared managed MT5 terminal/EA path; separate broker-qualified pairing and terminal identity check | NOT VERIFIED — no genuine Exness MT5 DEMO account acceptance evidence | BLOCKED |
| Exness Trader API | NOT IMPLEMENTED — official Exness help says the separate Exness API is currently available only in Vietnam and Thailand; standard MT4/MT5 accounts cannot connect | DEFERRED / NOT AVAILABLE for the stated Nigeria region under the published regional restriction; no code authorized | BLOCKED |
| Deriv Options / Multipliers API | REST account/OTP plus account-scoped authenticated WebSocket, proposal/purchase and execution ledger code exist | NOT VERIFIED — genuine account proposal, purchase, close/settlement and history acceptance are still required | BLOCKED |
| Deriv MT5 | Uses shared managed MT5 terminal/EA path when the customer has an actual Deriv MT5 account | NOT VERIFIED | BLOCKED |
| HFM MT5 | Shared managed MT5 terminal/EA path; broker-brand claim check for terminal company, login, server and mode | NOT VERIFIED — no genuine HFM DEMO account acceptance evidence | BLOCKED |
| OANDA v20 | NOT IMPLEMENTED — no OANDA execution adapter in this repository | DEFERRED — official v20 docs require an eligible v20 account; Nigeria individual-retail eligibility is not confirmed | BLOCKED |

## Capability disclosures

### Nigeria / OANDA v20 eligibility

OANDA's official v20 introduction says v20 trading accounts are available to all divisions except OANDA Global Markets and OANDA TMS Brokers S.A. OANDA's group page places clients in “other countries” under Global Markets; it does not identify a Nigeria-specific retail v20 path. A separate OANDA page listing countries accepted for partner traffic is not an individual-retail account or API eligibility list. Therefore this project's current answer for an individual Nigerian retail account is **not confirmed; do not treat OANDA v20 as eligible** until OANDA confirms in writing which legal entity would hold the account and that the specific DEMO account is v20-enabled. No OANDA code is authorized or implemented in Phase 1.

Sources checked 2026-11-06: [OANDA v20 introduction](https://developer.oanda.com/rest-live-v20/introduction/), [OANDA group coverage](https://www.oanda.com/group/where-we-are/), [OANDA partner traffic country list](https://www.oanda.com/eu-en/partners-global/frequently-asked-questions/new-partners/which-countries-does-oanda-accept-traffic).

The Android `BrokerCapabilityMatrix` is a static code capability profile for DERIV, MT5, and OANDA; it does not itself prove an account is ready. This document's FBS, Exness, HFM, and Deriv rows distinguish the connection methods that map to those profiles. Preview is a separate non-broker gateway, not a `BrokerType` profile. Preview mode has no account balance, cannot authenticate, cannot return a quote, and fails closed for order, close, and margin operations. No preview identifier is a broker transaction identifier.

## MT5 account isolation

The Android app uses a broker-qualified secure-storage namespace, for example FBS_DEMO, EXNESS_DEMO, and HFM_LIVE. Pairing tokens, account credentials, pending command IDs, and safety-stop metadata are separated by broker and mode. The selected account mode sent to the bridge remains exactly DEMO or LIVE; the local namespace is not sent as an account mode.

The managed-account registration includes `broker_brand`. The bridge stores it and checks the company reported by the actual terminal when the terminal claims the pairing. Supported MT5 brand checks currently recognize FBS, Exness, Deriv, and HF Markets/HFM company-name aliases. Exact server names remain user supplied and are never guessed. Older records without a broker brand remain UNKNOWN for compatibility and should be re-enrolled before being treated as brand-verified.

## What the implementation does not prove

- The FBS, Exness and HFM entries share one MT5 connector; this is not three separate broker APIs.
- The direct Exness Trader API connector and its Ed25519 key management are not implemented by the MT5 connector.
- Deriv MT5 and Deriv Options/Multipliers are separate account paths.
- A local unit test or a terminal heartbeat does not prove a real DEMO order lifecycle.
- DEMO and LIVE profiles are separate. LIVE trade authorization remains blocked until explicitly armed for the exact account.
- No genuine broker order was submitted in this development change.

## Required acceptance evidence

For each real DEMO account, record the exact broker and connection method, actual account ID/mode/server, fresh quote and instrument specification, broker-confirmed order/deal/contract IDs, protective order state, cancellation/close result, history reconciliation, and reconnect/restart evidence. Ambiguous order outcomes must remain unresolved until broker state confirms them.
