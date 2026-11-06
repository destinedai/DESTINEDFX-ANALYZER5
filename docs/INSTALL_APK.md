# Install the debug APK on an Android phone

This project does not publish a public release APK. The installable file is the `DestinedFX-debug-apk` artifact attached to a successful GitHub Actions run. Actions artifacts expire, and GitHub sign-in is required. Keep the repository private.

## Before installing

Use an Android phone with internet access. Do not send the Deriv App ID, PAT, OTP, password, or any token to chat, screenshots, issue comments, or logs. Enter credentials only in the app when the phone verification checklist asks for them.

## Download and verify

1. Open the exact successful GitHub Actions run linked from the pull request.
2. Confirm the run is for the commit you intend to test and that the Android JVM/APK build and emulator instrumentation jobs are successful.
3. Download the artifact named `DestinedFX-debug-apk` and unzip it on a computer or Android file manager.
4. In the run summary, open the `Android JVM tests and APK build` job and find the `Debug APK verification` step summary. Copy the SHA-256 value printed there.
5. Calculate the SHA-256 of the downloaded `app-debug.apk` on the device or a trusted computer. On a computer, use:

   ```text
   sha256sum app-debug.apk
   ```

   The calculated value must exactly match the value printed by CI. If it does not match, delete the file and do not install it.

## Install

1. On the phone, open the downloaded APK.
2. If Android asks for permission to install from this source, allow it for the file manager or browser being used, then return to the installer.
3. Install the APK. This is a debug build, not a production release.
4. Open DESTINEDFX and go to the bot workstation and Deriv connection section.
5. Select `DEMO`, enter the numeric Deriv App ID and DEMO PAT directly into the app, and tap `VERIFY DERIV DEMO · READ ONLY`.
6. Do not select REAL for this verification. LIVE remains blocked.

The in-app result card is evidence only when it shows the redacted account prefix, `DEMO`, currency, balance received, portfolio received, public tick received, and the check time. A successful APK installation is not broker acceptance.

## If the artifact is unavailable

The artifact may have expired. Do not download an APK from an untrusted mirror or public release. Ask the repository owner to use a newer successful Actions artifact and repeat the checksum check.
