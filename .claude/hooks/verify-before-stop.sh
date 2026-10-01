#!/usr/bin/env bash
# Stop hook: if Kotlin/Gradle-Kotlin files were edited, added, deleted or renamed since the last successful
# `scripts/verify.sh` (content snapshot in .claude/state/last-verify), ask Claude to run it before finishing.
# Blocks at most once per stop attempt (honors stop_hook_active) so it can't loop.
# No-op until the Gradle project exists.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
input="$(cat)"
[[ "$(jq -r '.stop_hook_active // false' <<<"$input")" == "true" ]] && exit 0
[[ -x "$ROOT/gradlew" ]] || exit 0
cd "$ROOT" || exit 0

MARKER=.claude/state/last-verify
if [[ -f "$MARKER" ]] && grep -qE '^[0-9a-f]{64}  ' "$MARKER" 2>/dev/null; then
  # Paths whose content differs, plus added and deleted/renamed files, relative to the verified snapshot.
  changed="$(diff "$MARKER" <(scripts/kotlin-snapshot.sh) | sed -nE 's/^[<>] [0-9a-f]{64}  //p' | sort -u | head -20)"
else
  base="$(git merge-base HEAD main 2>/dev/null || echo HEAD)"
  changed="$( { git diff --name-only "$base" 2>/dev/null; git ls-files --others --exclude-standard 2>/dev/null; } \
    | grep -E '\.kts?$' | sort -u | head -20)"
fi
[[ -n "$changed" ]] || exit 0

jq -n --arg files "$changed" '{
  decision: "block",
  reason: ("Kotlin files changed since the last successful verification:\n" + $files +
    "\n\nRun `scripts/verify.sh` and report the result before finishing (AGENTS.md §7). " +
    "If verification is deliberately skipped (e.g. work in progress), say so explicitly in your final message.")
}'
exit 0
