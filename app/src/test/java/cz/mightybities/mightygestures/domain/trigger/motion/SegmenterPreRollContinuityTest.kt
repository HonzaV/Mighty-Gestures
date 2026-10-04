package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #1 fix round, item D21 (raised by the reviewer, confirmed by writing this test first): a
 * second gesture following closely behind a first one used to get a truncated pre-roll, because
 * [Segmenter.enterArmed] and [Segmenter.confirmOnset] both cleared the ring buffer that
 * [MotionConfig.preRollNanos] is drawn from. Re-arming after a segment is a routine event (AC-M6:
 * two gestures separated by only slightly more than the 500 ms quiet debounce are legitimately two
 * segments, not something exotic), and the frames just before the second onset are real, recently
 * observed quiet frames — there is no reason to discard them just because they happened to occur
 * while finishing the first segment's bookkeeping, rather than after.
 *
 * Driven directly via [Segmenter.onFrame] (not the synthetic generator), like
 * [SegmenterBoundaryTest]: `Float` time-stepping in the generator is too coarse for this.
 */
class SegmenterPreRollContinuityTest {
    private val config = MotionConfig()
    private val emitted = mutableListOf<SegmentFrames>()
    private val segmenter =
        Segmenter(config, hasGyro = true) { seg -> emitted += MotionExemplar.fromSegment(seg).toSegmentFrames(config) }

    private fun quietFrame(t: Long) = segmenter.onFrame(t, 0f, 0f, 9.81f, 0f, 0f, 0f, 0f, 0f, 0f)

    private fun activeFrame(t: Long) = segmenter.onFrame(t, 0f, 5f, 9.81f, 0f, 5f, 0f, 0f, 0f, 0f)

    private fun settleToArmed(startT: Long): Long {
        var t = startT
        repeat(SETTLE_FRAME_COUNT) {
            quietFrame(t)
            t += FRAME_STEP_NANOS
        }
        assertEquals(Segmenter.State.ARMED, segmenter.state)
        return t
    }

    /** Confirms onset at [t] with 2 active frames, then holds active for 200ms (comfortably over
     * the 150ms minimum, so a subsequent quiet run finishes the segment rather than discarding it
     * TOO_SHORT) -- returns the timestamp of the last active frame. */
    private fun confirmOnset(t: Long): Long {
        activeFrame(t)
        activeFrame(t + FRAME_STEP_NANOS)
        assertEquals(Segmenter.State.ACTIVE, segmenter.state)
        val lastActiveT = t + HOLD_ACTIVE_NANOS
        activeFrame(lastActiveT)
        return lastActiveT
    }

    private fun finishWithQuiet(lastActiveT: Long): Long {
        var t = lastActiveT
        repeat(500) {
            t += 1_000_000L
            quietFrame(t)
        }
        return t
    }

    @Test
    fun `a second gesture starting 40ms after re-arming still draws pre-roll from the first gesture's quiet tail`() {
        var t = settleToArmed(0L)
        t = confirmOnset(t)
        val firstActiveEnd = t
        t = finishWithQuiet(firstActiveEnd) // emits segment 1, re-arms
        assertEquals(1, emitted.size)
        assertEquals(Segmenter.State.ARMED, segmenter.state)

        // Only 40ms of *new* ARMED-state quiet before the second onset -- deliberately shorter
        // than preRollNanos (100ms), so a full pre-roll is only possible by also drawing on frames
        // from finishWithQuiet's own quiet tail (fed while still ACTIVE, finishing segment 1) --
        // exactly the history enterArmed()/confirmOnset() used to discard by clearing the ring.
        // Fed as two real frames (continuous delivery), not a discrete jump: the ring can only hold
        // pre-roll from frames it actually saw, like a real sensor stream would deliver.
        repeat(2) {
            t += FRAME_STEP_NANOS
            quietFrame(t)
        }
        val secondOnsetT = t + FRAME_STEP_NANOS
        t = confirmOnset(secondOnsetT)
        finishWithQuiet(t) // emits segment 2

        assertEquals(2, emitted.size)
        val second = emitted[1]
        val onsetOffsetNanos = second.tNanos[second.onsetIndex] - second.tNanos[0]
        val newGapAloneNanos = 40_000_000L
        assertTrue(
            "expected a pre-roll drawing on the first gesture's quiet tail (well past the new " +
                "${newGapAloneNanos / 1_000_000}ms gap alone), got only " +
                "${onsetOffsetNanos / 1_000_000}ms (onset at $secondOnsetT, segment starts at ${second.tNanos[0]})",
            onsetOffsetNanos > newGapAloneNanos,
        )
    }

    @Test
    fun `a second gesture starting right at re-arming still gets what pre-roll time allows`() {
        var t = settleToArmed(0L)
        t = confirmOnset(t)
        t = finishWithQuiet(t) // emits segment 1, re-arms
        assertEquals(1, emitted.size)

        // Onset immediately on re-arming: at most one frame of "pre-roll" can possibly exist, but
        // what does exist (the re-arm frame itself) should still be included, not dropped to zero.
        t = confirmOnset(t)
        finishWithQuiet(t) // emits segment 2
        assertEquals(2, emitted.size)
        assertTrue("expected at least the onset-confirming frames themselves", emitted[1].length >= 2)
    }

    private companion object {
        const val SETTLE_FRAME_COUNT = 30
        const val FRAME_STEP_NANOS = 20_000_000L
        const val HOLD_ACTIVE_NANOS = 200_000_000L // comfortably over the 150ms minimum active duration
    }
}
