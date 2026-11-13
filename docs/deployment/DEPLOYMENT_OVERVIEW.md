# Deployment overview

## Android

GitHub Actions builds a debug APK. This is not a store release or production signing process. Owner-controlled credentials, provider consent and exact account identity are required for account workflows. The app does not provision external accounts or deploy a backend.

## Optional MT5 host and bridge

`bridge/` is a FastAPI/SQLite service with pairing, worker/terminal coordination and transaction-related command routes. `managed-worker/` and `mt5-ea/` run separately on an owner-controlled Windows MT5 host. Linux hosting files are a separate operational option and have not been verified on a genuine terminal.

The bridge is not required for local market analysis. An HTTP health response does not prove a worker, EA, terminal, provider or broker is ready. Execution requests require authenticated transport, exact account identity and mode, fresh heartbeat, policy authorization, risk checks and broker acknowledgement/reconciliation.

## Hosting limits and owner steps

The root `render.yaml` configures an ephemeral profile. It is not durable or suitable for reliable always-on terminal operation. Owner steps include selecting and securing the host, protecting configuration, exact broker/server setup, network restrictions, backups and monitoring. No credentials belong in source or CI.

See [known limitations](KNOWN_LIMITATIONS.md), [security overview](../security/SECURITY_OVERVIEW.md), and the host setup guides. No external deployment or broker connection is claimed here.
