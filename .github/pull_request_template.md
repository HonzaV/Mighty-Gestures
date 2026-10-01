## What & why
<!-- Link the spec (docs/specs/NNNN-…) and ADRs. Summarize the change in 2–5 bullets. -->

## Acceptance criteria
<!-- Copy from the spec; tick the ones this PR satisfies. -->
- [ ] …

## Verification
- [ ] `scripts/verify.sh` passes (paste the summary line)
- [ ] New behavior has tests; detectors have positive **and** negative sensor traces
- [ ] Verified on emulator/device (describe how) — or N/A with reason

## Checklist
- [ ] Google-free: `scripts/check-no-gms.sh` passes, no new trackers/analytics
- [ ] No new permission / special access / exported component — or justified in spec/ADR with rationale UI
- [ ] No new dependency — or justified (license, transitive deps, size) per `docs/engineering/f-droid.md`
- [ ] Battery impact measured for changes to sensors/service/wakeups (numbers below) — or N/A
- [ ] UI: edge-to-edge, dark mode, font scale, TalkBack labels checked — or N/A
- [ ] Docs / ADR / `CHANGELOG.md` updated

## Measurements / screenshots
<!-- dumpsys sensorservice rates, batterystats wakeups, screenshots before/after -->

## Review notes
<!-- Reviewer verdicts (code / security / performance) and any deferred follow-ups. -->
