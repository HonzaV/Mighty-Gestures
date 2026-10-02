package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.stillness
import cz.mightybities.mightygestures.motion.synthetic.walking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0008 segmenter timing (AC-M6, AC-M7): the `SETTLING` debounce, the 500 ms quiet gap that
 * separates two segments, and the 3 s cap that makes continuous motion self-cancel. The 1.5 s
 * per-rule cooldown mentioned alongside these in AC-M6 is [cz.mightybities.mightygestures.domain.engine.RuleEngine]
 * responsibility (ADR 0008 "Cooldown"), not part of this pipeline; it has no engine yet (spec 0001
 * milestone 2) and is out of this PR's scope.
 */
class SegmenterTimingTest {
    private val config = MotionConfig()

    @Test
    fun `a movement starting during SETTLING is not segmented`() {
        val recorded = RecordedPipeline(config)
        // No lead-in stillness at all: the segmenter starts in SETTLING and never accumulates the
        // 500 ms of quiet needed to reach ARMED, so onset can never be confirmed.
        recorded.feed(GesturePrimitives.chop(PerformerVariation.NONE), noise = NoiseSource(1))
        assertTrue(recorded.segments.isEmpty())
        assertEquals(Segmenter.State.SETTLING, recorded.state)
    }

    @Test
    fun `two movements separated by 600ms of quiet give two segments`() {
        // shake (pure translation, no rotation) rather than chop/twist: a rotation-heavy gesture's
        // gravity-filter leak (ADR 0008 "Consequences") extends how long it reads "active" well past
        // 600 ms, which would test the gravity filter's settling time, not the segmenter's own debounce.
        val trace =
            concat(
                listOf(
                    stillness(0.7f),
                    GesturePrimitives.shake(PerformerVariation.NONE),
                    stillness(0.6f),
                    GesturePrimitives.shake(PerformerVariation.NONE),
                    stillness(1.0f),
                ),
            )
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(2))
        assertEquals(2, recorded.segments.size)
    }

    @Test
    fun `two movements separated by 400ms of quiet merge into one segment`() {
        val trace =
            concat(
                listOf(
                    stillness(0.7f),
                    GesturePrimitives.shake(PerformerVariation.NONE),
                    stillness(0.4f),
                    GesturePrimitives.shake(PerformerVariation.NONE),
                    stillness(1.0f),
                ),
            )
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(3))
        assertEquals(1, recorded.segments.size)
    }

    @Test
    fun `continuous walking longer than 3s never yields a segment (AC-M7)`() {
        val trace = concat(listOf(stillness(0.7f), walking(durationSeconds = 6f, config = config)))
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(4))
        assertTrue(recorded.segments.isEmpty())
    }

    @Test
    fun `a bump shorter than the minimum active duration is discarded, not emitted`() {
        // A single brief, modest blip: long enough to confirm onset, far too short to be a gesture.
        val trace =
            concat(
                listOf(
                    stillness(0.7f),
                    GesturePrimitives.shake(PerformerVariation.NONE).let { spec ->
                        cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec(
                            durationSeconds = 0.05f,
                            linWorld = spec.linWorld,
                            angularBody = spec.angularBody,
                        )
                    },
                    stillness(1.0f),
                ),
            )
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(5))
        assertTrue(recorded.segments.isEmpty())
    }
}
