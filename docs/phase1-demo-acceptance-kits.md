# Phase 1 DEMO acceptance kits

**Scope:** Nigeria-based individual retail DEMO accounts only. LIVE remains BLOCKED. This is a planned operator checklist, not evidence of a completed broker test. No order, credential entry, broker login, or account verification was performed while preparing it. Each account login and each order/close test requires the owner's separate approval at the time of that step.

The two broker paths are distinct: Deriv Options/Multipliers uses the Android app's direct Deriv API path and does not need the Windows host; the second path uses one MT5 broker account through the app → bridge → Windows worker → MT5 terminal/EA. The owner has not yet identified which one of FBS, Exness, or HFM holds the relevant DEMO account. Select exactly one actual account/brand before its MT5 acceptance. This does not authorize Exness Trader API, OANDA, cTrader, IBKR, additional adapters, or any LIVE work.

## Rules for both kits

- Use only a genuine account issued by the broker for DEMO. Never use preview/sample values as evidence of a quote, connection, fill, or history item.
- Keep LIVE disabled and its credentials unused. Do not paste credentials, tokens, account passwords, worker tokens, or screenshots containing them into chat, repository files, issues, or test evidence.
- Record the app/build version, source commit, device/Android version, UTC time, account mode, currency, and redacted account identifier. Keep broker reference IDs only in the owner's private acceptance record.
- Before an order or close action, pause and obtain the owner's explicit approval for that exact DEMO action. Use the smallest broker-accepted size. Do not send a second request when the first result is ambiguous; reconcile with the broker first.
- Stop the test if identity, mode, currency, quote source/freshness, permission, SL/TP, or broker-side result is missing, stale, or inconsistent. A request queued by the app is not a broker confirmation.

## Kit A — Deriv Options / Multipliers DEMO (first priority)

The direct Deriv path is selected first because it has no Windows-host dependency. Use the current registered Deriv App ID and the API path implemented by the app. Legacy Deriv App IDs are not accepted by the new API path. Enter a DEMO token only in the app after the owner explicitly approves the connection step; do not disclose it in the evidence.

### A. Preflight and account verification

1. Confirm the selected workspace/environment is **DEMO** and LIVE controls are unavailable/disarmed. Record the selected Deriv account ID in a redacted form and record its reported account type and currency.
2. Connect and wait for the app's fresh account snapshot and portfolio synchronization. Require the app to show the exact account as **DEMO VERIFIED/READY** only after those broker responses match the selected DEMO account, mode, currency, and current authenticated session. If it remains synchronizing, the account is not verified.
3. Confirm the authenticated Options WebSocket session is for that same account and DEMO endpoint. A successful token check or WebSocket connection alone is not readiness.
4. Require a fresh broker-sourced tick for the chosen underlying and record its source, instrument, timestamp, and observed age. The timestamp must fall inside the current app policy; if the app does not expose enough information to judge freshness, mark this gate **BLOCKED**, not passed.

### B. One contract, broker confirmation, and protection

5. Select a supported Deriv Options/Multiplier contract and verify the instrument, direction, expiry/contract parameters, stake, multiplier, maximum loss, and take-profit amount before submission. The protection rule must satisfy the code's current 2.0R minimum (the configured cash take-profit must be at least twice the stop-loss cash amount); do not reduce or omit protection to make an order pass.
6. **Approval gate:** request explicit owner approval for one identified DEMO contract with the displayed instrument, stake, multiplier, and protection. If not approved, do not press buy.
7. Submit exactly once. Record the app request state, Deriv contract ID, transaction ID, and buy response. Do not label it accepted based only on proposal or HTTP/WebSocket acknowledgement.
8. Verify that a fresh broker portfolio record matches the selected account, DEMO mode, currency, contract ID, and transaction ID. Confirm the open-contract state and that the broker response reflects the requested stake/protection. Any mismatch or timeout becomes **UNRESOLVED**; do not retry.

### C. Close/expiry, history, STOP, and recovery

9. **Approval gate:** before a manual close/sell test, obtain separate owner approval. Otherwise allow the single DEMO contract to expire/settle. Require broker-confirmed closure/settlement, then compare contract and transaction identifiers and realized result with the account's contract/history record.
10. Reconcile `profit_table`/history and the current portfolio. Confirm the closed/settled contract is no longer open and that the app's durable execution ledger agrees. A local terminal state without matching broker history is not a pass.
11. After the contract is settled, use **STOP**. Confirm the app reports stopped, blocks new entries, preserves any unresolved broker state, and does not claim that STOP itself closed an already-open contract. If a contract is still open, close/settle it through the separately approved procedure first.
12. With no open test contract, perform a controlled app/service restart and a network-drop recovery check. After network return, require reauthentication/reconnection as needed, a fresh account snapshot, a fresh tick, and portfolio/history reconciliation before the app returns to ready. Confirm no duplicate purchase was sent and STOP remains effective until deliberately restarted by the owner.

**Pass only if:** identity/mode/currency, fresh tick, one broker-confirmed DEMO contract, portfolio match, close or expiry, history reconciliation, STOP, restart, and network-drop recovery all have redacted evidence. Otherwise record the exact gate as BLOCKED/FAILED/UNRESOLVED and keep the bot disabled.

## Kit B — one managed MT5 DEMO broker (second priority)

The broker is intentionally **not yet selected**: use only the one genuine DEMO account the owner identifies among FBS, Exness MT5, and HFM. Never substitute Exness Trader API. These are shared MT5 terminal/EA paths, not evidence of separate broker REST APIs. The selected brand's precise terminal server must come from the account's own broker onboarding; do not guess a server name.

**Current status: BLOCKED / NOT VERIFIED.** The repository's current Windows installer/supervisor starts a native bridge behind Tailscale Serve; it cannot safely be combined with the Docker/Caddy bridge recipe, and its backup task does not cover the Compose data volume. Do not start this acceptance until a separate worker-only supervisor and a verified Docker-volume backup/restore path are implemented and tested on Windows. The steps below are the planned acceptance criteria, not approval to begin or evidence of readiness.

### A. Host and account verification

1. Once the blocker above is cleared, use the owner-controlled Windows PC and the reviewed Docker bridge/TLS reverse-proxy recipe. No VPS or paid provider is authorized. Confirm the host is awake, patched, backed up, Tailscale-connected, proxy TLS validates, public/LAN paths are blocked, bridge readiness is healthy, and the Android device reaches the exact private HTTPS origin.
2. Compile/install the EA through the genuine MetaEditor/MT5 installation for the selected broker. Pair the one worker and one isolated terminal. Do not treat a successful build, process, pairing code, queued worker job, or `/health/ready` alone as a verified broker session.
3. **Approval gate:** enter the selected broker's DEMO login only through the app/MT5 terminal or approved host secret mechanism after the owner authorizes that connection step. Confirm broker brand/company, redacted login, exact server, account mode **DEMO**, currency, and authenticated fresh terminal heartbeat all match. If storage availability is memory-only, disclose that and do not claim durable credential storage.
4. Obtain a fresh quote directly from the verified terminal for the chosen symbol. Record symbol mapping, bid/ask, terminal/source timestamp and age, spread, and specification (digits, tick size/value, minimum volume, volume step, stop-level). Mark the quote unavailable if stale or unsupported.

### B. One DEMO order with SL/TP

5. Review the app's proposed side, volume, entry, stop-loss, take-profit, risk and reward/risk. Require RiskEngine approval at no more than **1%** risk and at least **2.0R** reward/risk, plus broker-valid minimum/step and stop-distance checks. Do not tighten stops to force eligibility. If any check is missing, no order is executable.
6. **Approval gate:** obtain explicit owner approval for one specific DEMO order showing symbol, side, volume, SL, TP, and maximum risk. Submit once only after approval.
7. Confirm broker-side order/deal/position identifiers and the actual filled volume. Independently verify the broker shows both SL and TP attached at the requested levels (or a documented broker-normalized level accepted by the owner). A bridge 202/queued response or EA acknowledgement alone is not a fill.
8. **Approval gate:** get separate approval before a manual close test. Verify broker-confirmed closure/deal and history, and reconcile the app's durable trade ledger to the MT5 account history. Do not infer close from a UI command or terminal state alone.

### C. STOP and restart/network recovery

9. With the position already closed, press **STOP** and verify it blocks new entries. Current bridge documentation states STOP blocks future entries but does not close existing positions; this is why closure is checked first. Do not create a second order merely to test STOP.
10. Restart the Android app and worker/bridge during a no-position state, then reconnect. Confirm account identity/mode/currency and terminal heartbeat re-verify, the fresh quote is reacquired, historical orders/deals reconcile, STOP remains stopped, and no duplicate or stale command is replayed.
11. Drop and restore network while no test position is open. Confirm the UI reports offline/stale rather than ready; after recovery, revalidate broker session and reconcile history before enabling new actions. If an interruption occurs after an order request, treat the result as ambiguous until the broker's authoritative order/history state resolves it.

**Pass only if:** verified account identity/mode/currency, fresh quote, one approved DEMO order with broker-attached SL/TP, confirmed fill, close, history reconciliation, STOP, restart, and network recovery have redacted evidence. Otherwise keep the managed bot disabled.

## Evidence record (one per acceptance attempt)

| Field | Record |
| --- | --- |
| Broker / route | Deriv Options/Multipliers or selected MT5 brand |
| Account | DEMO; redacted account ID; currency; exact MT5 server if applicable |
| Build | App version, source commit, CI run; device/Android version |
| Quote | Symbol/contract, source, timestamp, age, spread/specification as applicable |
| Order | Owner approval time; side, size/stake, SL/TP; broker contract/order/deal IDs |
| Lifecycle | Buy/fill confirmation; portfolio match; close/expiry; history match |
| Safety/recovery | STOP result; restart result; network-drop result; no duplicate order |
| Outcome | PASS / BLOCKED / FAILED / UNRESOLVED, with reason and next safe step |

Do not put credentials, API tokens, private worker tokens, or unredacted personal account data in this record. No acceptance evidence has been generated by this documentation change.
