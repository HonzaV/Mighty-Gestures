package cz.mightybities.mightygestures.platform.action

import android.accessibilityservice.AccessibilityService
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle

/**
 * Lock screen action (spec 0001, "Actions"). One `GLOBAL_ACTION_LOCK_SCREEN` call (API 28, verified) covers
 * both "lock" and "turn off the display": no public API turns the display off without locking (ADR 0007 F8).
 */
class LockScreenActionExecutor {
    fun execute(): ActionResult {
        val host =
            AccessibilityHostHandle.host
                ?: return ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.ACCESSIBILITY_SERVICE))
        val succeeded = host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        // A false result means the system refused the action (spec 0001 defines no specific reason for this
        // case); reporting it as Unsupported is better than telling the user the phone locked when it did not
        // (security review finding, PR #4 fix round).
        return if (succeeded) ActionResult.Success else ActionResult.Failed(ActionFailure.Unsupported)
    }
}
