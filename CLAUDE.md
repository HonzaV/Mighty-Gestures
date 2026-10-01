@AGENTS.md

# Claude Code specifics

Everything above is shared with all AI tools. This section only describes the Claude Code tooling in `.claude/`.

## Agents (`.claude/agents/`)

| Agent | Model | Writes code? | Use for |
|---|---|---|---|
| `architect` | opus | docs, build config, scaffolding | specs, ADRs, module design, project scaffold |
| `developer` | sonnet | yes | domain, detectors, rule engine, actions, services, data, ViewModels |
| `ui-expert` | sonnet | yes (UI) | Compose screens, theming, accessibility, permission flows |
| `tester` | sonnet | tests only | test design, sensor-trace fixtures, verification, emulator checks |
| `code-reviewer` | opus | no | review every diff before commit/PR |
| `security-reviewer` | opus | no | manifest, permissions, IPC, data, dependencies, releases |
| `performance-reviewer` | sonnet | no | sensors, service, wakelocks, hot path, battery |

Delegate instead of doing specialist work inline when a task matches an agent. Give subagents full context
(spec path, ADRs, previous reports) — they don't see this conversation.

## Skills

- `/feature <description>` — full pipeline: architect spec → **maintainer approval** → branch → developer /
  ui-expert → tester → reviewers → `scripts/verify.sh` → commit → PR offer. `/feature continue <spec>` resumes.
- `/quality-gate [base]` — read-only verification + reviews of the current branch.
- `device-verify` — emulator/device procedures (sensor injection, dumpsys, screenshots); preloaded by tester
  and performance-reviewer.

## Hooks (`.claude/hooks/`, configured in `.claude/settings.json`)

- **gms-guard** — denies edits that add Google proprietary / tracking dependencies; rescans after Bash commands.
- **format-kotlin** — formats edited `.kt`/`.kts` files (ktlint binary or Spotless IDE hook; no-op before scaffold).
- **verify-before-stop** — if Kotlin files changed since the last successful `scripts/verify.sh`, asks you to
  run it before finishing (once per stop; no-op before scaffold).

Never disable or work around these hooks; if one misfires, report it to the maintainer.

## Session hygiene

- Use the session scratchpad for screenshots, logs and temporary files — never the repo.
- Machine-specific settings (e.g. `ANDROID_HOME`) live in `.claude/settings.local.json` (gitignored).
