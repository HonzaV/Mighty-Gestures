package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0008 "Matcher" gates and selection: duration/RMS ratio gates, best-of-N (AC-M8), and the
 * collision gate at `1.2·τ` (decision 6). */
class MotionMatcherTest {
    private val config = MotionConfig()
    private val matcher = MotionMatcher(config)

    private fun processed(
        durationNanos: Long,
        linRms: Float,
        hasGyro: Boolean = false,
        gyroRms: Float = config.gyroQuietThreshold,
    ) = ProcessedSegment(
        frames = FloatArray(config.resampledFrameCount * if (hasGyro) 6 else 3),
        dim = if (hasGyro) 6 else 3,
        hasGyro = hasGyro,
        durationNanos = durationNanos,
        linRms = linRms,
        gyroRms = gyroRms,
    )

    @Test
    fun `duration ratio outside the gate rejects the pair without running DTW`() {
        val live = processed(durationNanos = 1_000_000_000L, linRms = 5f)
        val template = processed(durationNanos = 200_000_000L, linRms = 5f) // ratio = 5.0, > max 2.0
        assertNull(matcher.distance(live, template))
    }

    @Test
    fun `RMS ratio outside the gate rejects the pair`() {
        val live = processed(durationNanos = 500_000_000L, linRms = 1f)
        val template = processed(durationNanos = 500_000_000L, linRms = 10f) // ratio = 0.1, < min 0.5
        assertNull(matcher.distance(live, template))
    }

    @Test
    fun `mismatched channel sets never match`() {
        val live = processed(durationNanos = 500_000_000L, linRms = 5f, hasGyro = true)
        val template = processed(durationNanos = 500_000_000L, linRms = 5f, hasGyro = false)
        assertNull(matcher.distance(live, template))
    }

    @Test
    fun `within every gate, distance is zero for identical frames`() {
        val frames = FloatArray(config.resampledFrameCount * 3) { 0.1f }
        val a = ProcessedSegment(frames, 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        val b = ProcessedSegment(frames.copyOf(), 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        assertEquals(0f, checkNotNull(matcher.distance(a, b)), 0f)
    }

    @Test
    fun `distanceToTemplate is the minimum over every exemplar`() {
        val live =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3),
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val close = ProcessedSegment(live.frames.copyOf(), 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        val far =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    50f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        assertEquals(0f, checkNotNull(matcher.distanceToTemplate(live, listOf(far, close))), 0f)
    }

    @Test
    fun `bestMatch picks the lowest-distance rule among several armed matches (AC-M8)`() {
        val live =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val exact = ProcessedSegment(live.frames.copyOf(), 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        val close =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    1.1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val templates = mapOf("exact" to listOf(exact), "close" to listOf(close))
        val best = matcher.bestMatch(live, templates)
        assertEquals("exact", best?.key)
    }

    @Test
    fun `bestMatch picks the lowest-distance rule regardless of map iteration order (AC-M8)`() {
        // Regression against a "first match within tau wins" implementation: the previous test
        // above inserts "exact" first, so it alone cannot tell "lowest distance" apart from "first
        // encountered". Here "close" (the worse match) is inserted first instead.
        val live =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) { 1f },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val exact = ProcessedSegment(live.frames.copyOf(), 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        val close =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) { 1.1f },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val templates = linkedMapOf("close" to listOf(close), "exact" to listOf(exact))
        val best = matcher.bestMatch(live, templates)
        assertEquals("exact", best?.key)
    }

    @Test
    fun `bestMatch returns null when nothing is within tau`() {
        val live =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val faraway =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    -1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        assertNull(matcher.bestMatch(live, mapOf("faraway" to listOf(faraway))))
    }

    @Test
    fun `collidesWith uses 1point2 tau regardless of the duration-slash-RMS gates`() {
        // Deliberately outside the normal match gates (duration ratio 10x): the collision check
        // must still compare DTW distance directly (decision 6: collision is about similarity, not
        // "would this currently match live").
        val new =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    1f
                },
                3,
                false,
                5_000_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val existing = ProcessedSegment(new.frames.copyOf(), 3, false, 500_000_000L, 5f, config.gyroQuietThreshold)
        assertTrue(matcher.collidesWith(listOf(new), listOf(existing)))
    }

    @Test
    fun `collidesWith is false for genuinely different exemplars`() {
        val new =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        val existing =
            ProcessedSegment(
                FloatArray(config.resampledFrameCount * 3) {
                    -1f
                },
                3,
                false,
                500_000_000L,
                5f,
                config.gyroQuietThreshold,
            )
        assertTrue(!matcher.collidesWith(listOf(new), listOf(existing)))
    }
}
