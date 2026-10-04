package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 1 fix round, item 2 (reviewer): [Segmenter.finishSegment] used to call
 * [Segmenter.Listener.onSegment] (or [Segmenter.discardListener]) and only *afterwards* transition
 * back to `ARMED`. [CaptureSession.resultListener] is documented to be callable synchronously, on
 * the same sensor thread, and legally calls [CaptureSession.startConfirming] from inside it — which
 * calls [MotionPipeline.reset] -> [Segmenter.reset], setting `SETTLING`. With the old ordering, the
 * segmenter's own post-callback `ARMED` transition then ran *after* that re-entrant reset and
 * clobbered it, silently skipping the confirm attempt's required 500 ms settle.
 *
 * Driven directly via [Segmenter.onFrame] (not `CaptureSession`, which is covered by the excluded
 * `CaptureSessionTest.kt`), to isolate the segmenter's own re-entrancy contract.
 */
class SegmenterReentrancyTest {
    private val config = MotionConfig()
    private val emitted = mutableListOf<SegmentFrames>()
    private var reenterOnNextSegment = false
    private lateinit var segmenter: Segmenter

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

    private fun confirmOnset(t: Long): Long {
        activeFrame(t)
        activeFrame(t + FRAME_STEP_NANOS)
        val lastActiveT = t + HOLD_ACTIVE_NANOS
        activeFrame(lastActiveT)
        return lastActiveT
    }

    private fun finishWithQuiet(lastActiveT: Long): Long {
        var t = lastActiveT
        repeat(QUIET_TAIL_FRAME_COUNT) {
            t += FRAME_STEP_NANOS
            quietFrame(t)
        }
        return t
    }

    @Test
    fun `a reentrant reset from inside onSegment is not clobbered by the segmenter's own re-arm`() {
        segmenter =
            Segmenter(config, hasGyro = true) { seg ->
                // Copy before resetting: `seg` is the live buffer, about to be cleared/reused.
                emitted += MotionExemplar.fromSegment(seg).toSegmentFrames(config)
                // Mimics CaptureSession.startConfirming() calling MotionPipeline.reset(), which it
                // is documented to do legally and synchronously from inside resultListener.
                if (reenterOnNextSegment) segmenter.reset()
            }

        var t = settleToArmed(0L)
        t = confirmOnset(t)
        reenterOnNextSegment = true
        t = finishWithQuiet(t) // emits segment 1; the listener resets re-entrantly
        assertEquals(1, emitted.size)
        assertEquals(
            "the re-entrant reset() (SETTLING) must win over the segmenter's own post-callback re-arm (ARMED)",
            Segmenter.State.SETTLING,
            segmenter.state,
        )

        // A movement right after must NOT be segmented: the confirm attempt really starts in
        // SETTLING, needing a fresh quietDebounceNanos (500ms) of quiet before it can even reach
        // ARMED, exactly like a brand new attempt would.
        activeFrame(t + FRAME_STEP_NANOS)
        activeFrame(t + 2 * FRAME_STEP_NANOS)
        assertEquals(
            "an active frame right after the reentrant reset must still be ignored (still SETTLING)",
            Segmenter.State.SETTLING,
            segmenter.state,
        )
        assertEquals("no further segment may have been emitted yet", 1, emitted.size)

        // Confirm the pipeline still works normally once it has genuinely settled again.
        reenterOnNextSegment = false
        t = settleToArmed(t + 2 * FRAME_STEP_NANOS + SETTLE_RESTART_GAP_NANOS)
        t = confirmOnset(t)
        finishWithQuiet(t)
        assertEquals("a normal attempt after settling again must still segment", 2, emitted.size)
    }

    private companion object {
        const val SETTLE_FRAME_COUNT = 30
        const val FRAME_STEP_NANOS = 20_000_000L
        const val HOLD_ACTIVE_NANOS = 200_000_000L
        const val QUIET_TAIL_FRAME_COUNT = 30
        const val SETTLE_RESTART_GAP_NANOS = 20_000_000L
    }
}
