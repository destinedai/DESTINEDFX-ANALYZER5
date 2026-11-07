# Security overview

This is a scoped source review, not a penetration test, approved threat model, privacy/legal review, or evidence of a secure deployment.

## Android

- The manifest registers foreground services and a boot receiver for bot/account monitoring, plus a cTrader callback route. Exported components and callback inputs require strict validation. Never treat deep-link input as trusted identity or authorization.
- Cleartext traffic is disabled and the network security configuration uses system trust anchors.
- `.env.example` contains public placeholders. Never commit provider keys, broker credentials, OAuth secrets, access tokens, private keys or signing keys, or embed them in APK resources or `BuildConfig`.
- `MT5_MANAGED_SERVICE_URL` is endpoint configuration. HTTPS-origin validation does not prove DNS safety, service ownership or availability.
- FMP currently supplies a key in a query parameter, which may appear in URL-level telemetry. Protect or redact request URLs.
- AndroidX encrypted preferences are used when available; callers must fail closed when secure storage is unavailable. Do not report credentials as securely persisted otherwise.
- Room contains user, trade and Deriv execution data. Retention, deletion, encryption, migration, recovery and access control require continued review. Backup is disabled in the manifest.
- Debug APK signing is not production signing. Never commit a keystore.

## Execution authorization

`ModeArmingPolicy` binds DEMO acceptance and LIVE leases to account identity and credential generation. LIVE authorization is process-local and should expire or disarm on restart, identity change, credential rotation, failed checks or emergency stop. Generic `BrokerGateway` order submission defaults to denied unless an implementation returns a policy-derived decision. Deriv and managed MT5 paths have provider-specific gates. These code controls require further call-graph and runtime verification; they do not imply a verified or safe broker deployment.

Tests and development must use fake transports. Do not submit real orders while building or testing.

## Bridge and host

`bridge/`, `managed-worker/`, and `mt5-ea/` contain separately runnable account and execution components. They require operator-managed secrets, authenticated TLS, state backup, restricted networking, system hardening, log protection, and tested recovery. The root ephemeral Render profile is not an always-on host. No deployment was inspected.

## CI security controls and gaps

The restored GitHub Actions workflow includes a Gitleaks scan of the checked-out tree, Python/worker/EA tests, a Windows MetaEditor compile, Android JVM tests and APK build, emulator instrumentation, and a worker-kit artifact job. A workflow definition does not prove the run or each job succeeded. Secret scanning is not proof that the full Git history is clean, and no dependency vulnerability or independent penetration assessment is established by the workflow.

## Follow-up

1. Test cTrader callback scheme, host, path, state and one-time code validation. Reject unexpected intent data.
2. Audit WebView file access, navigation allow-lists and JavaScript bridges for OAuth and chart views.
3. Review logs, crash reports, URL redaction, local storage and account-data retention.
4. Validate network security, release signing, secret scanning and artifact provenance on an exact commit.
5. Complete independent security review before external deployment.
