package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR 0008 decision G1: a deterministic, device-independent low-pass gravity estimate. */
class GravityFilterTest {
    private val filter = GravityFilter(timeConstantNanos = 250_000_000L)

    @Test
    fun `seeds from the first raw sample instead of starting at zero`() {
        filter.update(timestampNanos = 0L, accX = 1f, accY = 2f, accZ = 9f)
        // Seeded exactly: no fake "linear acceleration" reading before the filter converges.
        assertEquals(1f, filter.gravityX, 0f)
        assertEquals(2f, filter.gravityY, 0f)
        assertEquals(9f, filter.gravityZ, 0f)
    }

    @Test
    fun `converges towards a sustained new value over several time constants`() {
        filter.update(0L, 0f, 0f, 9.81f)
        var t = 0L
        repeat(20) {
            t += 50_000_000L
            filter.update(t, 0f, 0f, 19.62f) // a sustained step to double gravity.
        }
        // After 1 s (4 time constants), the estimate should have moved most of the way.
        assertEquals(19.62f, filter.gravityZ, 0.5f)
    }

    @Test
    fun `reset drops the estimate so the next update reseeds`() {
        filter.update(0L, 1f, 1f, 1f)
        filter.reset()
        filter.update(1_000_000L, 5f, 5f, 5f)
        assertEquals(5f, filter.gravityX, 0f)
    }

    @Test
    fun `seed sets the estimate directly without requiring an update`() {
        filter.seed(x = 3f, y = 4f, z = 5f, timestampNanos = 10L)
        assertEquals(3f, filter.gravityX, 0f)
        assertEquals(4f, filter.gravityY, 0f)
        assertEquals(5f, filter.gravityZ, 0f)
    }

    @Test
    fun `non-monotonic or duplicate timestamps are ignored`() {
        filter.update(100L, 1f, 1f, 1f)
        filter.update(100L, 99f, 99f, 99f) // duplicate timestamp: must not move the estimate.
        assertEquals(1f, filter.gravityX, 0f)
        filter.update(50L, 99f, 99f, 99f) // earlier timestamp: also ignored.
        assertEquals(1f, filter.gravityX, 0f)
    }
}
