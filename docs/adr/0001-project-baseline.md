# ADR 0001 — Project baseline

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** maintainer

## Context
Mighty Gestures starts from an empty repository. These baseline decisions were made by the maintainer before
any code exists so that agents and contributors share the same constraints.

## Decision
1. **Product scope:** v1 = device-motion gestures only. v2 = touchscreen gestures. v3 = edge handle overlay
   ("side pill"). The architecture must allow v2/v3 as additional trigger types.
2. **Google-free:** no Play Services, Firebase, ML Kit, Play Core, Ads, Billing or trackers. AndroidX/Jetpack and
   Material Components are allowed.
3. **Distribution:** F-Droid (reproducible builds, FOSS-only dependencies). License AGPL-3.0.
4. **Stack:** Kotlin, Jetpack Compose + Material 3, Coroutines/Flow, Gradle Kotlin DSL with version catalog.
5. **SDK levels:** `minSdk 35` (Android 15); `compileSdk`/`targetSdk` = latest stable API.
6. **Testing:** pragmatic pyramid centered on JVM tests of pure-Kotlin detectors against sensor-trace fixtures,
   plus Robolectric and Compose UI tests.
7. **Workflow:** spec-first, branch per task, Conventional Commits, PRs to `main`; AI agents never push or merge
   without asking.
8. **AI harness:** `AGENTS.md` is the shared rule set; Claude Code agents, skills and hooks live in `.claude/`.

## Harness defaults (proposed by the agent harness, pending maintainer confirmation)
These were not chosen explicitly by the maintainer; they are defaults written into `AGENTS.md`. Change them by
editing `AGENTS.md` and noting it here, or supersede this ADR.
- No `INTERNET` permission, no telemetry (privacy by construction).
- Kotlin-only sources; single-activity Compose app without Fragments.
- JUnit 4 + kotlinx-coroutines-test + Turbine + Robolectric; Truth or kotlin.test assertions.
- Spotless + ktlint (`ktlint_official`) + detekt + Android Lint; JDK 17 toolchain.
- Emulator AVD `mg_api35` on the AOSP image (no Google APIs).

## Consequences
- `minSdk 35` excludes devices below Android 15 but removes compatibility branches and lets us rely on modern
  foreground-service, edge-to-edge and predictive-back behavior.
- Google-free rules out Firebase, cloud crash reporting and ML Kit; detection must be implemented on-device in
  plain Kotlin. (The no-`INTERNET` default above would additionally rule out any network feature.)
- Open decisions (module layout, DI, persistence, application ID, foreground-service type, detection approach)
  require their own ADRs before implementation depends on them.
