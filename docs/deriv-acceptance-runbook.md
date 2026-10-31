# DestinedFX Direct Deriv Acceptance Runbook

This runbook covers the Android app's direct Deriv Options WebSocket path and multiplier contracts. It is separate from the managed MT5 runbook. Account connection is not evidence of trading readiness, and code/CI success is not broker acceptance.

## Scope and safety boundary

The implementation supports selecting an exact Deriv DEMO or REAL Options account. REAL orders require a separate, explicit, process-memory-only authorization bound to the selected account ID, account type, currency, authenticated WebSocket session, and current credential generation. The authorization expires 60 minutes after explicit arming; it is not persisted or silently renewed. The app must be deliberately re-armed after expiry.

REAL acceptance in this runbook is limited to verifying account identity and the arming safety controls. Do not start the bot or submit a REAL order as part of software acceptance. Use an authorized broker DEMO account for any order-path test. No real credentials should be put into this repository, a report, chat, screenshot, or test fixture.

## Readiness gate

The REAL arm control is available only when all of the following are true:

- The gateway state is `READY`, with the current authenticated WebSocket session matching the account snapshot.
- The selected account is exactly the current REAL account, with a nonblank account ID and validated currency; the credential generation still matches the credential used for authentication.
- A fresh, positive balance in the account currency and a successful, fresh portfolio snapshot for the same account, mode, and currency are both available. The accepted freshness window is eight seconds.
- No unresolved execution exists for the selected account, and no legacy execution has an unknown account identity.

While any condition is incomplete, the UI reports `SYNCHRONIZING` (or `NOT READY` after a connection failure) and the arm control remains disabled. The same freshness, account, and session checks are repeated immediately before a proposal purchase is transmitted. The order executor separately blocks new entries while a prior execution remains unresolved or requires reconciliation.

## DEMO acceptance procedure

1. Record the APK version and source commit, Android device/OS, Deriv App ID, and the selected account ID, mode, and currency. Never record the PAT or include it in screenshots/logs.
2. Create or select a Deriv Personal Access Token with only the scopes required for the tested account and registered app. Enter it directly in the Android app. Start with DEMO mode and select the exact intended account if multiple active DEMO accounts are returned.
3. Connect and confirm the UI remains `SYNCHRONIZING` until a fresh balance and matching successful portfolio are available. Confirm it reports `DEMO VERIFIED` only after the gateway is `READY`; confirm account ID, DEMO mode, and currency match the broker account. A stale/mismatched snapshot or unresolved prior execution must block readiness.
4. Keep stake and multiplier at the smallest broker-accepted values and use a supported underlying/contract. Verify the app's proposal is validated before the purchase step. If a required validation, quote, portfolio, balance, or authorization check fails, confirm that no purchase is sent.
5. For an explicitly approved DEMO order-path test, start the bot once and observe the request lifecycle. Confirm the broker's contract ID, transaction ID, purchase price, and account history match the app. Do not infer a fill from a proposal or a queued request. If the outcome is ambiguous, stop and reconcile; do not retry with a new signal or request ID.
6. Stop the bot. Reconcile active contracts and settlements against the Deriv account history, then confirm the app no longer submits entries while disconnected, stale, or unresolved. Retain only redacted evidence and revoke the test token when the authorized operator's test window ends.

## REAL authorization-control check — no order

Use only an account owner/operator who is explicitly authorized to view the selected REAL account. Confirm the exact account ID, REAL account type, currency, current session, fresh balance, and fresh portfolio. Confirm the arm button is disabled before readiness and whenever any prerequisite is incomplete or an unresolved execution exists. After readiness is complete, an authorized operator may verify that the UI requires an explicit arm action, displays a 60-minute expiry policy, and returns to disarmed/blocked after expiry or session/credential changes.

Do not press the bot start control, submit a proposal, or transmit a REAL purchase during this check. An armed lease is not evidence that a REAL order has been placed, accepted, or tested. If any status is ambiguous, leave REAL disarmed.

## Evidence and reporting

Keep timestamps, selected account mode/ID/currency (redacted as required), app build/source commit, readiness state, snapshot ages, and broker-side DEMO order/contract/settlement evidence. Never retain PATs, app secrets, full authorization headers, or account credentials. Report separately: source tests, CI builds, authenticated broker connection, DEMO order lifecycle, and REAL trading. Do not describe one as proof of another.

## Current acceptance status

The source-level fail-closed readiness gate, account-bound 60-minute REAL lease, and order-boundary expiry check are covered by Android unit tests. This runbook still requires an authorized genuine Deriv DEMO session to validate the current API response and broker order lifecycle. No genuine Deriv broker session or REAL order has been tested by this task.
