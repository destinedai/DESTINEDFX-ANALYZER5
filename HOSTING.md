# DestinedFX hosting and execution policy

## Production target

The production target remains an owner-operated, persistent Ubuntu Linux host; GitHub Codespaces remains the current supervised DEMO-only environment.

## Current owner workflow — inside GitHub

The immediate owner workflow is **GitHub Codespaces for supervised DEMO testing**. The repository's `.devcontainer` installs the pinned Linux/Wine/MetaTrader prerequisites, starts the existing Codespaces host supervisor when the container opens, and forwards the Bridge and Management API ports automatically. The one-command readiness check is:

```bash
bash deploy/codespaces/start-demo.sh
```

The command starts or reuses the same supervised Bridge, Management API/PostgreSQL, host agent and isolated MT5 worker. It waits for real local and forwarded HTTPS readiness. Ports 8000/8010 are private by default and become public automatically only after the Bridge K3 hardening/authentication tests, Management API/EdDSA readiness and GitHub's exact forwarded-port inventory all pass. There is no manual port-forwarding step.

On the Android app, open **Bot → Codespaces Host**, authorize GitHub once through the one-time Device Flow, and tap **START HOST**. The app starts/reuses the Codespace, discovers the two verified origins and saves the Bridge connection; do not manually copy URLs into APK settings. The owner then chooses a broker DEMO profile and supplies that broker's own DEMO credentials. A verified Bridge origin alone is not broker login or trade acceptance.

LIVE needs persistent host, Codespaces = DEMO ONLY

Do not buy or configure another host as part of the current DEMO workflow. Codespaces remains EPHEMERAL: its state and runtime exist for the lifetime of the Codespace, and GitHub's own idle, lifetime and included-usage limits apply. It is for development and supervised DEMO testing, not unattended or 24/7 operation.

## Execution authorization and evidence

A DEMO account is accepted only when durable database readiness, authenticated broker identity, MASTER permission, fresh terminal/EA heartbeat, current quote, risk/news/session checks, protective-order validation and order reconciliation all pass. A queued Bridge command or HTTP 200 is not a broker fill.

LIVE authorization is separate. It requires a persistent host and all existing account-specific requirements: authenticated user and broker identity, correct LIVE account mode, isolated terminal, fresh attested EA heartbeat, actual balance/equity availability, explicit LIVE confirmation, risk limits, protective-order validation and order reconciliation. Host configuration or CI success alone never unlocks LIVE.

Keep these states separate:

- Android APK built and installed.
- Codespaces host supervisor active and attested.
- Management API/PostgreSQL ready.
- Bridge reachable at the verified forwarded origin.
- Per-pairing MT5 terminal provisioned and running.
- Broker account authenticated and exact broker identity/mode verified.
- EA heartbeat fresh and authenticated.
- DEMO order accepted, filled, reconciled and safely closed.

## Safety and recovery

The Codespaces startup script binds Bridge, Management API and host-agent listeners to loopback inside the container. It does not expose the database, host agent, Wine display or MT5 process ports. Public visibility for only ports 8000/8010 is conditional on the existing fail-closed K3 and forwarded-origin verification path.

The Codespaces supervisor preserves the existing state backup and safe-shutdown checks. STOP does not liquidate or close open positions. Shutdown is blocked whenever broker/order state is active, unresolved, stale or unknown; the app must not claim that STOP automatically closes positions.

A Codespaces CI pass validates repository code and fixtures. It does not by itself prove that the owner's Codespace is running, a real broker has authenticated, a DEMO EA heartbeat is current, or an order has filled. LIVE remains independently locked on EPHEMERAL hosts.
