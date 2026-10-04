package cz.mightybities.mightygestures.platform.action

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Routing depends only on the **live** [KeyguardLockQuery] result (code review, PR #4 fix round 2); there is
 * no snapshot parameter to set up, so each test picks [KeyguardLockQuery] explicitly rather than relying on
 * the real, always-unlocked-by-default Robolectric `KeyguardManager`.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchAppActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `live unlocked launches the target app directly`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        // Our own package is registered as a launcher app by Robolectric from the manifest.
        val result = executor.execute(context.packageName)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(context.packageName, started.component?.packageName)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `live unlocked with an uninstalled package fails without crashing`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { false })

        val result = executor.execute("com.example.not.installed")

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `live locked starts the trampoline instead of the target directly`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = executor.execute(context.packageName)

        assertEquals(ActionResult.Success, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(LaunchOverKeyguardActivity::class.java.name, started.component?.className)
        assertEquals(context.packageName, started.getStringExtra(LaunchOverKeyguardActivity.EXTRA_PACKAGE_NAME))
    }

    @Test
    fun `live locked with an uninstalled package fails without starting the trampoline`() {
        val executor = LaunchAppActionExecutor(context, keyguardLockQuery = { true })

        val result = executor.execute("com.example.not.installed")

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `live unlocked target removed between resolution and launch fails as app not found`() {
        // The target can be uninstalled/disabled between getLaunchIntentForPackage and startActivity
        // (Copilot review, PR #5); startActivity then throws ActivityNotFoundException even though the
        // catalog check above passed. This must report the same AppNotFound outcome as that catalog miss,
        // not the dispatcher's generic Unexpected.
        val throwingContext = ThrowingStartActivityContext(context)
        val executor = LaunchAppActionExecutor(throwingContext, keyguardLockQuery = { false })

        val result = executor.execute(context.packageName)

        assertEquals(ActionResult.Failed(ActionFailure.AppNotFound), result)
    }

    @Test
    fun `live unlocked permission-guarded target propagates SecurityException uncaught`() {
        // Deliberately NOT caught in LaunchAppActionExecutor (see its executeUnlocked KDoc comment): the app
        // is installed here, so AppNotFound would misreport it, and AndroidActionExecutor's catch-all already
        // maps an uncaught exception to Failed(Unexpected). Pinned so a future change doesn't silently start
        // treating a permission-guarded target the same as an uninstalled one.
        val throwingContext = SecurityExceptionStartActivityContext(context)
        val executor = LaunchAppActionExecutor(throwingContext, keyguardLockQuery = { false })

        assertThrows(SecurityException::class.java) {
            executor.execute(context.packageName)
        }
    }

    /** A real [Context] whose [startActivity] always throws, to simulate the late-disappearing-target race. */
    private class ThrowingStartActivityContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException(intent.toString())
    }

    /** As [ThrowingStartActivityContext], but for a permission-guarded target. */
    private class SecurityExceptionStartActivityContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent): Unit = throw SecurityException("permission denied")
    }
}
