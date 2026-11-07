# Deployment overview

## Android app

The Android APK is built by GitHub Actions as a debug artifact. That workflow does not publish a store release and does not use production release signing. A hosted artifact is available only for a specific successful run and within that run's retention period. See [CI/CD](../development/CI_CD.md).

## Optional legacy bridge

`bridge/` is a FastAPI/SQLite service with account/terminal pairing, worker jobs and execution-capable command routes. It is not required for local market analysis and should not be deployed as a read-only service. `render.yaml` configures a Free, ephemeral, non-durable profile for diagnostic/liveness behavior; the configured service file does not prove an actual deployment. `/health` is not a broker or provider readiness proof.

Owner-operated Docker/TLS, Windows-worker and MT5-terminal instructions exist in `docs/owner-operated-docker-bridge.md`, `docs/owner-operated-windows-bridge.md`, `bridge/README.md`, and `managed-worker/README.md`. They have not been verified as a complete deployment in this audit. The owner must provide secrets, HTTPS, storage and backups as appropriate, a Windows host/terminal, upstream provider configuration, network allowlists, and operational controls. The repository does not promise zero-cost always-on hosting or PC-off execution.

## No production deployment evidence

No live service URL, deployment account, TLS endpoint, durable database, worker, terminal, or broker was inspected. Do not publish instructions as proof of a deployable production path. Keep the legacy execution infrastructure disabled/unpublished for the intended analysis product until removed or isolated with complete tests.