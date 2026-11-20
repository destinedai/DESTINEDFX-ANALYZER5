# FBS MT5 — documentation findings

## Identification

- **id:** `fbs-mt5`
- **name:** FBS MT5

## Findings

- FBS’s MT5 login instructions identify the trading login as the account number and require the account’s trading password to trade. An investor password is read-only and disables order placement. MT5 also requires selecting the proper server; FBS says the server number is supplied when the account is opened and can be checked in Trader Area → Account info. On mobile, the instructions distinguish Real and Demo server selection. Thus, the login/server are account-specific values, not values inferable from the broker name alone. [1]
- FBS’s MT5 web page likewise says to use FBS MT5 account details and choose a server and platform. [2]
- FBS documents separate Demo and Real environments. Demo uses virtual funds and is for practice; Real uses real funds and carries real risk. The Demo page describes opening a Demo account and choosing/configuring it, but does not publish a universal login or per-account server assignment. [3] [4]
- FBS says to create an FBS account and open a Real MT5 account to obtain credentials, and to verify the account for full access. This is FBS Trader Area verification, not a documented MT5 server-authentication protocol. The Help Center procedure asks the user to select identity-document type and issuing country, enter details matching official documents, and upload color copies of a passport or government-issued photo ID **with address proof** (JPG, PNG, HEIC, WEBP, or PDF; combined size at most 50 MB). FBS says the request is reviewed and its status is shown in Trader Area. [4] [5]
- The official MT5 download links expose server labels `FBS-Demo` and `FBS-Real` in their query parameters. This is evidence those labels are offered by that download path, not proof they are valid server values for every account, legal entity, region, or terminal build. FBS’s login guide directs users to their own assigned server. [4] [6]
- Repository context: FBS currently appears as the broker company name `FBS Markets Inc.` in the broker directory and workstation default. The repository’s generic MT5 managed bridge/EA treats exact login, server, and DEMO/LIVE identity as inputs that must match the attached terminal; broker execution remains unverified in the capability matrix. These are project facts, not FBS claims. [7]

## Implementation constraints and documented gaps

- Do not hard-code or synthesize FBS login IDs, trading passwords, investor passwords, or account server names. Obtain the login and assigned server from the account owner/Trader Area and validate against the connected terminal’s reported identity. The FBS broker label alone is insufficient to select an account endpoint. [1] [7]
- Keep Real and Demo environments explicit and independently verified. Never infer Demo/Real solely from a display label or presume that the sample `FBS-Demo`/`FBS-Real` server strings cover all accounts. [1] [3] [4] [6]
- Trading access requires a trading password; investor credentials are observation-only according to FBS. The available FBS material does not document a broker API, OAuth flow, programmatic identity-verification endpoint, MFA/OTP behavior for MT5 logins, server hostnames/IPs/ports, a complete server catalog, or a stable machine-readable account-to-server mapping. Treat each as **not documented here**, not as supported or required. [1] [5]
- FBS Trader Area identity verification is distinct from supplying MT5 trading credentials. FBS describes ID and address-proof submissions for verification, but the reviewed pages do not specify that those documents should be collected or stored by this repository. Do not add such collection based on these sources. [4] [5]
- Repository support is not established by the FBS broker-name preset: project documentation marks MT5 managed-bridge authentication and DEMO order acceptance as not verified, requiring an authenticated bridge, worker, terminal, exact account identity, and broker-confirmed evidence. [7]

## References

[1]: https://fbs.com/hc/How-to-log-in-to-my-trading-account "How to Log In to My Trading Account — FBS Help Center"
[2]: https://fbs.com/trading/metatrader-5/mt5-web "MT5 WebTrader — FBS"
[3]: https://fbs.com/trade/demo-trading-account "Open a Forex Demo Account — FBS"
[4]: https://fbs.com/trade/mt5-trading-account "Open Your Real MT5 Trading Account — FBS"
[5]: https://fbs.helpcenter.io/en/content/how-can-i-verify-my-trader-area "How Can I Verify My Trader Area — FBS Help Center"
[6]: https://fbs.com/trade/mt5-trading-account "Official MT5 download links and listed server labels — FBS MT5 account page"
[7]: https://github.com/destinedai/DESTINEDFX-ANALYZER5 "DESTINEDFX-ANALYZER5 repository"
