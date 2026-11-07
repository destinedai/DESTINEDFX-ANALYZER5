# DestinedFX zero-cost and DEMO readiness

**Audit date:** 2 November 2026
**Repository:** `destinedai/DESTINEDFX-ANALYZER5`
**Production baseline:** `main` at `a7218911dc979e04d88ffedc296db060d09371bd`
**Work branch:** `feat/complete-demo-readiness` (created from that clean baseline)
**Decision:** **NO-GO** for verified zero-cost PC-off MT5 execution today; current managed MT5 is **NOT VERIFIED** for DEMO acceptance. LIVE trading remains unaccepted and locked.

**Historical note:** This report records findings on its original feature branch as of 2 November 2026. It is not a status report for the current `free/phone-only-v1` head. Current product and execution scope are described in the root README and current deployment guides. No external host, broker, account or trade is verified by this historical report.

## Scope and safety boundary

The audit traced the native Android/Compose client, Kotlin analyzer/risk and news boundaries, FastAPI bridge, SQLite/credential handling, Windows worker, MQL5 EA, source-level tests, GitHub Actions and deployment/runbook configuration. Work was performed on a feature branch based on `main`. No broker message was sent, no account was created or logged into, no credentials were supplied, no paid resource was provisioned, no service was deployed, and no trade was submitted. The existing `spike/cloudflare-workers-d1` branch was left untouched.

The system is not made execution-ready by an APK build, a healthy `/health` response, successful worker unit tests, or a queued command. The code-level audit and official-platform findings are supporting evidence only. Full external/platform verification remains outstanding.

## Current architecture

The Android app is a native Kotlin/Jetpack Compose client. Its deterministic Kotlin analyzer, validation and `RiskEngine` are distinct from the MQL5 EA's independent broker-facing risk/execution logic. The Bot screen controls and observes remote MT5 execution; it is not an MT5 terminal and does not submit MT5 orders itself. The existing managed path is Android app → authenticated FastAPI bridge → authenticated Windows worker → isolated MT5 terminal + EA → broker. The bridge requires a worker-owned READY job and a fresh identity-matched terminal heartbeat before managed execution is READY. A manual MT5 WebRequest allowlist step remains required for each isolated terminal.

The same app has a separate Android foreground-service Deriv path. It is handset-hosted and requires the phone/service/network; it is not MT5 or continuous PC-off MT5 hosting. The current Render blueprint is a diagnostic-only Free service with ephemeral database storage and execution-sensitive routes fail closed. The repository Cloudflare Workers/D1 spike is not deployed and does not host an MT5 terminal.

See the [current architecture diagram](architecture-current.png) and its [editable Mermaid source](architecture-current.mmd).

## Safe changes on this branch

- Removed the hard-coded temporary trycloudflare endpoint from the debug APK default. With no explicit build-time URL, debug now remains `CONFIGURATION_REQUIRED`; a manually entered, validated origin or a configured build value is required.
- Added an authenticated `GET /v1/app/dashboard` response that combines the current app-state shape and one bounded trade-event page (up to 200 rows), retaining the existing state/history endpoints.
- Changed the visible Bot monitor from two requests every two seconds to one combined request every five seconds. Command POST handlers are unchanged and send immediately; the EA's five-second heartbeat/news/command cadence is unchanged. On an older bridge that returns 404, the Android client detects that once, continues status polling every five seconds, and limits non-critical history polling to once per minute.
- Made managed MT5 profile persistence return success only after an encrypted-preferences commit. The Bot UI disables local password-save when secure storage is unavailable and warns explicitly if the bridge accepted account registration but local encrypted profile persistence then failed; it advises status verification rather than blind re-registration.
- Added a CI aggregation gate and made the Android build/artifact job wait for both Python tests and genuine Windows MetaEditor compilation.

These changes reduce Android monitoring traffic without reducing the EA's safety cadence. They do **not** implement a hosted-terminal provider, change trading logic or risk limits, deploy any endpoint, or claim DEMO acceptance.

## Request budget (nominal source-based HTTP calls)

A day has 86,400 seconds; a five-second cycle is 17,280 cycles. These counts exclude startup, manual control, retries, reconnects, pagination backlogs, provider work, and other operational calls unless stated. The Android component is counted only when its configured URL points to the same Worker and the Bot screen is visible.

| Design / source path | Calculation | Requests/day |
|---|---:|---:|
| Current EA, two accounts | 3 recurring EA calls per 5-second cycle (news verdict, command poll, heartbeat) × 17,280 × 2 | **103,680** |
| Current shared Windows worker, two terminals | 2 job-status GETs + 1 job-claim call per 5-second cycle | **51,840** |
| Current EA + Windows worker | 103,680 + 51,840 | **155,520** |
| Previous active Android Bot loop | state + trade history × 1/2 second | **86,400 per active app loop** |
| New combined dashboard | 1 combined request × 1/5 second | **17,280 per active app loop** |
| Legacy bridge fallback | state × 1/5 second + history × 1/60 second | **18,720 per active app loop** |
| Existing EA + worker + new Android dashboard | 103,680 + 51,840 + 17,280 | **172,800** |
| Hypothetical workerless, one-cycle EA + new dashboard | 1 EA request/5 sec × 2 + 1 dashboard request/5 sec | **51,840** |

The Android redesign cuts nominal per-screen monitoring calls by **80%** (from 86,400 to 17,280/day) on a bridge that supports the combined endpoint. The old-server fallback uses about 18,720/day. The five-second loop observes a remote command after submission; it does not delay the action POST itself. The EA still makes its own five-second requests.

Cloudflare's official Workers Free limits list 100,000 requests/day and 10 ms CPU per invocation. The current source-level EA + Windows-worker path already exceeds that request ceiling before Android or retries; with the revised app monitor, it is about 172,800/day. A hypothetical one-request EA cycle plus the new Android dashboard would leave 18,160 calls under the 70,000 project target before operations, failures/retries, startup, and other users. Neither that workerless EA cycle nor its CPU/deadline/retry/storage behavior is implemented or measured. Therefore, the 70,000/day target under realistic two-account conditions is **NOT VERIFIED** and the free-tier bridge remains **NO-GO**. The dashboard change alone is not a Cloudflare deployment plan; FastAPI/SQLite is not automatically portable to Workers/D1.

## Zero-cost PC-off hosting decision

MetaQuotes describes Sponsored VPS as a broker-conditional program; its public standard VPS price is per account-bound terminal and its 24-hour trial is not ongoing free hosting. Public sources reviewed do not establish that Deriv sponsors this user's Nigerian MT5 accounts, whether DEMO and LIVE are included, their eligibility conditions, or whether the exact account/authentication and third-party-EA/WebRequest setup is permitted. These points are **NOT VERIFIED**. The existing bridge additionally depends on a Windows worker job for readiness, so a hosted EA cannot be marked READY through the current worker-dependent contract merely because it sends a heartbeat.

cTrader Cloud is a different platform, not a drop-in MT5 replacement. The prior official-source review notes cloud-specific HTTP/network and local-file constraints and that the current DestinedFX app has no cloud-cBot lifecycle integration. No port was made.

**No paid trial, subscription, deployment, Cloudflare experiment or account action was performed. Actual spend: $0.** This verifies zero spend on this task; it does not prove an ongoing zero-cost host exists.

### Prepared support inquiry - not sent

**Subject: Confirmation of free MetaQuotes Virtual Hosting for Deriv MT5 accounts in Nigeria**

> Hello Deriv Support,
>
> I use Deriv MT5 and would like to know whether Nigerian customers are eligible for broker-sponsored MetaQuotes Virtual Hosting.
>
> Please confirm:
>
> 1. Is MetaQuotes Virtual Hosting free for eligible Deriv clients?
> 2. Does this apply to DEMO accounts, LIVE accounts, or both?
> 3. What balance, trading volume, deposit or account requirements apply?
> 4. Are there any subscription, renewal or indirect costs?
> 5. Can customers install and run their own third-party Expert Advisors?
> 6. Are HTTPS WebRequest connections supported from hosted MT5 terminals?
> 7. Are there any restrictions on external URLs or automated trading?
> 8. Are accounts with OTP or additional authentication supported?
> 9. Can two separate MT5 accounts use sponsored hosting?
>
> I need this information before implementing an automated MT5 hosting architecture.
>
> Thank you.

No message was sent. Obtain the user's permission before contacting a broker.

## DEMO acceptance gate

A genuine DEMO acceptance requires all gates below. No DEMO credentials or suitable terminal were supplied in this task; no broker response or fill was observed.

| Gate | Status | Evidence needed |
|---|---|---|
| Android tests/build/install | **BLOCKED** locally | Android SDK is absent in this sandbox; exact-commit CI and device install evidence still required. |
| Supported live market data | **NOT VERIFIED** | Real provider response, correct mapping, timestamps and freshness on the chosen instruments. |
| Worker/EA authentication | **NOT VERIFIED** | Genuine Windows worker, compiled EA, authorized public HTTPS bridge and terminal claim. |
| Exact DEMO identity/mode and fresh heartbeat | **NOT VERIFIED** | Broker-reported login/server/company/currency/mode, fresh authenticated telemetry and READY state. |
| Current news verdict | **NOT VERIFIED** | Valid, symbol-relevant, timestamped provider verdict that satisfies freshness coverage. |
| Risk-approved plan through execution boundary | **NOT VERIFIED** end-to-end | Actual approved signal/config and MQL5 checks on the same terminal/account; component tests are not broker evidence. |
| DEMO order submitted and broker-confirmed | **BLOCKED** | Requires a genuine user-authorized DEMO account and explicit later authorization; no order was submitted. |
| Fill, SL/TP, and history reconciliation | **NOT VERIFIED** | Broker-visible fill/protection and subsequent bridge/app reconciliation. |
| STOP and reconciliation | **NOT VERIFIED** operationally | Authenticated command, EA result, fresh terminal state; unit/source checks do not prove broker behavior. |
| Restart/disconnection recovery | **NOT VERIFIED** operationally | Windows/MT5/bridge outage and restart acceptance with no duplicate orders. Worker unit tests use fakes only. |
| LIVE trading | **NOT AUTHORIZED / NOT VERIFIED** | No LIVE order was authorized or attempted; keep LIVE locked. |

## Remaining security and reliability findings

1. `mt5-ea/DestinedFXTraderEA.mq5` has persistence operations whose failures are not consistently surfaced before acknowledgement/cursor advancement. This is a recovery risk. It was **not** changed here because genuine MetaEditor/MT5 validation is unavailable; a focused fault-injection redesign and Windows compile are required before changing the broker-facing journal.
2. Bridge Fernet encryption covers broker credentials, not every account/command/telemetry/ledger field in SQLite. Whole-database encryption, persistent volume access controls, backup and restoration are deployment requirements and are **NOT VERIFIED**.
3. The Windows startup INI briefly contains the broker password before the worker deletes it; terminal account data may also retain authorization. Windows ACLs, encrypted storage and cleanup need operator-side verification.
4. The worker writes the EA WebRequest URL input but cannot configure MT5's trusted URL list. The operator must add the exact HTTPS origin in every terminal; a running process is not connectivity evidence.
5. Kotlin and MQL5 RiskEngine/validation implementations are separate. Portfolio-wide exposure, daily drawdown, analyzer cache/source freshness, all strategy-specific execution gates, and manual UNKNOWN-news override isolation require end-to-end review; no risky scoring/risk rewrite was made.
6. Render's `autoDeploy` setting exists in source, but actual service/repository linkage was not checked or changed. Its current ephemeral-storage blueprint must remain diagnostic-only.

## Locally verified on this feature branch

- Bridge and quantitative suites: **73 passed**, with one third-party Starlette/AnyIO deprecation warning.
- Managed worker suite: **22 passed**, using fake executables/mocks on Linux; this is not genuine Windows/MT5 validation.
- EA source-contract and safety-math tests: **13 passed**; these do not compile or execute MQL5.
- YAML syntax parse and `git diff --check`: passed at the reported local test run.
- Architecture diagram: Mermaid source rendered to PNG and visually inspected.
- Android `./gradlew testDebugUnitTest`: **BLOCKED** before task execution by missing Android SDK (`ANDROID_HOME`/`sdk.dir`).
- Android `./gradlew assembleDebug`: **BLOCKED** because the sandbox JRE installation has no `javac`/`JAVA_COMPILER`; no APK was produced. No Android test or build is claimed as passed.

## Exact next external steps

1. Do not use the repository Render Free blueprint for execution and do not start MetaQuotes' paid auto-converting trial.
2. Only if the user chooses to send the prepared inquiry, obtain written account-specific Deriv answers for Nigerian eligibility, DEMO/LIVE terms, cost, OTP, third-party EA and WebRequest restrictions.
3. For the existing PC-on fallback, an operator must provide an already-owned and secured Windows machine, genuine MT5/MetaEditor, authorized DEMO credentials, and a separately authorized durable HTTPS bridge with persistent storage and tested backup/restore. These are not supplied or installed by this branch.
4. Run the final feature commit through GitHub Actions and verify the aggregate **All platform checks passed** job against its exact commit SHA. Then install the debug APK on a real Android device and complete controlled DEMO acceptance before any production PR.
5. Keep all LIVE activity locked. Do not describe a signal, queued command, READY-looking UI, CI pass, or service health response as a broker fill.

## Official platform references reviewed previously

- [MetaTrader 5 virtual-hosting migration](https://www.metatrader5.com/en/terminal/help/virtual_hosting/virtual_hosting_migration), [MQL5 hosting rules](https://www.mql5.com/en/vps/rules), [MQL5 WebRequest](https://www.mql5.com/en/docs/network/webrequest), [VPS pricing](https://www.mql5.com/en/vps/forex-plans), and [Sponsored VPS conditions](https://www.mql5.com/en/articles/13586).
- Official Deriv help reviewed includes [Deriv cTrader activation](https://deriv.com/help-centre-question/how-do-i-activate-a-deriv-ctrader-account) and [account limits](https://deriv.com/help-centre-question/how-many-deriv-ctrader-accounts-can-i-have); those pages do not establish Deriv-sponsored MT5 hosting for Nigerian customers.
- [cTrader Cloud features](https://help.ctrader.com/ctrader-algo/documentation/cloud-features/), [Cloud requirements](https://help.ctrader.com/ctrader-algo/documentation/cbots/cloud-requirements/), and [Open API scope](https://help.ctrader.com/open-api/).
- [Cloudflare Workers platform limits](https://developers.cloudflare.com/workers/platform/limits/).
- The detailed prior source audit and request analysis was maintained as a separate local report and is not included in this repository snapshot.
