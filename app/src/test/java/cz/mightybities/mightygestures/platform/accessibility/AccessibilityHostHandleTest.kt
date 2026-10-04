package cz.mightybities.mightygestures.platform.accessibility

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityHostHandleTest {
    private fun fakeHost() =
        object : AccessibilityActionHost {
            override fun performGlobalAction(globalAction: Int) = true
        }

    @After
    fun tearDown() {
        // AccessibilityHostHandle is a process-wide singleton; a test that forgot to clear it would leak
        // into whichever test runs next.
        AccessibilityHostHandle.host?.let { AccessibilityHostHandle.clear(it) }
    }

    @Test
    fun `publish makes the host readable`() {
        val host = fakeHost()

        AccessibilityHostHandle.publish(host)

        assertSame(host, AccessibilityHostHandle.host)
    }

    @Test
    fun `clear by the publisher nulls the host`() {
        val host = fakeHost()
        AccessibilityHostHandle.publish(host)

        AccessibilityHostHandle.clear(host)

        assertNull(AccessibilityHostHandle.host)
    }

    @Test
    fun `clear by a different instance than the current one is a no-op`() {
        // Simulates a fast rebind: the old service's onUnbind/onDestroy runs after the new one already
        // published itself. The old instance must not be able to null out the new, live host.
        val oldHost = fakeHost()
        val newHost = fakeHost()
        AccessibilityHostHandle.publish(oldHost)
        AccessibilityHostHandle.publish(newHost)

        AccessibilityHostHandle.clear(oldHost)

        assertSame(newHost, AccessibilityHostHandle.host)
    }

    @Test
    fun `clear when nothing is published is a no-op`() {
        AccessibilityHostHandle.clear(fakeHost())

        assertNull(AccessibilityHostHandle.host)
    }
}
