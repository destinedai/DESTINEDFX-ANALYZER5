# Build and test

## Android

```sh
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
./gradlew connectedDebugAndroidTest --stacktrace  # requires a connected device or emulator
```

Building requires a full JDK, configured Android SDK/platform/build tools, and the Python interpreter configured for Chaquopy. `testDebugUnitTest` runs JVM/Robolectric tests. The emulator script and test scope are defined in `scripts/run-android-emulator-tests.sh`.

## Python, bridge, worker and EA source tests

Use an isolated environment and install dependencies from `bridge/requirements.txt` before running bridge tests.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r bridge/requirements.txt pytest
python -m pytest -q scripts/test_deriv_demo_smoke.py bridge
python -m pytest -q managed-worker mt5-ea/test_ea_source.py mt5-ea/test_ea_safety_math.py
```

The EA source-contract and safety-math tests are not a genuine MetaEditor compile. CI has a separate Windows job for that compile.

## Safety and mode tests

Relevant Android tests include `ModeArmingPolicyTest`, `ModeArmingGatewayTest`, `DerivTradeRiskBoundaryTest`, `UniversalConnectorDispatcherTest`, and broker/market safety tests. `ModeArmingGatewayTest` uses a fake submission closure to prove that missing DEMO acceptance and an unarmed LIVE decision block before the broker call. It never connects to a broker.

A fixture passing proves only its local contract. It does not prove account authentication, order acknowledgement, fill execution, reconciliation, host uptime, provider access or deployment readiness. Never send real orders during tests.

## Restored CI coverage

The workflow defines Python/bridge/worker/EA tests, Gitleaks, Windows MetaEditor EA compilation, Android JVM tests and APK build, Android emulator instrumentation, and Windows worker-kit packaging. The actual status must be checked for each job on the exact commit. See [CI/CD](CI_CD.md).

## Verification record

Do not copy test counts from an earlier revision. Record the exact workflow SHA, run URL, per-job status, report XML totals, artifact ID, expiration, signing result and a SHA-256 computed from the downloaded artifact. A missing JDK or SDK means the local Android task could not run; it is not a pass or a code failure.
