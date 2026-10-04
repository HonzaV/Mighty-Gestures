package cz.mightybities.mightygestures.platform.action

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult

/**
 * Thin seam over `KeyguardManager.isKeyguardLocked()`, so [LaunchAppActionExecutor] can be exercised in tests
 * without the real framework call.
 */
fun interface KeyguardLockQuery {
    fun isKeyguardLocked(): Boolean
}

class AndroidKeyguardLockQuery(
    private val context: Context,
) : KeyguardLockQuery {
    override fun isKeyguardLocked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
}

/**
 * Open-app action (spec 0001, "Actions"). Unlocked: `startActivity` directly (background activity starts are
 * allowed while the accessibility host is bound, ADR 0007 F6). Keyguard showing: hand off to
 * [LaunchOverKeyguardActivity] (decision 12), which re-resolves the package itself.
 *
 * Routing uses [keyguardLockQuery]'s **live** state, not `ActionContext.keyguardLocked`'s snapshot (code
 * review, PR #4 fix round 2). Trusting a stale "locked" snapshot to route to the trampoline, when the device
 * is actually unlocked, used to report `Success` even though `requestDismissKeyguard` would then fail with
 * `ERROR` and launch nothing (MAJOR, security re-review LOW 2). [LaunchOverKeyguardActivity] has its own live
 * re-check as a second line of defense against the same race landing the other way (locked at routing time,
 * unlocked by the time the bouncer's callback fires).
 */
class LaunchAppActionExecutor(
    private val context: Context,
    private val keyguardLockQuery: KeyguardLockQuery = AndroidKeyguardLockQuery(context),
) {
    fun execute(packageName: String): ActionResult =
        if (keyguardLockQuery.isKeyguardLocked()) {
            executeOverKeyguard(packageName)
        } else {
            executeUnlocked(packageName)
        }

    private fun executeOverKeyguard(packageName: String): ActionResult {
        // Re-resolved inside the trampoline too; this call only rejects an uninstalled package up front.
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) {
            return ActionResult.Failed(ActionFailure.AppNotFound)
        }
        LaunchOverKeyguardActivity.start(context, packageName)
        return ActionResult.Success
    }

    private fun executeUnlocked(packageName: String): ActionResult {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult.Failed(ActionFailure.AppNotFound)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            ActionResult.Success
        } catch (ignored: ActivityNotFoundException) {
            // The target vanished (e.g. disabled) between the catalog check above and here (Copilot review,
            // PR #5); same user-facing outcome as the catalog miss (AC-A3's "App not installed").
            //
            // A permission-guarded target's SecurityException (see LaunchOverKeyguardActivity.launchTarget) is
            // deliberately left uncaught here: the app *is* installed, so AppNotFound would misreport it, and
            // unlike the trampoline this call site has a caller to report to — AndroidActionExecutor's
            // catch-all turns it into Failed(Unexpected) without us inventing an unapproved ActionFailure case.
            ActionResult.Failed(ActionFailure.AppNotFound)
        }
    }
}
