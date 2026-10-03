package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-boundary coverage for ADR 0008's segmenter thresholds, missing from
 * [SegmenterTimingTest] (which only exercises comfortably-inside/outside values, e.g. 600 ms vs
 * 400 ms around the 500 ms quiet debounce, never 499/500 ms themselves). Drives [Segmenter.onFrame]
 * directly with hand-placed timestamps (not [SensorModel]/[RecordedPipeline]): `Float` time
 * stepping in the synthetic generator drifts by about one frame period (see
 * [MotionMatcherDistanceSpreadTest]'s and this PR's own tester report), which is exactly the kind
 * of slop an exact-boundary test cannot tolerate. "Lin" values are supplied directly as
 * already-gravity-removed ([FakeFrame]'s convention elsewhere in this package), so these tests are
 * independent of [GravityFilter] too.
 */
class SegmenterBoundaryTest {
    private val config = MotionConfig()
    private val emitted = mutableListOf<SegmentFrames>()
    private val discards = mutableListOf<Segmenter.DiscardReason>()
    private val segmenter =
        Segmenter(config, hasGyro = true) { seg -> emitted += MotionExemplar.fromSegment(seg).toSegmentFrames(config) }

    init {
        segmenter.discardListener = { discards += it }
    }

    private fun quietFrame(t: Long) = segmenter.onFrame(t, 0f, 0f, 9.81f, 0f, 0f, 0f, 0f, 0f, 0f)

    private fun activeFrame(t: Long) = segmenter.onFrame(t, 0f, 5f, 9.81f, 0f, 5f, 0f, 0f, 0f, 0f)

    /** Settles [segmenter] from `SETTLING` to `ARMED` with 30 quiet frames 20 ms apart (580 ms of
     * accumulated quiet, comfortably over the 500 ms debounce), returning the next free timestamp. */
    private fun settleToArmed(startT: Long = 0L): Long {
        var t = startT
        repeat(SETTLE_FRAME_COUNT) {
            quietFrame(t)
            t += FRAME_STEP_NANOS
        }
        assertEquals(Segmenter.State.ARMED, segmenter.state)
        return t
    }

    /** Confirms onset with exactly [MotionConfig.onsetConfirmFrames] (2) active frames starting at
     * [t], returning (onsetTimestampNanos, nextFreeTimestamp). */
    private fun confirmOnset(t: Long): Pair<Long, Long> {
        val onsetT = t
        activeFrame(t)
        assertEquals(Segmenter.State.ARMED, segmenter.state) // one active frame alone never confirms onset
        activeFrame(t + FRAME_STEP_NANOS)
        assertEquals(Segmenter.State.ACTIVE, segmenter.state) // two consecutive frames do
        return onsetT to (t + 2 * FRAME_STEP_NANOS)
    }

    @Test
    fun `a single active frame followed by quiet never confirms onset (single-spike rejection)`() {
        val armedAt = settleToArmed()
        activeFrame(armedAt)
        assertEquals(Segmenter.State.ARMED, segmenter.state)
        quietFrame(armedAt + FRAME_STEP_NANOS)
        assertEquals(
            "a lone spike must not confirm onset: the segmenter must stay ARMED, never ACTIVE",
            Segmenter.State.ARMED,
            segmenter.state,
        )
        assertTrue(emitted.isEmpty())
        assertTrue(discards.isEmpty())
    }

    @Test
    fun `active duration of exactly 149ms is discarded TOO_SHORT`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        // One more active frame exactly 149ms after onset: this becomes the new "last active frame".
        activeFrame(onsetT + 149_000_000L)
        finishWithQuiet(onsetT + 149_000_000L)
        assertTrue("expected a TOO_SHORT discard, got $discards", discards == listOf(Segmenter.DiscardReason.TOO_SHORT))
        assertTrue("a too-short bump must not be emitted", emitted.isEmpty())
    }

    @Test
    fun `active duration of exactly 150ms is emitted, not discarded`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        activeFrame(onsetT + 150_000_000L)
        finishWithQuiet(onsetT + 150_000_000L)
        assertTrue("expected no discard, got $discards", discards.isEmpty())
        assertEquals("expected exactly one emitted segment at the 150ms boundary", 1, emitted.size)
    }

    /** Feeds quiet frames from [lastActiveT], 1ms at a time, until [MotionConfig.quietDebounceNanos]
     * (500ms) is reached (finishing the segment one way or another). */
    private fun finishWithQuiet(lastActiveT: Long) {
        var t = lastActiveT
        repeat(500) {
            t += 1_000_000L
            quietFrame(t)
        }
    }

    @Test
    fun `quiet accumulated to exactly 499ms since the last active frame emits nothing yet`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        // Keep it active for 200ms (comfortably over the 150ms minimum) so this test is purely
        // about the quiet debounce, not confounded by the TOO_SHORT gate.
        val lastActiveT = onsetT + 200_000_000L
        activeFrame(lastActiveT)
        quietFrame(lastActiveT + 499_000_000L)
        assertEquals(
            "499ms of quiet must not yet finish the segment",
            Segmenter.State.ACTIVE,
            segmenter.state,
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `quiet accumulated to exactly 500ms since the last active frame emits the segment`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        val lastActiveT = onsetT + 200_000_000L
        activeFrame(lastActiveT)
        quietFrame(lastActiveT + 499_000_000L)
        assertEquals(Segmenter.State.ACTIVE, segmenter.state)
        // One more ms of quiet tips the cumulative total from 499ms to 500ms.
        quietFrame(lastActiveT + 500_000_000L)
        assertEquals(Segmenter.State.ARMED, segmenter.state) // emitted, re-armed
        assertEquals(1, emitted.size)
        assertTrue(discards.isEmpty())
    }

    @Test
    fun `active duration of exactly 3000ms is still emitted, not discarded as TOO_LONG`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        // Active frames every 100ms, never going quiet, up to and including exactly onset+3000ms.
        var t = onsetT + 300_000_000L
        while (t < onsetT + MAX_ACTIVE_NANOS) {
            activeFrame(t)
            assertEquals(Segmenter.State.ACTIVE, segmenter.state)
            t += 100_000_000L
        }
        activeFrame(onsetT + MAX_ACTIVE_NANOS) // exactly the 3000ms boundary
        assertEquals(
            "exactly 3000ms active must not be discarded yet",
            Segmenter.State.ACTIVE,
            segmenter.state,
        )
        assertTrue(discards.isEmpty())
        finishWithQuiet(onsetT + MAX_ACTIVE_NANOS)
        assertEquals("the 3000ms-long movement must still be emitted", 1, emitted.size)
        assertTrue(discards.isEmpty())
    }

    @Test
    fun `active duration of 3000ms plus 1ns is discarded TOO_LONG`() {
        val armedAt = settleToArmed()
        val (onsetT, _) = confirmOnset(armedAt)
        var t = onsetT + 300_000_000L
        while (t < onsetT + MAX_ACTIVE_NANOS) {
            activeFrame(t)
            t += 100_000_000L
        }
        activeFrame(onsetT + MAX_ACTIVE_NANOS) // exactly the boundary: not discarded yet
        assertTrue(discards.isEmpty())
        activeFrame(onsetT + MAX_ACTIVE_NANOS + 1L) // one nanosecond over
        assertEquals(listOf(Segmenter.DiscardReason.TOO_LONG), discards)
        assertEquals(Segmenter.State.SETTLING, segmenter.state)
        assertTrue(emitted.isEmpty())
    }

    private companion object {
        const val SETTLE_FRAME_COUNT = 30
        const val FRAME_STEP_NANOS = 20_000_000L
        const val MAX_ACTIVE_NANOS = 3_000_000_000L
    }
}
