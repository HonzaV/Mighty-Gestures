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
 * [keyguardLocked] is a snapshot the caller (the rule engine, later PR) took when the gesture fired; it can be
 * stale by the time this runs. [keyguardLockQuery] re-checks the live state, and the trampoline is used if
 * *either* says locked (security review finding, PR #4 fix round): a direct `startActivity` while the
 * keyguard is actually showing would silently fail or expose the wrong activity.
 */
class LaunchAppActionExecutor(
    private val context: Context,
    private val keyguardLockQuery: KeyguardLockQuery = AndroidKeyguardLockQuery(context),
) {
    fun execute(
        packageName: String,
        keyguardLocked: Boolean,
    ): ActionResult =
        if (keyguardLocked || keyguardLockQuery.isKeyguardLocked()) {
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
