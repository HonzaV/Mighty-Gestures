package cz.mightybities.mightygestures.platform.action

import android.content.Intent
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Routing depends only on the **live** [KeyguardLockQuery] result (code review, PR #4 fix round 2); the
 * `keyguardLocked` snapshot parameter is accepted but intentionally not used for routing (see the executor's
 * KDoc), so most tests here set [KeyguardLockQuery] explicitly rather than relying on the real,
 * always-unlocked-by-default Robolectric `KeyguardManager`.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchAppActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `live unlocked launches the target app directly`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        // Our own package is registered as a launcher app by Robolectric from the manifest.
        val result = executor.execute(context.packageName, keyguardLocked = false)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(context.packageName, started.component?.packageName)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `live unlocked with an uninstalled package fails without crashing`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        val result = executor.execute("com.example.not.installed", keyguardLocked = false)

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `live locked starts the trampoline instead of the target directly`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = executor.execute(context.packageName, keyguardLocked = true)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
        assertEquals(context.packageName, started.getStringExtra(LaunchOverKeyguardActivity.EXTRA_PACKAGE_NAME))
    }

    @Test
    fun `live locked with an uninstalled package fails without starting the trampoline`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = executor.execute("com.example.not.installed", keyguardLocked = true)

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `a stale unlocked snapshot is overridden by the live locked keyguard state`() {
        // The caller's keyguardLocked snapshot says "unlocked", but the keyguard has since shown; routing on
        // the live state must still use the trampoline instead of a direct, likely-rejected startActivity.
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = executor.execute(context.packageName, keyguardLocked = false)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `a stale locked snapshot with the live state unlocked launches directly instead`() {
        // MAJOR (code review, PR #4 fix round 2): trusting a stale "locked" snapshot used to route to the
        // trampoline and report Success even though requestDismissKeyguard would then fail immediately
        // (keyguard already unlocked) and launch nothing. Routing must ignore the snapshot entirely.
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        val result = executor.execute(context.packageName, keyguardLocked = true)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(context.packageName, started.component?.packageName)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
