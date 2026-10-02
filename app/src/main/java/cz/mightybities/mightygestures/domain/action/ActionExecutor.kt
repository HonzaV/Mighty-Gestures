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

/** State the executor needs to pick behavior (spec 0001, "Per-action behavior on the lock screen"). */
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
}
