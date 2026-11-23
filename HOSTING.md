# DestinedFX hosting policy

## Development versus production

- **GitHub Codespaces is DEVELOPMENT ONLY.** Use it for source work, CI, isolated fixtures and development-host experiments. It is not the production trading host or an always-on MT5 terminal.
- **Production execution requires an owner-operated Windows PC or Windows VPS** that remains powered on, awake, connected, signed in and running the genuine broker-issued MetaTrader 5 terminal continuously.
- The broker-reported account identity is authoritative. Use the exact account login and server string issued for that account. Do not guess server names or append ports.
- Begin with an authorized **DEMO** account. This guide does not authorize LIVE trading.

## Move from Codespaces development to a production Windows host

1. Keep Codespaces for source changes, CI and development-only experiments. Do not turn a transient Codespace into the production host.
2. Prepare a dedicated Windows PC/VPS. Disable sleep/hibernation during operation, apply security updates, enable disk encryption and restrict interactive access to the owner.
3. Install MetaTrader 5 directly from the broker. Sign in to the broker-issued DEMO account and verify the exact account number and server in the terminal.
4. Install the DestinedFX Windows worker/bridge kit from a verified CI artifact. Read the worker's Windows OWNER-SETUP.md and the kit's START-HERE.txt before running setup scripts.
5. Configure the bridge, management API, terminal worker and EA using documented owner-only environment and secret stores. Never put passwords, API tokens, bootstrap codes or private keys in source, .env.example, GitHub commits, CI output or APK BuildConfig fields.
6. Keep the bridge bound to loopback on Windows. Use the supported private Tailscale tailnet/Serve arrangement; Android and Windows must both be authorized members of the same tailnet. Do not use Funnel, an open LAN listener, raw public ports or an unauthenticated public endpoint.
7. Attach the DestinedFX MT5 EA to the intended DEMO chart. Confirm Algo Trading and the required WebRequest address in MT5 settings. Independently verify terminal online, EA heartbeat, broker identity, account mode, balance, equity, worker READY, quotes and account snapshots.
8. On Android, install only the artifact labelled **DEBUG APK - FOR DEMO TEST ONLY**. Pair the exact DEMO account, wait for a heartbeat younger than 60 seconds, verify positive broker-reported balance/equity, then test login and STOP. An HTTP 200, queued command, running process or saved credential is not proof of terminal connectivity.
9. Test order acceptance, protective SL/TP, closure, ticket and deal profit only on the paired DEMO account. Reconcile terminal-side history. A fixture, local estimate or Codespaces test balance is not broker evidence.
10. Back up the Windows worker state, terminal data, encrypted credential store and database using the owner backup procedure. Test restore before relying on it. Keep the Windows host continuously available.

## Operational limits

- A Windows host that sleeps, signs out, loses broker connection or stops its EA is **NOT READY**.
- Bridge, management API and terminal health are separate checks. All must pass before the app reports a broker pairing as READY.
- Codespaces, fake terminals and CI fixtures cannot prove production broker pairing or a real DEMO order round trip.
- No LIVE action is authorized by this guide. Keep account isolation, authentication, risk, news, quote, protective-order and emergency-stop gates.
