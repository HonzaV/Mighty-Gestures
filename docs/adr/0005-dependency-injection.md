# ADR 0005 — Dependency injection: manual container

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
v1 has a small object graph. Process-wide singletons: the rule repository (one DataStore instance per file per
process is mandatory), sensor adapter, clocks, dispatchers, action executor, launcher-app catalog and
special-access checker. Per-screen ViewModels sit on top. The graph has two Android entry points: `MainActivity`
(ViewModels) and the AccessibilityService. Both run in the default process.
AGENTS.md §4 requires injected dispatchers and clocks. f-droid.md requires a justification for every new
dependency. ADR 0003 keeps a single `:app` module.

## Options considered
### Option A — Manual DI (`AppContainer` created in `Application`)
- Pros: no dependency, no annotation processing, no generated code. Construction is plain Kotlin, so tests
  substitute fakes by passing different constructor arguments. ViewModels use AndroidX
  `viewModelFactory { initializer { … } }` (lifecycle-viewmodel, already transitively present).
- Cons: wiring is hand-written (~100 lines). Scoping is by convention (process singletons as `val`s, everything
  else created per use).
### Option B — Hilt (Dagger) + KSP
- Pros: compile-time-checked graph, standard Android scopes, `@AndroidEntryPoint` for the service.
- Cons: adds `com.google.dagger:hilt-android`, `hilt-compiler`, the KSP plugin and a Hilt Gradle plugin, plus a
  Navigation 3 ViewModel integration. Annotation processing slows builds and adds generated classes that JaCoCo
  must exclude, which means touching the coverage exclusions. FOSS (Apache-2.0) and allowed by AGENTS.md §2,
  but heavy for this graph.
### Option C — Koin
- Pros: lightweight runtime DI, Kotlin DSL.
- Cons: new dependency. Graph errors surface at runtime, not compile time. It adds little over Option A at
  this size.

## Decision
We choose **Option A — manual DI**.

```kotlin
// di/AppContainer.kt
interface AppContainer {
    val ruleRepository: RuleRepository
    val deviceStateHolder: DeviceStateHolder        // screen/keyguard/capture state shared by service + UI
    val motionSampleSourceFactory: MotionSampleSourceFactory
    val actionExecutor: ActionExecutor
    val launcherApps: LauncherAppCatalog
    val specialAccess: SpecialAccessChecker
    val accessibilityHostHandle: AccessibilityHostHandle  // non-null service ref while bound (for Lock "Run now")
    val monotonicClock: MonotonicClock
    val wallClock: WallClock
    val dispatchers: AppDispatchers                 // default, io, main — never hard-coded in logic
}
class DefaultAppContainer(context: Context) : AppContainer { /* lazy vals */ }

class MightyGesturesApp : Application() {
    lateinit var container: AppContainer            // set in onCreate; tests may replace before use
}
```
`lateinit` is allowed here: AGENTS.md §5 forbids it only in domain code. The service reads
`(application as MightyGesturesApp).container`. Routes obtain ViewModels through factories that pull from the
container, so composables below the route never see the container.

## Consequences
- Positive: no new dependency, no codegen, and coverage needs no extra exclusions. Robolectric tests can
  install a fake container via a test `Application` or by replacing `container`.
- Negative / accepted trade-offs: wiring mistakes surface at runtime on first access, so a smoke test that
  constructs `DefaultAppContainer` and touches every property guards against them.
- Follow-ups: supersede with Hilt if the graph outgrows one file (> ~25 bindings) or multiple modules appear.

## References
- AGENTS.md §4–§5; docs/engineering/f-droid.md (dependency policy)
- AndroidX ViewModel factories: https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-factories
- ADR 0003 (single module), ADR 0006 (single DataStore instance)
