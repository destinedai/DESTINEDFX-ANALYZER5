# CI/CD and APK artifact

## Workflow

`.github/workflows/build-apk.yml` is named **Read-only Android build** and runs for pushes to `free/phone-only-v1`, pull requests targeting `main`, and manual dispatch. The job checks out the triggering source, sets up Temurin Java 21 and Gradle, copies `.env.example` to `.env`, runs `python3 scripts/verify_read_only_build.py`, runs `./gradlew testDebugUnitTest --stacktrace`, assembles with `./gradlew assembleDebug --stacktrace`, verifies the debug APK exists and its APK signature is structurally valid, then uploads the APK and JVM test reports with 14-day retention.

The APK path is `app/build/outputs/apk/debug/app-debug.apk`; artifact name is `DestinedFX-read-only-debug-apk`. The test report artifact is `Android-jvm-test-reports-${{ github.run_id }}`. The workflow writes `apk-sha256.txt` to the runner log, but does not upload that checksum as an artifact. A successful debug signature check is not production release signing verification.

## What CI does not verify

It does not run device instrumentation tests, Python bridge/worker tests, EA compilation, lint, dependency vulnerability scanning, secret scanning, external provider probes, broker account acceptance, production release signing, deployment checks, or end-to-end read-only proof across the entire repository. Its static boundary script checks only selected files/strings and must not be treated as whole-project enforcement.

## Evidence protocol

For a release/revision report, record:

1. Workflow run ID and URL.
2. Run conclusion and each meaningful job/step result.
3. Exact `head_sha` and branch association; they must match the claimed source.
4. JVM test totals from test reports, not an inferred number.
5. APK artifact name, artifact ID, retention/expiration and download availability from that run.
6. SHA-256 computed from the actual artifact downloaded/available for inspection; do not present runner log output as a verified download checksum.
7. APK signing result and signer certificate fingerprint, with debug-vs-release distinction.

A workflow source file does not prove any run happened. A completed prior run for a different commit does not certify later changes. Never say an APK was delivered unless the artifact exists and was verified.

## Repair and extension opportunities

Consider adding instrumentation testing on an emulator, Python/backend suites, artifact checksum upload, release-provenance metadata, dependency/secret scanning and a broader execution-surface boundary test. First ensure those additions are stable and do not falsely certify retained execution code.