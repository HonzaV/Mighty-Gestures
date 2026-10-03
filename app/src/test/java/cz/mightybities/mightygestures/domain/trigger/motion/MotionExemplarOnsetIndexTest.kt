package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item A6: [MotionExemplar] persists [SegmentFrames.onsetIndex], and [ExemplarReplay] restores it
 * exactly, so re-deriving a stored template (ADR 0006) does not silently default the onset back to
 * 0 — which would break the pre-roll/active split invariant for any future consumer that reads it
 * off a replayed segment (the current [TemplateValidator] never does, since it only ever runs on a
 * live, just-recorded segment, but this is a persisted-DTO-shape decision that must be right before
 * PR #2 freezes it).
 */
class MotionExemplarOnsetIndexTest {
    private val config = MotionConfig()

    @Test
    fun `fromSegment copies onsetIndex from the live segment`() {
        val recorded = RecordedPipeline(config)
        recorded.feed(settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE)), noise = NoiseSource(1))
        check(recorded.segments.size == 1)
        val exemplar = recorded.segments[0]
        assertTrue("expected a non-trivial pre-roll before onset", exemplar.onsetIndex > 0)
    }

    @Test
    fun `replaying an exemplar restores the same onsetIndex (ADR 0006 round-trip)`() {
        val recorded = RecordedPipeline(config)
        recorded.feed(settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE)), noise = NoiseSource(2))
        check(recorded.segments.size == 1)
        val exemplar = recorded.segments[0]

        val replayed = exemplar.toSegmentFrames(config)

        assertEquals(
            "replayed onsetIndex must equal the live exemplar's, not default to 0",
            exemplar.onsetIndex,
            replayed.onsetIndex,
        )
    }

    @Test
    fun `the constructor rejects an out-of-range onsetIndex`() {
        try {
            MotionExemplar(
                tNanos = longArrayOf(0L, 10L),
                accX = floatArrayOf(0f, 0f),
                accY = floatArrayOf(0f, 0f),
                accZ = floatArrayOf(9.81f, 9.81f),
                gyroX = floatArrayOf(0f, 0f),
                gyroY = floatArrayOf(0f, 0f),
                gyroZ = floatArrayOf(0f, 0f),
                hasGyro = true,
                gravityAtStartX = 0f,
                gravityAtStartY = 0f,
                gravityAtStartZ = 9.81f,
                onsetIndex = 5, // out of range for a 2-frame exemplar
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
