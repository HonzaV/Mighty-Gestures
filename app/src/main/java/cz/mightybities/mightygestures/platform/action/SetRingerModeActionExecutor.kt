package cz.mightybities.mightygestures.platform.action

import android.content.Context
import android.media.AudioManager
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.RingerMode
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.access.SpecialAccessChecker

/**
 * Sound mode action (spec 0001, "Actions"; decision 9). "Ringer mode adjustments that would toggle Do Not
 * Disturb are not allowed unless the app has been granted Notification Policy Access" (verified,
 * `AudioManager#setRingerMode` docs); which transitions touch DND varies by device, so **all** sound-mode
 * gestures require notification-policy access, not just the ones that provably need it.
 */
class SetRingerModeActionExecutor(
    private val context: Context,
    private val specialAccessChecker: SpecialAccessChecker,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun execute(mode: RingerMode): ActionResult =
        when {
            !specialAccessChecker.isGranted(SpecialAccess.NOTIFICATION_POLICY) -> {
                ActionResult.Failed(ActionFailure.MissingAccess(SpecialAccess.NOTIFICATION_POLICY))
            }

            audioManager.isVolumeFixed -> {
                ActionResult.Failed(ActionFailure.Unsupported)
            }

            else -> {
                audioManager.ringerMode =
                    when (mode) {
                        RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
                    }
                ActionResult.Success
            }
        }
}
