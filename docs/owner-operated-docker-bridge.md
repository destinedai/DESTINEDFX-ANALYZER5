# Owner-operated Windows host with Docker bridge and private TLS proxy

**Status:** Recipe only. No machine, Docker stack, TLS proxy, tailnet, broker terminal, paid service, or account was provisioned or changed. This setup is for one owner-selected MT5 DEMO account after explicit approval. It is not an always-on guarantee: Windows updates, power/sleep, ISP outages, and Docker/MT5 restarts can interrupt service. LIVE stays blocked.

**Important integration boundary:** the current `managed-worker/windows/install-owner-stack.ps1` and `run-owner-stack.ps1` configure Tailscale Serve and start a native Python bridge plus worker. They do **not** support the Docker/Caddy topology below. Do not run the existing installer/supervisor together with this Compose stack: the native bridge/Serve path conflicts with the Docker bridge/proxy, and the existing backup task does not back up the Compose data volume. This Phase 1 deliverable is a bridge/proxy configuration recipe only; the complete Android → Docker bridge → Windows worker → EA path remains **BLOCKED / NOT VERIFIED** until a worker-only supervised launcher and Docker-volume backup/restore integration are separately implemented and validated on Windows. The recipe must not be represented as ready for broker acceptance yet.

## Intended topology

```text
DestinedFX Android app
  │ HTTPS, private Tailscale tailnet only
  ▼
Windows host: Tailscale interface + owner-managed TLS reverse proxy (Caddy)
  │ private Docker network; proxy identity allow-listed
  ▼
Docker bridge container ── persistent local volume (SQLite/WAL)

MT5 EA/terminal and worker run on the same owner-controlled Windows PC.
```

Only the TLS proxy is reachable from the owner's tailnet. The bridge has no published host port. Do not expose it to the public Internet or LAN. Do not use Tailscale Funnel, public Cloudflare Tunnel, public unauthenticated listeners, or an unreviewed hosted service in this recipe.

## Prerequisites and licensing check

- A Windows PC that remains powered on, awake, online, and signed in; genuine MT5 terminal, MetaEditor, Docker Desktop, Git, Python and Tailscale from their official sources.
- Confirm Docker Desktop licensing for the owner's use. Do not assume a free license covers commercial use. Confirm the current Tailscale plan is eligible for the actual use; its Personal plan is described as non-commercial, while Standard is listed at $8/user/month. This recipe makes no purchase or plan change.
- An owner-administered tailnet with HTTPS certificates enabled and ACLs limited to the owner's phone and the Windows host. Use a nonsensitive device name because Tailscale certificates expose the machine's DNS name in public Certificate Transparency logs.
- A stable, BitLocker-protected data disk with a verified backup destination. Retain the bridge data volume, worker account directories, DPAPI secret file, and exact project checkout together during backups.
- One supported DEMO broker account, identified by its actual owner-issued company/server. No account secrets are required to prepare these files.

## Repository preparation

Run PowerShell as the owner/admin account and use a stable project directory:

```powershell
Set-Location 'C:\DestinedFX\DESTINEDFX-ANALYZER5'
git status --short
```

Use a clean, reviewed Phase 1 checkout. Do not store credentials or certificate private keys in Git. Create `bridge\certs\` locally and keep it excluded from commits. The `bridge/Dockerfile` builds the API service; the container requires both `app.py` and `quantitative.py` in the image.

## Private hostname and TLS certificate

1. Record the host's current Tailscale IPv4 address (`tailscale ip -4`) and its exact MagicDNS name, such as `windows-host.tailnet-name.ts.net`. Use that exact DNS hostname in the Android bridge URL and the MT5 WebRequest allowlist.
2. Enable HTTPS certificates for the tailnet and generate the host certificate/key using the official `tailscale cert <exact-hostname>` command. Copy the generated files to `bridge\certs\bridge.crt` and `bridge\certs\bridge.key`; restrict the key ACL to the installing owner and local administrators. The hostname is publicly recorded in Certificate Transparency; the service itself remains private only if the tailnet and firewall policy are correct.
   File-based Tailscale certificates expire after 90 days and are not automatically installed/renewed for Caddy. The owner must renew the certificate before expiry, replace both files securely, and reload/restart Caddy; no renewal automation is configured here.
3. Use a Caddy TLS reverse proxy configured with that certificate. Bind the published HTTPS port to the Windows host's Tailscale interface address, not `0.0.0.0`. If Docker Desktop cannot bind to the Tailscale address, stop and configure a narrowly scoped Windows firewall rule that permits inbound TCP/443 only from the owner-approved tailnet address range and the local Tailscale address. Validate a request from a non-tailnet network is denied before entering any account secret. If that isolation cannot be demonstrated, do not continue.
4. Configure tailnet ACLs so only the intended phone/user can reach the Windows host on TCP/443. Do not rely on DNS obscurity as access control.

## Docker Compose and proxy configuration

Use `bridge/compose.owner.yaml` and `bridge/Caddyfile.owner` supplied alongside this recipe. Review both files before start. The Compose configuration publishes only the TLS proxy, pins its source IP on a dedicated bridge network, mounts SQLite data persistently, and does not publish the bridge's port 8000. The bridge trusts forwarded HTTPS only from that proxy IP. If the local `172.30.250.0/29` subnet overlaps an existing Docker/VPN route, select an unused private subnet and change the Compose subnet plus both static addresses and `TRUSTED_PROXY_IPS` together.

Create the local Compose environment from the protected host secret store; never copy credentials into a tracked `.env`, the Compose YAML, PowerShell command arguments, or this document. Existing `%ProgramData%\DestinedFX\config\owner-secrets.dpapi` is protected with Windows DPAPI for its installing user and contains the Fernet key and worker token for the native owner stack. The Docker daemon/container must receive those values at runtime, so the host's Docker administrator and process environment are within the trust boundary. Use a restrictive, non-synced local environment file outside the repository with an ACL for the service owner/admins, protected by BitLocker, or a reviewed launcher that obtains values from the owner's secret store. Delete any transient plaintext file immediately after use and never put it in backups or logs. Do not paste broker credentials here.

The Compose environment must set:

- `BRIDGE_BASE_URL=https://<exact-windows-host>.ts.net` (no path/query/fragment)
- `CREDENTIALS_FERNET_KEY` and `WORKER_TOKEN` from the host secret store, not source control
- `BRIDGE_DB_PATH=/data/bridge.sqlite3`
- `ALLOW_LOOPBACK_HTTP=false`
- `TRUSTED_PROXY_IPS=172.30.250.2` (or the exact fixed Caddy container IP selected)
- only owner-approved provider URLs/tokens, if required for current bridge readiness; all provider secrets stay server-side

The bridge Dockerfile must use an unprivileged user, a persistent `/data` mount, and no direct host port. Caddy must forward `X-Forwarded-Proto: https`, enforce the exact hostname, and cap request bodies at or below the bridge's 64 KiB limit. Uvicorn's proxy-header trust list must contain only the fixed Caddy address; never use `*` or trust all container peers. Do not enable `ALLOW_LOOPBACK_HTTP` to work around a proxy configuration error.

Start only after reviewing the env and routing. The example below assumes a protected owner-only file at `%ProgramData%\DestinedFX\config\docker-bridge.env`; create it through the owner's approved secret-store workflow, give it a restrictive Windows ACL, keep it outside the repository/OneDrive, and delete any transient plaintext copy after the test window. Alternatively, set the Compose variables in the PowerShell process directly from a protected secret store.

```powershell
Set-Location 'C:\DestinedFX\DESTINEDFX-ANALYZER5\bridge'
docker compose --env-file "$env:ProgramData\DestinedFX\config\docker-bridge.env" -f compose.owner.yaml config --quiet
docker compose --env-file "$env:ProgramData\DestinedFX\config\docker-bridge.env" -f compose.owner.yaml up -d --build
docker compose --env-file "$env:ProgramData\DestinedFX\config\docker-bridge.env" -f compose.owner.yaml ps
```

Keep authorization headers and request bodies out of Caddy, Docker, and application access logs. Verify externally from the owner's phone on the same tailnet:

1. `https://<exact-host>.ts.net/health/live` returns HTTPS with a valid hostname certificate.
2. `/health/ready` returns ready only when durable storage and required managed-execution configuration are valid. A 503 is a safe block; do not bypass it.
3. The exact URL is unreachable from an off-tailnet device and from the public Internet. Port 8000 is not reachable from the host LAN, tailnet, or Internet.
4. Confirm the database survives a container restart and that a verified backup/restore process works before entering DEMO credentials.
5. Confirm the owner-selected MT5 terminal's Tools → Options → Expert Advisors → Allow WebRequest list contains only the exact HTTPS origin. Then use the separate [Phase 1 DEMO acceptance kits](phase1-demo-acceptance-kits.md).

A healthy bridge probe does not prove a broker login, terminal identity, EA heartbeat, fresh quote, fill, SL/TP, history, or STOP behavior. Those require the separately approved DEMO acceptance steps.

## Maintenance and recovery

- Keep the Windows PC awake during intended test windows. Schedule Windows updates/reboots outside tests; after restart, verify Tailscale, Docker Desktop, Caddy, bridge readiness, MT5 terminal, and worker heartbeat independently.
- Back up the SQLite database with an online-consistent SQLite backup procedure, plus the worker data and DPAPI ciphertext. DPAPI-protected secrets require the same Windows user profile on the original host; test restoration without printing values.
- On network loss, the app must become unavailable/stale and must not infer broker state. On recovery, reauthenticate and reconcile the account/history before permitting another action.
- To stop the service, stop the app bot first, then `docker compose -f compose.owner.yaml down` from the bridge directory. Do not remove the named data volume or DPAPI secret file as a troubleshooting shortcut.
- Stop and revoke the worker/app pairing before changing proxy identity or host identity. Preserve the exact MT5 server and broker brand record; never guess them after restore.

## Cost comparison for later choice (checked 2026-11-06)

Planning figures only, USD before taxes/FX, optional backups, electricity, broadband, and any plan/account eligibility. No plan or service is approved or purchased.

| Option | Estimated recurring cost | Trade-offs / boundaries |
|---|---:|---|
| Existing Windows PC + Docker bridge + Caddy + private tailnet | No new compute bill if the owner already has the PC, connection, and eligible tools; electricity, broadband, backup media, and support are not priced here. Tailscale Personal is listed at $0 for non-commercial individual use; Standard is $8/user/month if needed/eligible. Docker Desktop licensing must be checked separately. | Preferred Phase 1 recipe. Owner handles uptime, Windows patching, storage/restore, and terminal maintenance. No always-on promise; do not proceed if private TLS/firewall isolation or policy eligibility is unclear. |
| Small Windows VPS (MassiveGRID Growth, 2 vCPU/4 GB) | $9.58/month compute + $6.31/vCPU/month Windows licensing × 2 = **$22.20/month** with the permanent licence; provider also lists a 180-day evaluation licence at $0, which is not a permanent production licence. | Candidate only for later review. Backups, extra storage, tax, availability and fit remain separate. No provisioning authorized. |
| Small Windows VPS (Raff, 2 vCPU/4 GB) | **$37.99/month** recurring: $22.99 compute + $15 Windows Server Standard SPLA; backups/extra storage may add cost. | Simpler published Windows-included price; provider describes its plans as VPS/forex hosting. Validate exact sizing, region, terms, and recovery before considering. |
| Hosted MetaApi service | Regular subscription **$30/month** or Extended **$100/month**, plus API access billed pay-as-you-go; the official pricing page says API access pricing is temporarily unavailable. | Different third-party managed API, not this Docker bridge. Total cost cannot be estimated reliably from current public pricing. Security, data handling, broker compatibility, terms and exact usage pricing require owner review and later explicit approval. Not selected. |

The existing PC plus private proxy has the lowest new-cash cost only if the owner already has eligible hardware, network and software licenses. It trades recurring hosting cost for owner uptime and recovery duties. Revisit alternatives only after the first DEMO gate, with exact region, Windows licensing, backup and total-cost confirmation. No paid infrastructure is provisioned under Phase 1.

## Sources (official/vendor pages checked 2026-11-06)

- [Tailscale Serve: private tailnet reverse proxy, ACLs, HTTPS](https://tailscale.com/docs/features/tailscale-serve)
- [Tailscale pricing and plan eligibility](https://tailscale.com/pricing)
- [Tailscale HTTPS certificates](https://tailscale.com/docs/how-to/set-up-https-certificates)
- [Caddy `request_body` directive](https://caddyserver.com/docs/caddyfile/directives/request_body)
- [MassiveGRID Windows VPS pricing and Windows licence](https://massivegrid.com/windows-vps/)
- [Raff Windows VPS pricing](https://rafftechnologies.com/windows-server/windows-vps-pricing-explained)
- [MetaApi official pricing](https://metaapi.cloud/)
- [Docker Desktop licensing](https://www.docker.com/legal/docker-subscription-service-agreement/)

All prices and terms can change; re-check before a later owner decision. Vendor security and availability statements are not independent verification.
