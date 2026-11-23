# Persistent Linux host acceptance checklist

This checklist is for an owner-operated Ubuntu x86-64 host. It is separate from source tests and GitHub Actions fixtures. No checklist item is complete until evidence is collected from the actual owner host. Do not paste passwords, bearer tokens, raw pairing manifests, database contents, private endpoint names, Xauthority contents or private signing keys into the evidence report.

## Evidence levels

| Evidence level | What it establishes | What it does not establish |
|---|---|---|
| Source-code tests | Isolation, capacity, lifecycle and failure-path logic under controlled fixtures | Real Unix users, Wine processes, Xauthority permissions, systemd behavior or broker connectivity |
| CI fixture tests | The exact commit passed automated Python, Bridge, worker, Android and source-contract suites | A deployed host, real account pairing, broker login or a real order |
| Host installation verification | A specific Ubuntu host has expected packages, encrypted credentials, systemd units, private listeners and persistent data paths | Successful concurrent MT5 terminals or account acceptance |
| Real two-user isolation acceptance | Two actual paired DEMO terminal processes run under distinct OS identities with independent Wine/terminal/Xauthority/display state, with cross-user Xauthority access denied | Broker order acceptance, DEMO soak or LIVE authorization |
| Broker DEMO acceptance | Genuine broker heartbeats, exact account identity, quotes, order fill, reconciliation and safe close on the accepted host | LIVE authorization or production release by itself |

The Linux isolation and worker tests use controlled fixtures, including fake sudo/process/display behavior where identified. They are regression tests, not OS-level acceptance. The accept_two_user_runtime.py command queries the local Bridge configuration and checks actual private filesystem state, OS identities, terminal/Xvfb processes, root-agent attestations and cross-UID permission boundaries. It is the owner-host evidence step.

## 1. Verify the installed candidate and host

- [ ] Record the full repository commit SHA installed on the host and confirm it matches the reviewed candidate.
- [ ] Confirm Ubuntu 22.04 or 24.04 x86-64, systemd 250+, supported Wine version, and genuine x86-64 MetaTrader 5 terminal and MetaEditor.
- [ ] Confirm the reviewed hash of the official MT5 Linux installation wrapper before installing or updating the clean, unpaired template.
- [ ] Confirm at least two CPU cores and 8 GiB RAM for two concurrent slots. Current static admission reserves 2 GiB for the host plus 3 GiB per configured terminal; the worker also pauses new claims below 3 GiB currently available RAM per slot.
- [ ] Confirm available disk space exceeds the host-agent 2 GiB minimum and has headroom for two terminal copies, Wine prefixes, logs and backups.
- [ ] Confirm separate DNS names for Bridge and Management API resolve to the same owner-controlled host. Caddy is the only public ingress; Bridge, Management API, host agent, PostgreSQL, Wine displays and terminal ports remain loopback/private as documented.
- [ ] Confirm /run is tmpfs and systemd encrypted credentials exist for their respective services. Do not display credential values.

The resource checks are admission and readiness guards. They do not configure a hard CPU or memory cgroup quota for each individual terminal. Do not describe the current worker as providing per-terminal cgroup enforcement.

## 2. Verify service and privilege boundaries

Run sanitized service-status commands on the owner host:

    sudo systemctl is-active       destinedfx-host-agent.service       destinedfx-bridge.service       destinedfx-management-api.service       destinedfx-linux-slot-worker.service       destinedfx-postgres.service       destinedfx-caddy.service

    sudo systemctl is-enabled       destinedfx-host-agent.service       destinedfx-bridge.service       destinedfx-management-api.service       destinedfx-linux-slot-worker.service       destinedfx-postgres.service       destinedfx-caddy.service

- [ ] Confirm host agent and Linux slot worker run under the documented root-owned services with encrypted host-agent/worker credentials; do not copy their credential files.
- [ ] Confirm Bridge runs as destinedfx, Management API as destinedfx-api, and Caddy as caddy.
- [ ] Confirm only the isolated slot worker has the /etc write exception needed for atomic useradd/userdel account updates. Do not widen it to Bridge, Management API, host agent or Caddy.
- [ ] Confirm the slot worker runs with BRIDGE_HOST_PROFILE=PERSISTENT and BRIDGE_HOST_MODE=linux-host.
- [ ] Confirm DESTINEDFX_SLOT_MAX_TERMINALS is within verified CPU/RAM capacity.
- [ ] Confirm host agent reports healthy Wine, isolation roots, disk and memory. Its response must not contain broker credentials or private pairing data.
- [ ] Confirm no legacy shared-terminal Linux service or unowned MT5 terminal is running beside the per-pairing worker.

The slot-worker systemd unit uses KillMode=process so a supervisor stop/restart does not implicitly kill all child terminal processes. This is intentional for managed session recovery, but it makes worker/terminal reconciliation important: do not assume a supervisor restart has stopped or re-created an MT5 process. Use PID plus process start ticks, UID, Wine prefix, terminal path, display and Xauthority evidence to distinguish the same process from a reused PID.

## 3. Verify persistence, recovery and stale-session handling

- [ ] Confirm Bridge SQLite and PostgreSQL are on their documented persistent local paths.
- [ ] Confirm destinedfx-bridge-backup.timer and destinedfx-management-postgres-backup.timer are enabled and have completed at least one successful run.
- [ ] Test restoration of a Bridge SQLite backup into a scratch location and a PostgreSQL backup into a separate scratch database. Never overwrite a live database during a restore drill.
- [ ] Confirm encrypted credential backups and the systemd credential host key are stored separately from the host/disk. Same-disk backups do not cover disk loss.
- [ ] Reboot the host during an owner-supervised maintenance window with no active broker operation. Confirm systemd restarts expected services and does not create duplicate slot users, Xvfb displays or terminal processes.
- [ ] Inspect stale worker/terminal records using sanitized host diagnostics. PID alone is not identity proof: process start ticks, Unix UID, terminal path, Wine prefix, display and Xauthority must match.
- [ ] Confirm a host stop/restart request is refused when terminal/account state is unknown. Do not kill or erase a terminal/session to clear a stale marker.
- [ ] Confirm daily backups, disk monitoring, OS security updates and an external recovery-copy plan remain operational.

## 4. Run the real two-user isolation acceptance

This command must run on the persistent owner-operated Ubuntu host, not Codespaces, a developer laptop fixture, Windows or CI. First create two different authenticated DEMO pairings using the normal Android/Bridge flow and wait until each real terminal has a fresh EA heartbeat and worker READY state.

    sudo /opt/destinedfx/venv/bin/python       /opt/destinedfx/scripts/linux-host/accept_two_user_runtime.py       --expected-users 2       --owner-uid "$(id -u destinedfx)"       --state-dir /var/lib/destinedfx/host-runtime       --slots-root /home/destinedfx-slots       --bridge-url http://127.0.0.1:8000       --host-agent-url http://127.0.0.1:8787/health/ready

The command is read-only. Before filesystem inspection it queries the local loopback Bridge /health/config and refuses acceptance unless that live service reports host_profile=PERSISTENT, linux_host_mode=true, root-attested VERIFIED per-user and multi-user isolation, and at least two active and configured slots. It then verifies private manifests, non-login UIDs, unique slot roots, Wine prefixes, terminal copies, Xauthority files and displays, actual MT5 process PID/start-time/UID, root-agent reservation evidence, cross-user Xauthority read/write denial, removal of temporary startup credential artifacts, and host-agent readiness.

- [ ] Preserve the raw JSON output in the owner-controlled release evidence store.
- [ ] Confirm accepted=true, at least two unique OS UIDs, distinct slot users, real MT5 PID/start-time verification, root reservation attestation and cross-user Xauthority denial.
- [ ] Confirm the report identifies persistent Linux host profile and slot capacity as verified.
- [ ] Confirm the command rejects EPHEMERAL, Windows/non-Linux mode, missing root attestation and insufficient capacity.
- [ ] Record candidate SHA, host OS release, UTC timestamp and sanitized result. Do not record login numbers, server names, pairing IDs, passwords, tokens, Xauthority contents or private URLs.

A CI pass of test_linux_host_acceptance.py proves only that the validator rejects bad configuration fixtures. It does not produce or replace this real-host result.

## 5. Separate broker DEMO acceptance

Only after the two-user OS isolation check passes:

- [ ] Pair two genuine broker DEMO accounts through the normal authenticated app path.
- [ ] Confirm exact login, server, broker company, account mode, currency, MASTER/INVESTOR permission and profile identity for each pairing.
- [ ] Confirm fresh quotes/candles and fresh authenticated EA heartbeat for each account.
- [ ] Complete one owner-supervised DEMO market-order lifecycle: pre-trade review, broker acceptance/fill, returned ticket/deal, position reconciliation, history reconciliation, controlled close and restart/recovery.
- [ ] Confirm duplicate request protection, 20-second command expiry, 15-second heartbeat stale gate, news/risk limits and emergency-stop acknowledgement.
- [ ] Preserve redacted broker evidence with timestamps, artifact hashes and final candidate SHA.

LIVE remains a separate owner-authorized acceptance stage and is not part of this offline checklist. Do not arm LIVE, submit live orders or remove release gates to complete this document.

