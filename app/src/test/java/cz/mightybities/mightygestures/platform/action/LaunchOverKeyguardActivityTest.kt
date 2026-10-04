package cz.mightybities.mightygestures.platform.action

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInstrumentation

/**
 * Exercises the trampoline through a fake [KeyguardDismisser] (see its KDoc) for test isolation: a fake keeps
 * each case to the one outcome under test, rather than coaxing it out of `ShadowKeyguardManager`'s shared
 * static state. `AndroidKeyguardDismisserTest` and the end-to-end tests below cover the real glue separately.
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
    fun `dismiss error while the device is actually still locked finishes without launching anything`() {
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.ERROR)
        activity.keyguardLockQuery = KeyguardLockQuery { true }

        controller.create()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `dismiss error while the device is actually unlocked still launches the target`() {
        // requestDismissKeyguard also reports ERROR when the keyguard was already unlocked at call time
        // (AndroidKeyguardDismisserTest), which races with LaunchAppActionExecutor's own live check: by the
        // time the bouncer activity runs, the device may have unlocked. ERROR must not always mean "give up".
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.ERROR)
        activity.keyguardLockQuery = KeyguardLockQuery { false }

        controller.create()

        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started.component?.packageName == context.packageName)
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

    @Test
    fun `dismiss success with a package uninstalled between the check and the launch finishes without crashing`() {
        // LaunchAppActionExecutor only checks installation before starting the trampoline (AC-A3); the target
        // can still disappear while the bouncer is up. launchTarget's re-resolution must handle that itself.
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor("com.example.not.installed"),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.SUCCEEDED)

        controller.create()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(shadows = [ThrowingStartActivityInstrumentationShadow::class])
    fun `target vanishing between the check and the launch finishes instead of crashing`() {
        // LaunchAppActionExecutor only checks installation before starting the trampoline (AC-A3); the
        // target can still disappear (e.g. disabled) while the bouncer is up, so startActivity itself can
        // throw ActivityNotFoundException. That must not crash the process that also hosts the accessibility
        // service.
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        activity.keyguardDismisser = FakeKeyguardDismisser(KeyguardDismissOutcome.SUCCEEDED)

        controller.create()

        assertTrue(activity.isFinishing)
    }

    /**
     * Forces `Activity.startActivity`'s real path (`Instrumentation.execStartActivity`) to throw
     * `ActivityNotFoundException`, standing in for the target vanishing between
     * `PackageManager.getLaunchIntentForPackage` succeeding and `startActivity` actually running — Robolectric's
     * real `ShadowInstrumentation` never throws it from here itself (docs/engineering/testing.md).
     */
    @Implements(Instrumentation::class)
    class ThrowingStartActivityInstrumentationShadow : ShadowInstrumentation() {
        @Suppress("UnusedParameter")
        @Implementation
        override fun execStartActivity(
            who: Context,
            contextThread: IBinder?,
            token: IBinder?,
            target: Activity?,
            intent: Intent,
            requestCode: Int,
            options: Bundle?,
        ): Instrumentation.ActivityResult = throw ActivityNotFoundException(intent.action)
    }

    @Test
    fun `onDestroy cancels the safety timeout so it never fires on a gone activity`() {
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
                    // Never calls onResult: the activity is torn down (e.g. the user leaves) before any
                    // outcome arrives.
                }
            }
        controller.create()
        assertTrue(activity.hasPendingSafetyTimeout())

        controller.destroy()

        assertTrue(!activity.hasPendingSafetyTimeout())
    }

    /**
     * Drives the trampoline through the real [AndroidKeyguardDismisser] (default `keyguardDismisser`), not the
     * fake, so a swapped outcome mapping would be caught here even if [AndroidKeyguardDismisserTest] did not
     * exist.
     */
    @Test
    fun `end-to-end with the real dismisser launches the target once the keyguard unlocks`() {
        val keyguardManager = context.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguardManager).setKeyguardLocked(true)
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        controller.create()
        assertNull(shadowOf(activity).nextStartedActivity)

        shadowOf(keyguardManager).setKeyguardLocked(false)

        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started.component?.packageName == context.packageName)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `end-to-end with the real dismisser a repeated locked report cancels the dismiss and launches nothing`() {
        // ShadowKeyguardManager.setKeyguardLocked(true) while a dismiss is pending reports CANCELLED, not
        // ERROR (AndroidKeyguardDismisserTest), so this exercises the CANCELLED path end to end, not a
        // "bouncer stays up forever" scenario — there is no such outcome to simulate via the real dismisser.
        val keyguardManager = context.getSystemService(android.app.KeyguardManager::class.java)
        shadowOf(keyguardManager).setKeyguardLocked(true)
        val controller =
            Robolectric.buildActivity(
                LaunchOverKeyguardActivity::class.java,
                intentFor(context.packageName),
            )
        val activity = controller.get()
        controller.create()

        shadowOf(keyguardManager).setKeyguardLocked(true)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
