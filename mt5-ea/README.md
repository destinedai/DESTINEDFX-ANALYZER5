# DestinedFX MT5 EA (bridge v1)

`DestinedFXTraderEA.mq5` is a deterministic MQL5 Expert Advisor that scans configured symbol/timeframe pairs and relays authenticated telemetry and commands through the DestinedFX bridge. It supports all eight registered strategy identifiers: SMC confluence, ICT Silver Bullet, Wyckoff schematics, supply and demand, breakout momentum, mean reversion, trend following, and liquidity stop hunts. Each strategy has a closed-candle setup path; indicators and setup data are read from completed bars rather than the forming candle.

## Safety gates

The EA is fail-closed. It requires the expected account login, exact server, and DEMO/LIVE mode to match the attached terminal before claim, START, RESUME, and every order. It requires an accepted authenticated heartbeat, terminal/broker trading permission, fresh quotes, valid broker stops, a hard maximum of 1% risk per trade, and a configurable daily-loss limit no greater than 2%. It never enables LIVE trading by itself and does not liquidate positions when STOP, PAUSE, or EMERGENCY_STOP is issued.

Before each new order, the EA requests a symbol-specific news verdict from the authenticated bridge. Unknown, malformed, uncovered, stale, provider-failure, incomplete, or relevant high-impact verdicts block the order. Effective verdict freshness is the lower of the configured `InpNewsFreshnessSeconds`, the bridge-provided age, and 120 seconds. EA-owned pending orders are cancelled when the current symbol verdict becomes unsafe.

Sizing requires a verified positive round-turn cost. Configured per-side costs are doubled; configured round-turn costs are used as supplied; observed costs require representative history. The target is calculated from the same after-cost definition used for validation: required gross reward is `2 × (gross stop loss + round-turn cost)`, and the final broker-calculated reward must satisfy `gross reward / (gross loss + cost) >= 2.0` immediately before submission. Broker tick rounding, stops, volume, margin, and the 10% margin buffer are rechecked. If the target cannot safely meet the threshold, the order is rejected.

Volume scaling is disabled by default and cannot exceed the initial volume unless scaling is explicitly enabled and at least the configured minimum of 20 fully closed, reconciled positions qualify. Qualifying history must belong to this EA magic number, contain matching entry and exit deals for the same position, contain no unresolved/open remainder, and have positive aggregate after-cost P/L including commission, swap, and fees. Manual trades, other EAs, duplicate deal records, partial/unresolved positions, and losing histories do not qualify.

## Installation and DEMO verification

Copy the `.mq5` file into the terminal `MQL5/Experts` directory and compile it in the target MetaEditor. Add the exact HTTPS bridge origin under **Tools → Options → Expert Advisors → Allow WebRequest**, set the expected account identity and pairing code, enable Algo Trading, and begin with a broker DEMO account. Confirm broker symbol suffixes, contract specifications, stop/freeze restrictions, and the deployed authorized news provider. The Android app and bridge keep DEMO and LIVE pairings and report files account-scoped.

The EA stores its terminal token, command cursor, processed command acknowledgements, order state, and signal deduplication state persisted in an account-login-scoped terminal file under the MT5 data directory. Treat those files as sensitive. A revoked token or stale heartbeat fails closed and requires operator intervention.

## Verification limitations

MQL5 MetaEditor and a live broker terminal are not available in this Linux development environment. Source-contract and bridge tests do not substitute for genuine MetaEditor compilation, MT5 WebRequest allowlisting, authorized news-provider deployment, or broker DEMO acceptance. Before any LIVE consideration, perform the acceptance runbook with real broker DEMO fills, reconcile partial and final closures, inspect the generated Android PDFs, and retain broker evidence.
