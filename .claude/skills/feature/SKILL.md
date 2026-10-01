---
name: feature
description: Run the full Mighty Gestures delivery pipeline for a feature or substantial change — architect spec with a maintainer approval gate, then branch, implementation (developer + ui-expert), testing (tester), reviews (code-reviewer, plus security-reviewer / performance-reviewer when relevant), final verification, commit, and a PR offer.
argument-hint: "<feature description> | continue <docs/specs/NNNN-slug.md>"
disable-model-invocation: true
---

# /feature — spec → approve → build → test → review

Request: **$ARGUMENTS**

You (the main session) are the orchestrator. Delegate each stage to the named subagent with the Agent tool,
pass it everything it needs (spec path, previous reports, file lists) because subagents start without this
conversation, and relay results faithfully. Do not do the subagents' work yourself, and do not skip stages.
Keep a todo list of the stages so progress is visible.

## Stage 0 — Preflight
- Read `AGENTS.md`. Run `git status`, `git branch --show-current` and `git fetch origin main`.
- If the working tree has uncommitted changes, stop and ask how to handle them.
- **New request:** must start on `main`, up to date with `origin/main` (`git pull --ff-only`). If the current
  branch is anything else, stop and ask whether to switch to `main` — never start a new feature on top of an
  unrelated branch.
- **`continue <spec path>`:** read that spec and resolve its task branch (`feat|fix/<NNNN-slug>`, NNNN-slug from
  the spec filename). If the branch exists locally or on `origin`, switch to it; if you are on a different
  non-`main` branch, stop and ask. Confirm the spec status is **Approved** (otherwise run the approval gate in
  Stage 1b for it). Then jump to Stage 2.

## Stage 1 — Spec (architect)
Delegate to **architect**: "Write a spec for: <request>. Create ADRs for any significant decision. Follow
`docs/specs/0000-template.md`, mark spec status Draft, and finish with your Architect report."

## Stage 1b — Approval gate (STOP)
Present to the maintainer: spec path, a 5–10 line summary, key decisions (with ADR links), the open
questions, and the planned implementation order. Use AskUserQuestion for the open questions and for the
approval itself (Approve / Approve with changes / Reject).
- **Never proceed to Stage 2 without explicit approval in this conversation.**
- On changes: send the feedback to architect, then repeat the gate.
- On approval: set the spec status to **Approved** (and accepted ADRs to **Accepted**).

## Stage 2 — Branch
- New request: you are on an up-to-date `main` (Stage 0). Create `feat/<NNNN-slug>` (or `fix/…` for bug
  fixes), where NNNN-slug matches the spec filename. The approved spec and ADRs are committed as the first
  commit on this branch (`docs(spec): …`).
- `continue`: you must be on the spec's task branch (Stage 0); create it from up-to-date `main` only if it
  doesn't exist yet.
- Before every later stage, re-check `git branch --show-current` equals the task branch; if not, stop and ask.
  Never work on `main` directly.

## Stage 3 — Implementation
Follow the architect's implementation order:
- **developer** for domain, detectors, rule engine, actions, services, data, ViewModels, Gradle.
- **ui-expert** for screens, components, theming, navigation, permission flows.
Run them in parallel only when their files don't overlap; otherwise developer first (it defines UiState /
events), then ui-expert. Pass each the spec path, relevant ADRs, and the other agent's report.
If an agent reports a spec gap or a decision that belongs to the maintainer, stop and ask.

## Stage 4 — Test (tester)
Delegate to **tester** with the spec path, the implementation reports, and the branch name.
- Verdict FAIL → send the defects to developer / ui-expert, then re-run tester.
- After **2** failed fix rounds, stop and ask the maintainer how to proceed.

## Stage 5 — Review
Always run **code-reviewer**. Also run, in parallel:
- **security-reviewer** if the diff touches `AndroidManifest.xml`, permissions or special access, exported
  components, intents/PendingIntents/receivers, persistence, logging, build/signing config, or dependencies —
  or if the architect/code-reviewer asked for it.
- **performance-reviewer** if the diff touches sensor registration/sampling, the always-on service,
  wakelocks/alarms/jobs, the detection hot path, or heavy Compose screens — or if asked for.
Check the diff yourself (`git diff --stat main...HEAD`) to decide; when unsure, run the reviewer.
Fix loop: BLOCKER/MAJOR (code) and CRITICAL/HIGH (security/perf) findings go back to developer / ui-expert,
then the same reviewer re-reviews. MINOR/NIT/MEDIUM/LOW are fixed if cheap or listed as follow-ups.
Max 2 rounds, then ask the maintainer.

## Stage 6 — Final verification
Run `scripts/verify.sh` yourself (plus `--device` if an emulator/device is attached and the change is
user-visible or sensor-driven). Report the actual result; never claim green without running it.

## Stage 7 — Wrap-up
- Update spec status to **Implemented**, `CHANGELOG.md` (Unreleased), and docs touched by the change.
- Commit on the feature branch with Conventional Commits (split into logical commits if large).
- Summarize: what was built, test & review verdicts, measurements, deferred follow-ups.
- **Ask** before pushing or opening a PR. If approved: push the branch and open a PR to `main` using
  `.github/pull_request_template.md`, filled in from the reports.
