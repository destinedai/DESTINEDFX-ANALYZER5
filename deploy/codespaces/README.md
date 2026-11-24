# DestinedFX inside GitHub Codespaces — DEMO only

**Owner workflow: GitHub Codespaces only. No separate computer, VPS, DNS setup or manual port-forwarding step is part of this DEMO path.**

Open the [DestinedFX branch in GitHub](https://github.com/destinedai/DESTINEDFX-ANALYZER5/tree/feat/self-hosted-linux-host-runtime), choose **Code → Codespaces → Create codespace on feat/self-hosted-linux-host-runtime**, and wait for the devcontainer to finish its automatic setup. The devcontainer installs the pinned Ubuntu/Wine/Android prerequisites, installs and verifies the official MetaTrader terminal/MetaEditor/EA template, and runs its post-start hook on each Codespace open.

## One command

From the repository root in the Codespaces terminal:

```bash
bash deploy/codespaces/start-demo.sh
```

This command reuses the existing PID-attested Codespaces supervisor. It starts or adopts the Bridge, Management API with local PostgreSQL, root host agent, isolated Linux MT5 slot worker and verified terminal template. It waits for local service health and the actual GitHub-forwarded HTTPS endpoints, then prints **PASS: Codespaces DEMO host is ready** and the two verified origins. If it cannot verify both public endpoints, it fails instead of reporting a usable host.

The same host supervisor is also launched automatically by `.devcontainer/devcontainer.json` when the Codespace opens. Its background post-start invocation is non-blocking; the command above is the owner-facing readiness check and safe restart/recovery entry point.

## No manual port forwarding

The devcontainer automatically forwards source ports **8000 (Bridge)** and **8010 (Management API)**. Port 6080 remains private and is not part of phone pairing. Ports 8000/8010 start private. The Codespaces startup script changes them to public only after all of these checks pass:

- Bridge K3 hardening tests and the code-hash marker pass.
- Bridge HTTPS health succeeds, and both invalid-token and missing-token requests are rejected.
- Management API HTTPS health and PostgreSQL readiness succeed.
- The Management API publishes the expected EdDSA verification key.
- GitHub reports the exact forwarded port inventory, URLs and public visibility for this Codespace.

The manifest is generated from GitHub's live port inventory and the Codespace forwarding-domain environment variable. The app discovers those endpoints itself; do not paste a URL into APK settings and do not run `gh codespace ports visibility` yourself. If any verification fails, the startup path returns both service ports to private and the readiness command reports the failure.

## Connect the DestinedFX phone app

1. Install the **DestinedFX DEBUG APK — FOR DEMO TEST ONLY** from the successful Build APK workflow artifact.
2. In the Bot workspace, open **CODESPACES HOST · PHONE CONTROL**.
3. Tap **AUTHORIZE GITHUB** and approve the one-time device request at GitHub. The app polls approval and stores its expiring GitHub App token in Android encrypted storage. This is owner authorization; it is not a broker credential.
4. Tap **START HOST**. The app starts/reuses the Codespace on `feat/self-hosted-linux-host-runtime`, discovers port 8000 and port 8010, verifies Bridge configuration/EPHEMERAL profile and Management API readiness, then saves the Bridge origin automatically.
5. Choose the broker DEMO profile and enter the exact DEMO login, password and broker-issued server. Wait for MT5 to report the matching account identity and a fresh authenticated EA heartbeat before treating the pairing as ready.

The app's **START HOST** action and the terminal command use the same Codespaces lifecycle; neither launches a second Bridge or worker. The phone never sends its GitHub token to the forwarded Bridge.

## What this DEMO host proves

The Codespace is an **EPHEMERAL, supervised DEMO test host**, not an always-on execution service. The Bridge is durable only for the lifetime of this Codespace and its preserved state volume. The worker provisions isolated per-pairing Unix identities, Wine prefixes and terminal data. A running process, public health response, saved Bridge origin or generated pairing code is not proof of broker authentication or an executed order. A real DEMO login, identity-matched terminal snapshot, fresh EA heartbeat, broker-reported order result and reconciliation are separate acceptance checks.

LIVE needs persistent host, Codespaces = DEMO ONLY

The Codespaces supervisor keeps the existing safe stop/backup checks and the two-minute idle policy. GitHub's own Codespaces timeout and monthly included quota still apply; this is not an unattended 24/7 host.
