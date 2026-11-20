# Deriv Options API — documentation findings

## Identification

- **id:** `deriv_options_api`
- **name:** Deriv Options API

## Findings

- **Credentials and application identity:** The documented authenticated REST credential is a Bearer token, provided either through OAuth 2.0 or a Personal Access Token (PAT). For PAT calls, send the registered application's `Deriv-App-ID` header; OAuth access tokens identify the app and do not require that header. PATs are manually generated/entered and scope-limited. The account-list and OTP endpoints require the `trade` scope. The docs describe no account username/password as the Options API credential. [1] [2] [3]
- **Server/environment:** The documented REST base is exactly `https://api.derivws.com`. The API Overview documents public WebSocket `wss://api.derivws.com/trading/v1/options/ws/public`; authenticated demo and real paths are `/trading/v1/options/ws/demo` and `/trading/v1/options/ws/real`. The OTP response supplies the ready-to-use authenticated WebSocket URL. These are the documented Options API endpoints, not an MT5 server catalog. Public data needs no authentication; account-scoped actions do. [2] [3] [4] [5]
- **Authentication sequence and lifetime:** Call `GET /trading/v1/options/accounts` with Bearer auth (and `Deriv-App-ID` for PAT) to obtain the Options accounts associated with the credentials; the docs say the result includes demo and real accounts. Select an account ID, then `POST /trading/v1/options/accounts/{accountId}/otp` using the same REST authentication. The returned OTP is single-use and valid for 120 seconds. Connect to the exact returned `wss` URL promptly; the OTP is in its query string and the WebSocket connection requires no additional authentication headers. [2] [3] [4]
- **Identity/mode validation:** The documented account-list operation returns accounts for the authenticated credentials; OTP generation is scoped to a specified account ID. Demo and real use distinct WebSocket paths, and the docs explicitly state trades on the real path settle against real balance. The REST references document account ID, account type, currency and balance fields as account information in the account response model/examples, but do not prescribe a separate KYC, liveness, identity-document, or terminal-identity challenge for API authentication. Treat KYC/API access policy beyond these published mechanics as undocumented here. [3] [4] [5]
- **Repository context:** Current code uses `https://api.derivws.com`, sends Bearer token plus `Deriv-App-ID` to list accounts and request OTP, checks the returned URL host/path against `api.derivws.com` and the selected `demo`/`real` mode, then authenticates the account-scoped WebSocket with the OTP URL. It requires an explicitly selected/uniquely selectable active account, validates `account_id`, `account_type`, currency and balance, then waits for fresh matching balance and portfolio snapshots before READY. The repository’s DEMO/REAL separation, REAL arm lease, freshness gates, and no-real-order acceptance boundary are repository controls, not requirements stated by Deriv. [6] [7]

## Implementation constraints and documented gaps

- Keep the API base and WebSocket host/path exact as documented; do not use legacy `ws.derivws.com`, MT5 server labels, or guessed hostnames for this Options API integration. Use the OTP URL returned by Deriv rather than constructing or altering its query token. [2] [3] [4]
- Request only required scopes. For the current PAT integration, `trade` is documented for account listing and OTP; PAT requests also need the registered app's `Deriv-App-ID`. A numeric App ID is a repository input validation, but the documentation reviewed does not establish the repository's additional rejection of App ID `1089`; treat that as a code policy, not a vendor requirement. [1] [3] [6]
- Never treat the public WebSocket as an authenticated account session; it supports public market data only, not account balance or trading. [2] [5]
- Verify selected account ID and returned account type against the requested mode; do not infer demo/real mode from token, app ID, account label, or hostname. The API documentation identifies separate account types/endpoints but does not publish an independent user identity-proof handshake. [3] [4] [5]
- **Not established in reviewed official sources:** PAT expiry/rotation specifics; any fixed numeric App ID requirement or universal App ID value; an account KYC prerequisite for these API calls; guarantees that every user always has both account modes; and any MT5 hostname/server facts. These remain gaps, not evidence that such rules do not exist.

## References

[1]: https://developers.deriv.com/docs/intro/authentication/ "Deriv API Authentication"
[2]: https://developers.deriv.com/docs/intro/api-overview/ "Deriv API Overview"
[3]: https://developers.deriv.com/docs/options/ "Options Setup REST"
[4]: https://developers.deriv.com/docs/options/websocket/ "Options API WebSockets and OTP"
[5]: https://developers.deriv.com/docs/options/ws-demo/ "Options API Demo WebSocket Endpoint"
[6]: https://developers.deriv.com/docs/options/ws-real/ "Options API Real WebSocket Endpoint"
[7]: https://developers.deriv.com/docs/options/get-accounts/ "Options API Get All Accounts"
