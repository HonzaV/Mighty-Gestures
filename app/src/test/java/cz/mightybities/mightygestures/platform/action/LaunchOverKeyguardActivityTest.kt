package cz.mightybities.mightygestures.platform.action

import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the trampoline through a fake [KeyguardDismisser] (see its KDoc): Robolectric's
 * `ShadowKeyguardManager` cannot simulate a user-driven success or cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchOverKeyguardActivityTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun intentFor(packageName: String?): Intent =
        Intent(context, LaunchOverKeyguardActivity::class.java).apply {
            packageName?.let { putExtra(LaunchOverKeyguardActivity.EXTRA_PACKAGE_NAME, it) }
        }

    private class FakeKeyguardDismisser(
        private val outcome: KeyguardDismissOutcome,
    ) : KeyguardDismisser {
        var requested = false

        override fun requestDismiss(
            activity: android.app.Activity,
            onResult: (KeyguardDismissOutcome) -> Unit,
        ) {
            requested = true
            onResult(outcome)
        }
    }

    @Test
    fun `dismiss success launches the target and finishes`() {
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        val fakeDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.SUCCEEDED)
        activity.keyguardDismisser = fakeDismisser

        controller.create()

        assertTrue(fakeDismisser.requested)
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started.component?.packageName == context.packageName)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `dismiss cancelled finishes without launching anything`() {
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.CANCELLED)

        controller.create()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `dismiss error finishes without launching anything`() {
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.ERROR)

        controller.create()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `missing package extra finishes immediately without requesting a dismiss`() {
        val controller = Robolectric.buildActivity(LaunchOverKeyguardActivity::class.java, intentFor(null))
        val activity = controller.get()
        val fakeDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.SUCCEEDED)
        activity.keyguardDismisser = fakeDismisser

        controller.create()

        assertTrue(!fakeDismisser.requested)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `safety timeout finishes the activity if no callback ever arrives`() {
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser =
            object : KeyguardDismisser {
                override fun requestDismiss(
                    activity: android.app.Activity,
                    onResult: (KeyguardDismissOutcome) -> Unit,
                ) {
                    // Never calls onResult, simulating a bouncer that is dismissed neither way.
                }
            }

        controller.create()
        assertTrue(!activity.isFinishing)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))

        assertTrue(activity.isFinishing)
    }
}
