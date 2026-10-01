#!/usr/bin/env bash
# Prints a "<sha256>  <path>" line for every Kotlin / Gradle-Kotlin file in the repo (tracked and untracked,
# not ignored), sorted by path. scripts/verify.sh stores it as .claude/state/last-verify; the Stop hook diffs
# the current snapshot against it, so edits, additions, deletions and renames all invalidate a verification.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git ls-files -z --cached --others --exclude-standard -- '*.kt' '*.kts' \
  | while IFS= read -r -d '' f; do if [[ -f "$f" ]]; then printf '%s\0' "$f"; fi; done \
  | sort -zu \
  | xargs -0 -r sha256sum
