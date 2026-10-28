# DESTINEDFX Managed MT5 DEMO Acceptance Runbook

This procedure is for one authorized broker DEMO account only. It must be executed on the real Windows host with the genuine MT5 terminal, MetaEditor, the deployed HTTPS bridge, and a broker DEMO account. It must never be run against a LIVE account and must never be reported as complete from fake terminal tests.

## Preconditions

Record the bridge base URL, worker ID, MT5 terminal build, EA source/compiled build hash, broker company, exact server, login, currency, and account mode. Confirm that no broker password, worker token, Fernet key, or hosting secret is written into this record. Configure the MT5 terminal WebRequest allowlist manually with the exact bridge origin; the worker marker records this as an operator-required condition because MT5 does not reliably configure the allowlist through the startup INI.

Confirm `DESTINEDFX_WORKER_ID` is stable and present in the Windows service environment. Confirm the bridge `/health` liveness endpoint and `/health/ready` readiness endpoint, persistent database path, worker token authentication, and database backup policy. Confirm the Android repository variable `MT5_MANAGED_SERVICE_URL` is the exact HTTPS origin; never insert a placeholder URL.

## Execution checklist

1. Pair the selected DEMO profile from the native Android app and submit the exact login, broker server, password, `MASTER` or `INVESTOR` access level, and DEMO consent. Confirm DEMO and LIVE credentials remain independent.
2. Claim the job with the Windows worker. Confirm the stable worker ID, lease ownership, per-account portable terminal directory, executable identity marker, access type, expected login/server/mode, `startup_requires_start_command`, and `webrequest_allowlist_required` fields. Confirm no marker, log, or startup artifact contains the password.
3. Apply the MT5 WebRequest allowlist manually, launch the genuine terminal and EA, and confirm the terminal remains `AUTHENTICATING` until a fresh EA heartbeat is accepted.
4. Verify the heartbeat account exactly matches login, server, company, currency, and DEMO mode. Verify the bridge reports a fresh heartbeat, eligible EA state, connected terminal, and correct trade permission. Verify an INVESTOR profile remains read-only.
5. Send START once. Record the request ID. Confirm the bridge and Android UI show queued, then acknowledged, then strategy activated only after the matching acknowledgement and fresh heartbeat. Repeat the same request ID after an induced HTTP timeout and confirm no duplicate command or order is produced.
6. Observe a genuine strategy cycle. Confirm the EA reports the order lifecycle separately: submitted/pending, partially filled if applicable, filled with actual executed volume, rejected/cancelled, position reconciled, actual SL/TP, and closed deal history. `TRADE_RETCODE_PLACED` must remain submitted/pending, never filled.
7. Compare the heartbeat active-order list, open-position list, and closed-deal list against the MT5 terminal and broker history. Record order ticket, deal ticket, position ID, requested volume, executed volume, actual entry, SL, TP, commission, swap, realized P/L, and timestamps.
8. Confirm risk behavior using broker-supported volume step, minimum stop distance, fresh quotes, free margin, daily loss, concurrent-position limits, and the selected verified cost model. For `OBSERVED_ROUND_TURN`, confirm at least three representative same-symbol deal samples exist in the 30-day history. For a new account, use only `CONFIGURED_ROUND_TURN` or `CONFIGURED_PER_SIDE` with positive operator-supplied cost and documented broker evidence. If neither verified observed nor verified configured cost exists, confirm the EA blocks the trade rather than assuming zero.
9. Send STOP and confirm new entries are blocked while existing positions and broker SL/TP remain untouched. Close the DEMO position only through the approved operational procedure, then verify the closed deal and realized P/L.
10. Induce heartbeat loss or terminal crash. Confirm START/RESUME is blocked immediately, the worker enters bounded recovery, a restarted terminal remains AUTHENTICATING, and READY returns only after a fresh account-matched heartbeat. Confirm no duplicate order appears after recovery.
11. Test a pending or partially filled order if the broker supports one in DEMO. Confirm reconciliation uses the same order/deal identifiers and actual remaining/executed volume.
12. Revoke pairing. Confirm the worker verifies process identity, terminates only the owned terminal, quarantines or deletes only after definite process death, and the bridge rejects subsequent authenticated operations.

## Evidence to retain

Retain timestamped redacted bridge health/readiness responses, worker lifecycle markers without secrets, Android screenshots, EA heartbeat samples, MT5 terminal journal excerpts, order/deal/position exports, and the final bridge command history. Redact login if organizational policy requires it, and always remove passwords, tokens, encryption keys, and private URLs before sharing.

## Current environment limitation

The repository test environment has no Windows MT5 terminal, MetaEditor, broker credentials, deployed production bridge, or broker DEMO account. Its automated coverage validates protocol, lifecycle, URL safety, telemetry schema, native Android gates, EA source contracts, and failure handling only. It does **not** verify a genuine broker connection, MetaEditor compilation, DEMO order, fill, partial fill, SL/TP, closed deal, official MT5 visibility, WebRequest allowlist, or recovery against a real terminal. Those items remain unverified until this runbook is executed by an authorized operator.
