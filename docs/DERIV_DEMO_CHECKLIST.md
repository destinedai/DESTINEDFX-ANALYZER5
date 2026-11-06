# Owner checklist: Deriv Options DEMO verification

Status labels used in this checklist:

- `IMPLEMENTED`: code exists in the branch.
- `UNIT-TESTED`: a JVM or offline test covers the behavior.
- `CI-VERIFIED`: the exact commit passed repository CI.
- `BROKER-ACCEPTED`: the owner supplied a redacted result card from a real broker session.
- `NOT VERIFIED`: no real broker evidence has been supplied yet.

## Deriv Options DEMO read-only check

1. Register a current numeric Deriv App ID at `developers.deriv.com`. Do not use legacy App ID `1089`.
2. Create a Personal Access Token (PAT) for this App ID with the `trade` scope required by the Options account-list and OTP endpoints. The verifier itself only reads account/balance/portfolio and market data and does not trade. A PAT with `trade` scope is not inherently read-only, so keep it private and enter it only in the protected app input or local hidden prompt. Never paste it into chat, logs, reports, or screenshots.
3. Options API credentials and account selection are separate from Deriv MT5 credentials and the MT5 terminal flow. Do not use an Options PAT as an MT5 login.
4. Install the APK using [`docs/INSTALL_APK.md`](INSTALL_APK.md), and verify its SHA-256 against the CI summary first.
5. Open the app on the phone and select the Deriv Options section.
6. Select `DEMO`.
7. Enter the App ID and DEMO PAT only in the app.
8. Tap `VERIFY DERIV DEMO · READ ONLY`.
9. Wait for the redacted result card. It must show the first four characters of the account ID only, `DEMO`, currency, `Balance received: YES`, `Portfolio received: YES`, `Public tick received: YES`, and a check time. The public tick check creates a separate unauthenticated connection to `wss://api.derivws.com/trading/v1/options/ws/public`, subscribes specifically to `R_100`, and requires a matching, positive finite quote with fresh provider and receive timestamps. It never relies on a market/chart cache and does not change the selected chart source or instrument.
10. Send back only a screenshot of that redacted result card. Do not include the PAT, OTP, full account ID, App ID, or raw error response.

A result card is `BROKER-ACCEPTED` evidence only after the owner returns it. Until then, Deriv Options DEMO read-only verification is `NOT VERIFIED`. No order of any kind is placed by this check.

## Failure recovery

- App ID rejected: confirm the current numeric App ID and do not use `1089`.
- Authentication rejected: create a fresh PAT for this App ID with the `trade` scope required by Options account listing and OTP.
- No DEMO account: create or select an active DEMO Options account in Deriv, then retry.
- OTP or WebSocket timeout: retry once on a stable network. The app requests a fresh one-time code.
- Balance, portfolio, or public tick says `NO`: do not treat the connection as verified. Retry once and return the redacted failure card if it remains blocked.
- Secure storage unavailable: stop. Do not enter credentials again until Android secure storage is available.

## Separate DEMO order test later

Any future order test is a separate, owner-approved DEMO task. Before it starts, write down the symbol, stake, multiplier, stop loss, take profit, and the exact confirmation evidence required. This checklist does not authorize an order and no order is placed here.

## MT5 status

FBS, Exness MT5, HFM, and Deriv MT5 remain `BLOCKED BY EXTERNAL REQUIREMENT` until the owner provides a Windows host with the genuine MT5 terminal, exact broker server, DEMO login, worker runtime, EA, and a reachable bridge. The app must not claim MT5 connected without a fresh identity-matched DEMO EA heartbeat. No MT5 broker login was exercised in this task.
