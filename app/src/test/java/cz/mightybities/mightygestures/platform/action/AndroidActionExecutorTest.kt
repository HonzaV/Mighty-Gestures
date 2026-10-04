package cz.mightybities.mightygestures.platform.action

import android.accessibilityservice.AccessibilityService
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import cz.mightybities.mightygestures.domain.action.ActionContext
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.ActionSpec
import cz.mightybities.mightygestures.domain.model.RingerMode
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.domain.time.AppDispatchers
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityActionHost
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    private val configurationActivity =
        android.content.ComponentName(context, cz.mightybities.mightygestures.MainActivity::class.java)

    private val executor =
        AndroidActionExecutor(
            launchApp = LaunchAppActionExecutor(context),
            toggleTorch = ToggleTorchActionExecutor(context),
            lockScreen = LockScreenActionExecutor(),
            toggleDoNotDisturb = ToggleDoNotDisturbActionExecutor(context, grantedAccessChecker, configurationActivity),
            setRingerMode = SetRingerModeActionExecutor(context, grantedAccessChecker),
            dispatchers = dispatchers,
        )

    @After
    fun tearDown() {
        AccessibilityHostHandle.host?.let { AccessibilityHostHandle.clear(it) }
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
            AccessibilityHostHandle.publish(
                object : AccessibilityActionHost {
                    override fun performGlobalAction(globalAction: Int): Boolean {
                        requestedAction = globalAction
                        return true
                    }
                },
            )

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
    fun `routes to the trampoline when the device is actually locked`() =
        runTest {
            // LaunchAppActionExecutor routes on the live keyguard state, not the ActionContext snapshot
            // (code review, PR #4 fix round 2); set the real KeyguardManager's shadow state so the default
            // AndroidKeyguardLockQuery this dispatcher's executor was built with sees it as locked.
            val keyguardManager = context.getSystemService(android.app.KeyguardManager::class.java)
            shadowOf(keyguardManager).setKeyguardLocked(true)

            val result =
                executor.execute(
                    ActionSpec.LaunchApp(context.packageName, "Mighty Gestures"),
                    ActionContext(keyguardLocked = false),
                )

            assertEquals(ActionResult.Success, result)
            val started = shadowOf(context).nextStartedActivity
            assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
        }

    /**
     * `AndroidActionExecutor.execute()` wraps its dispatch in a catch-all (fix round 1): any sub-executor
     * exception, even a framework one already known to escape [ToggleTorchActionExecutor] (see
     * [ToggleTorchActionExecutorTest]), still reaches `Failed`, never the caller, through the dispatcher too.
     */
    @Test
    @Config(shadows = [ToggleTorchActionExecutorTest.DisconnectedCameraManagerShadow::class])
    fun `a known executor exception reaches the dispatcher as Failed, not a propagated exception`() =
        runTest {
            val result = executor.execute(ActionSpec.ToggleTorch, actionContext)

            assertEquals(ActionResult.Failed(ActionFailure.TorchUnavailable), result)
        }

    /**
     * Code review (PR #4 fix round 2): nothing previously exercised the dispatcher's catch-all with an
     * exception that is not already mapped to a specific `ActionFailure` by the sub-executor itself — this is
     * exactly the case `ActionFailure.Unexpected` exists for.
     */
    @Test
    @Config(shadows = [UnexpectedExceptionCameraManagerShadow::class])
    fun `an unmapped executor exception becomes Failed Unexpected`() =
        runTest {
            val result = executor.execute(ActionSpec.ToggleTorch, actionContext)

            assertEquals(ActionResult.Failed(ActionFailure.Unexpected), result)
        }

    /**
     * Code review (PR #4 fix round 2): the dispatcher must rethrow `CancellationException` rather than
     * mapping it to `Failed`, so cooperative cancellation of the caller (the rule engine, a later PR) still
     * works.
     */
    @Test
    @Config(shadows = [CancellingCameraManagerShadow::class])
    fun `a CancellationException from an executor propagates instead of becoming Failed`() =
        runTest {
            var caught: CancellationException? = null

            try {
                executor.execute(ActionSpec.ToggleTorch, actionContext)
            } catch (cancellation: CancellationException) {
                caught = cancellation
            }

            assertTrue("expected a CancellationException to propagate", caught != null)
        }

    /** Throws a plain `IllegalStateException` — not caught by any `ToggleTorchActionExecutor` catch clause. */
    @Implements(CameraManager::class)
    class UnexpectedExceptionCameraManagerShadow {
        @Implementation
        fun getCameraIdList(): Array<String> = error("camera service in a bad state")

        @Suppress("UnusedParameter")
        @Implementation
        fun registerTorchCallback(
            callback: CameraManager.TorchCallback,
            handler: android.os.Handler?,
        ) {
            // No-op.
        }
    }

    /** Throws `kotlinx.coroutines.CancellationException`, standing in for a cancelled caller coroutine. */
    @Implements(CameraManager::class)
    class CancellingCameraManagerShadow {
        @Implementation
        fun getCameraIdList(): Array<String> = throw CancellationException("cancelled")

        @Suppress("UnusedParameter")
        @Implementation
        fun registerTorchCallback(
            callback: CameraManager.TorchCallback,
            handler: android.os.Handler?,
        ) {
            // No-op.
        }
    }
}
