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

@RunWith(RobolectricTestRunner::class)
class LaunchAppActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val executor = LaunchAppActionExecutor(context)

    @Test
    fun `unlocked launches the target app directly`() {
        // Our own package is registered as a launcher app by Robolectric from the manifest.
        val result = executor.execute(context.packageName, keyguardLocked = false)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(context.packageName, started.component?.packageName)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `unlocked with an uninstalled package fails without crashing`() {
        val result = executor.execute("com.example.not.installed", keyguardLocked = false)

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `locked starts the trampoline instead of the target directly`() {
        val result = executor.execute(context.packageName, keyguardLocked = true)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
        assertEquals(context.packageName, started.getStringExtra(LaunchOverKeyguardActivity.EXTRA_PACKAGE_NAME))
    }

    @Test
    fun `locked with an uninstalled package fails without starting the trampoline`() {
        val result = executor.execute("com.example.not.installed", keyguardLocked = true)

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `a stale unlocked snapshot is overridden by the live keyguard state`() {
        // The caller's keyguardLocked snapshot says "unlocked", but the keyguard has since shown; re-checking
        // must still route to the trampoline instead of a direct, likely-rejected startActivity.
        val liveLockedExecutor =
            LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = liveLockedExecutor.execute(context.packageName, keyguardLocked = false)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `a stale locked snapshot still uses the trampoline even if the live state is unlocked`() {
        val liveUnlockedExecutor =
            LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        val result = liveUnlockedExecutor.execute(context.packageName, keyguardLocked = true)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
    }
}
