# Branch policy

## Active destination

All work for the self-hosted host-runtime implementation belongs on `feat/self-hosted-linux-host-runtime`. At the start of the final Android acceptance repair task on 2026-11-23, its remote HEAD was `a899055a0af91e1b17bb4d3456fcf0139dc84287`. The fully tested code checkpoint before this documentation alignment was `c659697a33761557e180c7861c5bd00c23e173ee` (236 commits ahead of `main`, 0 behind at that comparison). This documentation update is committed fast-forward on top of that checkpoint.

Always read the current remote branch HEAD before editing and preserve it. Do not assume a SHA or ahead/behind count in this document remains current after subsequent commits. Do not switch to `main` to continue this task.

## Divergent phone-only branch

`free/phone-only-v1` is a separate divergent branch. It is not the deployment source for the current self-hosted Linux host-runtime work. Do not switch to it, merge it, rebase it, reset it, force-update it, or wholesale cherry-pick from it. Its mention here is a warning, not a checkout instruction.

## Release policy

CI APK artifacts are labelled **DEBUG APK - FOR DEMO TEST ONLY**. GitHub Release/prerelease publishing remains disabled until Android JVM tests, emulator instrumentation, physical phone-to-Bridge pairing, authenticated broker DEMO telemetry, and supervised DEMO order/position acceptance are all independently recorded as PASS for the same candidate. CI fixtures and Codespaces tests are not real DEMO pairing evidence. LIVE remains subject to its separate authorization and risk gates.
