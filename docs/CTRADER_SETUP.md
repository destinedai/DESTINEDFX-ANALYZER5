# cTrader Open API setup

This path is free and runs on the Android phone. It does not require a PC, VPS, Render, Fly.io, MetaApi, or a paid API. It is only available when the chosen broker provides a cTrader account for the owner's entity.

## Owner steps

1. Create or sign in to a cTrader ID at the official cTrader site.
2. Open a **DEMO cTrader account** at the chosen broker. Confirm the account is actually cTrader, not MT4 or MT5.
3. Open the official cTrader Open API portal and register one Open API application. Provide a detailed description of the app to support Spotware's review.
4. Wait for Spotware's email. Every new Open API application is reviewed. While the portal status is **submitted**, DestinedFX displays **WAITING FOR SPOTWARE APPROVAL** and keeps authorization and account requests disabled. Do not treat this as a generic network error.
5. After approval, add the exact redirect URI that will be entered in the app. Spotware's default redirect URI is for the playground only. Prefer an owner-controlled HTTPS URI such as `https://your-owned-domain.example/ctrader/callback`. The custom-scheme fallback `destinedfx://ctrader-callback` is not claimed to work until a genuine owner-run test succeeds.
6. Copy the approved application's client ID and client secret into the app's cTrader setup screen. The client secret must never leave the phone. Do not paste it into chat, GitHub, a screenshot, a log, or a support message.

The official account-authentication page requires a redirect URI registered in the cTrader application and documents `client_id`, `redirect_uri`, `scope`, and optional `product` authorization parameters. It does not document a `state` parameter or explicitly confirm custom-scheme redirects. The app accepts callbacks only when scheme, host, and path exactly match the configured registered URI. An HTTPS callback also requires owner-controlled domain registration and app-link or browser configuration, which are OWNER STEPS. This task does not register or buy a domain and does not claim that an embedded WebView Google login path was tested.

7. Start with the `accounts` scope. This is read-only account access. Do not select `trading` until a separate DEMO acceptance record exists and the owner has explicitly approved the next phase.
8. Tap **Grant access**. Complete the cTrader ID consent page in the external browser. Return through the configured callback. The app rejects callback errors, mismatched redirect hosts or paths, reused codes, and codes older than one minute before exchanging the short-lived authorization code for encrypted access and refresh tokens.
9. Select the broker and DEMO account, then tap **VERIFY CTRADER DEMO**. The app must receive the account list, authenticate the account session, read trader information, and obtain a fresh quote before showing CONNECTED.

## What the app stores

The client ID, client secret, redirect URI, access token, refresh token, and access-token expiry are stored only in Android Keystore-backed encrypted preferences. If secure storage is unavailable, the app refuses to save the credentials. Tokens and secrets are not printed in logs or returned in UI error messages.

The access token normally lasts about 30 days. The app refreshes before expiry, uses the documented GET authorization-code exchange and POST refresh request with query parameters, retries HTTP 429 with bounded backoff, and stores rotated tokens atomically through encrypted preferences. Re-authorisation is required if cTrader invalidates the grant or the refresh token is no longer accepted.

## Safety boundary

The first implementation is read-only. It contains no new-order, amend-order, or close-order method. It must never send an order message during DEMO verification. A fresh authorised session and fresh account data are required. A missing quote, stale quote, account mismatch, broker mismatch, or DEMO/LIVE mismatch is BLOCKED.

Trading support is a later phase. It must remain DEMO-only until an acceptance record exists and must pass the existing risk gates, including the 1 percent cap, net 2R after verified costs, stop loss, quote freshness, margin, daily loss, news block, durable intent, duplicate prevention, reconciliation, restart recovery, and emergency stop.

## Official references

- [Spotware: Register a new application](https://help.ctrader.com/open-api/api-application/)
- [cTrader Open API account authentication](https://help.ctrader.com/open-api/account-authentication/)
- [cTrader Open API FAQ](https://help.ctrader.com/open-api/faq/)
- [cTrader Open API messages](https://help.ctrader.com/open-api/messages/)
- [Official protobuf definitions](https://github.com/spotware/openapi-proto-messages)

This setup does not verify that any named broker offers cTrader for the owner's entity. The owner must return the redacted verification card before any broker is described as working. No order was placed and no profit is promised.
