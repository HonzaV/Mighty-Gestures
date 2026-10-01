# AI agent harness

How AI agents work on this repository. The rules every tool follows are in [`AGENTS.md`](../../AGENTS.md);
this page explains the Claude Code tooling built on top of it.

## Layout

```
AGENTS.md                      shared rules for all AI tools and humans (single source of truth)
CLAUDE.md                      imports AGENTS.md + Claude Code specifics
docs/engineering/*.md          deep-dive guides loaded on demand (testing, sensors, UI, security, F-Droid)
docs/specs/, docs/adr/         feature specs and architecture decisions (templates: 0000-*.md)
.claude/agents/*.md            role subagents
.claude/skills/*/SKILL.md      /feature, /quality-gate, device-verify
.claude/hooks/*.sh             mechanical guardrails (wired in .claude/settings.json)
.claude/settings.local.json    machine-specific env (gitignored)
scripts/                       setup-android-sdk.sh, verify.sh, check-no-gms.sh (usable without any AI tool)
```

## Roles

| Agent | Model | Access | Responsibility |
|---|---|---|---|
| architect | opus | docs/config/scaffold | specs, ADRs, module boundaries, roadmap guard |
| developer | sonnet | read/write | domain, detectors, rules, actions, services, data, ViewModels + unit tests |
| ui-expert | sonnet | read/write (UI) | Compose/M3 screens, accessibility, insets, back, permission UX |
| tester | sonnet | tests only | coverage of acceptance criteria, trace fixtures, emulator verification, false-positive hunting |
| code-reviewer | opus | read-only | correctness, architecture, Kotlin/Compose pitfalls, project rules |
| security-reviewer | opus | read-only | permissions, exported components, IPC, data, dependencies |
| performance-reviewer | sonnet | read-only | sensor rates, batching, wakeups, hot path, Doze |

Reviewers have no Edit/Write tools; none of the agents can spawn further agents — orchestration happens in the
main session so the maintainer approval gate is always respected.

## The `/feature` pipeline

```
request ─► architect (spec + ADRs) ─► ✋ maintainer approval ─► branch feat/NNNN-slug
        ─► developer ⇄ ui-expert ─► tester ──fail──► fix (≤2 rounds) ─► ask maintainer
                                       │pass
                                       ▼
            code-reviewer (+ security / performance when the diff warrants) ──blocking──► fix (≤2 rounds)
                                       │clean
                                       ▼
                        scripts/verify.sh ─► commit ─► ✋ ask before push / PR
```

## Guardrails

| Guardrail | Where | Effect |
|---|---|---|
| Google-free check | `scripts/check-no-gms.sh`, hook `gms-guard.sh` | denies edits adding GMS/Firebase/ML Kit/Play Core/Ads/Billing/trackers; rescans after shell commands |
| Auto-format | hook `format-kotlin.sh` | ktlint / Spotless on each edited `.kt`/`.kts` |
| Verify before finish | hook `verify-before-stop.sh` + `scripts/verify.sh` | Claude must run verification after Kotlin changes before ending a turn |
| Git safety | `.claude/settings.json` permissions | push, PR create/merge, hard reset, rebase, branch delete always ask |

## Using other AI tools

Codex, Cursor, Gemini CLI, Copilot and others read `AGENTS.md` directly. To emulate a role there, point the
tool at the role file, e.g. "Act as described in `.claude/agents/architect.md`" — the prompts are plain
Markdown and tool-agnostic apart from the frontmatter.

## Extending the harness

- New recurring mistake → add a concrete rule to `AGENTS.md` (or the relevant `docs/engineering/` guide).
- New role → add `.claude/agents/<name>.md` (frontmatter: `name`, `description`, `tools`, `model`), document it
  in the tables above and in `CLAUDE.md`.
- New mechanical rule → prefer a script in `scripts/` (so CI can run it too) wrapped by a hook.

## Credits

Role boundaries ("when NOT to use"), evidence labeling (verified vs inferred) and fixed report formats were
inspired by public Claude Code subagent collections, notably
[aravindusdev/agents-in-android](https://github.com/aravindusdev/agents-in-android) and
[VoltAgent/awesome-claude-code-subagents](https://github.com/VoltAgent/awesome-claude-code-subagents).
No text was copied; all prompts here are written for this project.
