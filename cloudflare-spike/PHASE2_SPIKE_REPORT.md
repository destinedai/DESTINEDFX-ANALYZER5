# Phase 2 Cloudflare Workers / D1 Compatibility Spike — Recovery Report

**Repository:** `destinedai/DESTINEDFX-ANALYZER5`
**Starting commit:** `a7218911dc979e04d88ffedc296db060d09371bd`
**Branch:** `spike/cloudflare-workers-d1` (created locally from the verified `main`; no remote branch existed during recovery)
**Status:** **Phase 2 implementation not recovered; compatibility and D1 results are NOT VERIFIED.** This report records the available evidence and does not recreate the missing spike.

## Recovery and missing work

The sandbox had no repository checkout or Phase 2 workspace at the start of this task. I checked for a local checkout and `cloudflare-spike/` before cloning. The named GitHub repository was accessible; its `main` HEAD matched the stated commit. The intended spike branch was absent from the remote refs, and no local branch or uncommitted workspace snapshot was available. A fresh clone was made at `/home/ubuntu/DESTINEDFX-ANALYZER5`; the preserved Phase 1 audit at `docs/production-readiness-audit.md` remains unchanged.

The following Phase 2 artifacts described in the recovery note were not present and could not be recovered: the entire prior `cloudflare-spike/` tree, including `pyproject.toml`, `wrangler.jsonc`, `entry.py`, D1 migrations, synthetic integration tests, package-compatibility/Fernet tests, restart/retention/concurrency tests, quota-model tests, and README. The NaN/Infinity fix and its regression tests were likewise absent. The exact version of the isolated newer cryptography candidate and its prior test logs were not in the available repository or workspace. No missing code or earlier test result is represented here as recovered.

Only this report has been added under `cloudflare-spike/`. Production bridge code, authentication, EA source, and Windows worker code were not edited. The spike suite could not be run because its test files and runtime configuration do not exist in the recovered workspace.

## Compatibility matrix

The production bridge pins are taken from `bridge/requirements.txt`. The pinned environment was installed into a temporary virtual environment under `/tmp`; on CPython 3.12.3 it resolved FastAPI 0.115.12, Starlette 0.46.2, Pydantic 2.11.4, and cryptography 44.0.3. The existing bridge/EA Python tests passed in that native environment (see Test evidence). Native CPython test success does not establish Pyodide/PyEmscripten or Cloudflare Workers compatibility.

| Dependency | Available version evidence | Native-host evidence | Python Workers / Pyodide result |
|---|---|---|---|
| FastAPI | `0.115.12` pinned | Existing production suite passed; no spike runtime test | **NOT VERIFIED** |
| Starlette | `0.46.2` resolved with pinned FastAPI | Existing production suite passed; no spike runtime test | **NOT VERIFIED** |
| Pydantic 2 | `2.11.4` pinned | Existing production suite passed; NaN/Infinity Worker regression unavailable | **NOT VERIFIED** |
| `cryptography`, original bridge pin | `44.0.3` pinned | Present in isolated native test environment; this is not a Worker/Fernet spike result | **NOT VERIFIED** |
| Isolated cryptography candidate | Candidate version and files not recovered | **NOT VERIFIED** | **NOT VERIFIED** |
| `hashlib` / `hmac` | CPython 3.12.3 standard library | No isolated spike test | **NOT VERIFIED** |
| `secrets` / `uuid` | CPython 3.12.3 standard library | No isolated spike test | **NOT VERIFIED** |
| `datetime` / `json` | CPython 3.12.3 standard library | No isolated spike test | **NOT VERIFIED** |

The earlier recovery note says the original cryptography pin lacked a usable PyEmscripten wheel and that a newer candidate was investigated. Those prior build artifacts, candidate version, and logs were not recoverable here, so this report does not independently mark either package pass or fail for Workers. No production dependency was changed or recommended for replacement.

## D1, transaction, and concurrency evidence

All requested D1 implementation checks are **NOT VERIFIED**: Python D1 binding, prepared statements/bound parameters, uniqueness, foreign keys, atomic batches, rollback, idempotence, one-use pairing-code concurrency, ownership, restart persistence, snapshot retention, event-retention bounds, and replay. There is no recovered D1 schema, migration, Worker entry point, or corresponding test output. No D1 database was created, and no remote query or usage measurement was made.

The existing production bridge is SQLite-backed (`bridge/app.py`), not a D1 implementation. Its schema or SQLite query behavior cannot be substituted for measured D1 row metrics. In particular, without the missing spike queries, indexes, and audit-event write path, row reads/writes and index-related write amplification cannot be responsibly projected for any account count. D1 read/write estimates for 1, 2, 5, 10, and 25 trading accounts are therefore **NOT VERIFIED**, not zero. Cloudflare documents that reads count scanned rows, indexes can reduce scans, and writes to indexed data add write amplification; the D1 query `meta.rows_read` and `meta.rows_written` or account analytics are needed for actual measurement.[2]

## NaN and malformed-input validation

The requested fix was not applied because the Worker scaffold and validation handler were absent. No claim is made that the local Worker returns HTTP 422 for NaN, positive or negative Infinity, malformed nested objects, or invalid numeric values. Sanitized field locations/error types, suppression of raw values/context, and avoidance of non-finite JSON serialization are **NOT VERIFIED**. No regression tests for these cases were available or run. Production `bridge/app.py` and production authentication were left untouched.

## Source-based Workers request model

This is a nominal **EA-only incoming HTTP request estimate**, derived from `mt5-ea/DestinedFXTraderEA.mq5`, not a measured Cloudflare deployment. Assumptions: one continuously running authenticated EA per trading account; default inputs `InpPollSeconds=5`, `InpHeartbeatSeconds=5`, and at least one configured symbol; 86,400 seconds/day; one timer turn is serviced every five seconds. The source schedules the EA timer at `max(1, InpPollSeconds)` (`OnInit`, line 237), polls commands once per timer turn (`ProcessCommands`, lines 162–164 and 239), requests the first configured symbol's news verdict once per timer turn (`NewsClear`, lines 65 and 239), and posts a heartbeat every `max(5, InpHeartbeatSeconds)` seconds (`Heartbeat`, lines 24, 236, and 239).

At the defaults this is nominally 17,280 timer turns per day and three recurring HTTP requests per turn: one command poll, one first-symbol news check, and one heartbeat. That gives a baseline of **51,840 requests per active EA per day**. This is not a strict upper bound. The EA also requests news for each EA-magic-number order visited by its pending-order check, may request news again when an eligible setup reaches the placement gate, and sends command acknowledgements/reconciliation updates. Claim retries have no source-defined backoff or maximum. The optional Windows worker also makes a job-claim request every `max(2, poll_seconds)` seconds (default five seconds), with status calls per owned terminal. App polling and other clients are not included. Synchronous request timeouts and runtime scheduling can also make realized cadence differ from the nominal schedule.

| Active EA trading accounts | EA baseline requests/day | Against 100,000 Workers requests/day |
|---:|---:|---|
| 1 | 51,840 | Below limit by 48,160 before other traffic |
| 2 | 103,680 | Exceeds limit by 3,680 before other traffic |
| 5 | 259,200 | Exceeds limit |
| 10 | 518,400 | Exceeds limit |
| 25 | 1,296,000 | Exceeds limit |

The free Workers plan currently lists 100,000 requests/day and 10 ms CPU time per HTTP invocation.[1] On these EA defaults, one account is the largest whole-account count that fits the request limit in this simplified baseline; even one account leaves only 48,160 requests/day for pending-order news checks, acknowledgements, retries, the optional worker, app traffic, and any other Worker users sharing the account. Two or more accounts do not fit the free request allowance even before those additional calls. Do not reduce the five-second heartbeat or weaken the 120-second news-freshness validation to make this estimate fit. The EA's default news freshness is 120 seconds and its startup validation caps the configured value at 120 (`mt5-ea/DestinedFXTraderEA.mq5`, lines 25, 65, and 237).

No D1 row/day or storage estimate is claimed because the implementation is missing. The recovery note requested a 500 MB storage planning ceiling. Current official D1 pricing documentation lists **5 GB total** on Workers Free, not 500 MB; 500 MB can still be used as a more conservative project assumption if desired, but should not be described as Cloudflare's current published limit.[2] Starting September 1, 2026, D1 queries on the Free plan fail after the daily row-read or row-write limit is exceeded, until the reset; this makes an unmeasured design unsafe to treat as capacity-ready.[3]

Cloudflare CPU time is **unavailable** for this spike: there is no recovered Worker to measure and no Cloudflare runtime invocation or CPU profile. Local wall-clock durations from Python tests are not Cloudflare CPU measurements. The previously attempted `time.process_time()` measurement is not relied on.

## Security, isolation, and execution boundary

During this recovery run, work was limited to source inspection, local Python tests, a fresh clone, a local feature branch, and this report. No broker credentials or real app/terminal bearer tokens were used or created. No live or demo order was submitted. No Worker was deployed, no D1 database was provisioned, and no paid infrastructure was created. No Cloudflare account, Render service, broker, or MT5 terminal was contacted. No secrets were added to the branch. The only new file is this report under `cloudflare-spike/`; the production bridge, Windows worker, and EA remain untouched.

The preserved Phase 1 audit documents Render as Free and non-durable, with execution-sensitive routes disabled in that source configuration. The live Render service was not inspected in this recovery run, so its current remote runtime state is **NOT VERIFIED**. Likewise, the Phase 1 audit's no-broker-activity statement applies to its stated audit scope; this report does not claim to have independently verified external activity before this recovery session. No unattended DEMO or LIVE execution readiness is claimed.

## Test evidence

The initial system-interpreter command failed because `pytest` was not installed there. To run the existing production Python tests without changing repository dependencies, the pinned bridge requirements were installed into `/tmp/destinedfx-prod-tests` using an isolated CPython 3.12.3 virtual environment.

| Command | Result |
|---|---|
| `uv venv /tmp/destinedfx-prod-tests --python python3` | Passed; isolated environment created |
| `uv pip install --python /tmp/destinedfx-prod-tests/bin/python -r bridge/requirements.txt` | Passed; repository pins installed |
| `/tmp/destinedfx-prod-tests/bin/python -m pytest -q bridge mt5-ea/test_ea_source.py mt5-ea/test_ea_safety_math.py` | **85 passed**, 1 third-party deprecation warning |
| `python3 -m unittest discover -s managed-worker -p 'test_*.py' -v` | **22 passed** |
| `git diff --check` | Run after report creation; result recorded with the branch commit |
| PyWrangler/Workers and D1 tests | **NOT RUN** — scaffold, config, migrations, and tests missing |
| NaN/Infinity Worker regression suite | **NOT RUN** — Worker validation code and tests missing |
| Android Gradle tests | **NOT RUN** — Android SDK variables were not configured in this environment |

The 85 passing Python tests are the existing production bridge and EA source/safety tests, not Phase 2 Worker tests. The 22 passing worker tests use the checked-in unit-test suite; they do not validate a live Windows host or MT5 terminal. The historical counts in the Phase 1 audit are earlier evidence and are not substituted for these current local runs.

## Files and remaining blockers

Added: `cloudflare-spike/PHASE2_SPIKE_REPORT.md`. No production file or existing audit was modified. No spike implementation or test file was recovered or added.

Before any MT5 hosting work can be considered, the Phase 2 workspace or its missing files and exact test logs must be restored. Then the Worker validation bug must be fixed and tested without echoing invalid values, and the complete local Workers/D1 suite must cover the listed transaction, ownership, concurrency, replay, retention, and persistence cases. The package matrix must be re-established with exact versions and runtime evidence; the cryptography candidate must remain separate from the production pin unless independently reviewed and approved. Request capacity already blocks two default-cadence EA accounts on one Free Workers account, before conditional and other client calls. D1 rows, indexes, and storage require an actual instrumented local implementation and measured query metadata; CPU requires supported Worker runtime metrics. Finally, any hosting/acceptance step still needs authorized durable infrastructure and the separate genuine MT5/DEMO operator acceptance described in the preserved audit. This report does not authorize deployment or spending.

## References

[1]: https://developers.cloudflare.com/workers/platform/limits/ "Cloudflare Workers limits"
[2]: https://developers.cloudflare.com/d1/platform/pricing/ "Cloudflare D1 pricing"
[3]: https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/ "D1 free tier daily limit enforcement"
