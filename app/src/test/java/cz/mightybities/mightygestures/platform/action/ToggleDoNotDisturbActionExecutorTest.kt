package cz.mightybities.mightygestures.platform.action

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.Condition
import cz.mightybities.mightygestures.MainActivity
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class ToggleDoNotDisturbActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)
    private val configurationActivity = ComponentName(context, MainActivity::class.java)

    private class FakeSpecialAccessChecker(
        private val granted: Boolean,
    ) : SpecialAccessChecker {
        override fun isGranted(access: SpecialAccess) = granted
    }

    @Test
    fun `without notification policy access fails instead of crashing`() {
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = false), configurationActivity)

        val result = executor.execute()

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY)),
            result,
        )
    }

    @Test
    fun `first run creates and turns on the Mighty Gestures zen rule`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)

        val result = executor.execute()

        assertEquals(ActionResult.Success, result)
        val rules = notificationManager.automaticZenRules
        assertEquals(1, rules.size)
        val rule = rules.values.first()
        assertEquals(context.getString(cz.mightybities.mightygestures.R.string.app_name), rule.name)
        val ruleId = rules.keys.first()
        assertEquals(
            android.service.notification.Condition.STATE_TRUE,
            notificationManager.getAutomaticZenRuleState(ruleId),
        )
    }

    @Test
    fun `toggles the existing rule off then on again`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)

        executor.execute() // off -> on
        executor.execute() // on -> off

        val ruleId = notificationManager.automaticZenRules.keys.first()
        assertEquals(
            android.service.notification.Condition.STATE_FALSE,
            notificationManager.getAutomaticZenRuleState(ruleId),
        )
        // No duplicate rule was created on the second call.
        assertEquals(1, notificationManager.automaticZenRules.size)
    }

    @Test
    fun `removeZenRule removes a previously created rule`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)
        executor.execute()
        assertEquals(1, notificationManager.automaticZenRules.size)

        executor.removeZenRule()

        assertEquals(0, notificationManager.automaticZenRules.size)
    }

    @Test
    fun `the created rule matches decision 8 (own mode, not global DND)`() {
        // AC-A6 names this an "app-owned" mode: PRIORITY filter (not global), owned via a configuration
        // activity (AutomaticZenRule requires one of owner/configurationActivity, ADR text), named after the
        // app. A test asserting only `rule.name` and the toggled state (as this file did before) would still
        // pass if the rule used the wrong filter or no configuration activity at all.
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)

        executor.execute()

        val rule = notificationManager.automaticZenRules.values.first()
        assertEquals(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY, rule.interruptionFilter)
        assertEquals(configurationActivity, rule.configurationActivity)
        assertTrue(rule.isEnabled)
    }

    @Test
    fun `without access does not touch NotificationManager at all`() {
        // Grant policy access in the shadow so this assertion call itself does not throw (ShadowNotificationManager
        // enforces the same access for reads), but deny it through the checker so the code under test must be
        // the one skipping the call.
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = false), configurationActivity)

        executor.execute()

        assertEquals(0, notificationManager.automaticZenRules.size)
    }

    @Test
    fun `notification policy access revoked between the check and the call fails instead of crashing`() {
        // The executor's own SpecialAccessChecker says granted, but the real NotificationManager (e.g. the
        // user revoked policy access in Settings a moment earlier) says otherwise: toggleRule() must still
        // report MissingAccess, not let the SecurityException escape.
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)

        val result = executor.execute()

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY)),
            result,
        )
    }

    @Test
    fun `removeZenRule with access revoked does not throw`() {
        // The last Do Not Disturb gesture being deleted must not crash even if notification policy access was
        // revoked (e.g. in Settings) since the rule was created: removeZenRule() is best-effort.
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)
        executor.execute()
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)

        executor.removeZenRule() // must not throw
    }

    @Test
    @Config(shadows = [ConditionCapturingShadowNotificationManager::class])
    fun `the condition reported to setAutomaticZenRuleState carries SOURCE_USER_ACTION`() {
        // See toggleRule()'s KDoc: SOURCE_USER_ACTION is what lets a gesture override a rule the user
        // previously activated or deactivated manually (AOSP ZenModeHelper.reconsiderConditionOverride).
        ConditionCapturingShadowNotificationManager.lastCondition = null
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)

        executor.execute()

        val condition = ConditionCapturingShadowNotificationManager.lastCondition
        assertEquals(Condition.SOURCE_USER_ACTION, condition?.source)
    }

    /**
     * Captures the [Condition] passed to `setAutomaticZenRuleState`: the real
     * [ShadowNotificationManager] only records its `state` field (`getAutomaticZenRuleState`), not `source`
     * (verified: javap on robolectric 4.17's shadows-framework jar), so this seam extends it the same way
     * [LaunchOverKeyguardActivityTest]'s `ThrowingStartActivityInstrumentationShadow` extends a shadow for one
     * method while delegating everything else to the real implementation.
     */
    @Implements(NotificationManager::class)
    class ConditionCapturingShadowNotificationManager : ShadowNotificationManager() {
        @Implementation
        override fun setAutomaticZenRuleState(
            id: String,
            condition: Condition,
        ) {
            lastCondition = condition
            super.setAutomaticZenRuleState(id, condition)
        }

        companion object {
            var lastCondition: Condition? = null
        }
    }

    @Test
    fun `rule disabled externally (e g in Settings) fails instead of re-enabling or recreating it`() {
        // Maintainer decision 2026-10-04: Android ignores condition updates for a disabled rule, so the
        // gesture must respect the user's Settings choice instead of silently turning it back on.
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)
        executor.execute() // creates the rule, turns it on (STATE_TRUE)
        val ruleId = notificationManager.automaticZenRules.keys.first()
        val originalRule = notificationManager.automaticZenRules.getValue(ruleId)

        // Simulate the user disabling the mode from Settings directly (not through this executor).
        val disabledRule =
            AutomaticZenRule(
                originalRule.name,
                originalRule.owner,
                originalRule.configurationActivity,
                originalRule.conditionId,
                originalRule.zenPolicy,
                originalRule.interruptionFilter,
                false,
            )
        notificationManager.updateAutomaticZenRule(ruleId, disabledRule)
        // Precondition: isEnabled survives the shadow's round-trip, so the setup actually disabled the rule.
        assertEquals(false, notificationManager.automaticZenRules.getValue(ruleId).isEnabled)

        val result = executor.execute()

        assertEquals(ActionResult.Failed(ActionFailure.DoNotDisturbModeDisabled), result)
        // Neither re-enabled nor recreated: same single rule, still disabled, state untouched.
        assertEquals(1, notificationManager.automaticZenRules.size)
        assertEquals(ruleId, notificationManager.automaticZenRules.keys.first())
        assertEquals(false, notificationManager.automaticZenRules.getValue(ruleId).isEnabled)
        assertEquals(
            android.service.notification.Condition.STATE_TRUE,
            notificationManager.getAutomaticZenRuleState(ruleId),
        )
    }

    @Test
    fun `rule deleted externally (e g in Settings) is recreated on the next toggle, turned on`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor =
            ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true), configurationActivity)
        executor.execute() // creates the rule, turns it on
        val originalRuleId = notificationManager.automaticZenRules.keys.first()

        // Simulate the user deleting the mode from Settings directly (not through removeZenRule()).
        notificationManager.removeAutomaticZenRule(originalRuleId)
        assertEquals(0, notificationManager.automaticZenRules.size)

        val result = executor.execute()

        assertEquals(ActionResult.Success, result)
        assertEquals(1, notificationManager.automaticZenRules.size)
        val newRuleId = notificationManager.automaticZenRules.keys.first()
        assertEquals(
            android.service.notification.Condition.STATE_TRUE,
            notificationManager.getAutomaticZenRuleState(newRuleId),
        )
    }
}
