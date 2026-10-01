# ADR 0004 — Trigger → Rule → Action domain model and extension points

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
AGENTS.md §4: a `Trigger` emits events, a `Rule` binds a trigger to one or more `Action`s plus conditions,
and actions know nothing about triggers. v1 has one trigger type, motion. v2 adds touchscreen gestures and v3
an edge handle overlay; both must plug in as new trigger types without touching action code.

User-facing terms differ from domain terms (spec 0001, "Wording"):

| User sees | Domain |
|---|---|
| Gesture | `Rule` |
| Trigger: Motion | `TriggerSpec.Motion` (trigger type) |
| Movement (recorded and confirmed) | configuration of `TriggerSpec.Motion`: a `MotionTemplate` |
| Action | `ActionSpec` |
| "Allow on lock screen" | `RuleConditions.allowOnLockScreen` |

The persisted form of this model is hard to change after release (ADR 0006), so its shape needs a decision now.

## Options considered
### Option A — Sealed specs + per-type runtime sources/executors (data separate from behavior)
`Rule` holds plain-data `TriggerSpec` and `ActionSpec` sealed hierarchies. Behavior lives in
`TriggerSource` (one per trigger type) and `ActionExecutor` (Android implementation in `platform/action`).
The `RuleEngine` routes `TriggerEvent(ruleId)` to the rule's actions.
- Pros: specs serialize trivially and exhaustive `when` catches unhandled types at compile time. Trigger and
  action sides never reference each other. Easy to fake in tests.
- Cons: adding a trigger type touches the sealed `TriggerSpec` file, its DTO/serializer, and the source
  registration in the host. Action code stays untouched, which is the requirement.
### Option B — Open polymorphism (interfaces + plugin registry with string type IDs)
- Pros: new types need no edit to a central sealed file.
- Cons: loses exhaustiveness checks, needs a runtime registry and reflective or hand-written serializer
  registration. Speculative generality for 3 known trigger types.
### Option C — "Movement" as its own top-level entity (user's wording: Rule = movement)
- Cons: violates AGENTS.md §4 and couples the motion template to the rule identity. v2/v3 triggers would need
  parallel entities. Rejected.

## Decision
We choose **Option A**.

```kotlin
// domain/model — pure Kotlin
@JvmInline value class RuleId(val value: String)            // UUID string

data class Rule(
    val id: RuleId,
    val name: String,
    val enabled: Boolean,
    val trigger: TriggerSpec,
    val actions: List<ActionSpec>,                           // non-empty; v1 UI creates exactly one
    val conditions: RuleConditions,
    val createdAtEpochMillis: Long,
)

data class RuleConditions(val allowOnLockScreen: Boolean)   // "screen on" is a global precondition in v1

sealed interface TriggerSpec {
    data class Motion(val template: MotionTemplate) : TriggerSpec
    // v2: data class Touch(...) : TriggerSpec      v3: data class EdgeHandle(...) : TriggerSpec
}

sealed interface ActionSpec {
    data class LaunchApp(val packageName: String, val label: String) : ActionSpec
    data object ToggleTorch : ActionSpec
    data object LockScreen : ActionSpec
    data object ToggleDoNotDisturb : ActionSpec
    data class SetRingerMode(val mode: RingerMode) : ActionSpec
}
enum class RingerMode { NORMAL, VIBRATE, SILENT }

enum class SpecialAccess { ACCESSIBILITY_SERVICE, NOTIFICATION_POLICY }
/** Access an action needs when fired from the background; UI uses it for "needs attention". */
fun ActionSpec.requiredAccess(): Set<SpecialAccess>

data class DeviceState(val interactive: Boolean, val keyguardLocked: Boolean, val captureInProgress: Boolean)

// domain/trigger
data class TriggerEvent(val ruleId: RuleId, val elapsedRealtimeNanos: Long)
/** One per trigger type. The engine tells it which rules are armed; it reports which fired. */
interface TriggerSource {
    fun events(armedRules: Flow<List<Rule>>): Flow<TriggerEvent>
}

// domain/action
interface ActionExecutor {
    suspend fun execute(action: ActionSpec, context: ActionContext): ActionResult
}
data class ActionContext(val keyguardLocked: Boolean)
sealed interface ActionResult {
    data object Success : ActionResult
    data class Failed(val reason: ActionFailure) : ActionResult  // MissingAccess, AppNotFound, TorchUnavailable, …
}

// domain/engine
class RuleEngine(/* rules: Flow<List<Rule>>, deviceState: Flow<DeviceState>,
                    sources: Map<KClass<out TriggerSpec>, TriggerSource>, executor: ActionExecutor,
                    clock: MonotonicClock, config: EngineConfig */)
```

Engine contract:
1. **Armed rules** = `enabled` rules, empty when `!interactive` or `captureInProgress`. When
   `keyguardLocked`, only rules with `allowOnLockScreen`. Each source receives only its own type's armed rules.
   An empty list must make the source release its hardware (sensors unregistered).
2. On `TriggerEvent`, re-check the rule against the *current* `DeviceState` (state may have changed since
   arming), apply the per-rule cooldown, then execute the rule's actions in order. Results are reported
   in-process only: no logging of sensor data, and nothing identifying in release logs.
3. The engine never inspects a `TriggerSpec`'s contents, and executors never see a `TriggerSpec`.

The motion `TriggerSource` (spec 0001, ADR 0008) picks at most **one** rule per movement: the best match.

## Consequences
- Positive: v2/v3 add a `TriggerSpec` subtype, a DTO, a `TriggerSource` and UI for configuring it. `ActionSpec`,
  executors and the engine stay untouched. Conditions extend `RuleConditions` without touching triggers or
  actions.
- Negative / accepted trade-offs: `actions` is a list although v1 allows only one, so the persisted format needs
  no migration when multi-action arrives. The name `Rule` clashes with JUnit's `org.junit.Rule` in tests: use
  an import alias.
- Follow-ups: when v2 lands, `DeviceState` may need more fields (foreground app, orientation). Add them as
  conditions, not as trigger-specific code paths.

## References
- AGENTS.md §4
- Spec [0001 — Motion gestures](../specs/0001-motion-gestures.md)
- ADR 0006 (persistence of this model), ADR 0008 (motion trigger)
