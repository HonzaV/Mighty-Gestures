package cz.mightybities.mightygestures.platform.accessibility

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
 */
object AccessibilityHostHandle {
    @Volatile
    var host: AccessibilityActionHost? = null
}
