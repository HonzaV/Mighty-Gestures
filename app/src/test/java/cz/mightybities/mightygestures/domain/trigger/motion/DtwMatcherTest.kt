package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    /**
     * GitHub Copilot PR #6 round 2: an equal-cost pair of predecessors with *different* path
     * lengths let the old "prefer diag, else up, else left" tie-break record a different warping
     * path length for `distance(a, b)` than for `distance(b, a)` (the up/left roles swap under
     * operand transposition, "diag" doesn't — see [DtwMatcher]'s class KDoc), even though the raw
     * minimum cost itself was always symmetric. Dividing that cost by a direction-dependent length
     * made the final normalized distance asymmetric too: Copilot's isolated repro hit cost 8 over a
     * path of 84 frames one way and 82 the other, so a threshold of 0.0964 (8/84 = 0.0952,
     * 8/82 = 0.0976) gave opposite match verdicts depending on operand order.
     *
     * Rather than hand-copy a human-found 64 x 6 pair of floats into source, this searches a small,
     * deterministic sweep of seeded, quantized ({-1, 0, 1}) frame pairs — the same frame count and
     * band as live matching — for the first one that the *old*, buggy tie-break ([referenceOldTieBreakDistance])
     * disagrees on, then checks the real [DtwMatcher] on that exact pair. [kotlin.random.Random] is
     * deterministic given a seed, so this is as reproducible as a hand-picked literal, and the
     * search range is small enough (few hundred seeds, 64-frame band-8 DTW) to run in milliseconds.
     */
    @Test
    fun `distance is symmetric at an equal-cost, different-length tie (Copilot round-2 regression)`() {
        for (dim in intArrayOf(6, 3)) {
            val tie = findEqualCostDifferentLengthTie(dim = dim, frameCount = FRAME_COUNT, bandRadius = BAND_RADIUS)
            assertNotNull("expected to find an equal-cost/different-length tie for dim=$dim in the seed sweep", tie)
            val (a, b) = tie!!

            // Positive control: the pair actually exercises the bug in the old tie-break, so this
            // test would have failed before the fix (not vacuously passed because no tie exists).
            val oldAB = referenceOldTieBreakDistance(a, b, dim, FRAME_COUNT, BAND_RADIUS)
            val oldBA = referenceOldTieBreakDistance(b, a, dim, FRAME_COUNT, BAND_RADIUS)
            assertTrue(
                "the old positional tie-break must disagree on this constructed pair (dim=$dim, " +
                    "old d(a,b)=$oldAB, old d(b,a)=$oldBA), or this test doesn't discriminate the regression",
                oldAB != oldBA,
            )

            val dtw = DtwMatcher(FRAME_COUNT, BAND_RADIUS)
            val forward = dtw.distance(a, b, dim)
            val backward = dtw.distance(b, a, dim)
            assertEquals(
                "dim=$dim: distance(a, b) must equal distance(b, a) exactly, bit-for-bit",
                forward.toRawBits(),
                backward.toRawBits(),
            )
        }
    }

    @Test
    fun `distance is symmetric across a randomized sweep of seeded quantized pairs`() {
        for (dim in intArrayOf(6, 3)) {
            val dtw = DtwMatcher(FRAME_COUNT, BAND_RADIUS)
            for (seed in 0 until SYMMETRY_SWEEP_SEED_COUNT) {
                val a = quantizedFrames(Random(seed * 2 + dim), FRAME_COUNT, dim)
                val b = quantizedFrames(Random(seed * 2 + dim + 1), FRAME_COUNT, dim)
                val forward = dtw.distance(a, b, dim)
                val backward = dtw.distance(b, a, dim)
                assertEquals(
                    "dim=$dim, seed=$seed: distance(a, b) must equal distance(b, a) exactly",
                    forward.toRawBits(),
                    backward.toRawBits(),
                )
            }
        }
    }

    /** Generates quantized (`{-1, 0, 1}`) frames, flat `[frameCount * dim]`: small-integer local
     * costs (`0`, `1`, `4`, ...) make exact cost ties -- and therefore exact path-length-dependent
     * asymmetry, if the tie-break regresses -- far more likely to occur than with continuous data,
     * where an exact tie has probability ~0. */
    private fun quantizedFrames(
        random: Random,
        frameCount: Int,
        dim: Int,
    ): FloatArray = FloatArray(frameCount * dim) { (random.nextInt(QUANTIZATION_LEVELS) - 1).toFloat() }

    /** Searches a small, deterministic seed range for a pair where [referenceOldTieBreakDistance]
     * disagrees on operand order -- i.e. a pair that actually hits an equal-cost/different-length
     * tie under the old (buggy) predecessor selection -- or `null` if none turns up in range. */
    private fun findEqualCostDifferentLengthTie(
        dim: Int,
        frameCount: Int,
        bandRadius: Int,
    ): Pair<FloatArray, FloatArray>? {
        for (seed in 0 until TIE_SEARCH_SEED_COUNT) {
            val a = quantizedFrames(Random(seed * 2), frameCount, dim)
            val b = quantizedFrames(Random(seed * 2 + 1), frameCount, dim)
            val ab = referenceOldTieBreakDistance(a, b, dim, frameCount, bandRadius)
            val ba = referenceOldTieBreakDistance(b, a, dim, frameCount, bandRadius)
            if (ab != ba) return a to b
        }
        return null
    }

    /**
     * A faithful copy of [DtwMatcher]'s pre-fix recurrence: ties among diag/up/left predecessors
     * are broken by **position** ("prefer diag, else up, else left") rather than by path length.
     * Exists only to prove, in [`distance is symmetric at an equal-cost, different-length tie`],
     * that a given constructed pair really does exercise the asymmetry the real fix removes (a
     * positive control), not to assert anything about the fixed [DtwMatcher] itself.
     */
    private fun referenceOldTieBreakDistance(
        a: FloatArray,
        b: FloatArray,
        dim: Int,
        frameCount: Int,
        bandRadius: Int,
    ): Float {
        val size = frameCount + 1
        val cost = FloatArray(size * size) { Float.POSITIVE_INFINITY }
        val pathLength = IntArray(size * size)
        cost[0] = 0f
        for (i in 1..frameCount) {
            val loJ = maxOf(1, i - bandRadius)
            val hiJ = minOf(frameCount, i + bandRadius)
            for (j in loJ..hiJ) {
                var localCost = 0f
                val aOffset = (i - 1) * dim
                val bOffset = (j - 1) * dim
                for (d in 0 until dim) {
                    val diff = a[aOffset + d] - b[bOffset + d]
                    localCost += diff * diff
                }
                val diagIndex = (i - 1) * size + (j - 1)
                val upIndex = (i - 1) * size + j
                val leftIndex = i * size + (j - 1)
                var bestPrev = cost[diagIndex]
                var bestLength = pathLength[diagIndex]
                if (cost[upIndex] < bestPrev) {
                    bestPrev = cost[upIndex]
                    bestLength = pathLength[upIndex]
                }
                if (cost[leftIndex] < bestPrev) {
                    bestPrev = cost[leftIndex]
                    bestLength = pathLength[leftIndex]
                }
                pathLength[i * size + j] = bestLength + 1
                cost[i * size + j] = localCost + bestPrev
            }
        }
        val finalIndex = frameCount * size + frameCount
        val length = pathLength[finalIndex]
        return if (length <= 0) Float.POSITIVE_INFINITY else cost[finalIndex] / length
    }

    private companion object {
        const val FRAME_COUNT = 64
        const val BAND_RADIUS = 8
        const val QUANTIZATION_LEVELS = 3
        const val TIE_SEARCH_SEED_COUNT = 500
        const val SYMMETRY_SWEEP_SEED_COUNT = 50
    }
}
