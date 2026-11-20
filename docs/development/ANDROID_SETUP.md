# Android development setup

## Prerequisites

- Full JDK 21 (the checked-in workflow uses Temurin 21; a JRE without `javac` is insufficient).
- Android SDK and the platform/build tools required by the Gradle configuration (compile SDK 36 extension 1, target SDK 36; the workflow runner provisions Android tooling).
- Python 3.12 at `/usr/bin/python3.12` for the configured Chaquopy build.
- Git and the repository's checked-in Gradle wrapper.
- A device/emulator only for instrumented UI/device tests.

## Checkout

```sh
git clone https://github.com/destinedai/DESTINEDFX-ANALYZER5.git
cd DESTINEDFX-ANALYZER5
git switch free/phone-only-v1
cp .env.example .env
```

Do not populate `.env` with private production values for ordinary development. It is ignored by Git. The Android app can run without a configured managed-bridge URL for local analysis; bridge-dependent paths remain unavailable unless an operator configures a service.

## Configuration precedence

`MT5_MANAGED_SERVICE_URL` is resolved in `app/build.gradle.kts` from Gradle property `mt5ManagedServiceUrl`, then environment variable, then the root `.env` value. It is injected as an endpoint string; it is not a credential. Without it, the placeholder `__CONFIGURATION_REQUIRED__` is compiled. Release task configuration requires a public HTTPS origin without credentials/query/fragment/path. That check does not verify a remote server or DNS safety.

Never store private provider/broker API credentials, signing keys or passwords in tracked files, APK resources, client BuildConfig fields, or logs. Runtime secret entry/storage is provider-specific and must be independently reviewed.

## Helpful commands

```sh
java -version
javac -version
./gradlew --version
./gradlew tasks --all
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon
```

An `SDK location not found` error means SDK discovery/configuration is incomplete. A Gradle error about `JAVA_COMPILER` means the selected Java installation lacks a full compiler. Neither is a test result. For CI behavior see [CI/CD](CI_CD.md).