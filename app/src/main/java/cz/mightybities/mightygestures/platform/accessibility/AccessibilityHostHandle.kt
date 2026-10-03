package cz.mightybities.mightygestures.platform.accessibility

import java.util.concurrent.atomic.AtomicReference

/**
 * The subset of `AccessibilityService` capability that action executors need (ADR 0007). Kept as an interface,
 * not the Android class itself, so `platform/action` does not depend on the accessibility host implementation
 * (`MightyGesturesAccessibilityService`, PR #6) and can be exercised in tests with a fake.
 */
interface AccessibilityActionHost {
    /** @return whatever `AccessibilityService.performGlobalAction` returns (API 28, verified). */
    fun performGlobalAction(globalAction: Int): Boolean
}

/**
 * Published by the accessibility host while bound, cleared on `onUnbind`/`onDestroy` (PR #6). Null means the
 * host is not running: actions that need it (e.g. Lock screen) report `MissingAccess(ACCESSIBILITY_SERVICE)`
 * instead of failing silently (AC-A5).
 *
 * `clear` only removes the instance that published it (`compareAndSet`), not whatever is current. On a fast
 * service rebind, the old instance's `onUnbind`/`onDestroy` can run after the new instance's
 * `onServiceConnected` already published itself; without this check, the old instance's cleanup would null
 * out the live host instead of being a no-op (security review finding, PR #4 fix round).
 */
object AccessibilityHostHandle {
    private val current = AtomicReference<AccessibilityActionHost?>(null)

    val host: AccessibilityActionHost?
        get() = current.get()

    fun publish(host: AccessibilityActionHost) {
        current.set(host)
    }

    /** No-op unless [host] is still the currently published instance. */
    fun clear(host: AccessibilityActionHost) {
        current.compareAndSet(host, null)
    }
}
