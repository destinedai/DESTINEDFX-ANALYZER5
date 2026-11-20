# Installing a debug APK from GitHub Actions

The workflow produces a debug APK, not a store-ready release. The artifact is named `DestinedFX-debug-apk` and contains `app-debug.apk`. Confirm the selected run's branch and `head_sha` match the source revision you intend to install.

## Download and verify

1. Open the [GitHub Actions workflow runs](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/workflows/build-apk.yml).
2. Select a completed run for the intended branch and commit. Check its exact `head_sha` and APK job result.
3. Download `DestinedFX-debug-apk` from the run's Artifacts section and extract `app-debug.apk`.
4. Compute SHA-256 locally, for example `sha256sum app-debug.apk`, and compare with evidence recorded for that artifact.
5. Check the run summary for APK signature and certificate details. This is debug signing, not production signing.

The workflow uses the protected `DESTINEDFX_DEBUG_KEYSTORE_B64` when configured. If it is absent, the workflow generates a debug keystore and warns that the APK may not update an earlier installation. Artifact expiration is determined by the actual workflow run metadata.

## Install

Open the APK on the Android phone and follow Android's package-installer prompts. If Android requests permission to install from that source, allow it for the browser or file manager, then return to the installer.

Installation does not prove that a provider is reachable, an account is verified, a broker accepted an order, or the host is operating. DEMO and LIVE execution are governed by account identity, `ModeArmingPolicy`, risk gates and provider-specific readiness. Do not use real orders as an installation test.

## If the artifact is unavailable

Use only a completed run for the exact intended source SHA. Artifacts expire according to the actual run metadata. Do not use an untrusted mirror or an artifact from a failed or unrelated workflow.
