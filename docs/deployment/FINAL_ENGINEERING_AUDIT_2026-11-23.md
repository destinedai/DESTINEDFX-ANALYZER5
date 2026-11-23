# DestinedFX Analyzer 5 — engineering audit and acceptance ledger

**Audit baseline:** 234523c36e0799a93a22d02eeee5417f3c5e9d06  
**Repository:** destinedai/DESTINEDFX-ANALYZER5  
**Branch:** feat/self-hosted-linux-host-runtime  
**Date:** 2026-11-23

This document separates source implementation, automated evidence, emulator evidence and owner-operated external acceptance. It is not a penetration test or production approval.

## Evidence completed at the audit baseline

- Build APK workflow 45201228383: successful. Python, backend/PostgreSQL, Gitleaks, Azure/Bicep, MetaEditor EA compile, Windows worker kit, broker DNS/TCP probe, Android JVM/APK, API 34 emulator and locked Codespaces bootstrap jobs passed.
- Android JVM: 451 tests executed, 0 failed.
- Android API 34 emulator: 9 instrumentation tests executed, 0 failed, 0 skipped.
- Codespaces diagnostics workflow 45201228430: offline contract, deployment-shell syntax, source audit, Bridge probe, MT5 diagnostics and Linux/Wine regression passed.
- Broker validation was DNS/TCP only. No broker account was authenticated and no order was submitted.
- Host-v1 prerelease packaging remains hard-disabled. CI fixtures cannot replace actual owner-host acceptance.

These results apply to the audit baseline SHA, not automatically to a later commit.

## Linux host runtime

**Implemented in source:** Ubuntu 22.04/24.04 x86-64 installation/bootstrap, systemd service layout, encrypted systemd credentials, host agent, root-attested per-pairing Unix identities, Wine/terminal/Xauthority separation, readiness and diagnostic scripts, and a real-host two-user acceptance command.

**Automated-test evidence:** offline regression tests cover private modes/ownership, symlink and hard-link rejection, path binding, terminal process identity, persistent-vs-ephemeral LIVE guard and cross-user file permission probes. The audit baseline Linux host acceptance suite reported 103 passed.

**Not verified:** clean installation on an actual owner Ubuntu host, upgrade of an installed stack, service restart/shutdown/recovery with active users, two real simultaneous isolated terminal processes, and real MT5 terminal/broker heartbeat.

## Android and encrypted persistence

**Implemented in source:** Compose application navigation, SQLCipher-backed Room database when Android Keystore storage is available, in-memory read-only fallback when secure storage is unavailable, validated legacy plaintext migration, non-exported internal services/receiver/provider, disabled app backup, and API 34 emulator instrumentation.

**Automated/emulator evidence:** JVM suite 451 passed; API 34 emulator suite 9 passed. Instrumentation covers packaged Python runtime and encrypted database copy/reopen tests.

**Confirmed security gap:** TradeEntity and AnalysisEntity do not record the authenticated Supabase user ID; their DAO read methods return unfiltered records. The application database is a process-wide singleton and logout does not partition or clear records by authenticated owner. SQLCipher is encryption at rest, not row-level user authorization. Older records have no trustworthy owner identifier, so a migration must quarantine unowned rows rather than assign them to the next signed-in user. A schema migration, repository-wide owner scoping, lifecycle/account-switch behavior and two-user Android regression tests are still required.

**Not verified:** physical Android installation, rotation/process-death/background/foreground acceptance on an owner device, supported-version matrix, accessibility review and an actual in-place upgrade.

## GitHub Device Flow

**Implemented in source:** public Client ID supplied by an Android user or public build variable, no client secret in APK, one-time device code, exact GitHub verification URL validation, polling and slow_down, expiry/denial/disabled states, encrypted access/refresh token storage, refresh rotation, expiry clearing, repository/ref-bound Codespaces selection and explicit cancel/disconnect UI.

**Additional regressions added in the audit commit:** device-code expiry, authorization denial, slow_down, coroutine cancellation and network disconnect must all leave access/refresh tokens empty. These become verified only when the exact-head CI run passes.

**Not verified:** approval and pairing on a physical phone using the owner's actual GitHub App.

## APK signing and artifact provenance

The build workflow runs APK builds in separate jobs. In the audit baseline run, DESTINEDFX_DEBUG_KEYSTORE_B64 was absent; the Android JVM job generated signer 74d2e5d2… while the Codespaces bootstrap job generated signer d7c69e1d…. The bootstrap and independent standard APK built within the same job shared the latter signer. This is the documented fresh-install-only fallback, not stable signing.

The signing consistency checker added in the audit commit compares all three exact-run APKs, their real byte sizes/digests, metadata sidecars, source SHA, package/version/SDK/ABI contract and certificate fingerprints. When the stable keystore secret is configured, signer disagreement is a CI failure. Without it, the report explicitly says fresh-install-only. It never claims an upgrade test.

**Not verified:** actual Android in-place update/upgrade. The selected APKs are debug-only, not production release artifacts.

## Management API and Bridge configuration

The baseline CI conditionally validates public HTTPS origin syntax, Management API /ready and public EdDSA issuer/audience when configured, and Bridge /health/ready when configured. In the baseline run, MANAGEMENT_API_ORIGIN and MT5_MANAGED_SERVICE_URL were not configured; those conditional steps were skipped/not configured, not passed. This remains the honest state until a real owner endpoint is supplied.

## Feature parity — current scope

| Capability | Audited status |
|---|---|
| Account identity, mode, quotes, telemetry and history | Implemented behind freshness/identity checks; external broker acceptance not verified |
| Pending-order snapshot and cancellation | Implemented |
| Pending-order placement and general modification/expiry | Not implemented |
| Phone market order | Constrained explicit-confirmation PLACE_MARKET_ORDER exists for the first EA-configured symbol on a persistent host; real broker acceptance not verified |
| Position close / close-all | Implemented with snapshot/confirmation gates; broker acceptance not verified |
| Position SL tightening | Implemented; strictly tighten-only, preserves TP |
| SL widening/removal and TP editing | Not implemented |
| Analyzer charts/timeframes/indicators | Analyzer/provider functionality exists; not terminal-native MT5 chart parity |
| Native MT5 chart/timeframe and attached indicator controls | Not implemented/verified |
| Market Depth / DOM | Not implemented/verified |
| Terminal-native MT5 mailbox/news | Not implemented; economic-calendar guard is separate |
| cTrader order placement | Not implemented |

A visually similar control, source contract, queued request or mocked response is not proof of broker feature parity.

## Release blockers

1. Add user ownership to all Android-local user data; scope every query/mutation and quarantine legacy rows with unknown ownership.
2. Execute clean install/upgrade/recovery on an actual persistent Ubuntu host.
3. Pass real two-user OS isolation acceptance using the host's real active slots.
4. Install and pair on a physical Android device.
5. Complete owner-supervised DEMO broker identity, heartbeat and order lifecycle/recovery acceptance.
6. Configure and verify the actual public Management API and Bridge origins.
7. Provision a stable non-production debug signing key for repeatable debug updates; separately configure production signing only through protected owner-controlled secrets.
8. Keep host-v1 prerelease and production release disabled until owner acceptance evidence and independent review exist.

**Final disposition:** engineering task is only partially complete until the local Android ownership gap and exact-head CI are resolved. Production release remains **HOLD**.
