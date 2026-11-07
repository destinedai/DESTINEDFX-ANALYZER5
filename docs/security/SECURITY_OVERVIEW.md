# Security overview (source review)

This is a scoped source-level review, not a penetration test, threat model approval, secret scan attestation, or privacy/legal assessment.

## Android

- Main manifest requests Internet/network state, notifications, biometrics and legacy external-storage access; inspect each permission before removing or retaining it. Current manifest does not declare the retained foreground bot/session services or boot receiver.
- Cleartext traffic is disabled and system trust anchors are used in the network security configuration.
- App build uses `.env` and `.env.example` through the Secrets Gradle Plugin. The root example is public placeholder configuration; private keys/tokens must not be committed or embedded in APK resources/BuildConfig.
- `MT5_MANAGED_SERVICE_URL` is non-secret endpoint configuration. Release builds validate its HTTPS-origin shape and reject obvious local/placeholder values; this does not establish DNS safety, certificate endpoint availability or a deployment.
- Provider credentials require an appropriate protected runtime path. The FMP adapter places its key in a query parameter, which may be recorded by URL-level logs/telemetry; prefer a protected proxy or ensure request URLs are redacted.
- Release signing configuration expects environment-provided keystore/password values; never commit the keystore. CI builds a debug APK and verifies that APK's signature, not production signing identity.
- Room stores multiple entity classes including user/trade/Deriv execution records; retention, deletion, encryption and migration policy need a separate complete audit. App backup is disabled in the manifest.

## Legacy bridge and execution components

`bridge/` implements credential/pairing and command-management routes; `managed-worker/` and `mt5-ea/` are separately runnable components. Their presence is incompatible with a blanket claim that the repository is read-only. The bridge requires HTTPS, protected secrets, durable storage for stateful operation and an operator-controlled worker/terminal. Render's Free configuration is ephemeral and not execution-ready. No remote deployment was inspected.

## CI security properties and gaps

The workflow grants `contents: read`, builds a debug APK, and uses the upload-artifact action. It does not run a secret scanner, dependency vulnerability audit, instrumentation suite, Python/backend/worker/EA test suite, or production release-signing attestation. Its static verifier checks a narrow set of expected text/source boundaries and is not whole-repository reachability analysis.

## Retention and incident handling

The repository documents local Room and bridge SQLite persistence, but this review did not establish user-accessible deletion, retention windows for all records, backup restoration, incident response, or legal retention requirements. Document these before claiming comprehensive data governance.

## Required release follow-ups

1. Remove or technically isolate transaction-capable source and prevent packaging/deployment of the worker/EA/bridge execution features.
2. Audit secrets handling, log redaction, request URLs, persistence, backup/restore and data deletion.
3. Add reproducible dependency/secret checks and boundary tests spanning all call sites and build variants.
4. Verify release build/signing independently; identify the release key owner and artifact provenance process.
5. Perform independent security review before any external deployment.