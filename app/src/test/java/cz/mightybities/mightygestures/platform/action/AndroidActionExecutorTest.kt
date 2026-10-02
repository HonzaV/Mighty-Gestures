package cz.mightybities.mightygestures.platform.action

import android.accessibilityservice.AccessibilityService
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import cz.mightybities.mightygestures.domain.action.ActionContext
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.ActionSpec
import cz.mightybities.mightygestures.domain.model.RingerMode
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.domain.time.AppDispatchers
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityActionHost
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics

/** Dispatch tests: each branch's own behavior is covered by that executor's dedicated test. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val grantedAccessChecker =
        object : SpecialAccessChecker {
            override fun isGranted(access: SpecialAccess) = true
        }
    private val dispatchers = AppDispatchers(main = UnconfinedTestDispatcher())
    private val actionContext = ActionContext(keyguardLocked = false)

    private val executor =
        AndroidActionExecutor(
            launchApp = LaunchAppActionExecutor(context),
            toggleTorch = ToggleTorchActionExecutor(context),
            lockScreen = LockScreenActionExecutor(),
            toggleDoNotDisturb = ToggleDoNotDisturbActionExecutor(context, grantedAccessChecker),
            setRingerMode = SetRingerModeActionExecutor(context, grantedAccessChecker),
            dispatchers = dispatchers,
        )

    @After
    fun tearDown() {
        AccessibilityHostHandle.host = null
    }

    @Test
    fun `dispatches LaunchApp`() =
        runTest {
            val result = executor.execute(ActionSpec.LaunchApp(context.packageName, "Mighty Gestures"), actionContext)

            assertEquals(ActionResult.Success, result)
            assertEquals(context.packageName, shadowOf(context).nextStartedActivity.component?.packageName)
        }

    @Test
    fun `dispatches ToggleTorch`() =
        runTest {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
            shadowOf(characteristics).set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            val cameraManager = context.getSystemService(CameraManager::class.java)
            shadowOf(cameraManager).addCamera("0", characteristics)

            val result = executor.execute(ActionSpec.ToggleTorch, actionContext)

            assertEquals(ActionResult.Success, result)
            assertEquals(true, shadowOf(cameraManager).getTorchMode("0"))
        }

    @Test
    fun `dispatches LockScreen`() =
        runTest {
            var requestedAction: Int? = null
            AccessibilityHostHandle.host =
                object : AccessibilityActionHost {
                    override fun performGlobalAction(globalAction: Int): Boolean {
                        requestedAction = globalAction
                        return true
                    }
                }

            val result = executor.execute(ActionSpec.LockScreen, actionContext)

            assertEquals(ActionResult.Success, result)
            assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, requestedAction)
        }

    @Test
    fun `dispatches ToggleDoNotDisturb`() =
        runTest {
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

            val result = executor.execute(ActionSpec.ToggleDoNotDisturb, actionContext)

            assertEquals(ActionResult.Success, result)
            assertEquals(1, notificationManager.automaticZenRules.size)
        }

    @Test
    fun `dispatches SetRingerMode`() =
        runTest {
            val audioManager = context.getSystemService(AudioManager::class.java)

            val result = executor.execute(ActionSpec.SetRingerMode(RingerMode.SILENT), actionContext)

            assertEquals(ActionResult.Success, result)
            assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        }

    @Test
    fun `routes the keyguard-locked context to the trampoline`() =
        runTest {
            val result =
                executor.execute(
                    ActionSpec.LaunchApp(context.packageName, "Mighty Gestures"),
                    ActionContext(keyguardLocked = true),
                )

            assertEquals(ActionResult.Success, result)
            val started = shadowOf(context).nextStartedActivity
            assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
        }
}
