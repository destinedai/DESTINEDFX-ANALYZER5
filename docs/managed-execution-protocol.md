# DestinedFX managed MT5 execution protocol (v1)

This protocol extends the existing HTTPS relay so a DestinedFX-operated Windows worker can provision and supervise an isolated MT5 terminal for an app user. The Android client must never connect to a broker with a generic TCP/WebSocket login or expose worker credentials. The worker runs the real MT5 terminal and EA; the relay is not a trading engine.

## Credentials and pairing

The app creates/retains its opaque application bearer through `POST /v1/pairings`. The user then submits `POST /v1/app/managed-account` with `{ "login":"123456", "server":"Broker-MT5", "password":"…", "access_type":"MASTER|INVESTOR", "consent":true }` under that app bearer. Investor access is read-only and MUST never authorize START. No broker credentials are returned from any subsequent API response.

The service requires `CREDENTIALS_FERNET_KEY` and `WORKER_TOKEN` at startup for managed-account endpoints; there is no plaintext fallback. It encrypts broker credentials at rest and exposes plaintext only in the authenticated, encrypted worker-claim response. Operator secrets are server-side only. Requests and logs must never include passwords or tokens.

## Managed provisioning

`POST /v1/worker/jobs/claim` requires the operator-only worker bearer and `{ "worker_id":"…" }`. It atomically leases a queued job and returns `{ "job_id":"…", "kind":"PROVISION", "worker_id":"…", "pairing_id":"…", "pairing_code":"…", "bridge_url":"https://…", "account":{"login":"…","server":"…","password":"…","access_type":"MASTER|INVESTOR"} }`, or `{ "job":null }` when the queue is empty. Revocation jobs use `kind=REVOKE` and contain no credentials. A job is claimed at most once per lease. The worker provisions a user-isolated MT5 data directory, starts the terminal with its account settings and an auto-start EA profile, and deletes transient plaintext configuration files after launch where possible. Persisted terminal credentials still require encrypted host storage and restrictive per-user ACLs.

`PATCH /v1/worker/jobs/{job_id}` accepts `{ "worker_id":"…", "status":"PROVISIONING|AUTHENTICATING|FAILED|REVOKED", "error_code":"…", "error_message":"…" }`; worker IDs are bound to leases. `GET /v1/worker/jobs/{job_id}/status?worker_id=…` is operator-authenticated and lets the worker wait for completion. READY is set only after a fresh, account-matched EA heartbeat reports READY and terminal-connected; MASTER also needs broker trade permission. INVESTOR can be marked connected for read-only visibility, but remains ineligible for START. A process launch alone never means READY. The app state response includes a `managed_execution` object with provisioning state, account access type, terminal freshness, and sanitized error details.

## Trading commands

Existing app command IDs remain UUID idempotency keys. The service rejects START unless managed execution is READY, the EA heartbeat is fresh, and the actual terminal reports trade permission. It also rejects START for INVESTOR access. STOP is permitted to an authorized app even if the heartbeat is stale; it is queued for the EA, while the worker supervisor also stops the process from accepting new work when terminal authentication or bridge authorization is lost. Existing positions are not silently closed.

## Worker deployment requirements

The worker is Windows-only and requires a licensed/authorized MT5 terminal installation, the compiled `DestinedFXTraderEA.ex5`, outbound HTTPS to the relay and broker server access. Each account requires separate terminal data, logs, configuration and process supervision, protected with OS ACLs and encrypted storage. An operator must size host capacity, provision backups/monitoring, secure the TLS endpoint and set the credential-encryption and worker-authentication keys. The repo does not contain those secrets or a production endpoint.
