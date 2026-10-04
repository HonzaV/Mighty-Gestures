package cz.mightybities.mightygestures.platform.action

import android.media.AudioManager
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.RingerMode
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
class SetRingerModeActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private class FakeSpecialAccessChecker(
        private val granted: Boolean,
    ) : SpecialAccessChecker {
        override fun isGranted(access: SpecialAccess) = granted
    }

    @Test
    fun `without notification policy access fails instead of crashing`() {
        val executor = SetRingerModeActionExecutor(context, FakeSpecialAccessChecker(granted = false))

        val result = executor.execute(RingerMode.SILENT)

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY)),
            result,
        )
    }

    @Test
    fun `sets the ringer mode when access is granted`() {
        val executor = SetRingerModeActionExecutor(context, FakeSpecialAccessChecker(granted = true))

        assertEquals(ActionResult.Success, executor.execute(RingerMode.SILENT))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        assertEquals(ActionResult.Success, executor.execute(RingerMode.VIBRATE))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)

        assertEquals(ActionResult.Success, executor.execute(RingerMode.NORMAL))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    @Config(shadows = [FixedVolumeAudioManagerShadow::class])
    fun `a fixed-volume device reports Unsupported instead of changing the ringer`() {
        val executor = SetRingerModeActionExecutor(context, FakeSpecialAccessChecker(granted = true))

        val result = executor.execute(RingerMode.SILENT)

        assertEquals(ActionResult.Failed(ActionFailure.Unsupported), result)
    }

    /**
     * Robolectric 4.17's `ShadowAudioManager` does not shadow `isVolumeFixed()` at all (verified: no
     * `@Implementation` for it), so it falls through to the real, resource-backed implementation whose result
     * depends on device config. This dedicated shadow makes the "fixed volume" case deterministic
     * (docs/engineering/testing.md).
     */
    @Implements(AudioManager::class)
    class FixedVolumeAudioManagerShadow : ShadowAudioManager() {
        // A fixed return value is the point of this shadow: it stands in for a real fixed-volume device.
        @Suppress("FunctionOnlyReturningConstant")
        @Implementation
        fun isVolumeFixed(): Boolean = true
    }

    /**
     * AC-A7: "Without access -> `Failed(MissingAccess)`". `AudioManager#setRingerMode` documents that
     * "Ringer mode adjustments that would toggle Do Not Disturb are not allowed unless the app has been
     * granted Notification Policy Access" — i.e. the framework itself can refuse with a `SecurityException`
     * for a transition that touches DND, independent of our own `SpecialAccessChecker` check a moment earlier
     * (the same revoke-between-check-and-call race `ToggleDoNotDisturbActionExecutor` guards against too).
     */
    @Test
    @Config(shadows = [SecurityExceptionAudioManagerShadow::class])
    fun `notification policy access revoked between the check and the call fails instead of crashing`() {
        val executor = SetRingerModeActionExecutor(context, FakeSpecialAccessChecker(granted = true))

        val result = executor.execute(RingerMode.SILENT)

        assertEquals(
            ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY)),
            result,
        )
    }

    /**
     * Stands in for the framework refusing a ringer-mode change that would toggle Do Not Disturb without
     * notification-policy access (verified docs quote above); Robolectric's real `ShadowAudioManager` never
     * throws from `setRingerMode`.
     */
    @Implements(AudioManager::class)
    class SecurityExceptionAudioManagerShadow : ShadowAudioManager() {
        @Suppress("UnusedParameter")
        @Implementation
        override fun setRingerMode(mode: Int): Unit = throw SecurityException("notification policy access denied")
    }
}
