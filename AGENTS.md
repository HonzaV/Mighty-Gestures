# AGENTS.md — Mighty Gestures

Single source of truth for every AI coding agent (Claude Code, Codex, Cursor, Gemini CLI, Copilot…) and for
human contributors. Tool-specific files (`CLAUDE.md`, `.claude/`) only add tooling on top of these rules; if they
ever disagree with this file, this file wins.

## 1. Product

Mighty Gestures is a **Google-free Android app that runs user-defined actions when the device detects a motion
gesture** (shake, flip face-down, twist, chop, …). It is distributed through **F-Droid** under **AGPL-3.0**.

| Version | Scope |
|---|---|
| **v1 (current)** | Device-motion gestures only (accelerometer / gyroscope / other platform sensors) → actions. |
| v2 (later) | Touchscreen gestures as additional triggers. |
| v3 (later) | Edge handle overlay ("side pill"): a pill-shaped handle on the screen edge you swipe/tap to trigger actions. |

Build v1 only, but never paint v2/v3 into a corner: see the trigger → action model in §4.

## 2. Non-negotiables

1. **No Google proprietary code.** Forbidden in any configuration, flavor or transitive dependency:
   Play Services (`com.google.android.gms`, `com.google.gms` plugins), Firebase (`com.google.firebase`),
   ML Kit (`com.google.mlkit`), Play Core / Review / In-App Update / Integrity (`com.google.android.play`),
   UMP / Ads (`com.google.android.ump`, `com.google.android.gms.ads`), Billing (`com.android.billingclient`),
   and any analytics / crash / attribution SDK (Crashlytics, Sentry, Bugsnag, AppsFlyer, Amplitude, Mixpanel…).
   **Allowed:** AndroidX / Jetpack, Material Components (`com.google.android.material`), and FOSS libraries that
   merely live under a `com.google` namespace (KSP, Dagger/Hilt, Truth, Protobuf, Gson, Guava).
   Direct references are blocked by `scripts/check-no-gms.sh` (also run as an agent hook); transitive ones are
   caught by `scripts/check-no-gms.sh --classpath` inside `scripts/verify.sh`. Never bypass or weaken either.
2. **Privacy by construction.** No `INTERNET` permission, no telemetry, no network calls. Sensor data never
   leaves the device and is never persisted except in explicit, user-initiated debug recordings.
   Adding any network capability requires an approved ADR.
3. **F-Droid-ready at all times.** FOSS-only dependencies (license compatible with AGPL-3.0), reproducible
   builds, no dependency-metadata blob. Details: `docs/engineering/f-droid.md`.
4. **Least privilege.** Every permission, exported component, and special access (accessibility, overlay,
   notification policy, battery-optimization exemption) needs a written justification in a spec or ADR and a
   user-facing explanation in the UI before it is requested.
5. **Battery is a feature.** Always-on sensing must be measured, not guessed. See `docs/engineering/sensors-and-power.md`.

## 3. Tech baseline

| Area | Decision |
|---|---|
| Language | Kotlin only (no Java sources). Coroutines + Flow for async. |
| UI | Jetpack Compose + Material 3. No XML layouts or Fragments. Single-activity. |
| SDK levels | `minSdk 35` (Android 15). `compileSdk`/`targetSdk` = latest **stable** major API (37 at time of writing — re-check `sdkmanager --list` / `android sdk list`). |
| Build | Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`, JDK 17 toolchain, no dynamic versions (`+`, `latest.release`). |
| Formatting / static analysis | Spotless + ktlint (`.editorconfig`), detekt, Android Lint (warnings as errors for new code). |
| Testing | JUnit 4, kotlinx-coroutines-test, Turbine, Robolectric, Compose UI test. Details: `docs/engineering/testing.md`. |

Decisions that are **still open** and must be settled by an ADR from the architect before code depends on them:
module layout, DI approach, persistence (DataStore vs Room), application ID / package name, foreground-service
type for always-on sensing, gesture-detection approach. Open/accepted ADRs live in `docs/adr/`.

## 4. Architecture principles

- **Trigger → Rule → Action.** A `Trigger` emits events (v1: motion gestures; later: touch gestures, edge handle).
  A `Rule` binds a trigger to one or more `Action`s plus conditions (screen state, app in foreground…). Actions
  know nothing about triggers. New trigger types must plug in without touching action code.
- **Pure-Kotlin core.** Gesture detection (signal processing, state machines, thresholds) is pure Kotlin with no
  `android.*` imports, so it is unit-testable against recorded sensor traces. Android sensor APIs sit behind a
  thin adapter that converts `SensorEvent` into plain data.
- **Layers:** `ui` (Compose screens + ViewModels) → `domain` (use cases, rules, detectors, models) ← `data`/`platform`
  (sensors, persistence, system actions). Dependencies point inward; `domain` depends on nothing Android.
- **Unidirectional data flow.** ViewModels expose a single immutable `StateFlow<UiState>` and accept events;
  composables are stateless where possible and hoist state. No business logic in composables.
- **Thin service.** The always-on component (foreground service and/or accessibility service — see ADRs) only
  wires sensor adapters to detectors to the rule engine. It owns no business logic.
- **Inject dispatchers and clocks.** Never hard-code `Dispatchers.IO` or `System.currentTimeMillis()` in logic;
  inject them so tests are deterministic. No `GlobalScope`. No `runBlocking` outside tests.

## 5. Code conventions

- ktlint official style (enforced by Spotless). Composable functions are PascalCase; everything else follows Kotlin conventions.
- No `!!`; no `lateinit` in domain code; prefer `sealed interface` for states/events and `@Immutable`/`@Stable`
  data for Compose UI state.
- User-visible strings in `strings.xml` (translatable). No hard-coded colors or dimensions in composables — use
  `MaterialTheme` tokens.
- Logging through a single project logger; never log raw sensor streams or anything identifying in release builds.
- Public domain APIs get KDoc explaining *why*, not *what*. Avoid comments that restate code.
- Keep diffs focused. No drive-by refactors in feature PRs; propose them separately.

## 6. Commands

Environment: Android SDK at `$ANDROID_HOME` (default `~/Android/Sdk`, installed by `scripts/setup-android-sdk.sh`),
JDK 17+. Emulator AVD `mg_api35` (AOSP image, no Google APIs).

| Purpose | Command |
|---|---|
| Full local verification (run before declaring work done) | `scripts/verify.sh` |
| Quick verification (format + unit tests) | `scripts/verify.sh --fast` |
| Verification incl. instrumented tests (needs device/emulator) | `scripts/verify.sh --device` |
| Google-free dependency check | `scripts/check-no-gms.sh` |
| Format | `./gradlew spotlessApply` |
| Unit tests | `./gradlew testDebugUnitTest` |
| Lint / static analysis | `./gradlew lintDebug detekt` |
| Install debug build | `./gradlew installDebug` |
| Start emulator | `$ANDROID_HOME/emulator/emulator -avd mg_api35 -no-snapshot-save &` |
| Inject motion on emulator | `adb emu sensor set acceleration <x>:<y>:<z>` (also `gyroscope`, `magnetic-field`) |

The project scaffold **must** provide the Gradle tasks above under these exact names (application module `:app`);
`scripts/verify.sh` and the agent hooks depend on them. Until the Gradle project exists, `scripts/verify.sh` exits 0 with a notice.

## 7. Workflow

- **Spec first** for anything beyond a small fix: `docs/specs/NNNN-slug.md` (template `docs/specs/0000-template.md`).
  Architectural decisions go to `docs/adr/NNNN-slug.md` (template `docs/adr/0000-template.md`).
- **Branch per task:** `feat/…`, `fix/…`, `chore/…`, `docs/…`, `test/…` from up-to-date `main`. Never commit to `main` directly.
- **Conventional Commits** (`feat(detector): add flip-face-down gesture`). One logical change per commit.
- **PR to `main`** with the template checklist filled in. Agents may commit on their branch but must **ask before
  pushing, opening/merging PRs, force-pushing, rewriting history, or deleting branches**.
- **Definition of done:** spec acceptance criteria met · `scripts/verify.sh` green · tests added for new behavior
  (detectors: positive *and* negative traces) · docs/ADR/CHANGELOG updated · reviewer findings resolved or
  explicitly deferred with a reason.

## 8. Rules for agents

- **Verify, don't assume.** Label claims as *verified* (you ran it / read it) or *inferred*. Read version numbers
  and APIs from the project and official docs, not memory; Android behavior changes per API level.
- **Read before editing**, match surrounding style, keep changes minimal and scoped to the task.
- **Never** weaken tests, lint rules, the GMS check, or hooks to make something pass. Never delete a failing test
  without explaining why in the PR. Never add `@Suppress` without a justification comment.
- **Never** commit secrets, keystores, `local.properties`, or signing config.
- When a requirement is ambiguous or a decision belongs to the maintainer (product behavior, new permission,
  new dependency, architecture change), stop and ask instead of guessing.
- Report outcomes faithfully: failing tests are reported with output; skipped steps are named.

## 9. Further reading (load when relevant)

| Topic | File |
|---|---|
| Testing strategy, sensor-trace fixtures, emulator sensor injection | `docs/engineering/testing.md` |
| Sensors, foreground services, Doze, battery measurement | `docs/engineering/sensors-and-power.md` |
| Compose UI, theming, accessibility, edge-to-edge, predictive back | `docs/engineering/compose-ui.md` |
| Security & privacy checklist | `docs/engineering/security-privacy.md` |
| F-Droid & reproducible builds, dependency policy | `docs/engineering/f-droid.md` |
| Agent roles and the feature pipeline | `docs/agents/README.md` |
| Architecture decisions | `docs/adr/` |
