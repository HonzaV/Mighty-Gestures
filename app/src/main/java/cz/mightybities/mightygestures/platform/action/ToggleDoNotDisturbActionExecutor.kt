package cz.mightybities.mightygestures.platform.action

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.Condition
import cz.mightybities.mightygestures.R
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker

/**
 * Do Not Disturb action (spec 0001, "Actions"; decision 8). Apps targeting API 35+ cannot change global DND
 * (`setInterruptionFilter` now only toggles an implicit app-owned rule, verified: Android 15 behavior changes).
 * We therefore own an **explicit** `AutomaticZenRule` named after the app (`R.string.app_name`), found again
 * by its fixed `conditionId` (no rule ID is persisted) and toggled via
 * `getAutomaticZenRuleState`/`setAutomaticZenRuleState`.
 *
 * `zenPolicy = null` is *inferred* to mean "the user's default Do Not Disturb policy"; unverified on a device
 * (spec 0001 AC-A6 is a non-device acceptance criterion in this PR).
 *
 * [configurationActivity] is injected (`MainActivity`'s `ComponentName`, by whoever wires this executor)
 * rather than referenced by class here, so `platform/action` does not import an app-level `ui`/top-level
 * activity class (code review, PR #4 fix round 2).
 *
 * If the rule exists but the user disabled it in Settings (`AutomaticZenRule.isEnabled == false`), we fail
 * with [ActionFailure.DoNotDisturbModeDisabled] instead of toggling it: re-enabling or recreating the rule
 * here would override the user's own Settings choice (maintainer decision 2026-10-04).
 */
class ToggleDoNotDisturbActionExecutor(
    private val context: Context,
    private val specialAccessChecker: SpecialAccessChecker,
    private val configurationActivity: ComponentName,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val conditionId: android.net.Uri by lazy {
        Condition.newId(context).appendPath(RULE_PATH).build()
    }

    fun execute(): ActionResult {
        if (!specialAccessChecker.isGranted(SpecialAccess.NOTIFICATION_POLICY)) {
            return ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY))
        }
        return try {
            toggleRule()
        } catch (ignored: SecurityException) {
            // Access can also be revoked between the check above and these calls.
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY))
        }
    }

    /**
     * Called when the last Do Not Disturb gesture is deleted (AC-A6); wiring is a later PR's responsibility.
     * Best-effort and `Unit`, not `Boolean`: deleting a gesture must succeed from the user's point of view even
     * if cleaning up the zen rule fails, and the caller has no decision to make on a `false`/exception either
     * way. Access can be revoked (e.g. in Settings) between the gesture being deleted and this running, so both
     * the lookup and the removal are guarded against [SecurityException] (Copilot review, PR #5).
     */
    fun removeZenRule() {
        try {
            findRule()?.key?.let { notificationManager.removeAutomaticZenRule(it) }
        } catch (ignored: SecurityException) {
            // Nothing to clean up without access; the rule (if any) is simply left behind.
        }
    }

    /**
     * The condition carries [Condition.SOURCE_USER_ACTION] (a gesture is an explicit user action) instead of the
     * 3-arg constructor's `SOURCE_UNKNOWN`. Verified in AOSP `ZenModeHelper.applyConditionAndReconsiderOverride`
     * (main, read 2026-10-04): with the `modes_ui` flag, a user-action condition from the owning app replaces a
     * manual on/off override the user set in Settings, whereas any other source keeps that override until the
     * app's condition agrees with it. Without `modes_ui` (the `android15-release` branch), the source only
     * re-attributes the change to the user; a manual "turn off" (snooze) may then need one extra gesture to clear
     * (*inferred*).
     * Which shipped releases enable `modes_ui` is *inferred* (Android 16+), not verified.
     */
    private fun toggleRule(): ActionResult {
        val existingRule = findRule()
        if (existingRule != null && !existingRule.value.isEnabled) {
            return ActionResult.Failed(ActionFailure.DoNotDisturbModeDisabled)
        }
        val ruleId = existingRule?.key ?: notificationManager.addAutomaticZenRule(buildRule())
        val currentlyOn = notificationManager.getAutomaticZenRuleState(ruleId) == Condition.STATE_TRUE
        val newState = if (currentlyOn) Condition.STATE_FALSE else Condition.STATE_TRUE
        notificationManager.setAutomaticZenRuleState(
            ruleId,
            Condition(conditionId, ruleName, newState, Condition.SOURCE_USER_ACTION),
        )
        return ActionResult.Success
    }

    private fun findRule(): Map.Entry<String, AutomaticZenRule>? =
        notificationManager.automaticZenRules.entries
            .firstOrNull { (_, rule) -> rule.conditionId == conditionId }

    private fun buildRule(): AutomaticZenRule =
        AutomaticZenRule(
            ruleName,
            null,
            configurationActivity,
            conditionId,
            null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )

    private val ruleName: String
        get() = context.getString(R.string.app_name)

    private companion object {
        const val RULE_PATH = "dnd"
    }
}
