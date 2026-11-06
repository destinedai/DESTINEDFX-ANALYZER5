# Deriv MT5 — documentation findings

## Identification

- **id:** `deriv_mt5`
- **name:** Deriv MT5

## Findings

- **MT5 credentials:** Deriv's documented MT5 sign-in requires the account-specific login ID, server name, and Deriv MT5 trading password. The login ID and server are obtained from the user's Deriv MT5 dashboard. Sign-in is supported through the MT5 desktop app, web terminal, or mobile app. Deriv says the MT5 credentials are separate from the main Deriv login; the password is separately created for MT5. [1] [2] [3]
- **Server/environment:** No universal server name, hostname/IP, port, or broker-server catalog appears in the reviewed official pages. The server must be read from the exact account dashboard; do not substitute a guessed or generic Deriv server. Deriv offers Demo and real-money MT5 accounts; demo uses virtual funds. The official demo help page says inactivity for 30 days automatically deactivates a demo account, and a new one can be created. The reviewed sources do not list exact server names per environment or claim that server string alone proves DEMO/REAL mode. [1] [4]
- **Authentication/identity:** The documented MT5 terminal authentication is the account's login ID + MT5 password + server, not the main Deriv password. The reviewed Deriv pages do not specify an MT5 OAuth/API token, REST/WebSocket broker-auth protocol, API entitlement, or separate terminal identity-validation challenge. Deriv's profile verification is distinct: its current guide describes liveness selfie, government-issued proof of identity, and address verification (GPS or proof-of-address documents), followed by terms/FATCA steps. This is Deriv profile/KYC, not an MT5 terminal credential or handshake requirement; the page does not say those checks are required for every MT5 login. [1] [3] [5]
- **Repository state:** Repository documentation defines managed MT5 as a terminal/EA/worker path, not direct Android broker login. The app supplies login, exact server, password, access type, and explicit DEMO/LIVE profile to the managed bridge. The EA independently checks actual terminal login, exact server, and `ACCOUNT_TRADE_MODE`; readiness additionally requires a fresh matching heartbeat, connectivity, and trade permission for MASTER access. Credentials are encrypted at rest by the bridge and exposed only in authenticated worker claim; LIVE has a separate explicit authorization gate. Current repo docs say there is no deployed managed service or genuine broker login/order test; MT5 remains NOT VERIFIED and LIVE BLOCKED. These are repository constraints, not Deriv-published requirements. [6] [7] [8]

## Implementation constraints and documented gaps

- Accept/store the user's account-specific login ID, exact server as displayed in that Deriv MT5 dashboard, and MT5 trading password. Do not reuse the main Deriv password or infer server/mode from a `Deriv` broker label. Never hard-code credentials, hostnames, or DEMO/REAL assignment. [1] [2] [3]
- Keep DEMO and LIVE as explicit separate profiles. Verify the actual terminal login, exact server, and terminal-reported mode against the selected profile; do not trust a user-entered mode or server as identity proof. This matches the repository protocol, which obtains identity/mode from the running MT5 terminal. [6] [7]
- Apply the existing managed MT5 requirements: authenticated HTTPS bridge, encrypted broker credentials, isolated Windows terminal/worker and EA, fresh identity-matched heartbeat, MASTER access for trading, and broker-confirmed DEMO acceptance before claiming readiness. No Deriv-specific exception to these repository gates is documented. [6] [7] [8]
- Do not add KYC document collection to this integration based solely on the profile-verification page. Do not invent a Deriv MT5 API key/token, endpoint, OAuth handshake, server hostname, port, or machine-level identity proof from the reviewed material. [1] [5]
- **Not documented in reviewed official sources:** all assigned Deriv MT5 server names and their environment mapping; server endpoint/IP/port catalog; any standalone API authentication or API entitlements for this repository use case; mandatory KYC status for each MT5 terminal login. These are evidence gaps, not proof that such data/services do not exist.

## References

[1]: https://deriv.com/help-centre-question/how-to-log-in-to-deriv-mt5-account "How do I log in to my Deriv MT5 account? — Deriv Help Centre"
[2]: https://deriv.com/help-centre-question/how-do-i-activate-a-deriv-mt5-trading-account "How do I activate a Deriv MT5 trading account? — Deriv Help Centre"
[3]: https://deriv.com/help-centre-question/deriv-mt5-login-different-from-deriv-login "Why are my Deriv MT5 login details different from my Deriv login details? — Deriv Help Centre"
[4]: https://deriv.com/help-centre-question/how-long-use-my-deriv-mt5-demo-account "How long can I use my Deriv MT5 demo account? — Deriv Help Centre"
[5]: https://deriv.com/help-centre-question/how-do-i-verify-my-profile "How do I verify my profile on Deriv? — Deriv Help Centre"
[6]: https://github.com/destinedai/DESTINEDFX-ANALYZER5/blob/main/docs/managed-execution-protocol.md "DestinedFX managed MT5 execution protocol"
[7]: https://github.com/destinedai/DESTINEDFX-ANALYZER5/blob/main/docs/managed-execution-operator-setup.md "Managed MT5 operator setup"
[8]: https://github.com/destinedai/DESTINEDFX-ANALYZER5/blob/main/docs/broker-capability-matrix.md "DestinedFX broker capability matrix"
