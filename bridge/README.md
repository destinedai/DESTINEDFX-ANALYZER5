# DestinedFX MT5 HTTPS bridge

This service is a command/telemetry relay. It never connects to a broker, invents market/broker data, or places orders. The EA must derive identity and all telemetry from the running MT5 terminal. See [`../docs/mt5-bridge-protocol.md`](../docs/mt5-bridge-protocol.md) for the authoritative v1 contract.

## Local development

Python 3.11+ is required. From this directory:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
ALLOW_LOOPBACK_HTTP=true BRIDGE_DB_PATH=./bridge.sqlite3 uvicorn app:app --host 127.0.0.1 --port 8000
pytest -q
```

The HTTP exception is accepted only for IPv4/IPv6 loopback clients. Do not set it on a public deployment. `GET /health` is a liveness check; `GET /health/ready` verifies database access and the mandatory managed-execution configuration and returns HTTP 503 until the service is safe to use.

Automated execution also requires `NEWS_PROVIDER_URL` to be configured with an authorized HTTPS economic-calendar provider. The provider response must contain a timestamped `status`, `source`, coverage interval, affected currencies, and event window when applicable. `NEWS_PROVIDER_TOKEN` is read only from the service environment. Missing, stale, incomplete, malformed, non-JSON, or unavailable provider data returns `UNKNOWN` and blocks automated entries; it is never treated as a clear calendar. New-order verdict freshness is capped at 120 seconds through `NEWS_MAX_AGE_SECONDS`; the conservative blackout defaults are `NEWS_BLACKOUT_BEFORE_SECONDS=1800` and `NEWS_BLACKOUT_AFTER_SECONDS=1800`.

## Deployment and TLS

Copy `.env.example` to `.env`, set persistent `BRIDGE_DB_PATH=/data/bridge.sqlite3`, keep `ALLOW_LOOPBACK_HTTP=false`, and change limits only with care. `docker compose up -d --build` runs an unprivileged container and persists its WAL/database in the named volume. Back up the volume consistently; SQLite WAL files must be included in live backups or use SQLite's online backup API.

Put a maintained TLS reverse proxy (for example Caddy, nginx, or a managed HTTPS load balancer) in front. Use a valid public certificate, redirect HTTP to HTTPS at the proxy, permit only the intended hostname, set a request body limit at or below 64 KiB, and forward requests only to the private bridge port. Do not expose port 8000 directly. Configure proxy trust so spoofed forwarded headers cannot be supplied by arbitrary clients; the sample container trusts forwarded headers because it is intended to be reachable only from the proxy network. Health and API endpoints must also be served exclusively through HTTPS.

The bridge rejects requests it sees as HTTP unless `ALLOW_LOOPBACK_HTTP=true` and the connecting peer is loopback. At a proxy, TLS is terminated before the app; forward `X-Forwarded-Proto: https` and ensure the app port is not publicly reachable. For deployments where proxy headers cannot be securely configured, enforce HTTPS at the proxy/firewall boundary and use a private proxy-to-app network.

Use the exact public origin, e.g. `https://bridge.your-real-domain.example`, as the Android app's bridge base URL (no placeholder host; no trailing path required). Add that same exact origin to MT5 **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**. The EA makes outbound HTTPS only. The app setup screen must use the operator's deployed URL, not a sample domain. Configure the same URL in any build-time/app setting required by the Android project; this bridge repository does not change Android settings.

## Safe launch checklist (demo only)

1. Deploy and verify a valid TLS certificate, HTTPS redirect, persistent storage, backups, rate limits, and firewall isolation. Call `GET /health` over HTTPS.
2. Configure the real HTTPS bridge URL in the Android setup and MT5 WebRequest allowlist exactly as above.
3. Start with a **broker demo account only** and a dedicated MT5 terminal. Verify the EA reports the live account login/server/company/currency from MT5; never type credentials into the bridge or send passwords/API keys.
4. Pair once, verify terminal status goes OFFLINE when heartbeats stop, and exercise START/STOP/PAUSE/RESUME/EMERGENCY_STOP in demo. A 202 response only means queued; verify fresh terminal telemetry before reporting execution status.
5. Confirm risk guards and broker-side SL/TP behavior in the EA. STOP/EMERGENCY_STOP block new entries and do not close positions. Do not proceed to a live account without independent testing and authorization.
6. Use authenticated `GET /v1/app/trades?cursor=...&limit=...` to synchronize the durable account-scoped ledger. Repeated heartbeats are idempotent and DEMO/LIVE records remain separated by pairing/profile.

## Security behavior

Pairing codes expire after ten minutes and are single-use; app and terminal credentials are generated with OS cryptographic randomness and only SHA-256 digests are stored. Bearer comparisons use constant-time digest comparison. App revocation invalidates both token paths and clears telemetry/queued commands. SQLite transactions and WAL protect concurrent changes. Pairing creation is rate-limited per client IP. Requests have body, field count, field name, nesting, list, string, and finite-number checks. Secrets are never logged by the application. Deploy behind a trusted TLS proxy and avoid application access logs that include authorization headers or request bodies.

Heartbeat timestamps must be timezone-aware UTC within the freshness window; verified identity must match the terminal claim. App status reports OFFLINE for absent/stale heartbeat. A heartbeat never promotes a worker provisioning job to READY: only the worker holding the current authenticated lease can obtain that transition through the job-status endpoint. Commands are keyed by request UUID; repeats return the original command. The terminal owns durable command-ID deduplication and should acknowledge only after applying/rejecting. This service does not claim command application until acknowledgement/telemetry.
