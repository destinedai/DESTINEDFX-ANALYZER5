# DestinedFX implementation status

Updated: 2026-11-20

## Nov 20 code review fixes (CI pending)

- **Bridge Linux-host enrollment:** EPHEMERAL Codespaces with verified per-user slot isolation can enrol DEMO only. LIVE is rejected before managed-account credential storage or worker-job creation. Regression tests cover allowed DEMO enrollment, LIVE refusal, and unready isolation.
- **Codespaces first-run worker check:** now inspects the exact isolated slot-worker process path/arguments under `/proc`, avoiding the legacy worker mismatch and self-matching `pgrep` issue.
- **Xvfb ownership continuity:** repeated `vnc-up.sh` preserves the `started` marker only when prior PID and managed-process record agree; incomplete or mismatched prior ownership fails closed.
- **Installer marker lifecycle:** a shared `scripts/linux-host/finalize-mt5-install.sh` now requires verified MetaQuotes installer provenance, terminal + MetaEditor files, and a successful isolated compile of the EA before writing `installer-verified`. Headless post-create uses it; the GUI fallback tells the owner to run it after completing the wizard. This compile acceptance does not prove the terminal can authenticate to a broker or that it opens cleanly without an owner-observed debugger dialog.
- **Validation status:** these changes were pushed to PR #16; the matching GitHub Actions runs are pending/queued. Do not call them passed until the runs finish. No physical-phone, live Codespaces, two-user host, or broker DEMO acceptance is claimed.

## Bridge and account security

- **Implemented:** per-pairing encrypted credentials, broker/login/server checks, DEMO/LIVE mode checks, fail-closed commands, fresh EA heartbeat requirements, signed worker attestation, nonce replay protection, expiry, host-generation binding, single-use job linkage, process/EA finalization, and READY gating.
- **Tested:** pending enrollment to worker reservation, signed claim, finalization, and READY transition; cross-authentication and replay rejection.
- **Implemented in this stage:** workers now use independently provisioned credentials keyed to a stable worker ID, with one-time issuance, short expiry, rotation, revocation, last-seen tracking, and claim/attestation/finalization/status worker-ID binding. The legacy shared-token path is disabled by default and is enabled only by explicit test fixtures.
- **Implemented in this stage:** the Windows supervisor now verifies the stored credential against the local Bridge before starting the worker, provisions the exact stable identity when absent, rotates only when rejected or near expiry, and persists the new credential through an atomic DPAPI rewrite. Legacy bundles receive missing fields through the same validated migration path.
- **Not production-proven:** this lifecycle has not yet been exercised on a persistent owner host.

## Execution workers

- **Windows worker:** has per-job terminal copies, per-job data directories, process ownership checks, restart/revocation handling, signed reservation claims, and final process-evidence finalization integrated with the bridge.
- **Codespaces Linux slot worker:** provisions one non-login Unix identity per pairing, a private per-job Wine prefix and portable MT5 tree, a private Xauthority cookie, a dedicated Xvfb display, separate logs/manifests, and CPU/RAM-bounded capacity. A pairing may have only one active terminal; re-enrolment is blocked until the previous worker-confirmed revoke completes. The root host agent verifies the account/UID, home and directory ownership/modes, Xvfb identity, and the live MT5 process PID/start-time before the Bridge accepts reservation and final process evidence. New Linux slot provisioning remains DEMO-only; LIVE remains blocked.
- **Legacy owner-managed systemd worker:** `linux-worker.py` is still the single-terminal service path. It is not the multi-user slot implementation and must not be treated as providing per-user isolation. The Codespaces launcher uses `linux-slot-worker.py` with `linux_isolation.py` and the root host agent.
- **Code-level acceptance:** Python regression tests cover distinct identities and terminal trees for FBS, Exness, HFM and Deriv DEMO pairings; duplicate-terminal rejection; unreadable-marker fail-closed behaviour; loopback-only root-agent routing; and atomic terminal-marker updates. On PR head `33b5bd91e100dc2d40483e23fa223f4d37538677`, the Build APK Python job reported 48 Linux host/isolation/worker tests and 65 Bridge tests passing, and the Wine guard regression workflow passed. These are CI/offline tests, not proof of real OS-account creation or MT5 runtime readiness on the owner's host.
- **Not runtime-accepted:** the isolated worker has not yet been exercised on the owner's actual Codespace or a persistent root-managed Linux host with independently observed sessions.
- **Real verification:** no owner-controlled Wine/MT5 terminal or genuine broker account was available for runtime acceptance in this review.

## Android

- **Implemented:** phone-first managed broker flow, encrypted profile storage, explicit broker/mode identity, Bridge state polling, verified account/heartbeat gating, safe command authorization, Deriv and cTrader-specific flows, and Codespaces host discovery/Bridge verification UI.
- **Fixed in this stage:** MT5 registration sends the selected account mode to the Bridge; missing mode metadata previously caused the server to treat the request as unverified. Broker login switching also resets the login form scroll position to its header. The Bot Accounts heading is now constrained to one line on narrow phone widths following the 320dp screenshot review.
- **Test maintenance:** storage tests now assert the encrypted-preferences fail-closed policy when Robolectric has no Android Keystore; MT5 login tests match the current explicit verification copy and scroll to off-screen verification text/DEMO controls before interaction. No production safety gate was weakened.
- **Current source validation:** commit `f63a9f5980340d4d3dad917e50527e12fec76687` passed 402 Android JVM tests and produced a signature/metadata/ABI/checksum-verified debug APK in Build APK run [44509927948](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/44509927948). Clean API 34 emulator instrumentation for that same commit was still in progress when this document was refreshed.
- **Feature parity record:** see [Android vs MT5 Android feature parity audit](android-mt5-parity-audit.md) for verified implementation status, gaps and next engineering steps.
- **Unverified owner runtime:** installation and interaction on the owner's physical Android device, live Codespaces-to-phone pairing, real broker DEMO authentication, live quotes/orders, and isolated per-user noVNC sessions.

## Hosting and deployment

- **Implemented:** persistent Bridge Docker Compose templates, durable SQLite volume, Caddy/TLS owner stack, systemd host/worker units, backup/restore scripts, health/readiness endpoints, and Codespaces noVNC diagnostics.
- **Not deployed:** no paid VPS or persistent production worker has been provisioned or accepted. Codespaces remains an ephemeral DEMO/test host and is unsuitable as the sole always-on execution host.
- **Codespaces terminal:** CI diagnostics do not establish that the real Wine/MT5 terminal starts successfully. The owner must run the documented start and first-run checks in the actual Codespace and return redacted diagnostics; the MT5 debugger-warning observation must not be bypassed.

## Brokers and EA

- **Code paths:** HFM, Exness, FBS, and Deriv mappings exist; Deriv Options uses its separate API path; cTrader is assessed separately and remains read-only for trading.
- **Synthetic/automated verification:** broker mapping, identity, authorization, command, EA safety, adapter contracts, Bridge/worker regressions, Windows worker packaging, and genuine MetaEditor compilation are covered by CI.
- **Real verification pending:** broker-server authentication, terminal-reported account data, fresh EA heartbeat, one controlled DEMO order, attached SL/TP, close/history reconciliation, duplicate suppression, reconnect/restart, and LIVE behavior for every broker.

## Current release gates

- **DEMO:** implementation is not accepted until the owner-side host is running, per-user terminal isolation is independently attested, and a genuine broker DEMO lifecycle is completed with redacted evidence.
- **Linux isolation:** the Codespaces per-pairing slot implementation and root-agent attestation path are present and CI-tested. Real-host acceptance still requires exercising distinct OS identities, Wine prefixes, portable terminals, displays and live-process attestations on the actual host; the 3 GiB per-slot memory floor and Azure Students constraints remain enforced. A passing IaC workflow does not mean Azure has been deployed or can satisfy runtime requirements.
- **LIVE:** server-enforced and blocked on ephemeral hosts; not enabled. No LIVE order or account acceptance is claimed.
- **APK:** the current branch app source commit `f63a9f5980340d4d3dad917e50527e12fec76687` completed Build APK run [44509927948](https://github.com/destinedai/DESTINEDFX-ANALYZER5/actions/runs/44509927948). Artifact `DestinedFX-debug-apk` ID `14521437968` contains `app-debug.apk`. Verified package: `com.aistudio.destinedfx.qwvzxp`; version `1.0.13` (code `13`); min SDK 24; target SDK 36; ABIs `arm64-v8a` and `x86_64`; APK v2 signature verified. Signing certificate SHA-256: `82fc416ba463d7c4a174094d00b2131fbdaa3c82b5220b4c94f8970f124d338a`. Extracted APK SHA-256: `9ed5922a6881f563228d337b96a1990e4044f97efbe226c2359d581df51e55e2`. Artifact ZIP SHA-256: `96c10614dbcef299675e6a8db6b3642bd51c6ad0760b13276b18859e3de33d99`. This is a debug APK, not a production-signed release.
- **CI evidence for current app source commit:** 402 Android JVM tests executed with 0 failures; APK assembly, signature, package/version, target/min SDK, ABI and checksum verification passed. Python/Bridge/managed-worker/EA tests, backend API and PostgreSQL migration/readiness tests, Azure IaC validation, secret scanning, genuine MetaEditor EA compilation and Windows worker-kit packaging also passed. The clean API 34 emulator instrumentation job was still in progress when this document was refreshed, so the current source has not yet been credited with that final runtime check. Separate Codespaces deployment diagnostics, Wine guard regression tests and official MetaQuotes installer-pin verification for the same head passed. These CI checks do not prove physical-phone installation, a live host or broker session.

## Owner-side acceptance still required

1. Install the debug APK on the physical Android phone and verify startup, encrypted storage, broker navigation/login switching, Bot workspace layout, and connection/error states. Report only redacted diagnostics.
2. Start the actual 2-core Codespace on `free/phone-only-v1`; run `deploy/codespaces/start.sh`, `first-run-check.sh`, and the documented public-readiness checks. Confirm the real MT5 terminal opens without the debugger warning. Do not expose the VNC port or share URLs/tokens.
3. Complete the Android Bot UI review against MT5 Android feature-by-feature: Quotes, Charts, Trade, History, account metrics, positions and pending orders, modification/closure, indicators/timeframes, Market Depth where supported, news, alerts, messages, and settings. Appearance alone is not parity evidence.
4. Perform owner-side Linux slot acceptance on the actual Codespace: verify at least two separate DEMO pairings receive distinct OS UIDs, slot roots, Wine prefixes, portable terminal trees, Xauthority files and Xvfb displays; confirm the root agent verifies each running terminal PID/start-time; exercise revoke/re-enrolment and prove one user's lifecycle cannot stop or inspect another user's terminal. The legacy single-terminal systemd worker is not evidence of this acceptance.
5. On an owner-controlled DEMO account, complete broker login, identity-matched fresh EA heartbeat, quote verification, one explicitly approved DEMO order, broker deal/ticket evidence, SL/TP, close, history reconciliation, restart/reconnect, and duplicate-suppression checks. No broker credentials are requested in chat or stored in the repository.
6. Keep production deployment, persistent-host acceptance, management-to-Bridge trust configuration, production signing, and any reconsideration of LIVE separate from this DEMO/test work. Do not merge or mark the PR ready solely because CI is green.


## Live Codespaces two-user runtime acceptance

The offline CI suite validates the isolation code and its failure guards; it does not establish that two real MT5 terminals are running as separate operating-system users on the owner's Codespaces host. A read-only runtime audit is now provided at `scripts/linux-host/accept_two_user_runtime.py`.

After two different broker pairings have each been provisioned as DEMO and have reached `READY` on the actual Codespaces host, run from the checked-out repository:

```bash
sudo -E python3 scripts/linux-host/accept_two_user_runtime.py --expected-users 2
```

The script fails closed unless it can verify at least two READY slot manifests; distinct pairing/job IDs, Unix usernames and UIDs, Wine prefixes, portable terminal trees, Xauthority files, Xvfb displays and real process identities; root-agent reservation checks; per-slot ownership and restrictive permissions; denial of cross-user Xauthority reads and writes; removal of startup credential files; and a healthy loopback host-agent report. It emits sanitized JSON and does not print account identifiers or credentials.

This audit is strictly read-only: it does not create users, launch or stop MT5, revoke pairings, modify host files, or submit trades. It must be run on the real Codespaces host while both DEMO sessions are running. A passing unit test or hosted GitHub runner cannot substitute for that live-host result. Android API 34 instrumentation and the captured Bot UI screen evidence remain separate gates; screenshots alone do not prove MT5 Android feature parity.
