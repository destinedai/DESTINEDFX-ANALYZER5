# Read-only conversion audit

**Target:** `free/phone-only-v1`  
**Starting commit:** `22dbcddc3fd3f16bd9b555c7618e9f339cdeb9c3`  
**Audit date:** 2026-11-07

## Starting-state verification

- Repository: `destinedai/DESTINEDFX-ANALYZER5` (cloned from GitHub).
- Target branch: `free/phone-only-v1`.
- Fetched branch HEAD: `22dbcddc3fd3f16bd9b555c7618e9f339cdeb9c3`.
- Working tree at inspection: clean.
- Commits in `22dbcddc3fd3f16bd9b555c7618e9f339cdeb9c3..HEAD`: none (empty range).
- Recent history included demo/live arming, broker dispatcher, Deriv readiness, and execution-related work; this is not evidence of a read-only product.
- Root README did not exist at the starting commit.

## Findings

The Android source exposes broker and bot workspaces, includes order-submission and autonomous bot code, and declared foreground broker-monitoring services plus boot recovery. The repository also contains a standalone FastAPI bridge with managed-account enrollment, live authorization, command queueing, terminal heartbeat, close/modify order operations, trade history, a worker, and MT5 EA. Hiding only the Android Bot tab would not make the repository or a deployed bridge read-only.

## Changes in this pass

- Added `ReadOnlyExecutionPolicy` and placed its unconditional rejection at the beginning of `TradeUseCaseImpl.executeTrade`, before broker lookup, market reads, journaling, or order submission.
- Removed Bot from primary navigation and changed its former destination to a read-only scope disclosure.
- Removed broker callback, bot/session service, boot receiver, foreground-service, boot-recovery, and battery-optimization declarations from the Android manifest.
- Updated the navigation regression expectation and added a unit test for the rejection policy.
- Added a root README and this audit document, with remaining repository risks called out explicitly.

## Verification attempts

- `git ls-remote` and `git fetch origin free/phone-only-v1` confirmed the baseline commit above.
- Baseline `./gradlew testDebugUnitTest --no-daemon`: **not run**; Gradle failed during task configuration because no Android SDK was installed/configured (`SDK location not found`, no `ANDROID_HOME` or `local.properties` sdk.dir).
- Initial baseline `python3 -m pytest -q bridge scripts/test_deriv_demo_smoke.py`: **not run**; system Python reported `No module named pytest`.
- Against commit `8e22edb3e819fcaac24b11d630230b5af2ad5186`, after installing the repository's `bridge/requirements.txt` into an isolated temporary virtual environment, `python -m pytest -q bridge scripts/test_deriv_demo_smoke.py`: **160 passed, 1 warning** (Starlette imports deprecated `anyio.abc.BlockingPortal`). The backend implementation was not changed in this conversion slice; this verifies its existing tests, not read-only behavior.
- Against commit `8e22edb3e819fcaac24b11d630230b5af2ad5186`, `./gradlew testDebugUnitTest --no-daemon`: **blocked before tests ran** because Gradle could not locate the Android SDK (`ANDROID_HOME` unset and no `local.properties` sdk.dir).
- Python XML validation of the changed Android manifest passed: no service/receiver declarations, no cTrader callback data route, and none of the four removed service/boot permissions remained. `git diff --check` passed before commit.
- No successful Android build, Android unit/UI test, emulator test, APK artifact, or complete CI run is claimed for this revision.

## Implemented vs incomplete

**Implemented in this slice:** fail-closed Android order-submission boundary; hidden/repurposed Bot navigation; removal of Android manifest declarations for the listed account/trading service entry points; product-scope documentation and regression tests.

**Implemented but unverified:** the changes above have not compiled or run because of missing build dependencies. Existing market-data safety behavior was inspected and left unchanged.

**Not implemented:** bridge execution endpoints and worker/EA source remain in the repository and can still be separately built/deployed; their routes were not disabled in this pass. Legacy broker/autonomous bot implementation source remains (although the Android submission use case rejects before reaching it). Settings/authentication and every indirect call path have not received a full security audit. The requested full responsive/rotation/persistence/accessibility audit, malformed/network tests, all-CI execution, and verified release build remain outstanding.

**Deliberately excluded from the intended product:** broker login and account connections, automated or manual order submission, live trading controls, position/order modification or closing, and any financial transaction.

## Release warning

This branch is **not a completed or verified read-only release**. Do not publish or deploy it until the standalone bridge/worker/EA execution surfaces are removed or made unreachable with regression evidence; all app pathways are audited; the full test/build pipeline passes on a configured Android environment; and the exact resulting branch HEAD and artifact are verified.

## Follow-up audit at the reported branch HEAD

The requested repository state was independently fetched and confirmed as `free/phone-only-v1` at `9ac48a8c0bfe351bb666ba3694768d6b7ec23410`. The working tree was clean before this follow-up. A code review confirmed the warning above still applies: the Android trade use case rejects at its entry point, but transaction-capable gateway methods and legacy bridge/worker/EA source remain. Provider adapters and tests are source evidence only; no external market provider, authenticated broker account, or deployed service was tested.

GitHub Actions run [42377888338](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/42377888338) is a completed successful `Build APK` push run whose `head_sha` matches the audited starting commit. Its JVM test, debug assembly, APK signature check, APK upload, and test-report upload steps are reported successful. This is evidence for that exact prior revision only; it does not cover later documentation edits. Artifact name is `DestinedFX-read-only-debug-apk`, configured for 14-day retention. Refer to the workflow run for the exact downloadable artifact and its status; no local APK checksum or artifact ID is asserted in this document.

In the current sandbox, `python3 scripts/verify_read_only_build.py` passed. An isolated Python 3.12 environment ran `python -m pytest -q bridge scripts/test_deriv_demo_smoke.py` (**160 passed, 1 third-party deprecation warning**) and `python -m pytest -q managed-worker mt5-ea` (**72 passed**). These Python tests are not part of the current GitHub Actions workflow and do not prove external provider/broker behavior or safe deployment. Local Android compilation/tests were not run: Java 21 is present but `javac` is absent, and neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` is configured. This environment limitation is not a failing product test and does not replace hosted CI. The current workflow also omits instrumentation, Python, bridge, worker, EA, lint, and security-scanning suites.

The public market-data matrix and backend architecture documents now distinguish adapters from external verification and document source-level limitations. The stale installation guide was aligned to the current workflow artifact name. No code-level provider, lifecycle, backend, or execution-boundary remediation is claimed by this documentation update.
