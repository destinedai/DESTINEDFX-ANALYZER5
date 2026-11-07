# Broker connection and account-data audit

**Repository:** `destinedai/DESTINEDFX-ANALYZER5`
**Branch:** `free/phone-only-v1`
**Audited HEAD:** `6c4dbeb` before the provenance change
**Scope:** phone-compatible, read-only broker foundation; no broker order was attempted.

## Repository evidence

| Component | Existing implementation | Status before this change | Required interpretation |
|---|---|---|---|
| cTrader | `CTraderOAuthClient`, `CTraderReadOnlyGateway`, protobuf definitions, callback and freshness tests | Partial | Source and unit coverage exist. No genuine authorized account or quote acceptance was run in this environment. |
| Deriv | REST account selection, authenticated WebSocket, balance/portfolio synchronization, read-only adapter, extensive safety tests | Partial | Code-level lifecycle exists. A real authenticated DEMO response and broker-side acceptance remain unverified. |
| MT5 | managed-terminal/bridge protocol, `MT5GatewayRepository`, adapter, bridge and EA sources | Partial | Phone-only operation depends on a separate terminal/bridge. Direct MT5 Android connection is fail-closed and no terminal/MetaEditor/broker acceptance was available. |
| Broker manager | `BrokerManager`, `BrokerCapabilityMatrix`, `UnavailableBrokerGateway` | Implemented, not proof of readiness | Provider routing and capability disclosure exist. A capability entry is not connection evidence. |
| Account data | `AccountMetrics` flows in Deriv/MT5 adapters | Partial | The prior contract did not carry origin, freshness, environment, or validity as a normalized value. This change adds `BrokerAccountSnapshot`. |
| Market data | Deriv, OANDA, Binance and other provider paths with source/freshness helpers | Partial | Public market data remains separate from authenticated account data; provider-specific rate-limit and delay acceptance still require evidence. |
| Android build | `.github/workflows/build-apk.yml` | Pending in this sandbox | The local read-only verifier passes. Local Gradle compilation is blocked before source compilation because no Android SDK is configured. |

## Implemented in this change

`BrokerAccountSnapshot` now requires provider ID, origin, connection status, retrieval timestamp, environment, currency, nullable account values, validity, and an optional safe error code. `isDisplayable()` only returns true for fresh `BROKER_API` data from a connected provider with finite values and an explicit currency. Missing values remain null.

The Deriv adapter derives snapshots from its authenticated account metrics and retrieval timestamp, and publishes an unavailable snapshot for every non-connected state. The MT5 adapter clears snapshot data on disconnect and treats its direct Android connection as unavailable. Preview and unsupported gateways explicitly expose `UNAVAILABLE` provenance. The risk screen now renders `--` unless the snapshot passes the provenance/freshness predicate and labels the data source.

## Capability matrix

| Provider | Public market data | Authenticated account data | Phone-only status | Evidence status |
|---|---:|---:|---|---|
| Deriv Options/Multipliers | Yes, provider-specific | Code path present; source/freshness gated | Native API path | Not verified against a genuine account in this task |
| cTrader | Read-only quote/session path present | Read-only account path present | Native Open API path | Not verified against a genuine account in this task |
| MT5 | Depends on managed terminal/bridge | Depends on authenticated terminal/bridge | Not direct Android API | Not verified; terminal and bridge acceptance required |
| OANDA | Public provider feed path | No broker execution/account gateway | Public data only in this build | Account integration not implemented |

## Verification performed

- Repository cloned from the requested GitHub repository and branch; working tree was clean at audit start.
- `python3 scripts/verify_read_only_build.py`: passed.
- `./gradlew testDebugUnitTest --stacktrace`: blocked during Gradle configuration with `SDK location not found`; no Kotlin tests were executed locally.
- No cTrader, Deriv, MT5, or OANDA credentials were used. No broker connection, order, fill, close, or live action was attempted.
- No APK artifact or successful workflow run is claimed for the post-change commit. Exact CI evidence must be collected from a workflow run whose `head_sha` matches the resulting commit.

## Known limitations and next steps

The legacy `AccountMetrics` flow remains for compatibility with existing domain code; new account UI should consume `accountSnapshot` and its displayability predicate. A future cleanup should migrate remaining account consumers and add a provider-specific error taxonomy (`timeout`, `rate limit`, `authorization expired`, `unsupported capability`, and `invalid response`) without weakening fail-closed behavior.

A configured Android SDK or GitHub Actions run is required for compilation, unit-test execution, APK packaging, signature verification, and artifact association. Real-provider verification requires separately authorized DEMO accounts and redacted evidence; source presence and green tests are not broker acceptance.
