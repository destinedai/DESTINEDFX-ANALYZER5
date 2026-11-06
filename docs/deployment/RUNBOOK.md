# Stop and rollback runbook

**Use this during an incident.** This procedure stops new entries. It does not automatically liquidate open positions. The broker terminal is the source for checking and manually managing live positions. Keep the broker's official contact and account recovery details separately available.

## Stop everything now

1. In the app, select the affected broker and account slot. Tap **EMERGENCY STOP · STOP NEW ENTRIES** once. Confirm the bridge request and fresh EA acknowledgement in the account state. If the phone is offline or there is no fresh acknowledgement, do not assume the bot stopped.
2. On the Windows host, open the genuine broker-issued MT5 desktop terminal for that exact account. Confirm the visible login, server, and DEMO or LIVE label before acting. Disable Algo Trading and remove or disable the DestinedFX EA on every affected chart. Do not use an unverified account tab.
3. Inspect open positions and pending orders in the terminal. Existing positions are not closed by Emergency Stop. According to the owner's plan and broker instructions, manually close or protect each position and cancel pending orders. Check broker history and verify the final result.
4. Stop the worker and bridge from the owner's Windows task interface: run `Stop-ScheduledTask -TaskName 'DestinedFX Owner Bridge and Worker'` in an elevated PowerShell window. If the task name differs, inspect `Get-ScheduledTask` and stop only the verified DestinedFX task. Preserve logs and the database.
5. Revoke the selected account pairing and any LIVE lease after the terminal is stopped and positions are reconciled. If the bridge is unreachable, record the failed revocation and keep the host stopped. Never treat an unacknowledged revocation as confirmed.
6. Confirm that no DestinedFX worker or MT5 terminal process is left able to send new commands. Do not kill unrelated MT5 terminals. Keep the Windows host powered on if it is needed to inspect or protect positions.

## Recovery and rollback

1. Preserve `%ProgramData%\DestinedFX`, Windows event logs, worker logs, bridge logs, and the latest backup. Do not delete DPAPI secrets, SQLite, account folders, or evidence.
2. Verify broker positions and history directly in the terminal. Reconcile all submissions, acknowledgements, fills, SL/TP, cancellations, and closing deals before restoring service.
3. If the database is damaged, restore only a previously integrity-checked backup into a separate recovery directory first. Validate `PRAGMA integrity_check`, pairing and account mapping, command journal, and reconciliation state. Do not overwrite the active database until the owner signs off on the test restore.
4. Reinstall or roll back only to a previously reviewed artifact. Verify its SHA-256 and commit. Restart with no automatic trading action, no LIVE authorization, and no unreviewed queued commands. Require a fresh terminal heartbeat and exact identity match before marking connected.
5. Resume at the earliest stage that has incomplete evidence in `SAFE_DEPLOYMENT.md`. A host reboot, app reinstall, worker restart, heartbeat loss, identity mismatch, or unverified database restore resets the relevant acceptance gate. LIVE lease must not persist across restart.

## Alert and incident record

Record timestamp and time zone, operator, account slot, commit and APK checksum, last fresh heartbeat, worker and terminal state, command request ID, broker tickets, position reconciliation, stop actions, backup used, and final owner decision. Redact account IDs and credentials. Do not paste a LIVE password, worker token, Fernet key, or unredacted logs into chat or issue trackers.

## Limits

This runbook has not been tested on the owner's Windows host or broker account. It is not proof that a stop request, terminal shutdown, rollback, or backup restore works in the owner's environment. Until a supervised DEMO exercise verifies every action, use the broker terminal's manual stop procedures as the primary fallback.
