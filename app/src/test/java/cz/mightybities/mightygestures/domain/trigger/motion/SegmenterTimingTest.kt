package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Vector3
import cz.mightybities.mightygestures.motion.synthetic.VectorProfile
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.stillness
import cz.mightybities.mightygestures.motion.synthetic.walking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ADR 0008 segmenter timing (AC-M6, AC-M7): the `SETTLING` debounce, the 500 ms quiet gap that
 * separates two segments, and the 3 s cap that makes continuous motion self-cancel. The 1.5 s
 * per-rule cooldown mentioned alongside these in AC-M6 is `RuleEngine`'s responsibility (ADR 0008
 * "Cooldown"), not part of this pipeline; it has no engine yet (spec 0001
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
    fun `two movements separated by a clearly-short gap merge into one segment`() {
        // 300ms, not 400ms: shake's Hann window (PR #1 fix round, item C8) tapers its amplitude to
        // zero smoothly well before its nominal duration ends, so the segmenter reads "quiet"
        // earlier than the raw stillness() block alone would suggest -- the two together push the
        // actual 1-vs-2-segment crossover for this gesture to ~355ms, not exactly 500ms minus a
        // gesture's own tail. The *exact* 499ms/500ms boundary (independent of any gesture shape)
        // is SegmenterBoundaryTest's job; this test only needs "comfortably inside" (per this
        // class's own KDoc), confirmed empirically to be comfortably below that crossover.
        val trace =
            concat(
                listOf(
                    stillness(0.7f),
                    GesturePrimitives.shake(PerformerVariation.NONE),
                    stillness(0.3f),
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
        // PR #1 fix round, item C11: at realistic (absolute, config-independent) walking
        // amplitudes, the oscillation never actually reaches accOnsetThreshold, so this trace
        // never leaves ARMED at all -- AC-M7 holds this way, not via the ADR's TOO_LONG discard
        // (which is what continuous motion that *does* cross onset would hit instead; both are
        // legitimate ways to "never yield a segment", and this is the one actually observed here).
        val trace = concat(listOf(stillness(0.7f), walking(durationSeconds = 6f)))
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(4))
        assertTrue(recorded.segments.isEmpty())
        assertEquals(Segmenter.State.ARMED, recorded.state)
        assertTrue("expected no TOO_LONG discard at these amplitudes", recorded.discards.isEmpty())
    }

    @Test
    fun `a bump shorter than the minimum active duration is discarded, not emitted`() {
        // A single brief, modest blip: long enough to confirm onset, far too short to be a gesture.
        // A dedicated constant-acceleration spec, not a truncated slice of shake(): shake's Hann
        // window (item C8) is near zero within the first 50ms of its nominal duration, so slicing
        // it there no longer confirms onset at all.
        val bump =
            MotionSegmentSpec(
                0.05f,
                VectorProfile { Vector3(0f, BUMP_PEAK_ACCELERATION, 0f) },
                VectorProfile { Vector3.ZERO },
            )
        val trace = concat(listOf(stillness(0.7f), bump, stillness(1.0f)))
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(5))
        assertTrue(recorded.segments.isEmpty())
        assertEquals(
            "expected the bump to be discarded as TOO_SHORT specifically",
            listOf(Segmenter.DiscardReason.TOO_SHORT),
            recorded.discards,
        )
    }

    @Test
    fun `continuous motion that does cross onset is discarded TOO_LONG, not merely never-segmented`() {
        // Complements the AC-M7 test above: realistic walking amplitudes never cross onset at all
        // (ARMED forever), but ADR 0008's prose also describes continuous motion that *does* cross
        // onset and is discarded via the TOO_LONG mechanism instead -- this exercises that path
        // directly, with a rotating vector (zero-mean per axis, so it is not absorbed into the
        // gravity estimate, same reasoning as walking() itself) comfortably above accOnsetThreshold.
        val vigorous =
            MotionSegmentSpec(
                durationSeconds = 4f,
                linWorld =
                    VectorProfile { t ->
                        val phase = 2f * PI.toFloat() * 1.5f * t
                        Vector3(6f * sin(phase), 6f * cos(phase), 0f)
                    },
                angularBody = VectorProfile { Vector3.ZERO },
            )
        val trace = concat(listOf(stillness(0.7f), vigorous))
        val recorded = RecordedPipeline(config)
        recorded.feed(trace, noise = NoiseSource(6))
        assertTrue(recorded.segments.isEmpty())
        assertEquals(listOf(Segmenter.DiscardReason.TOO_LONG), recorded.discards)
    }

    private companion object {
        const val BUMP_PEAK_ACCELERATION = 4f // comfortably above accOnsetThreshold=3.0
    }
}
