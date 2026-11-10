# R1 go-live checklist

This checklist is owner-operated. A green CI run proves only the exact commit's automated checks; it does not prove a broker login, terminal heartbeat, DEMO fill, LIVE readiness, or deployment.

## Before release

- Confirm the branch head and CI run are green for the exact commit.
- Review the Android debug APK artifact and SHA-256 from that run. It is a debug build, not a production signing release.
- Review `DestinedFX-MT5-EA-ex5` and its compiler evidence. Confirm the EA was compiled by genuine MetaEditor with zero errors.
- Download and inspect `DestinedFX-Windows-Worker-Kit`; keep the owner-operated Windows host powered on, awake, connected, and signed in.
- Set the managed bridge HTTPS origin in the app at **Bot > Remote MT5 EA Bridge**. Do not put credentials or tokens in source, CI variables, issue reports, or the APK.

## Owner-side DEMO proof

- Install the APK and use **Accounts > Add account**. Select the exact broker and enter the exact broker-issued server; never guess a server name.
- Begin with DEMO. Verify the terminal-reported login, server, company identity, account mode, worker `READY`, fresh EA heartbeat, and bridge identity match.
- Accept the exact DEMO account only after fresh evidence. Verify one complete lifecycle separately: entry, SL/TP, close, history/reconciliation, restart, and emergency STOP.
- Record a redacted evidence report containing commit SHA, APK/EA/kit checksums, broker brand, account mode, server, timestamps, heartbeat age, and result. Never include passwords, tokens, or private URLs.

## LIVE gate

- LIVE is not enabled by login. It requires the existing `ModeArmingPolicy`, fresh exact identity evidence, risk/news/session checks, typed confirmation, an active process-local lease, and a persistent owner host.
- Do not attempt LIVE on an ephemeral/test host. Confirm the owner host profile, backup/recovery path, terminal process identity, and emergency-stop procedure first.
- The owner must explicitly approve any live action; this repository does not create accounts, reset passwords, submit orders, or claim external connectivity.

## Creating the prerelease artifacts

The workflow creates a prerelease only when the owner deliberately pushes a tag matching `host-v*`. It packages the APK, the genuine MetaEditor `DestinedFXTraderEA.ex5`, the Windows worker kit, and `SHA256SUMS.txt`.

```sh
git fetch origin free/phone-only-v1
git switch free/phone-only-v1
git pull --ff-only origin free/phone-only-v1
# Review the exact green CI run and commit first.
git tag -a host-v1 -m "DestinedFX host-v1 prerelease"
git push origin host-v1
```

Do not run the tag commands until the owner has reviewed the exact green CI run and deliberately approved the prerelease publication. The tag workflow is packaging only; it is not a broker or deployment proof.
