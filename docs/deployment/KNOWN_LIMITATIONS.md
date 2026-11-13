# Known limitations and remaining work

## Product and execution

- DEMO and LIVE execution is an owner requirement, and mode-policy code exists. No broker path is verified against a genuine account or terminal in this source review.
- The generic `TradeUseCaseImpl` delegates only through `BrokerGateway.executeTradeWhenArmed`; the default decision denies. The current generic Deriv Forex/CFD mapping and direct Android MT5 adapter do not place orders.
- Deriv Multiplier execution has provider-specific code and checks but requires owner-run DEMO verification, acceptance evidence, fill reconciliation and device lifecycle testing.
- cTrader account authorization exists; cTrader order placement is not implemented. Spotware approval remains an owner step.
- MT5 operation depends on a configured bridge, worker, terminal, EA, identity-matched heartbeat and compatible broker settings. Windows and Linux host operation and broker-specific behavior are unverified.
- LIVE requires a matching DEMO pass, fresh identity evidence, typed confirmation, owner reauthentication, risk clearance and an unexpired lease. No real-account order was sent during development.

## Market data

- No provider endpoint or account entitlement was externally verified.
- Retrieval time does not always equal provider event time. Recent receipt is not proof of current data.
- Binance quote and book calls are separate, and its WebSocket adapter is not wired into repository stream startup.
- Provider credentials, quotas, geographic eligibility, delays and licensing are not uniformly known or enforced.
- Calendar empty responses, stale caches and upstream errors need conservative UI semantics.

## Android and persistence

- Accessibility, rotation, process recreation, privacy, retention, deletion and recovery audits remain incomplete.
- Room includes user, trade and Deriv records. Retention and migration require review.
- Service locator/global state and direct service/preferences access make component boundaries porous.
- Instrumentation tests are present; check the exact run for the restored CI emulator job result.

## Backend and deployment

- Bridge, worker and EA source are not verified end to end on an owner host.
- The root Render profile uses ephemeral storage and is not an always-on terminal host.
- Deployment requires owner infrastructure, exact broker server, protected secrets, HTTPS, backups and MT5 setup. No deployment was confirmed.
- Android Supabase auth/cloud-sync classes remain unavailable or stubbed.

## Release quality

- The restored CI defines Python/bridge/worker/EA, Gitleaks, genuine MetaEditor, Android JVM/APK, emulator and worker-kit jobs. Check each job for the exact commit.
- A debug APK is not a production release. Verify signer, source SHA, artifact and checksum independently.
- Local Android builds require a full JDK and configured Android SDK. An environment blocker is not a pass or a code failure.

## Next steps

1. Complete owner-run DEMO connection reports for Deriv, cTrader account access, and each MT5 broker path.
2. Harden MT5 symbol, fill, margin, stop, volume, commission and company-identity checks.
3. Implement cTrader trading behind a separate gateway and shared arming/risk controls.
4. Complete Android lifecycle, accessibility, privacy, secret-handling and data-retention reviews.
5. Test bridge/worker/EA recovery on owner-controlled hardware before enabling an unattended path.
