# Deployment overview

## Android

GitHub Actions builds a debug APK. This is not a store release or production signing process. Owner-controlled credentials, provider consent and exact account identity are required for account workflows. The app does not provision external accounts or deploy a backend.

## Optional MT5 host and bridge

The Bridge is a FastAPI/SQLite service with pairing, worker/terminal coordination and transaction-related command routes. The current production-target deployment is the persistent Ubuntu x86-64 systemd runtime in scripts/linux-host/: it runs the Bridge and Management API alongside a root-attested per-pairing Linux slot worker, isolated Wine prefixes and genuine MT5 terminals. The same linux-slot-worker.py is also reused by the separate ephemeral Codespaces development/test launcher, which remains DEMO-only. The Windows-specific owner-worker package is in managed-worker/windows/ and is a separate, limited alternative; separate Windows /portable directories do not prove per-user OS isolation, so Windows LIVE remains blocked.

The Linux installer, host agent and slot-worker paths are implemented and CI-tested, but no genuine MT5 terminal, owner host or broker account has been accepted on that runtime. A successful source build or test does not establish real broker connectivity.

The Bridge is not required for local market analysis. An HTTP health response does not prove a worker, EA, terminal, provider or broker is ready. Execution requests require authenticated transport, exact account identity and mode, fresh heartbeat, policy authorization, risk checks and broker acknowledgement/reconciliation.

## Hosting limits and owner steps

The root `render.yaml` configures an ephemeral profile. It is not durable or suitable for reliable always-on terminal operation. Owner steps include selecting and securing the host, protecting configuration, exact broker/server setup, network restrictions, backups and monitoring. No credentials belong in source or CI.

See [known limitations](KNOWN_LIMITATIONS.md), [security overview](../security/SECURITY_OVERVIEW.md), and the host setup guides. No external deployment or broker connection is claimed here.
