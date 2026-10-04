# ADR 0003 — Module layout and package structure

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
AGENTS.md §4 requires a pure-Kotlin `domain` layer with no `android.*` imports, which makes detectors testable
against recorded sensor traces. Dependencies point inward: `ui` → `domain` ← `data`/`platform`. The first
feature (spec [0001](../specs/0001-motion-gestures.md)) adds a motion pipeline, a rule engine, persistence,
actions, an accessibility host and several screens.

The existing build is wired to a single `:app` module:
- `scripts/verify.sh` runs `spotlessCheck detekt lintDebug testDebugUnitTest` by task name. Gradle runs a
  task name in every project that has a task of that name. A plain `kotlin("jvm")` module has `test`, not
  `testDebugUnitTest`, so `verify.sh` would **silently skip** its tests.
- The JaCoCo gate (`:app:jacocoCoverageVerification`, 75 % lines, also run by CI) reads only `:app` class
  directories and `:app` exec data. Code moved into another module would not count. The domain code is the
  easiest to cover, so moving it out would make the gate harder to meet for what stays in `:app`.
- detekt is applied per module (`app/build.gradle.kts`). Spotless is configured at the root for `**/*.kt`.

## Options considered
### Option A — Single `:app` module, layered packages, purity enforced by detekt
- Pros: zero build changes; `verify.sh`, the CI coverage gate and detekt work as they are. Fastest to start.
  The `domain` package can be extracted into a module later, mechanically, if its imports stay clean.
- Cons: the compiler does not enforce the boundary, so a lint rule has to. detekt `ForbiddenImport` does not
  catch fully-qualified references without an import, such as `android.util.Log.d(...)`; code review must
  catch those. Domain tests run in the Android unit-test task, which is slightly slower than a plain JVM module.
### Option B — `:domain` (Kotlin/JVM) + `:app`
- Pros: the compiler enforces "no Android in domain"; domain tests need no Android classpath.
- Cons: build plumbing before any feature code. `:domain` needs a `testDebugUnitTest` alias task (or
  `verify.sh` must change), detekt applied to it, and JaCoCo aggregation of `:domain` classes and exec data
  into the `:app` report and gate. CI uploads only `app/build/reports`. Each of these is a place where a
  silent gap could hide untested code.
### Option C — Full modularization (`core:*`, `feature:*`)
- Pros: scales to large teams; build caching per feature.
- Cons: speculative for a one-maintainer app with ~6 screens. It multiplies the plumbing of Option B.

## Decision
We choose **Option A**: one `:app` module with these packages under `cz.mightybities.mightygestures`:

```
domain/                    pure Kotlin only: no android.*, androidx.* (kotlinx.coroutines allowed)
  model/                   Rule, RuleId, RuleConditions, TriggerSpec, ActionSpec, SpecialAccess, DeviceState
  engine/                  RuleEngine, armed-rule computation, cooldown
  trigger/                 TriggerSource contract, TriggerEvent
  trigger/motion/          gravity filter, segmenter, preprocessing, DTW matcher, template validator, MotionConfig
  action/                  ActionExecutor contract, ActionResult
  repository/              RuleRepository contract
  time/                    MonotonicClock, WallClock
data/                      DataStore + kotlinx.serialization DTOs, mappers, RuleRepository impl
platform/
  sensor/                  SensorManager adapter → primitive samples; HandlerThread
  accessibility/           MightyGesturesAccessibilityService, ScreenStateMonitor, host wiring
  action/                  Android ActionExecutor impls, LaunchOverKeyguardActivity (trampoline)
  apps/                    launcher-app catalog (package visibility via <queries>)
  access/                  special-access status checks + settings intents
ui/                        Compose screens, ViewModels, navigation, theme
di/                        AppContainer (ADR 0005)
MightyGesturesApp.kt       Application: creates the AppContainer
```

Enforcement: enable detekt `style>ForbiddenImport` with `forbiddenPatterns: '^(android|androidx)\..*'` and
`includes: ['**/domain/**']` in `config/detekt/detekt.yml`. detekt supports per-rule `includes`/`excludes` glob
filters (verified: https://detekt.dev/docs/1.23.0/introduction/configurations/ ; ForbiddenImport options:
https://detekt.dev/docs/1.23.0/rules/style/#forbiddenimport). The glob also covers domain tests, which must be
pure as well. Code review checks for fully-qualified `android.` references in `domain/`.

## Consequences
- Positive: no change to `verify.sh`, CI or the coverage gate. Domain purity is still machine-checked for
  imports.
- Negative / accepted trade-offs: the boundary is weaker than a module boundary. Domain tests run under the
  Android unit-test task.
- Follow-ups: revisit (supersede) when a second app-level consumer of the domain appears, or when build times
  justify extraction. Extraction is then a move plus Option B's plumbing.

## References
- AGENTS.md §4 (layers), §6 (task names)
- `scripts/verify.sh`, `app/build.gradle.kts` (JaCoCo wiring), `.github/workflows/ci.yml`
- detekt configuration and ForbiddenImport docs (links above)
