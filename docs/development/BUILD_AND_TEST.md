# Build and test

## Android commands

```sh
python3 scripts/verify_read_only_build.py
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon  # device/emulator required
```

`testDebugUnitTest` is the JVM/Robolectric suite used by CI. Instrumented tests are present under `app/src/androidTest/`, but are not run by the current GitHub Actions workflow. Building requires the configured Android SDK and a full JDK; Chaquopy also expects the Python build interpreter configured in `app/build.gradle.kts`.

## Python/backend commands

Use a virtual environment and install dependencies from `bridge/requirements.txt` before running the bridge suite:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r bridge/requirements.txt pytest
python -m pytest -q bridge scripts/test_deriv_demo_smoke.py
python -m unittest discover -s managed-worker -v
```

These are separate from Android build and do not establish that bridge, worker or MT5 paths are read-only. They are not currently part of `.github/workflows/build-apk.yml`.

## Relevant test coverage

- Android read-only guard: `ReadOnlyExecutionPolicyTest`, `TradeUseCaseImpl` boundary and static verifier.
- Navigation/source intent: `DestinedFXNavigationTest`.
- Broker routing/provenance: `BrokerManagerRoutingTest`, `BrokerCapabilityMatrixTest`, `BrokerAccountSnapshotTest`, `UniversalConnectorDispatcherTest`.
- Market safety and parsing: `MarketDataSafetyTest`, calendar and news repository/provider tests.
- Broader app tests: signal integrity, risk, Deriv, cTrader, MT5, UI state and serialization tests under `app/src/test/`.
- Device/UI instrumentation: `app/src/androidTest/`.
- Bridge/worker/EA helper tests: under `bridge/`, `managed-worker/`, `mt5-ea/`, and `scripts/`.

A test existing in source is not the same as a test passing. Fixtures validate local logic; they do not prove live market-provider, broker, deployment or account acceptance. `ReadOnlyExecutionPolicyTest` tests the guard helper; end-to-end transaction-surface removal/reachability still needs proof.

## Current environment result

In this follow-up audit, `python3 scripts/verify_read_only_build.py` passed. An isolated Python 3.12 environment ran `python -m pytest -q bridge scripts/test_deriv_demo_smoke.py`: **160 passed, one third-party Starlette/AnyIO deprecation warning**. `python -m pytest -q managed-worker mt5-ea`: **72 passed**. These suites exercise source/tests with fixtures and fakes; they do not establish real Windows/MT5, broker, live provider, or deployment behavior.

The local sandbox has Java 21 runtime but no `javac` and no `ANDROID_HOME`/`ANDROID_SDK_ROOT`; Gradle could not proceed through Android compilation/tests here. This is a local environment blocker, not evidence of a code failure or a pass. See the exact-revision GitHub Actions run only when its `head_sha` matches the commit being assessed.

## Quality boundaries

Do not disable failing tests. Record test counts from the XML/summary for the exact run. Build success, APK signature validity, artifact upload, and external provider verification are separate facts; report each separately.
