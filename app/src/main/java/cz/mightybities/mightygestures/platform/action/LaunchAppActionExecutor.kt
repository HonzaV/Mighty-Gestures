package cz.mightybities.mightygestures.platform.action

import android.content.Context
import android.content.Intent
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult

/**
 * Open-app action (spec 0001, "Actions"). Unlocked: `startActivity` directly (background activity starts are
 * allowed while the accessibility host is bound, ADR 0007 F6). Keyguard showing: hand off to
 * [LaunchOverKeyguardActivity] (decision 12), which re-resolves the package itself.
 */
class LaunchAppActionExecutor(
    private val context: Context,
) {
    fun execute(
        packageName: String,
        keyguardLocked: Boolean,
    ): ActionResult =
        if (keyguardLocked) {
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
