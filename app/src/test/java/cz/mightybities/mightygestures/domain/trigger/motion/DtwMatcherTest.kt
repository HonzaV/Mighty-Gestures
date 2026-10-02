package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0008 "Matcher": dependent multivariate DTW, Sakoe-Chiba band, normalized by path length. */
class DtwMatcherTest {
    @Test
    fun `identical sequences have zero distance`() {
        val dtw = DtwMatcher(frameCount = 4, bandRadius = 4)
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        assertEquals(0f, dtw.distance(a, a.copyOf(), dim = 2), 0f)
    }

    @Test
    fun `constant offset gives the squared offset as the per-step cost`() {
        val dtw = DtwMatcher(frameCount = 3, bandRadius = 3)
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 1f, 1f)
        // Every frame differs by 1 in 1 dimension: on the diagonal path (length 3), cost = 3*1 = 3,
        // normalized by path length 3 => 1.
        assertEquals(1f, dtw.distance(a, b, dim = 1), 1e-6f)
    }

    @Test
    fun `a narrow band rejects a time shift a wide band can absorb`() {
        // A single spike shifted by 3 frames: a band of radius >= 3 can realign it at zero cost;
        // a narrower band cannot reach that cell and is forced through a costlier path.
        val a = FloatArray(10).also { it[1] = 5f }
        val b = FloatArray(10).also { it[4] = 5f }
        val narrow = DtwMatcher(frameCount = 10, bandRadius = 1).distance(a, b, dim = 1)
        val wide = DtwMatcher(frameCount = 10, bandRadius = 3).distance(a, b, dim = 1)
        assertEquals(0f, wide, 0f)
        assertTrue("narrow band ($narrow) should cost more than a wide band ($wide)", narrow > wide)
    }

    @Test
    fun `reused matcher gives the same distance on repeated calls`() {
        val dtw = DtwMatcher(frameCount = 4, bandRadius = 2)
        val a = floatArrayOf(1f, 0f, 2f, 0f, 3f, 0f, 4f, 0f)
        val b = floatArrayOf(1f, 0f, 2f, 0f, 3f, 0f, 5f, 0f)
        val first = dtw.distance(a, b, dim = 2)
        val second = dtw.distance(a, b, dim = 2)
        assertEquals(first, second, 0f)
    }
}
