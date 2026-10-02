package cz.mightybities.mightygestures.platform.action

import android.accessibilityservice.AccessibilityService
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityActionHost
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LockScreenActionExecutorTest {
    private val executor = LockScreenActionExecutor()

    @After
    fun tearDown() {
        AccessibilityHostHandle.host = null
    }

    @Test
    fun `calls performGlobalAction LOCK_SCREEN on the bound host`() {
        var requestedAction: Int? = null
        AccessibilityHostHandle.host =
            object : AccessibilityActionHost {
                override fun performGlobalAction(globalAction: Int): Boolean {
                    requestedAction = globalAction
                    return true
                }
            }

        val result = executor.execute()

        assertEquals(ActionResult.Success, result)
        assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, requestedAction)
    }

    @Test
    fun `missing host fails instead of crashing`() {
        AccessibilityHostHandle.host = null

        val result = executor.execute()

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.ACCESSIBILITY_SERVICE)),
            result,
        )
    }
}
