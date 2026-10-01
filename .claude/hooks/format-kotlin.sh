#!/usr/bin/env bash
# PostToolUse Edit|Write hook: format the edited Kotlin file.
# Uses a ktlint binary if installed, otherwise Spotless's single-file IDE hook once the Gradle project exists.
# Silently does nothing when neither is available, so it is safe before the project is scaffolded.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
file="$(jq -r '.tool_response.filePath // .tool_input.file_path // empty')"
[[ -n "$file" && -f "$file" ]] || exit 0
case "$file" in *.kt|*.kts) ;; *) exit 0 ;; esac
case "$file" in "$ROOT"/*) ;; *) exit 0 ;; esac   # only files inside this repo

if command -v ktlint >/dev/null 2>&1; then
  (cd "$ROOT" && ktlint --format --relative "$file" >/dev/null 2>&1) || true
  exit 0
fi

if [[ -x "$ROOT/gradlew" ]] && grep -rqs --include='*.gradle.kts' --include='*.toml' 'spotless' "$ROOT/build.gradle.kts" "$ROOT/gradle" "$ROOT/build-logic" 2>/dev/null; then
  (cd "$ROOT" && ./gradlew --quiet --console=plain spotlessApply -PspotlessIdeHook="$file" >/dev/null 2>&1) || true
fi
exit 0
