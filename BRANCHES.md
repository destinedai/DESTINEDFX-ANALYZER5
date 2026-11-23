# Branch policy

## Active destination

All checkpoint work belongs on feat/self-hosted-linux-host-runtime. Its verified starting head was 7ccc7b90aa0d15561d0b60e484ac86ddc7a1df25: **228 commits ahead of main, 0 behind**. Preserve this history and add one fast-forward commit on top.

## Divergent phone-only branch

free/phone-only-v1 remains separate and must not be merged into this work.

- Comparing main as base with free/phone-only-v1 as head: phone-only is **680 commits ahead and 185 behind**.
- Reversing the comparison: main is **185 commits ahead and 680 behind** phone-only.

Do not merge, rebase, reset, force-update or wholesale cherry-pick from either branch in this task. Do not use free/phone-only-v1 as the deployment source. Only the explicit feature branch receives the requested push.

## Release policy

CI APK artifacts are labelled **DEBUG APK - FOR DEMO TEST ONLY**. GitHub Release publishing remains disabled until Android JVM tests, emulator instrumentation tests, and real broker-paired DEMO terminal/order/close acceptance are all independently recorded as PASS from the same candidate. CI fixtures and Codespaces tests are not real DEMO pairing evidence.
