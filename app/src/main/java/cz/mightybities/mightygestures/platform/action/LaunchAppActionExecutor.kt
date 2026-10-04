package cz.mightybities.mightygestures.platform.action

import android.app.KeyguardManager
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
 * Routing uses [keyguardLockQuery]'s **live** state only (code review, PR #4 fix round 2). [keyguardLocked] is
 * a snapshot the caller (the rule engine, later PR) took when the gesture fired; it can be stale by the time
 * this runs, in either direction. Trusting a stale "locked" snapshot to route to the trampoline, when the
 * device is actually unlocked, used to report `Success` even though `requestDismissKeyguard` would then fail
 * with `ERROR` and launch nothing (MAJOR, security re-review LOW 2) — this executor no longer has that
 * failure mode, because it never routes on the snapshot. [keyguardLocked] is kept in the signature for the
 * caller's `ActionContext` shape; [LaunchOverKeyguardActivity] has its own live re-check as a second line of
 * defense against the same race landing the other way (locked at routing time, unlocked by the time the
 * bouncer's callback fires).
 */
class LaunchAppActionExecutor(
    private val context: Context,
    private val keyguardLockQuery: KeyguardLockQuery = AndroidKeyguardLockQuery(context),
) {
    @Suppress("UNUSED_PARAMETER") // kept for ActionContext's shape; routing uses the live check only, see KDoc
    fun execute(
        packageName: String,
        keyguardLocked: Boolean,
    ): ActionResult =
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
        context.startActivity(launchIntent)
        return ActionResult.Success
    }
}
