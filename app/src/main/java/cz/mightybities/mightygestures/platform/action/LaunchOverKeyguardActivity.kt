package cz.mightybities.mightygestures.platform.action

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting

/** Outcome of a keyguard dismiss request, independent of the framework's callback shape (see [KeyguardDismisser]). */
enum class KeyguardDismissOutcome { SUCCEEDED, CANCELLED, ERROR }

/**
 * Thin seam over `KeyguardManager.requestDismissKeyguard` (API 23, verified), so
 * [LaunchOverKeyguardActivity] can be driven by a hand-written fake instead of the real framework callback in
 * most tests — [AndroidKeyguardDismisserTest] covers the real glue separately (docs/engineering/testing.md:
 * test isolation, not a Robolectric capability gap).
 */
interface KeyguardDismisser {
    fun requestDismiss(
        activity: Activity,
        onResult: (KeyguardDismissOutcome) -> Unit,
    )
}

class AndroidKeyguardDismisser : KeyguardDismisser {
    override fun requestDismiss(
        activity: Activity,
        onResult: (KeyguardDismissOutcome) -> Unit,
    ) {
        val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
        keyguardManager.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = onResult(KeyguardDismissOutcome.SUCCEEDED)

                override fun onDismissCancelled() = onResult(KeyguardDismissOutcome.CANCELLED)

                override fun onDismissError() = onResult(KeyguardDismissOutcome.ERROR)
            },
        )
    }
}

/**
 * Trampoline for "Open app" while the keyguard is showing (spec 0001 decision 12). Not exported; started by
 * [LaunchAppActionExecutor]. Requests the keyguard dismiss (bouncer for a secure keyguard); on success,
 * re-resolves the target package via `PackageManager.getLaunchIntentForPackage` — never a raw component — and
 * starts it.
 *
 * No `noHistory`: `requestDismissKeyguard`'s callback "will not be invoked if the activity was destroyed
 * before the callback was received" (verified, KeyguardManager docs), and `noHistory` could finish this
 * activity while the bouncer still covers it. Instead, every callback path (and a 60 s safety timeout) calls
 * [finishOnce] itself.
 *
 * An `ERROR` outcome does not always mean "still locked": `requestDismissKeyguard` also reports it when the
 * keyguard was already unlocked at call time (code review, PR #4 fix round 2), which races with
 * [LaunchAppActionExecutor]'s own live check. So `ERROR` re-checks [KeyguardManager.isKeyguardLocked] itself:
 * unlocked means the race resolved in our favor and the target still opens; locked means a genuine refusal.
 */
class LaunchOverKeyguardActivity : ComponentActivity() {
    /** Overridable by tests (see class KDoc); defaults to the real framework call in production. */
    internal var keyguardDismisser: KeyguardDismisser = AndroidKeyguardDismisser()

    /** Overridable by tests; defaults to the real framework call in production. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var keyguardLockQuery: KeyguardLockQuery = AndroidKeyguardLockQuery(this)

    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val safetyTimeout = Runnable { finishOnce() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (packageName == null) {
            finishOnce()
            return
        }
        handler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MILLIS)
        keyguardDismisser.requestDismiss(this) { outcome ->
            onDismissOutcome(outcome, packageName)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(safetyTimeout)
        super.onDestroy()
    }

    /**
     * Test-only: whether the safety timeout is still scheduled. `ShadowPausedMessageQueue.internalGetSize()`
     * throws on SDK 37 ("size() is not supported ... use Handler.hasMessages or hasCallbacks instead",
     * verified from the exception message), so tests use this instead of inspecting the looper's queue size.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun hasPendingSafetyTimeout(): Boolean = handler.hasCallbacks(safetyTimeout)

    private fun onDismissOutcome(
        outcome: KeyguardDismissOutcome,
        packageName: String,
    ) {
        when (outcome) {
            KeyguardDismissOutcome.SUCCEEDED -> {
                launchTarget(packageName)
            }

            KeyguardDismissOutcome.CANCELLED -> {
                finishOnce()
            }

            KeyguardDismissOutcome.ERROR -> {
                if (keyguardLockQuery.isKeyguardLocked()) {
                    finishOnce()
                } else {
                    launchTarget(packageName)
                }
            }
        }
    }

    private fun launchTarget(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
            } catch (ignored: ActivityNotFoundException) {
                // The target vanished (e.g. disabled) between LaunchAppActionExecutor's check and here; this
                // process also hosts the accessibility service, so it must not crash (AC-A3).
            } catch (ignored: SecurityException) {
                // A launcher activity guarded by android:permission would refuse us the same way; must not
                // crash the process that also hosts the accessibility service (security review, PR #4).
            }
        }
        finishOnce()
    }

    private fun finishOnce() {
        if (finished) return
        finished = true
        handler.removeCallbacks(safetyTimeout)
        finish()
    }

    companion object {
        internal const val EXTRA_PACKAGE_NAME = "cz.mightybities.mightygestures.EXTRA_PACKAGE_NAME"
        private const val SAFETY_TIMEOUT_MILLIS = 60_000L

        fun start(
            context: Context,
            packageName: String,
        ) {
            val intent =
                Intent(context, LaunchOverKeyguardActivity::class.java)
                    .putExtra(EXTRA_PACKAGE_NAME, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
