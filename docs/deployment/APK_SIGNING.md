# Android APK build and signing integrity

## Build classes

| Artifact | Purpose | Signing and release status |
|---|---|---|
| DestinedFX-DEBUG-APK-FOR-DEMO-TEST-ONLY | Generic CI Android test and controlled DEMO validation | Debug certificate; not a production release |
| DestinedFX-DEBUG-APK-FOR-DEMO-TEST-ONLY-Codespaces-bootstrap | Debug build with locked Codespaces host discovery | Debug certificate; not a production release |
| DestinedFX-DEBUG-APK-FOR-DEMO-TEST-ONLY-independent | Independent standard debug build | Debug certificate; not a production release |
| Host-generated endpoint-locked APK | Binds a reviewed persistent Bridge and Management API manifest | Currently uses Android debug signing; not production release signing |
| Android release build type | Separate upload/release signing configuration in app/build.gradle.kts | Requires KEYSTORE_PATH, STORE_PASSWORD and KEY_PASSWORD; publishing remains HOLD |

The shared APK verification tool records the exact checked-out Git commit SHA, package name, version name, version code, minimum SDK, target SDK, supported ABIs, APK size, SHA-256, signing certificate SHA-256 and verified signature scheme. CI invokes it on each APK artifact, and scripts/linux-host/build-self-hosted-apk.sh invokes the same tool after checking that the Git working tree is clean and the owner-generated endpoint strings are embedded in the final APK. Both outputs include apk-metadata.json and a metadata checksum sidecar. The metadata contains no signing private key or passwords. Verification fails unless the checked-out HEAD matches the recorded source SHA and the APK certificate matches the fingerprint from the debug keystore used by Gradle.

The verifier is implemented in scripts/verify_apk_artifact.py. Parser and validation contracts are exercised by scripts/test_apk_integrity.py. These tests use labelled synthetic metadata; the workflow invokes the same verifier on the actual APK produced by Gradle.

## Stable debug signing secret

CI reads the optional repository Actions secret DESTINEDFX_DEBUG_KEYSTORE_B64. It must contain Base64-encoded JKS or PKCS12 keystore bytes with the Android debug signing identity expected by the debug build:

- Alias: androiddebugkey
- Store password: android
- Key password: android
- One valid private-key entry and its certificate

The CI job decodes the secret only into its temporary runner workspace, applies mode 0600, validates that the alias can be opened, derives the certificate fingerprint without printing the keystore, and checks that the APK was signed by that exact certificate. The encoded secret, keystore bytes, private key and passwords must never be printed or committed.

### Owner-side provisioning

Perform this on a trusted owner-controlled workstation. Do not upload the .jks file to the repository.

    umask 077
    mkdir -p "$HOME/.config/destinedfx"
    chmod 700 "$HOME/.config/destinedfx"

    keytool -genkeypair       -keystore "$HOME/.config/destinedfx/debug-signing.jks"       -storetype JKS       -storepass android       -keypass android       -alias androiddebugkey       -keyalg RSA -keysize 2048 -validity 10000       -dname "CN=DestinedFX Debug,O=DestinedFX,C=NG"

    chmod 600 "$HOME/.config/destinedfx/debug-signing.jks"

    base64 -w0 "$HOME/.config/destinedfx/debug-signing.jks"       | gh secret set DESTINEDFX_DEBUG_KEYSTORE_B64

The gh secret set pipeline sends the encoded file directly to GitHub Actions secret storage. Do not redirect its output into the repository, print it, or paste it into a terminal transcript. Verify only that the secret is configured in repository Actions settings; do not read or display its value. Keep an independently protected backup of the keystore and recovery instructions.

The fixed Android debug passwords are the Android debug-signing convention, not a production signing recommendation. Restrict this keystore to non-production debug artifacts. Never reuse it as the upload/release key.

## Missing-secret behavior and upgrade compatibility

When DESTINEDFX_DEBUG_KEYSTORE_B64 is absent, every independent CI job deliberately creates a fresh debug key and emits a visible Fresh-install-only warning. The owner-built endpoint-locked script uses the Android user's local debug keystore (or an explicitly configured DESTINEDFX_DEBUG_KEYSTORE_PATH); it records the actual signer but cannot guarantee continuity when that local keystore is absent, replaced or different from the prior install. The APK is still signed and its certificate fingerprint is recorded and verified. A new ephemeral key is not a stable signing identity: an APK from a later run or separate job may not update an APK signed by a previous key. Android rejects an in-place update when the signing identity changes; uninstalling may remove local application data.

When the stable secret is present, independent jobs use the same certificate identity. This supports Android signature compatibility only when the package ID stays identical, versionCode increases appropriately, and the prior installed app was signed by that same private key. A matching certificate does not, by itself, prove that a specific owner's device can upgrade.

The APK built at checkpoint 45bb613 was signed with certificate SHA-256 70c0fb2cbc6c181772e1699bb049260ea772fcb247772f943eacdaff3c0a1d1c while the secret was absent. That fingerprint is evidence for that artifact only. It is not a durable signing identity and must not be assumed to match future CI builds.

## Production signing remains separate

The release build type uses the separate upload alias and environment variables KEYSTORE_PATH, STORE_PASSWORD and KEY_PASSWORD. The repository owner must provision those through protected GitHub Actions secrets or a trusted release runner, independently verify the release certificate fingerprint and backup/recovery path, and review the production signing workflow separately. Never substitute the debug keystore.

A successful debug APK build, stable debug fingerprint, host endpoint lock, CI suite or MetaEditor compile does not authorize a production release. Keep GitHub Release/prerelease publishing on HOLD until persistent Linux host installation, real two-user isolation, physical phone pairing, authenticated MT5 DEMO telemetry and supervised DEMO order lifecycle acceptance are complete for the same candidate.

