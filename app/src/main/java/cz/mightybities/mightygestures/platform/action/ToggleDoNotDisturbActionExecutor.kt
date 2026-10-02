package cz.mightybities.mightygestures.platform.action

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.Condition
import cz.mightybities.mightygestures.MainActivity
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker

/**
 * Do Not Disturb action (spec 0001, "Actions"; decision 8). Apps targeting API 35+ cannot change global DND
 * (`setInterruptionFilter` now only toggles an implicit app-owned rule, verified: Android 15 behavior changes).
 * We therefore own an **explicit** `AutomaticZenRule` named "Mighty Gestures", found again by its fixed
 * `conditionId` (no rule ID is persisted) and toggled via `getAutomaticZenRuleState`/`setAutomaticZenRuleState`.
 *
 * `zenPolicy = null` is *inferred* to mean "the user's default Do Not Disturb policy"; unverified on a device
 * (spec 0001 AC-A6 is a non-device acceptance criterion in this PR).
 */
class ToggleDoNotDisturbActionExecutor(
    private val context: Context,
    private val specialAccessChecker: SpecialAccessChecker,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val conditionId: android.net.Uri by lazy {
        Condition.newId(context).appendPath(RULE_PATH).build()
    }

    fun execute(): ActionResult {
        val ruleId =
            resolveRuleId()
                ?: return ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY))
        val currentlyOn = notificationManager.getAutomaticZenRuleState(ruleId) == Condition.STATE_TRUE
        val newState = if (currentlyOn) Condition.STATE_FALSE else Condition.STATE_TRUE
        notificationManager.setAutomaticZenRuleState(ruleId, Condition(conditionId, RULE_NAME, newState))
        return ActionResult.Success
    }

    /** Called when the last Do Not Disturb gesture is deleted (AC-A6); wiring is a later PR's responsibility. */
    fun removeZenRule() {
        findRuleId()?.let { notificationManager.removeAutomaticZenRule(it) }
    }

    /** Null means "no notification-policy access", whether detected up front or via `SecurityException`. */
    private fun resolveRuleId(): String? {
        if (!specialAccessChecker.isGranted(SpecialAccess.NOTIFICATION_POLICY)) return null
        return try {
            findRuleId() ?: notificationManager.addAutomaticZenRule(buildRule())
        } catch (expected: SecurityException) {
            null
        }
    }

    private fun findRuleId(): String? =
        notificationManager.automaticZenRules.entries
            .firstOrNull { (_, rule) -> rule.conditionId == conditionId }
            ?.key

    private fun buildRule(): AutomaticZenRule =
        AutomaticZenRule(
            RULE_NAME,
            null,
            ComponentName(context, MainActivity::class.java),
            conditionId,
            null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )

    private companion object {
        const val RULE_NAME = "Mighty Gestures"
        const val RULE_PATH = "dnd"
    }
}
