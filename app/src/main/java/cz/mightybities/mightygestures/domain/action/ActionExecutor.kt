package cz.mightybities.mightygestures.domain.action

import cz.mightybities.mightygestures.domain.model.ActionSpec
import cz.mightybities.mightygestures.domain.model.SpecialAccess

/**
 * Runs an [ActionSpec]. Implementations (`platform/action`) know nothing about triggers (AC-A8); the engine
 * never inspects a `TriggerSpec`'s contents either (ADR 0004).
 */
interface ActionExecutor {
    suspend fun execute(
        action: ActionSpec,
        context: ActionContext,
    ): ActionResult
}

/**
 * State the executor needs to pick behavior (spec 0001, "Per-action behavior on the lock screen"). This is
 * the engine's snapshot taken when the gesture fired; it can be stale by the time an executor actually runs
 * (ADR 0004). Where staleness matters, an executor re-queries the live platform state itself instead of
 * trusting this snapshot (e.g. `LaunchAppActionExecutor` and `KeyguardManager.isKeyguardLocked()`, code
 * review, PR #4 fix round 2).
 */
data class ActionContext(
    val keyguardLocked: Boolean,
)

sealed interface ActionResult {
    data object Success : ActionResult

    data class Failed(
        val reason: ActionFailure,
    ) : ActionResult
}

sealed interface ActionFailure {
    data class MissingAccess(
        val access: SpecialAccess,
    ) : ActionFailure

    data object AppNotFound : ActionFailure

    data object TorchUnavailable : ActionFailure

    /** e.g. ringer mode cannot change because `AudioManager.isVolumeFixed()`. */
    data object Unsupported : ActionFailure

    /**
     * Additive to ADR 0004's `ActionFailure` shape (maintainer decision 2026-10-04): the app-owned Do Not
     * Disturb `AutomaticZenRule` exists but the user disabled it in Settings. Android ignores condition
     * updates for a disabled rule (*inferred*; not confirmed by reading AOSP's `ZenModeHelper`), so the
     * gesture must never re-enable or recreate the rule — that would override the user's own Settings choice.
     * Milestone #5's UI tells the user to re-enable the mode in Settings instead.
     */
    data object DoNotDisturbModeDisabled : ActionFailure

    /**
     * Additive to ADR 0004's `ActionFailure` shape (PR #4 fix round): an executor threw an exception the
     * dispatcher ([cz.mightybities.mightygestures.platform.action.AndroidActionExecutor]) did not expect and
     * could not map to a more specific reason. Carries **no payload** — an exception message can contain a
     * package name or other identifying detail, which must never be logged or stored (AGENTS.md §5).
     */
    data object Unexpected : ActionFailure
}
