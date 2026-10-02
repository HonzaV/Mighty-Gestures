package cz.mightybities.mightygestures.platform.access

import android.app.NotificationManager
import android.content.Context
import cz.mightybities.mightygestures.domain.model.SpecialAccess
import cz.mightybities.mightygestures.platform.accessibility.AccessibilityHostHandle

/**
 * Whether the user has granted a [SpecialAccess] Mighty Gestures needs (spec 0001, "Permissions, privacy &
 * security impact"). Used by action executors to fail with `MissingAccess` instead of silently doing nothing,
 * and by the UI (later PR) for "needs attention".
 */
interface SpecialAccessChecker {
    fun isGranted(access: SpecialAccess): Boolean
}

class AndroidSpecialAccessChecker(
    private val context: Context,
) : SpecialAccessChecker {
    override fun isGranted(access: SpecialAccess): Boolean =
        when (access) {
            SpecialAccess.NOTIFICATION_POLICY -> notificationManager.isNotificationPolicyAccessGranted

            // The host (PR #6) publishes itself to AccessibilityHostHandle only while bound, which is the same
            // signal Lock screen's executor relies on: "enabled in Settings but not currently bound" behaves
            // identically for a live action, so there is no separate Settings.Secure lookup here.
            SpecialAccess.ACCESSIBILITY_SERVICE -> AccessibilityHostHandle.host != null
        }

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
}
