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
}
