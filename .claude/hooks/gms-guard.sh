#!/usr/bin/env bash
# Claude Code hook enforcing the Google-free rule (AGENTS.md §2).
#  - PreToolUse  Edit|Write|MultiEdit: deny the edit if the new text adds a forbidden dependency/import.
#  - PostToolUse Bash: rescan the repo (catches edits made through shell commands) and report violations.
set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
CHECK="$ROOT/scripts/check-no-gms.sh"
[[ -x "$CHECK" ]] || exit 0

input="$(cat)"
event="$(jq -r '.hook_event_name // empty' <<<"$input")"
tool="$(jq -r '.tool_name // empty' <<<"$input")"

if [[ "$event" == "PreToolUse" ]]; then
  file="$(jq -r '.tool_input.file_path // empty' <<<"$input")"
  [[ -n "$file" ]] || exit 0
  rel="${file#"$ROOT"/}"
  case "$tool" in
    Write)     text="$(jq -r '.tool_input.content // empty' <<<"$input")" ;;
    Edit)      text="$(jq -r '.tool_input.new_string // empty' <<<"$input")" ;;
    MultiEdit) text="$(jq -r '[.tool_input.edits[]?.new_string] | join("\n")' <<<"$input")" ;;
    *)         exit 0 ;;
  esac
  if ! out="$(printf '%s\n' "$text" | "$CHECK" --stdin "$rel")"; then
    jq -n --arg r "$out
Mighty Gestures must stay Google-free and tracker-free. Use an AndroidX/FOSS alternative, or ask the maintainer to approve an ADR." \
      '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  fi
  exit 0
fi

if [[ "$event" == "PostToolUse" && "$tool" == "Bash" ]]; then
  if ! out="$(cd "$ROOT" && "$CHECK")"; then
    jq -n --arg r "$out
A shell command introduced a Google proprietary or tracking dependency. Revert it now (AGENTS.md §2)." \
      '{decision: "block", reason: $r}'
  fi
  exit 0
fi
exit 0
