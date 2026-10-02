package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exact-number gate tests (ADR 0008 "Template validator"), using [fakeSegment] so each case tests
 * exactly one gate without the noise/physics of the synthetic generator. [TemplateValidatorTest]
 * doubles as the regression test for the pre-roll bug fixed in this PR: a validator that measured
 * duration/energy over the whole segment (pre-roll included) would pass gates these cases fail.
 */
class TemplateValidatorTest {
    private val config = MotionConfig()
    private val validator = TemplateValidator(config)

    /** A vigorous, long-enough movement: every gate passes. */
    @Test
    fun `passes every gate`() {
        val segment =
            fakeSegment(
                onsetIndex = 2,
                frames =
                    listOf(
                        FakeFrame(0L), // pre-roll, quiet
                        FakeFrame(50_000_000L), // pre-roll, quiet
                        FakeFrame(60_000_000L, linY = 10f), // onset
                        FakeFrame(200_000_000L, linY = 10f),
                        FakeFrame(360_000_000L, linY = 10f), // 300 ms of active duration
                    ),
            )
        assertNull(validator.validate(segment))
    }

    /** Pre-roll padding must not count towards duration: regression for the fixed onsetIndex bug. */
    @Test
    fun `pre-roll does not count towards active duration`() {
        val segment =
            fakeSegment(
                onsetIndex = 2,
                frames =
                    listOf(
                        // 100 ms of pre-roll before onset: if counted, duration would read 280 ms (>= 250 ms gate).
                        FakeFrame(0L),
                        FakeFrame(100_000_000L),
                        FakeFrame(100_000_001L, linY = 10f), // onset
                        FakeFrame(180_000_001L, linY = 10f), // only 80 ms of real active duration
                    ),
            )
        assertEquals(ValidationFailure.TOO_SHORT, validator.validate(segment))
    }

    @Test
    fun `too gentle fails neither peak gate`() {
        val segment =
            fakeSegment(
                onsetIndex = 0,
                frames =
                    listOf(
                        // Peak lin 2 m/s² (< 8), peak gyro 1 rad/s (< 5): gentle handling, not a gesture.
                        FakeFrame(0L, linY = 2f, gyroX = 1f),
                        FakeFrame(300_000_000L, linY = 2f, gyroX = 1f),
                    ),
            )
        assertEquals(ValidationFailure.TOO_GENTLE, validator.validate(segment))
    }

    @Test
    fun `passes peak gate but fails mean energy gate`() {
        // Peak lin 8 m/s² clears validatorPeakAccThreshold, but only for the first of many frames:
        // mean energy over the whole active window stays low.
        val frames = mutableListOf(FakeFrame(0L, linY = config.validatorPeakAccThreshold))
        for (i in 1..20) {
            frames += FakeFrame(i * 15_000_000L, linY = 0.01f)
        }
        val segment = fakeSegment(onsetIndex = 0, frames = frames)
        assertEquals(ValidationFailure.TOO_GENTLE_ENERGY, validator.validate(segment))
    }

    @Test
    fun `peak gyro alone is enough to avoid too gentle`() {
        val segment =
            fakeSegment(
                onsetIndex = 0,
                frames =
                    listOf(
                        FakeFrame(0L, gyroX = config.validatorPeakGyroThreshold, linY = 0.1f),
                        FakeFrame(300_000_000L, gyroX = config.validatorPeakGyroThreshold, linY = 0.1f),
                    ),
            )
        assertNull(validator.validate(segment))
    }
}
