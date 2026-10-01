---
name: quality-gate
description: Run the Mighty Gestures quality gate on the current branch without changing code — verification script, Google-free check, code review, and security/performance reviews when the diff warrants them. Use before committing, pushing, or opening a PR, or when asked to review the current work.
argument-hint: "[base-ref, default main]"
---

# /quality-gate — verify and review the current branch (read-only)

Base ref: `$ARGUMENTS` (use `main` if empty).

1. `git status`, `git diff --stat <base>...HEAD`, and `git diff --stat` for uncommitted work. If there is no
   change at all, say so and stop.
2. Run `scripts/check-no-gms.sh` and `scripts/verify.sh`. Capture pass/fail and the relevant failure output.
3. Delegate to **code-reviewer** with the base ref and the list of changed files.
4. In parallel, also run **security-reviewer** and/or **performance-reviewer** when the diff matches their
   triggers (see each agent's description: manifest/permissions/exported components/intents/persistence/
   logging/build config/dependencies → security; sensors/service/wakelocks/hot path/heavy Compose → performance).
5. Do **not** modify files. Produce one consolidated report:

```
## Quality gate: PASS | FAIL
- verify.sh: <result>          - check-no-gms: <result>
- code-reviewer: <verdict>     - security: <verdict | skipped (reason)>     - performance: <verdict | skipped (reason)>

### Must fix before merge
<BLOCKER/MAJOR/CRITICAL/HIGH findings with file:line>

### Should fix / follow-ups
<the rest, condensed>
```

Offer to route the must-fix items to the developer / ui-expert agents.
