# DestinedFX MT5 terminal bridge protocol (v1)

This protocol links the Android control app to an Expert Advisor running inside a logged-in MT5 desktop terminal/VPS. The bridge relays authenticated commands and telemetry; it never places broker orders itself. The EA obtains account identity, market data, trades, and trade results directly from its actual MT5 terminal. No MetaAPI token is used.

All public deployments MUST use HTTPS. A deployment must not expose HTTP except loopback-only development. Configure the bridge base URL in the Android setup screen and add the same exact base URL to MT5's **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**. Do not use a placeholder host. The EA makes outbound HTTPS requests only.

## Pairing and credentials

`POST /v1/pairings` creates a single-use, short-lived pairing code and an Android app bearer token. Response: `{ "pairing_id": "…", "pairing_code": "…", "app_token": "…", "expires_at": "…" }`. The code expires after ten minutes and is consumed by one terminal claim. The app stores its opaque app token only in Android encrypted preferences.

`POST /v1/terminal/claim` accepts `{ "pairing_code": "…", "terminal_instance_id": "…", "account": {"login":"…", "server":"…", "company":"…","currency":"…"} }`. The EA must derive account fields from the running terminal, never trust user-entered account identity. Success returns `{ "terminal_id": "…", "terminal_token": "…", "poll_seconds": 2 }`. The EA retains the terminal token in its terminal-local persistent state and sends it as a bearer token. Tokens are random, unguessable, stored hashed at the bridge, never logged, and revocable by the app.

`DELETE /v1/app/pairing` revokes the app and terminal tokens for the paired terminal. It requires the app bearer token.

## App API

`GET /v1/app/state` requires the app bearer token. Response includes terminal online/heartbeat age, EA state, the terminal's verified account identity and metrics, positions, recent closed deals, last signal, and last error. A missing or stale heartbeat is **OFFLINE**, never **RUNNING**.

The app-state response shape is `{ "pairing_status":"PAIRING|PAIRED", "terminal_online":true, "heartbeat_age_seconds":1, "heartbeat_stale_seconds":15, "ea_state":"READY", "terminal_connected":true, "trade_allowed":true, "account":{ "login":"…", "server":"…", "company":"…", "currency":"…", "balance":0, "equity":0, "margin":0, "free_margin":0, "margin_level":0, "leverage":0 }, "configuration":{…}, "last_signal":null, "last_error":"", "open_positions":[], "closed_deals":[], "last_heartbeat_at":"…", "last_command":null }`. The online flag and client freshness check use the server-configured stale threshold. Account is null until a valid terminal heartbeat. `last_command` is null or `{ "id":1, "request_id":"…", "action":"START", "status":"QUEUED|ACKNOWLEDGED", "outcome":"APPLIED|REJECTED", "state":"READY|RUNNING|PAUSED|STOPPED|ERROR", "message":"…" }`; outcome/state/message are omitted until the EA acknowledges. Position records contain `ticket`, `position_id`, `symbol`, `direction`, `volume`, `open_price`, `stop_loss`, `take_profit`, `floating_profit`, `commission` (number or null when unavailable), `swap`. Closed-deal records contain `ticket`, `position_id`, `symbol`, `closed_at`, `profit`, `commission`, `swap`.

The **terminal heartbeat request** is not an app-state response and must contain only `{ "state":"READY|RUNNING|PAUSED|STOPPED|ERROR", "timestamp":"ISO-8601 UTC", "terminal_connected":true, "trade_allowed":true, "account":{…}, "configuration":{…}, "last_signal":null, "last_error":"", "open_positions":[], "closed_deals":[] }`. The server derives `pairing_status`, freshness, and online status; do not send app-only fields such as `ea_state`, `terminal_online`, or `heartbeat_age_seconds` in the heartbeat request.

`POST /v1/app/commands` requires the app bearer token and accepts `{ "request_id":"<UUID>", "action":"START|STOP|PAUSE|RESUME|EMERGENCY_STOP", "config": {"strategy":"ict-silver-bullet","symbols":["EURUSD"],"timeframes":["M5"],"risk_percent":0.25,"max_daily_loss_percent":2.0,"max_concurrent_positions":2} }`. `request_id` is an idempotency key. Repeating it returns the original command. HTTP 202 means **queued only**, not applied or running. The app reports success only after fresh EA telemetry confirms the requested state. STOP/EMERGENCY_STOP prevent new entries; they do not close open positions. Existing SL/TP remain in place. Closing positions is a separate explicit operation and is not part of v1.

## EA API

`GET /v1/terminal/commands?cursor=<integer>` requires the terminal bearer token. It returns `{ "cursor": n, "commands": [{"id":"…","request_id":"…","action":"…","config":{…}}] }`. Commands are retained and replayed until acknowledged. The EA must persist processed command IDs and apply each at most once.

`POST /v1/terminal/commands/{id}/ack` accepts `{ "outcome":"APPLIED|REJECTED", "state":"READY|RUNNING|PAUSED|STOPPED|ERROR", "message":"…" }`. An acknowledgement of START alone does not establish RUNNING; a subsequent heartbeat must show the bot running and checks passing.

`POST /v1/terminal/heartbeat` requires the terminal bearer token and reports current state, UTC timestamp, terminal connectivity/trade permissions, actual `ACCOUNT_LOGIN` / server / company / currency, balance/equity/margin/free-margin/leverage, selected configuration, last signal/reason, open positions, recent realized deals including profit/commission/swap, and errors. The bridge rejects invalid, oversized, non-finite, mismatched-account, or stale data. The EA must not send passwords, broker API keys, or account credentials.

## Safety contract

EA state is authoritative for execution. START is allowed only after terminal/broker connection, trade permissions, fresh market data, valid account snapshot and risk configuration are confirmed. Max per-trade risk is 1% of `min(balance,equity)`; default is 0.25%. Minimum reward/risk is 2.0. Enforce broker tick size/value, volume step/min/max, stop distance, margin, duplicate-signal, daily-loss and concurrent-position limits in MQL5. STOP/PAUSE/EMERGENCY_STOP block new entries; existing positions retain their broker-side SL/TP. No retry may create a duplicate order.

The bridge is a relay/status service, not an MT5 terminal host. It requires separate deployment with a persistent database and HTTPS. The paired MT5 desktop terminal/VPS must remain online for the EA to trade. An Android phone alone cannot host an MQL5 EA.
