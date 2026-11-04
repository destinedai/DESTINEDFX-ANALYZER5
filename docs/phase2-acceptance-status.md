# DESTINEDFX Phase 2 acceptance status

## Source and scope

- Starting main SHA: `40e601f1a0454cacc83db53371178d3e028cb9a7`
- Feature branch: `feat/deriv-demo-acceptance-phase2`
- LIVE authorization and LIVE trading remain disabled.
- No PAT, password, App ID secret, account credential, or broker transaction evidence was entered into Manus or committed.

## Direct Deriv implementation review

The Direct Deriv path uses the current REST + Options WebSocket architecture. PAT requests include `Authorization: Bearer ...` and `Deriv-App-ID`; the app lists Options accounts, selects one exact active account for the chosen mode, requests the account-specific OTP, validates the returned authenticated `wss://api.derivws.com/trading/v1/options/ws/demo` or `/real` endpoint, and does not send the legacy v3 `authorize` message.

The current implementation now also:

- uses the documented `active_symbols: "brief"` request shape without the removed product filter field;
- requests live tick subscription on the authenticated Options WebSocket;
- keeps the existing one-contract-at-a-time, 1% per-contract risk ceiling and minimum 2R product validation;
- refuses to report a purchase as accepted until the broker buy response has valid contract and transaction IDs and a fresh account-, mode-, currency-, contract-, and transaction-matched portfolio record is observed;
- keeps ambiguous purchase outcomes unresolved and blocks blind retries;
- retains broker-confirmed open-contract, sell, expiry/settlement, and `profit_table` reconciliation behavior;
- removes the legacy `1089` default from the separate public market-data gateway.

The current official references used for this review are [Deriv API Overview](https://developers.deriv.com/docs/intro/api-overview/), [Authentication](https://developers.deriv.com/docs/intro/authentication/), [Get All Accounts](https://developers.deriv.com/docs/options/get-accounts/), [Options WebSockets](https://developers.deriv.com/docs/options/websocket/), [Complete Workflows](https://developers.deriv.com/docs/workflows/), [Buy Contract](https://developers.deriv.com/docs/trading/buy/), [Sell Contract](https://developers.deriv.com/docs/trading/sell/), and [Open Contract Status](https://developers.deriv.com/docs/trading/proposal-open-contract/).

## Test status

The local Android Gradle baseline could not start because this sandbox does not contain an Android SDK and no `ANDROID_HOME` or `local.properties` SDK path is configured. This is an environment blocker, not a reported source failure. The required Android JVM tests, APK build, emulator tests, and Windows MetaEditor compile must be run by GitHub Actions or an Android/Windows-capable CI environment.

The locally available Python suites passed: `147 passed` for bridge and MT5 EA pytest coverage, plus `26` managed-worker unittest cases. No genuine broker session was created in this task, so there is no DEMO purchase, contract ID, transaction ID, or settlement evidence to report.

## Exact remaining user actions

1. Register or confirm a genuine numeric Deriv App ID for the current API. Create a PAT with only the account/trading scope required by the Deriv application. Do not enable payment, deposit, withdrawal, or transfer permissions for this test.
2. Enter the PAT and App ID directly into the Android app. Do not paste them into Manus, GitHub, chat, screenshots, issues, logs, or source files.
3. Select DEMO mode and the exact intended active Options account. Complete the read-only balance, portfolio, active-symbol, and tick preflight.
4. Authorize one DEMO contract test only after the preflight succeeds. Use one supported underlying, one contract, the smallest broker-accepted stake, and remain under the application's 1% balance ceiling.
5. Verify the broker buy confirmation, matching portfolio record, open-contract monitoring, contract ID, transaction ID, sell or expiry result, and account history. If the purchase response or portfolio state is ambiguous, stop and reconcile without retrying.
6. Press STOP and verify no new purchase can be sent. Revoke the PAT after the operator test window, retaining only suitably redacted evidence.

For MT5, choose one genuine broker first. The separate operator checklist in `docs/managed-execution-operator-setup.md` requires the exact broker company and server identity from the real terminal, an isolated portable terminal, stable worker ID, durable HTTPS/database infrastructure where needed, WebRequest allowlisting, authenticated heartbeat, START, one explicitly authorized DEMO order, protection, STOP, and history reconciliation. Do not use the free Render profile for execution and do not create paid hosting without approval.

## Acceptance conclusion

The source-level changes improve the evidence boundary, but genuine DEMO order acceptance has **not** occurred in this task. The application must not claim broker acceptance until the user completes the credentialed Android/device procedure and supplies redacted broker-side evidence.
