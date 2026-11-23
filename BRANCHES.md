# Branch policy

## Active destination

All work for the self-hosted host-runtime implementation belongs on `feat/self-hosted-linux-host-runtime`. At the 2026-11-23 live GitHub audit before this repair, remote HEAD was `714ad2a8a14b390ce43e12e89134c954237b3c7a`. GitHub's live comparison against `main` (`61bc5373bd9b546e1f6fc75fb48a1d988a7b49f4`) reported **257 commits ahead and 0 behind**. This is a dated audit record, not a permanent count; re-read remote refs before future work.

Always read the current remote branch HEAD before editing and preserve it. Do not assume a SHA or ahead/behind count in this document remains current after subsequent commits. Do not switch to `main` to continue this task.

## Divergent phone-only branch

`free/phone-only-v1` is a separate divergent branch. It is not the deployment source for the current self-hosted Linux host-runtime work. Do not switch to it, merge it, rebase it, reset it, force-update it, or wholesale cherry-pick from it. Its mention here is a warning, not a checkout instruction.

## Release policy

CI APK artifacts are labelled **DEBUG APK - FOR DEMO TEST ONLY**. GitHub Release/prerelease publishing remains disabled until Android JVM tests, emulator instrumentation, physical phone-to-Bridge pairing, authenticated broker DEMO telemetry, and supervised DEMO order/position acceptance are all independently recorded as PASS for the same candidate. CI fixtures and Codespaces tests are not real DEMO pairing evidence. LIVE remains subject to its separate authorization and risk gates.
