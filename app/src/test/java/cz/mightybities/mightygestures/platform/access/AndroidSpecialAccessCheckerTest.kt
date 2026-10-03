package cz.mightybities.mightygestures.platform.access

import android.app.NotificationManager
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityActionHost
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidSpecialAccessCheckerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val checker = AndroidSpecialAccessChecker(context)

    @After
    fun tearDown() {
        AccessibilityHostHandle.host?.let { AccessibilityHostHandle.clear(it) }
    }

    @Test
    fun `notification policy access reflects NotificationManager`() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        assertFalse(checker.isGranted(SpecialAccess.NOTIFICATION_POLICY))

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        assertTrue(checker.isGranted(SpecialAccess.NOTIFICATION_POLICY))
    }

    @Test
    fun `accessibility service access reflects whether the host is bound`() {
        assertFalse(checker.isGranted(SpecialAccess.ACCESSIBILITY_SERVICE))

        AccessibilityHostHandle.publish(
            object : AccessibilityActionHost {
                override fun performGlobalAction(globalAction: Int) = true
            },
        )
        assertTrue(checker.isGranted(SpecialAccess.ACCESSIBILITY_SERVICE))
    }
}
