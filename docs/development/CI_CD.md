# CI/CD and build artifacts

## Workflow and jobs

`.github/workflows/build-apk.yml` is named **Build APK**. It runs on pushes to branches, pull requests targeting `main`, and manual dispatch. It defines these jobs:

- **Python tests:** Deriv demo smoke harness, bridge, managed-worker, MT5 EA source-contract and safety-math tests, plus Docker configuration/image checks.
- **Secret scan:** Gitleaks scan of the checked-out tree with redacted findings.
- **MT5 EA compile:** Windows/MetaEditor compilation and compiler evidence artifact.
- **Android JVM tests and APK build:** Java 21, Python 3.13, Android build environment, JVM tests, debug APK assembly, metadata/signature/checksum verification, JVM reports and APK upload.
- **Android emulator instrumentation:** clean API 34 emulator, runtime tests, nonzero test-count check and diagnostic reports.
- **Windows worker kit:** packages the managed-worker/bridge/EA owner kit.

The emulator job depends on the Android build job. Other job dependencies and all step details are defined in the workflow file. The workflow restores a signing step that uses the protected `DESTINEDFX_DEBUG_KEYSTORE_B64` secret when configured and falls back to a generated debug keystore otherwise. It does not produce a production release signature.

## Artifact names

- Android debug APK: `DestinedFX-debug-apk`, file `app-debug.apk`.
- Android JVM test reports: `Android-jvm-test-reports-${{ github.run_id }}`.
- Android emulator reports: `Android-emulator-test-reports`.
- Compiled MT5 EA and evidence: `DestinedFX-MT5-EA-ex5`.
- Windows worker kit: `DestinedFX-Windows-Worker-Kit`.

The workflow does not set a custom artifact retention period; use the actual run's artifact metadata to determine expiry. The Android job computes a SHA-256 and includes it in the run summary. A checksum computed from a downloaded APK is the stronger delivered-artifact record.

## Limits of CI evidence

CI does not prove real broker authentication, external provider entitlements, DEMO fill acceptance, LIVE authorization, terminal uptime, bridge deployment, or successful trades. A MetaEditor compile proves compilation only. A green job is evidence only for the exact source SHA and job that completed.

## Evidence protocol

For each release or task report, record the run ID and URL, branch and exact `head_sha`, every requested job's result, JVM/emulator test totals from reports, APK artifact name/ID/expiry, signer and debug-vs-release status, plus SHA-256 calculated from the downloaded APK. A workflow file alone is not evidence a run succeeded.
