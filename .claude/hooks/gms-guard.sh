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
    Write) text="$(jq -r '.tool_input.content // empty' <<<"$input")" ;;
    Edit|MultiEdit)
      # Scan the whole file as it will look after the edit, so a forbidden coordinate can't be assembled from
      # an innocent new_string plus existing text. Falls back to the new strings alone without python3.
      if command -v python3 >/dev/null 2>&1; then
        text="$(python3 -c '
import json, os, sys
inp = json.load(sys.stdin)["tool_input"]
path = inp["file_path"]
text = open(path, encoding="utf-8", errors="replace").read() if os.path.exists(path) else ""
edits = inp.get("edits") or [inp]
for e in edits:
    old, new = e.get("old_string", ""), e.get("new_string", "")
    if old == "":
        text = new + text
    else:
        text = text.replace(old, new) if e.get("replace_all") else text.replace(old, new, 1)
sys.stdout.write(text)' <<<"$input")"
      else
        text="$(jq -r '[.tool_input.new_string // empty, (.tool_input.edits[]?.new_string)] | join("\n")' <<<"$input")"
      fi ;;
    *) exit 0 ;;
  esac
  out="$(printf '%s\n' "$text" | "$CHECK" --stdin "$rel" 2>&1)"; rc=$?
  if [[ "$out" == "Google-free violation"* ]]; then
    jq -n --arg r "$out
Mighty Gestures must stay Google-free and tracker-free. Use an AndroidX/FOSS alternative, or ask the maintainer to approve an ADR." \
      '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  elif (( rc != 0 )); then
    # Fail closed for scanned files: a broken checker must not silently let dependencies through.
    jq -n --arg r "gms-guard could not check $rel because scripts/check-no-gms.sh failed (exit $rc):
$out
Fix the checker (or tell the maintainer) before editing build files." \
      '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  fi
  exit 0
fi

if [[ "$event" == "PostToolUse" && "$tool" == "Bash" ]]; then
  out="$(cd "$ROOT" && "$CHECK" 2>&1)"; rc=$?
  if [[ "$out" == "Google-free violation"* ]]; then
    jq -n --arg r "$out
A shell command introduced a Google proprietary or tracking dependency. Revert it now (AGENTS.md §2)." \
      '{decision: "block", reason: $r}'
  elif (( rc != 0 )); then
    # The checker itself failed (not a violation): surface it without blocking.
    jq -n --arg m "gms-guard: scripts/check-no-gms.sh failed (exit $rc): $out" '{systemMessage: $m}'
  fi
  exit 0
fi
exit 0
