package cz.mightybities.mightygestures.platform.action

import android.app.NotificationManager
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class ToggleDoNotDisturbActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val shadowNotificationManager: ShadowNotificationManager = shadowOf(notificationManager)

    private class FakeSpecialAccessChecker(
        private val granted: Boolean,
    ) : SpecialAccessChecker {
        override fun isGranted(access: SpecialAccess) = granted
    }

    @Test
    fun `without notification policy access fails instead of crashing`() {
        val executor = ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = false))

        val result = executor.execute()

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY)),
            result,
        )
    }

    @Test
    fun `first run creates and turns on the Mighty Gestures zen rule`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val executor = ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true))

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
        val executor = ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true))

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
        val executor = ToggleDoNotDisturbActionExecutor(context, FakeSpecialAccessChecker(granted = true))
        executor.execute()
        assertEquals(1, notificationManager.automaticZenRules.size)

        executor.removeZenRule()

        assertEquals(0, notificationManager.automaticZenRules.size)
    }
}
