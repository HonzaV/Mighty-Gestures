package cz.mightybities.mightygestures.domain.model

/**
 * What a [cz.mightybities.mightygestures.domain.model.Rule] (ADR 0004) does when its trigger fires. Plain data:
 * behavior lives in `domain.action.ActionExecutor` implementations under `platform/action`. Actions never
 * reference trigger types (AC-A8).
 */
sealed interface ActionSpec {
    data class LaunchApp(
        val packageName: String,
        val label: String,
    ) : ActionSpec

    data object ToggleTorch : ActionSpec

    data object LockScreen : ActionSpec

    data object ToggleDoNotDisturb : ActionSpec

    data class SetRingerMode(
        val mode: RingerMode,
    ) : ActionSpec
}

enum class RingerMode { NORMAL, VIBRATE, SILENT }

/** Special, user-granted access an action needs to run from the background (ADR 0004). */
enum class SpecialAccess { ACCESSIBILITY_SERVICE, NOTIFICATION_POLICY }

/**
 * Access this action needs when fired from the background. The UI uses it to show "needs attention" without
 * running the action (spec 0001, "Needs attention").
 */
fun ActionSpec.requiredAccess(): Set<SpecialAccess> =
    when (this) {
        is ActionSpec.LaunchApp -> emptySet()
        ActionSpec.ToggleTorch -> emptySet()
        ActionSpec.LockScreen -> setOf(SpecialAccess.ACCESSIBILITY_SERVICE)
        ActionSpec.ToggleDoNotDisturb -> setOf(SpecialAccess.NOTIFICATION_POLICY)
        is ActionSpec.SetRingerMode -> setOf(SpecialAccess.NOTIFICATION_POLICY)
    }
