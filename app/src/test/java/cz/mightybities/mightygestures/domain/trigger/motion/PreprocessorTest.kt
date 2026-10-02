package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0008 "Preprocessor": fixed-N resampling, per-group RMS normalization, RMS-floored at the
 * quiet thresholds so a near-silent channel is not amplified into noise. */
class PreprocessorTest {
    private val config = MotionConfig()
    private val preprocessor = Preprocessor(config)

    @Test
    fun `resamples to the configured frame count`() {
        val segment =
            fakeSegment(
                onsetIndex = 0,
                frames = (0..10).map { FakeFrame(it * 10_000_000L, linY = 10f, gyroX = 3f) },
            )
        val processed = preprocessor.process(segment)
        assertEquals(config.resampledFrameCount * 6, processed.frames.size)
        assertTrue(processed.hasGyro)
        assertEquals(6, processed.dim)
    }

    @Test
    fun `acc-only segment resamples to 3 dimensions`() {
        val segment =
            fakeSegment(
                onsetIndex = 0,
                hasGyro = false,
                frames = (0..10).map { FakeFrame(it * 10_000_000L, linY = 10f) },
            )
        val processed = preprocessor.process(segment)
        assertFalse(processed.hasGyro)
        assertEquals(3, processed.dim)
        assertEquals(config.resampledFrameCount * 3, processed.frames.size)
    }

    @Test
    fun `normalizes each channel to unit RMS when well above the quiet floor`() {
        val linPeak = 10f
        val segment =
            fakeSegment(
                onsetIndex = 0,
                frames = (0..63).map { FakeFrame(it * 10_000_000L, linY = linPeak, gyroX = linPeak) },
            )
        val processed = preprocessor.process(segment)
        // A constant signal's RMS equals its value, so every resampled lin-Y frame should read ~1.
        for (i in 0 until config.resampledFrameCount) {
            assertEquals(1f, processed.frames[i * 6 + 1], 0.01f)
        }
    }

    @Test
    fun `a near-silent channel is floored instead of amplified to unit RMS`() {
        // linY is far below accQuietThreshold throughout: this is noise, not a signal.
        val tinySignal = config.accQuietThreshold / 100f
        val segment =
            fakeSegment(
                onsetIndex = 0,
                frames = (0..63).map { FakeFrame(it * 10_000_000L, linY = tinySignal) },
            )
        val processed = preprocessor.process(segment)
        assertEquals(tinySignal / config.accQuietThreshold, processed.frames[1], 0.001f)
    }

    @Test
    fun `reports the original un-resampled duration and RMS`() {
        val frames = (0..10).map { FakeFrame(it * 10_000_000L, linY = 4f, linZ = 3f) } // |lin| = 5
        val segment = fakeSegment(onsetIndex = 0, frames = frames)
        val processed = preprocessor.process(segment)
        assertEquals(100_000_000L, processed.durationNanos)
        assertEquals(5f, processed.linRms, 0.001f)
    }

    @Test
    fun `requires at least two frames to resample`() {
        val segment = fakeSegment(onsetIndex = 0, frames = listOf(FakeFrame(0L)))
        try {
            preprocessor.process(segment)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
