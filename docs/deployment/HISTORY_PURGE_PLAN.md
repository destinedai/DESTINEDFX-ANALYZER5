# Git history purge plan

**Status:** plan only. No repository visibility change, history rewrite, force-push, ref deletion, or credential rotation was performed.

The removed AI Studio dump `public/assets/aistudio/MT5_small_e94051c6.txt` is absent from the current tree but remains in two reachable historical commits. Because the repository has been publicly cloneable, deleting the file from the current branch does not remove copies already fetched, forked, cached, or downloaded.

## Containment and approval sequence

1. **Preserve evidence and pause distribution.** Record the current branch/ref list, commit IDs, and affected path. Do not rewrite history or merge more content while the owner decides. Avoid copying the dump into new logs or artifacts.
2. **Make the repository private first.** The repository owner must change visibility through GitHub after confirming the intended collaborators and their access. This limits future public fetches but cannot retract public clones, forks, cached copies, or prior downloads. Contact fork owners or GitHub support where appropriate. This task did not change visibility.
3. **Classify and rotate only if needed.** Review the original redacted Gitleaks finding report. If a finding is a real credential, revoke or rotate it at its issuing service before or during cleanup. Do not publish, paste, or add raw values to an ignore file. False positives should be ignored only with their exact Gitleaks fingerprint and a short source-based justification.
4. **Obtain explicit owner approval before rewriting.** If the owner elects to purge the dump from reachable history, coordinate a maintenance window and all contributors first. A history rewrite with `git filter-repo` changes commit IDs and invalidates signatures, PR references, release references, and local branch ancestry. Existing clones must be discarded or carefully recloned; do not merge old clones back into the rewritten repository. Protect required refs and preserve a private audit copy only if the owner authorizes it.
5. **Rewrite only after approval.** Use a fresh mirror clone and a reviewed path-specific `git filter-repo` operation to remove `public/assets/aistudio/MT5_small_e94051c6.txt` from every selected ref. Review the rewritten ref list and diff before publishing. Updating GitHub refs requires coordinated ref replacement, which is expressly outside this task and requires the owner's separate approval.
6. **Verify after the approved change.** Scan every intended ref with Gitleaks, verify the path is absent from reachable history, inspect fresh public/private clone behavior, confirm CI and release refs, and record the new commit map. If a real credential was found, verify its revocation independently.

## Finding review performed in this task

Gitleaks v8.24.3 current-tree scanning returned zero findings. Redacted scans of 48 commits touching `SimplifiedAnalyzerTab.kt`, `SimplifiedBotWorkstation.kt`, and `DestinedFXTraderEA.mq5`, plus their targeted history patches, also found no `generic-api-key` matches. The Gitleaks `git` command in this sandbox reported zero commits scanned, so this is not represented as a complete full-history audit. The four previously reported findings could not be reproduced and their fingerprints were not available. No `.gitleaksignore` entry was guessed or added. Obtain the original redacted report or fingerprints and rerun the complete scan before classifying or ignoring those four reports.

No history rewrite has been performed. The purge remains a decision for the repository owner after visibility containment and explicit approval.
