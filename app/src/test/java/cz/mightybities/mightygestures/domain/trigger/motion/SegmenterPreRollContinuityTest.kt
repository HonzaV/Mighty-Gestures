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
 * Also covers milestone 1 fix round item 1 (a related but distinct bug: [Segmenter.handleSettling]
 * itself only ever pushed the single frame that crossed `SETTLING` into `ARMED`, not every
 * `SETTLING` frame, truncating the very *first* gesture's pre-roll whenever onset followed
 * re-arming quickly).
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

    /** Feeds just enough quiet frames, [stepNanos] apart, to clear [MotionConfig.quietDebounceNanos]
     * (plus one more): the default 1ms stepping finishes a segment's quiet tail in fine-grained
     * steps (test 1 below); [stepNanos] = [FRAME_STEP_NANOS] instead keeps the whole quiet tail on
     * the same cadence as everything else in a test, so `ring`'s continuity guarantee is not
     * artificially capped by crowding its fixed frame capacity with an unrealistically fast (1ms)
     * stepping (item 3, PR #1 fix round: this used to measure the ring's own capacity limit at 1ms
     * stepping, not the real pre-roll continuity guarantee). */
    private fun finishWithQuiet(
        lastActiveT: Long,
        stepNanos: Long = 1_000_000L,
    ): Long {
        var t = lastActiveT
        val frameCount = (config.quietDebounceNanos / stepNanos).toInt() + 1
        repeat(frameCount) {
            t += stepNanos
            quietFrame(t)
        }
        return t
    }

    @Test
    fun `the first gesture, settling cold with no generous lead-in margin, still gets a near-full pre-roll`() {
        // Item 1 (reviewer, PR #1 fix round): handleSettling used to push only the single frame
        // that crossed SETTLING into ARMED, not every SETTLING frame -- a bug the rest of this
        // class's tests never caught because MotionTestHarness's SETTLING_LEAD_SECONDS = 0.7s
        // margin always leaves many ARMED-state frames in the ring well before onset, and the two
        // partner tests above/below re-arm via ACTIVE -> ARMED (confirmOnset/finishSegment), a path
        // that always pushed every frame and was never buggy. This drives SETTLING itself, cold,
        // stopping the instant ARMED is reached (no lead-in, no filler ARMED frames before onset).
        var t = 0L
        while (segmenter.state != Segmenter.State.ARMED) {
            quietFrame(t)
            t += FRAME_STEP_NANOS
        }
        val armedAtT = t - FRAME_STEP_NANOS // the frame that actually crossed SETTLING -> ARMED

        val onsetT = armedAtT + 40_000_000L // onset confirmed ~40ms after arming, no frames in between
        t = confirmOnset(onsetT)
        finishWithQuiet(t)

        assertEquals(1, emitted.size)
        val segment = emitted[0]
        val onsetOffsetNanos = segment.tNanos[segment.onsetIndex] - segment.tNanos[0]
        assertTrue(
            "expected a pre-roll close to preRollNanos (${config.preRollNanos / 1_000_000}ms), " +
                "drawn from the SETTLING-phase quiet history, got only " +
                "${onsetOffsetNanos / 1_000_000}ms (bug symptom: truncated to the ~40ms new-ARMED " +
                "gap alone)",
            onsetOffsetNanos >= config.preRollNanos - FRAME_STEP_NANOS,
        )
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
        // Quiet tail at FRAME_STEP_NANOS, not the default 1ms (see finishWithQuiet's KDoc): at
        // 1ms stepping, `ring`'s fixed frame capacity fills with far less than preRollNanos of
        // elapsed time, which used to make this test measure that capacity limit instead of the
        // real continuity guarantee.
        t = finishWithQuiet(t, stepNanos = FRAME_STEP_NANOS) // emits segment 1, re-arms
        assertEquals(1, emitted.size)
        assertEquals(Segmenter.State.ARMED, segmenter.state)

        // Onset one frame-step after re-arming (unlike the SETTLING -> ARMED path tested above,
        // this re-arm goes straight from ACTIVE via finishSegment -> enterArmed, a path that always
        // pushed every frame to `ring` and was never buggy -- item 1 is specifically about
        // handleSettling): with the quiet tail now on the same cadence as everything else, `ring`
        // holds a genuinely continuous history, so the pre-roll should reach the full preRollNanos.
        val secondOnsetT = t + FRAME_STEP_NANOS
        t = confirmOnset(secondOnsetT)
        finishWithQuiet(t, stepNanos = FRAME_STEP_NANOS) // emits segment 2
        assertEquals(2, emitted.size)
        val second = emitted[1]
        val onsetOffsetNanos = second.tNanos[second.onsetIndex] - second.tNanos[0]
        assertTrue(
            "expected a near-full pre-roll (preRollNanos = ${config.preRollNanos / 1_000_000}ms), " +
                "got only ${onsetOffsetNanos / 1_000_000}ms",
            onsetOffsetNanos >= config.preRollNanos - FRAME_STEP_NANOS,
        )
    }

    private companion object {
        const val SETTLE_FRAME_COUNT = 30
        const val FRAME_STEP_NANOS = 20_000_000L
        const val HOLD_ACTIVE_NANOS = 200_000_000L // comfortably over the 150ms minimum active duration
    }
}
