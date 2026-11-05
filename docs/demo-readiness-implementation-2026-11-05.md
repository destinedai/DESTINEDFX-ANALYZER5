# DESTINEDFX DEMO readiness implementation milestone

**Work branch:** `integration/complete-demo-readiness`
**Starting SHA:** `fc6e7c20a1dbfee29275f94ae4fe366a3cb309e0`
**Scope:** DEMO execution only. LIVE remains locked and server-side blocked.

## Implemented in this milestone

### MQL5 persistence and recovery

- Replaced journal writes with explicit UTF-8 byte serialization using `FILE_BIN`, including removal of the conversion NUL, exact byte-count checks, flush/close, binary readback, round-trip byte comparison, and deliberate empty-file handling.
- Added verified journal recovery from `.bak` and validated `.tmp` files for token, cursor, processed-command, signal-deduplication, operation, and order-state journals. Invalid or malformed state now stops the EA and exposes manual-recovery status instead of silently resetting state.
- Kept a verified backup through successful replacement; a failed replacement attempts recovery without deleting the only verified copy.
- Made periodic `PersistOrderState()` failures fail closed in `OnTimer()`.
- Preserved durable order intent before broker submission and unresolved-order behavior; no broker request is submitted when intent persistence cannot be verified.

### Windows worker and installer

- The owner installer now accepts and validates separate genuine `terminal64.exe` and `metaeditor64.exe` paths for FBS, EXNESS, HFM, and DERIV.
- It writes a broker-keyed `broker_terminals` TOML configuration and asks the owner for a measured safe simultaneous DEMO-terminal capacity rather than claiming capacity from UI account slots.
- Re-running after an interrupted setup reuses the existing DPAPI-protected Fernet key and worker token when they decrypt and validate under the same Windows user; it does not rotate secrets or delete the database/account trees.
- Existing loopback-only bridge and private Tailscale Serve constraints are retained; no public tunnel or Funnel path was added.

### Tests and documentation

- Updated the EA source contracts for the binary journal contract and added installer contracts for resume, four mappings, measured capacity, and private networking.
- Added this implementation report with the verification matrix and explicit external blockers.

## Verification matrix

| Area | Result | Evidence / limitation |
|---|---|---|
| Python bridge tests | PASS | `python3 -m pytest -q bridge` |
| Python worker tests | PASS | `python3 -m pytest -q managed-worker` |
| EA source contracts and safety math | PASS | `python3 -m pytest -q mt5-ea` |
| Combined available Python/source suite | PASS | 180 passed |
| Python bytecode compilation | PASS | bridge, quantitative, worker modules compile |
| Android JVM tests | BLOCKED_EXTERNAL_INPUT | Gradle resolved, but the sandbox has no Android SDK: `SDK location not found`; requires `ANDROID_HOME` or `local.properties` on a build host |
| Android instrumentation tests | BLOCKED_EXTERNAL_INPUT | Requires Android emulator/device runtime not available in this sandbox |
| Genuine MetaEditor EA compilation | BLOCKED_EXTERNAL_INPUT | Requires Windows MT5 installation and genuine `metaeditor64.exe` |
| MQL5 journal runtime self-test | BLOCKED_EXTERNAL_INPUT | Requires genuine Windows desktop MT5 terminal; Strategy Tester WebRequest is not sufficient |
| Windows PowerShell execution validation | BLOCKED_EXTERNAL_INPUT | Requires Windows PowerShell, DPAPI CurrentUser context, Tailscale, and genuine broker terminals |
| Real broker DEMO acceptance | BLOCKED_EXTERNAL_INPUT | Owner must provide FBS, Exness, HFM, and Deriv MT5 DEMO profiles and explicitly authorize any order test |

## Broker acceptance table

All four broker execution gates remain `BLOCKED_EXTERNAL_INPUT` in this sandbox. No credentials were entered, no terminal authenticated, no broker identity was verified, and no order or fill was claimed.

| Broker DEMO | Terminal/profile | Authentication and identity | Worker/EA heartbeat | Order/fill/recovery |
|---|---|---|---|---|
| FBS DEMO | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT |
| Exness DEMO | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT |
| HFM DEMO | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT |
| Deriv MT5 DEMO | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT | BLOCKED_EXTERNAL_INPUT |

## Required owner actions

1. On the authorized Windows owner PC, run the installer under the original Windows user, provide the actual four terminal installation paths, the actual EA source path, and a measured safe terminal capacity.
2. Confirm exact assigned MT5 login, server, company, currency, DEMO mode, and MASTER trading permission for each broker profile. Never substitute a brand label for terminal-reported identity.
3. Add only the exact private Tailscale Serve HTTPS origin to each terminal's MT5 WebRequest allowlist.
4. Compile the EA with the genuine MetaEditor, run the journal self-test EA with trading disabled, and retain logs showing binary round-trip, backup recovery, corrupt-state fail-closed, and restart cases.
5. Run the controlled DEMO acceptance procedure separately for each broker. Before any order, explicitly review and authorize the proposed symbol, direction, volume, entry, SL, TP, and maximum risk; then verify broker-visible execution, protection, history, app reconciliation, STOP behavior, and restart recovery.

## Verdict

`NOT_READY`

The code-level milestone and available automated checks pass, but genuine MetaEditor compilation, MQL5 runtime persistence testing, Windows installer execution, Android instrumentation, and all broker DEMO acceptance gates remain external and unverified. This report does not claim production readiness, LIVE readiness, a connected broker, a submitted order, a fill, or 24/7/offline hosting.
