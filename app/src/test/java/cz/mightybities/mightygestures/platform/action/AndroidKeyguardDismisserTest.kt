package cz.mightybities.mightygestures.platform.action

import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [AndroidKeyguardDismisser] is the real glue over `KeyguardManager.requestDismissKeyguard` and was
 * previously untested (the trampoline tests only exercise a hand-written fake, per its KDoc). That KDoc also
 * understates what `ShadowKeyguardManager` 4.17 can simulate: besides the immediate "already unlocked" error
 * (`requestDismissKeyguard` offsets 0-27, read from `ShadowKeyguardManager.class`), `setKeyguardLocked` while a
 * callback is pending fires `onDismissSucceeded`/`onDismissCancelled` on it (offsets 52-62, decompiled from the
 * shadow's `.class` file: unlock -> succeeded, (re-)lock -> cancelled). All three outcomes are reachable without
 * a fake, so a swapped SUCCEEDED/CANCELLED/ERROR mapping in [AndroidKeyguardDismisser] would not be caught by
 * [LaunchOverKeyguardActivityTest]'s fake-based tests alone.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyguardDismisserTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
    private val dismisser = AndroidKeyguardDismisser()

    @Test
    fun `reports an immediate error when the keyguard is already unlocked`() {
        var outcome: KeyguardDismissOutcome? = null

        dismisser.requestDismiss(activity) { outcome = it }

        assertEquals(KeyguardDismissOutcome.ERROR, outcome)
    }

    @Test
    fun `reports succeeded once a locked keyguard becomes unlocked`() {
        shadowOf(activity.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        var outcome: KeyguardDismissOutcome? = null
        dismisser.requestDismiss(activity) { outcome = it }
        assertEquals(null, outcome) // still pending: the keyguard has not been dismissed yet

        shadowOf(activity.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)

        assertEquals(KeyguardDismissOutcome.SUCCEEDED, outcome)
    }

    @Test
    fun `reports cancelled if the user leaves the keyguard locked`() {
        shadowOf(activity.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        var outcome: KeyguardDismissOutcome? = null
        dismisser.requestDismiss(activity) { outcome = it }

        // The bouncer stays up (e.g. the user backs out) instead of being dismissed.
        shadowOf(activity.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)

        assertEquals(KeyguardDismissOutcome.CANCELLED, outcome)
    }
}
