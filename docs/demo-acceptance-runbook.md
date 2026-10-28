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

## News-provider and report acceptance gates

Before START, configure `NEWS_PROVIDER_URL` to an authorized HTTPS provider that returns the bridge news-verdict contract: `status`, `source`, `retrieved_at_ms`, `coverage_from_ms`, `coverage_to_ms`, `affected_currencies`, and, when applicable, `event`, `currency`, `event_time_ms`. `NEWS_PROVIDER_TOKEN` is optional and must be supplied only through the service environment. The provider is not bundled and no API key is embedded in this repository; if it is unset, unreachable, stale, incomplete, malformed, or unable to map the instrument, the bridge and EA must report `NEWS UNKNOWN — TRADING BLOCKED`. This is a deployment blocker, not a clear-calendar result.

For the genuine DEMO run, record a fresh `NEWS CLEAR` verdict for the exact symbol immediately before entry, including source, UTC retrieval time, coverage interval, affected base and quote currencies, and blackout start/end. Repeat with a relevant high-impact event and verify `HIGH-IMPACT NEWS — TRADING BLOCKED` before and after the 30-minute boundaries. Verify an unrelated-currency event does not block a mapped FX pair, while an unmapped instrument remains blocked. Verify an EA-owned pending entry is cancelled and confirmed in broker order history, while a manually placed order and existing position remain untouched.

After the first broker-confirmed fill, wait for the Android Bot screen to synchronize `/v1/app/trades` and verify an `OPEN_TRADE_DEMO_...pdf` appears under `Downloads/DestinedFX/TradeReports/DEMO/`. Confirm requested versus cumulative executed volume, order/deal/position identifiers, floating P/L, actual SL/TP verification, news evidence, and unavailable values are labelled `UNAVAILABLE`. After all closing deals reconcile, verify one stable `FINAL_TRADE_REPORT_DEMO_...pdf` aggregates every closing deal, gross P/L, commission, swap, fees and net P/L. Repeat the same heartbeat and reconnect to prove no duplicate PDF or double-counted money. Run the same evidence sequence for LIVE only after the existing explicit LIVE authorization succeeds.

## Evidence to retain

Retain timestamped redacted bridge health/readiness responses, worker lifecycle markers without secrets, Android screenshots, EA heartbeat samples, MT5 terminal journal excerpts, order/deal/position exports, and the final bridge command history. Redact login if organizational policy requires it, and always remove passwords, tokens, encryption keys, and private URLs before sharing.

## Current environment limitation

The repository test environment has no Windows MT5 terminal, MetaEditor, broker credentials, deployed production bridge, or broker DEMO account. Its automated coverage validates protocol, lifecycle, URL safety, telemetry schema, native Android gates, EA source contracts, and failure handling only. It does **not** verify a genuine broker connection, MetaEditor compilation, DEMO order, fill, partial fill, SL/TP, closed deal, official MT5 visibility, WebRequest allowlist, or recovery against a real terminal. Those items remain unverified until this runbook is executed by an authorized operator.

## All-strategy parity acceptance

The EA now accepts the same eight managed identifiers as the Android registry and dispatches each to a deterministic closed-candle path: SMC confluence, ICT Silver Bullet, Wyckoff, Supply and Demand, Volatility Breakout, Mean Reversion, Multi-EMA Trend Following, and Liquidity/Stop Hunts. For each selected identifier, record the strategy ID/version in the START configuration, heartbeat, signal, ledger record, and PDF report. Verify at least one positive fixture and one invalidation or insufficient-data WAIT/REJECT result per strategy before enabling it on DEMO. Do not infer success from the Android signal engine; the genuine MT5 terminal must show the corresponding EA rationale and broker-reconciled result.

For every strategy, verify decisions use closed candles only, reject stale quotes or insufficient history, deduplicate the same signal bar, and retain the reason when news, cost, risk, margin, minimum volume, or broker constraints reject an entry. Breakout volume is broker tick activity and must never be described as centralized exchange volume. Unsupported instruments or missing provider mappings remain `NEWS UNKNOWN — AUTOMATED ENTRY BLOCKED`.

## Audit findings and resolution

The repository audit found that the Android registry and deterministic Kotlin engine exposed eight strategies, but the managed EA validator and scanner accepted only ICT Silver Bullet. The EA now validates and dispatches all eight identifiers through closed-candle, strategy-specific setup functions; the runbook still requires genuine MT5 DEMO evidence for each path. The audit also found a six-hour EA-side news acceptance window; it is now bounded by the bridge-provided freshness value, capped at 120 seconds, with strict source, coverage, relevance, and event evidence checks. Separate broker fees were absent from the closed-deal/PDF net calculation; fee fields now remain nullable when unavailable and net P/L is reported as `UNAVAILABLE` rather than silently assuming zero.

Still unavailable in this repository environment are Windows MetaEditor compilation, a genuine broker news provider, MT5 WebRequest configuration, broker DEMO credentials, real fills/partial fills, and broker-confirmed report reconciliation. These are acceptance gates, not claims of completion.
