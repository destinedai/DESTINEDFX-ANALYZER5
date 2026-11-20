# Secret scan report

Scanned 2026-11-06 with Gitleaks 8.24.3. The checkout contained 536 reachable commits. The full-history scan consumed the `git log --all -p` output through Gitleaks stdin mode and returned four `generic-api-key` findings. All candidate values are intentionally omitted. Findings are scanner matches, not confirmation that a credential is valid.

| Finding count | File path | Commit |
| ---: | --- | --- |
| 2 | `app/src/main/java/com/example/ui/SimplifiedAnalyzerTab.kt` | `c1907d335d96b0253e8c7b305f5717d48358cc27` |
| 1 | `mt5-ea/DestinedFXTraderEA.mq5` | `858b3c1af2b3e2a171ccfc0784dbec9798a7bb4a` |
| 1 | `app/src/main/java/com/example/ui/SimplifiedBotWorkstation.kt` | `5aa4533a256e17ad78a2b57e06fb5532cff4eaa0` |

The current checked-out tree scan reported zero findings after removing 20 tracked AI Studio context, dump, and artifact files and replacing flagged fake worker-token literals in bridge test fixtures. These changes do not remove prior versions from Git history. The release branch does not rewrite history.

The original brief separately identified two Google API-key-like strings in the removed `public/assets/aistudio/MT5_small_e94051c6.txt`. That file is no longer in the working tree but remains in repository history. The values were not displayed or copied into this report. The owner should verify the original strings through their provider console and revoke them if genuine. The repository is publicly cloneable; the owner should make it private and review other public branches and forks. No repository visibility setting was changed.

The CI secret scan added in this branch scans the checked-out tree and fails on findings. It does not rewrite history or assert that historical findings are valid credentials.
