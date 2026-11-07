# Installing a debug APK from GitHub Actions

The repository workflow produces a **debug** APK, not a store-ready release. It uploads an artifact named `DestinedFX-read-only-debug-apk` containing `app-debug.apk` and retains it for 14 days from the run. Verify that the run's branch and `head_sha` match the source revision you intend to test.

## Download and verify

1. Open the [GitHub Actions workflow runs](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/workflows/build-apk.yml).
2. Select a completed successful run for the intended branch and commit; check that its `head_sha` matches.
3. Download `DestinedFX-read-only-debug-apk` from that run's Artifacts section and extract `app-debug.apk`.
4. Compute a SHA-256 checksum for the downloaded APK on a trusted computer, for example with `sha256sum app-debug.apk`.

The workflow prints a SHA-256 value in the runner log but does not upload a separate checksum artifact. Do not assume a runner log checksum is bound to your download unless you compare it with a locally computed checksum from the exact artifact.

## Install

Open the APK on the Android phone and follow Android's package-installer prompts. If Android asks for permission to install from that source, allow it for the browser/file manager being used, then return to the installer. The artifact is debug-signed, not production-signed.

Do not follow outdated instructions to use a Bot/Deriv workstation for account verification. The intended product boundary is analysis-only, but the repository is not yet verified as a fully read-only system; legacy execution-capable code remains. APK installation does not verify provider connectivity, broker acceptance, or production readiness.

## If the artifact is unavailable

Actions artifacts expire after the configured retention period. Use only a newer successful run for the intended branch and exact source SHA. Do not use an untrusted mirror or treat a failed/unrelated workflow run as an artifact source.
