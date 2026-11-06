# Exness MT5 and optional Trader API — documentation findings

## Identification

- **id:** `exness-mt5`
- **name:** Exness MT5 and optional Trader API

## Findings

- MT5 sign-in requires the trading account’s **Login (account number), trading Password, and Server**. Exness directs users to the Personal Area (PA) account card for the assigned MT4/MT5 login and server; these are account-specific and fixed. The desktop guide says if the server is not listed, search Exness and select **Exness Technologies Ltd.**, then choose the server from the list. That instruction is a terminal discovery path, not a universal account server value. [1] [2]
- Exness documents separate Real and Demo account environments. Its help page says registration automatically creates a Real Standard MT5 and Demo Standard MT5 account; Demo uses virtual equity and Real uses real funds. The selected account’s actual mode must be taken from the authenticated terminal, not inferred from the broker name or a typed label. [3] [7]
- An Exness MT5 article gives a Python `MetaTrader5` package example calling `mt5.initialize(login=..., server="Exness-MT5server", password=...)`. It also calls `terminal_info()` and describes MT5 as the API/platform for algorithmic access. `Exness-MT5server` is an example string in the article, not a documented universal production/demo hostname or account server assignment. The reviewed sources do not provide a separate Exness-issued Trader API credential, endpoint, OAuth/token protocol, API entitlement, or server catalog. Treat “Trader API” as an unresolved separate integration unless a specific official API documentation link is supplied; do not equate the terminal-attached Python package with a standalone broker API. [4]
- Exness’s account KYC process validates the **client**, not the MT5 terminal login. The reviewed current help pages describe contact confirmation (email one-time code and SMS/call code), personal details matching official documents, proof of identity, and proof of address. POI examples include passport, national ID, and driver’s licence; POA examples include recent utility bill, bank statement, local tax bill, or bank letter. POA is generally dated within six months; country-specific and PA-presented requirements can vary. This is Exness onboarding/KYC, not an API identity-verification handshake and not a repository credential-collection requirement. [5] [6]
- Exness’s platform page states that MT4 accounts cannot be used on MT5 and vice versa, and that certain regions require MT5-based accounts to use MetaTrader 5 terminal rather than Exness Terminal. It also gives supported-version information: MT5 Build 4755 (released 13 Dec 2024) and says older versions will not connect, effective 1 July 2025. Verify live support/build requirements at deployment; this dated page is not a repository compatibility guarantee. [1] [2]
- Repository context: Exness appears as the broker label `Exness Global` in `BrokerDirectory`; it is not a verified execution integration. Project documentation says managed MT5 requires the authenticated bridge, worker, real MT5 terminal and exact account identity; current MT5 managed-bridge authentication and DEMO order acceptance remain NOT VERIFIED, and LIVE is BLOCKED. The relay/EA independently verifies terminal login, exact server, company/currency, and actual `ACCOUNT_TRADE_MODE` against a separately selected DEMO/LIVE profile. [7] [8] [9]

## Official Exness API update (23 October 2026)

Exness documents a separate REST and WebSocket API for eligible Exness-platform accounts, including account information, instruments, market data and trading: [Exness API](https://get.exness.help/hc/en-us/articles/27866287512476-Exness-API). The current Help Center article (updated 2026-09-29) says this API is **only available in Vietnam and Thailand** and explicitly excludes standard MT4/MT5 accounts. Nigeria is outside the published API regions. Therefore this application must continue to authenticate and trade an owner-identified Exness MT5 account only through the managed MetaTrader 5 terminal, exact account server, EA and bridge. The separate Exness API is not an authentication or trading shortcut for Exness MT5 and is deferred/no-code for the user's Nigeria region.

## Implementation constraints and documented gaps

- Do not hard-code an Exness login, password, server, terminal hostname/IP, or DEMO/LIVE assignment. Collect the account’s login and exact server from the account owner’s PA, and validate login/server/mode/company against a fresh authenticated terminal heartbeat. The broker label alone cannot identify the account server. Never treat the blog example `Exness-MT5server` as a universal server. [1] [2] [4] [7] [8]
- Maintain DEMO and LIVE as separate explicit profiles. Exness distinguishes virtual versus real funds; the repository also scopes credentials and authorization by profile and fails closed on mode or identity mismatch. Investor/read-only credentials must not authorize START; repository rules require MASTER access for trading. [3] [7] [8]
- MT5 credentials are terminal trading-account credentials. The reviewed official Exness sources do not document an Exness REST/WebSocket “Trader API” auth flow, API key/token, generic direct-to-broker endpoint, or programmatic KYC identity-check API. Do not invent or implement those requirements based on this evidence. [4]
- Exness PA KYC proof requirements belong to Exness onboarding. Do not collect or store POI/POA documents in this repository based on these pages; the app needs broker login/password/server only for its documented managed MT5 flow. [5] [6] [9]
- The repository has no proof of Exness broker login, API entitlement, broker-confirmed DEMO order/fill, or acceptance on an Exness terminal. Keep Exness execution marked NOT VERIFIED; do not promote the directory preset to supported/ready. Managed execution separately requires durable HTTPS bridge/worker infrastructure, encrypted credentials, isolated Windows terminal data/processes, and fresh identity checks; these are project requirements, not Exness-published API requirements. [7] [8] [9]
- **Not documented in reviewed sources:** universal Exness MT5 server names for all account types/regions; hostname/IP/port catalog; how to obtain or rotate a user’s trading password beyond linked account workflows; whether a standalone Exness Trader API exists for this use case; its auth, permissions, rate limits, or certification. Absence here is a documentation gap, not proof a service does not exist.

## References

[1]: https://get.exness.help/hc/en-us/articles/360011514572-MT5-Trading-guide-Desktop "MT5 Trading Guide for Desktop — Exness Help Center"
[2]: https://get.exness.help/hc/en-us/articles/360012578619-Trading-account-login-and-server-details "Trading Account Login and Server Details — Exness Help Center"
[3]: https://get.exness.help/hc/en-us/articles/360013191919-Real-and-demo-accounts "Real and Demo Accounts — Exness Help Center"
[4]: https://www.exness.com/blog/behind-the-markets/an-introduction-to-algorithmic-trading-with-python-and-mt5 "An Introduction to Algorithmic Trading with Python and MT5 — Exness"
[5]: https://get.exness.help/hc/en-us/articles/360004039991-How-to-verify-your-Exness-account "How to Verify Your Exness Account — Exness Help Center"
[6]: https://get.exness.help/hc/en-us/articles/17537772392732-Verification-document-requirements "Verification Document Requirements — Exness Help Center"
[7]: https://github.com/destinedai/DESTINEDFX-ANALYZER5 "DESTINEDFX-ANALYZER5 repository — broker capability matrix and broker directory"
[8]: https://github.com/destinedai/DESTINEDFX-ANALYZER5/blob/feat/complete-demo-readiness/docs/managed-execution-protocol.md "DESTINEDFX managed MT5 execution protocol"
[9]: https://github.com/destinedai/DESTINEDFX-ANALYZER5/blob/feat/complete-demo-readiness/docs/managed-execution-operator-setup.md "Managed MT5 execution operator setup"
[10]: https://get.exness.help/hc/en-us/articles/27866287512476-Exness-API "Exness API — official Help Center"
