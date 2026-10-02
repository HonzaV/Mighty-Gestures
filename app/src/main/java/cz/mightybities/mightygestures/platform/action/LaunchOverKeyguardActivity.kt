package cz.mightybities.mightygestures.platform.action

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity

/** Outcome of a keyguard dismiss request, independent of the framework's callback shape (see [KeyguardDismisser]). */
enum class KeyguardDismissOutcome { SUCCEEDED, CANCELLED, ERROR }

/**
 * Thin seam over `KeyguardManager.requestDismissKeyguard` (API 23, verified). Robolectric 4.17's
 * `ShadowKeyguardManager` only simulates the "keyguard already not locked" immediate-error path; it has no
 * public way to simulate a user-driven success or cancellation (docs/engineering/testing.md: "wrap the
 * framework call in a thin interface and fake it").
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
 * re-resolves the target package through the launcher catalog — never a raw component — and starts it.
 *
 * No `noHistory`: `requestDismissKeyguard`'s callback "will not be invoked if the activity was destroyed
 * before the callback was received" (verified, KeyguardManager docs), and `noHistory` could finish this
 * activity while the bouncer still covers it. Instead, every callback path (and a 60 s safety timeout) calls
 * [finishOnce] itself.
 */
class LaunchOverKeyguardActivity : ComponentActivity() {
    /** Overridable by tests (see class KDoc); defaults to the real framework call in production. */
    internal var keyguardDismisser: KeyguardDismisser = AndroidKeyguardDismisser()

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
            when (outcome) {
                KeyguardDismissOutcome.SUCCEEDED -> launchTarget(packageName)
                KeyguardDismissOutcome.CANCELLED, KeyguardDismissOutcome.ERROR -> finishOnce()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(safetyTimeout)
        super.onDestroy()
    }

    private fun launchTarget(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
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
