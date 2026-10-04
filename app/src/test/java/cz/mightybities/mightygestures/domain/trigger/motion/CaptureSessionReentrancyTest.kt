package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Milestone 1 fix round, item 2 (reviewer), at the [CaptureSession] level -- the actual legal use
 * documented on [CaptureSession.resultListener]/[CaptureSession.startConfirming]: calling
 * [CaptureSession.startConfirming] synchronously from inside [CaptureSession.resultListener], on
 * [CaptureResult.Recorded]. [Segmenter] now moves its own state/counter transition before invoking
 * its listener for exactly this reason (see `SegmenterReentrancyTest`, which isolates the segmenter
 * contract alone); this test instead exercises the full `CaptureSession` -> `MotionPipeline` ->
 * `Segmenter` chain [CaptureSession]'s own KDoc documents as legal.
 *
 * Not in `CaptureSessionTest.kt` (excluded from this fix round: the tester owns it concurrently).
 */
class CaptureSessionReentrancyTest {
    private val config = MotionConfig()
    private var clockNanos = 0L
    private val clock = MonotonicClock { clockNanos }

    private fun newSession() =
        CaptureSession(
            config,
            hasGyro = true,
            TemplateValidator(config),
            MotionMatcher(config),
            Preprocessor(config),
            clock,
        )

    @Test
    fun `startConfirming called reentrantly from resultListener on Recorded starts the confirm attempt in SETTLING`() {
        val session = newSession()
        var reentered = false
        session.resultListener = { outcome ->
            if (outcome is CaptureResult.Recorded) {
                session.startConfirming(nowNanos = CONFIRM_START_CLOCK_NANOS)
                reentered = true
            }
        }

        val recordingTrace =
            SensorModel().generate(
                concat(listOf(stillness(0.6f), GesturePrimitives.chop(PerformerVariation.NONE), stillness(1.5f))),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(60),
            )
        session.startRecording(nowNanos = 0L)
        var lastFedTimestampNanos = 0L
        for (sample in recordingTrace) {
            session.onSample(sample.kind, sample.timestampNanos, sample.x, sample.y, sample.z)
            lastFedTimestampNanos = sample.timestampNanos
            // Stop as soon as the reentrant startConfirming() has run: any further samples from the
            // recording trace's own tail must not also leak into the confirm attempt it just started.
            if (reentered) break
        }
        assertEquals("expected the reentrant startConfirming() to have run", true, reentered)
        assertEquals(
            "beginAttempt()'s result = null must win over the Recorded outcome finish() just set",
            null,
            session.result,
        )
        assertEquals(CaptureStage.CONFIRMING, session.stage)

        // A movement right after must not be segmented: the confirm attempt really starts in
        // SETTLING (not clobbered back to ARMED by Segmenter's post-callback re-arm), so it needs a
        // fresh 500ms quiet debounce before it can even reach ARMED again. Fed as a *continuation*
        // of the same timestamp sequence (gap well under MotionConfig.maxTimestampGapNanos), so
        // MotionPipeline's own unrelated gap-reset can never be the thing putting it back in
        // SETTLING -- only the fix under test can.
        val movementRightAfter =
            SensorModel().generate(
                concat(listOf(GesturePrimitives.chop(PerformerVariation.NONE), stillness(1.5f))),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(61),
                startTimestampNanos = lastFedTimestampNanos + CONTINUATION_GAP_NANOS,
            )
        movementRightAfter.feedTo(session)

        assertEquals(
            "a movement immediately following the reentrant reset must not be segmented/confirmed",
            null,
            session.result,
        )
        assertEquals(CaptureStage.CONFIRMING, session.stage)
    }

    private companion object {
        const val CONFIRM_START_CLOCK_NANOS = 5_000_000_000L
        const val CONTINUATION_GAP_NANOS = 20_000_000L // one frame at 50Hz, well under maxTimestampGapNanos (200ms)
    }
}
