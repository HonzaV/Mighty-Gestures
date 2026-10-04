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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-M2: capture (record/confirm) and live detection share the same [Segmenter], [Preprocessor],
 * [MotionMatcher] and τ, so the same trace yields identical segment boundaries and distances
 * regardless of which of the three consumes it. [CaptureSession] already shares the pipeline
 * *classes* by construction; this test proves the *numbers* agree bit-for-bit, not just the class
 * references, by replaying the same two traces through an independent setup built the way a real
 * deployment is: the template side reconstructed from its stored raw [MotionExemplar] (ADR 0006 —
 * a live matcher always replays a saved template, it never keeps a capture session's pipeline
 * around), the probe side processed directly from a fresh segment, exactly like live detection.
 */
class MotionPipelineSharedAcrossCaptureAndLiveTest {
    private val config = MotionConfig()
    private val preprocessor = Preprocessor(config)
    private val matcher = MotionMatcher(config)

    @Test
    fun `record-confirm distance equals an independent live pipeline's distance on the same traces`() {
        val recordTrace =
            concat(listOf(stillness(0.6f), GesturePrimitives.chop(PerformerVariation.NONE), stillness(1.5f)))
        val confirmTrace =
            concat(
                listOf(
                    stillness(0.6f),
                    GesturePrimitives.chop(PerformerVariation.sample(NoiseSource(100))),
                    stillness(1.5f),
                ),
            )

        // Path 1: CaptureSession's own record -> confirm.
        var clockNanos = 0L
        val clock = MonotonicClock { clockNanos }
        val session = CaptureSession(config, hasGyro = true, TemplateValidator(config), matcher, preprocessor, clock)
        session.startRecording(0L)
        SensorModel().generate(recordTrace, Quaternion.IDENTITY, hasGyro = true, NoiseSource(1)).feedTo(session)
        val recorded = session.result as CaptureResult.Recorded
        clockNanos = 5_000_000_000L
        session.startConfirming(clockNanos)
        SensorModel().generate(confirmTrace, Quaternion.IDENTITY, hasGyro = true, NoiseSource(2)).feedTo(session)
        val confirmed = session.result
        assertTrue("expected Confirmed, was $confirmed", confirmed is CaptureResult.Confirmed)
        val captureDistance = (confirmed as CaptureResult.Confirmed).distance

        // Path 2: an independent setup fed the exact same two traces (same seeds, so bit-identical
        // sample streams).
        val liveTemplateProcessed = preprocessor.process(recorded.exemplar, config)
        var liveProbeProcessed: ProcessedSegment? = null
        val probePipeline =
            MotionPipeline(config, hasGyro = true) { segment ->
                liveProbeProcessed =
                    preprocessor.process(segment)
            }
        SensorModel().generate(confirmTrace, Quaternion.IDENTITY, hasGyro = true, NoiseSource(2)).feedTo(probePipeline)
        val liveDistance = matcher.distance(checkNotNull(liveProbeProcessed), liveTemplateProcessed)

        assertEquals(captureDistance, liveDistance)

        // Segment boundaries must agree too: an independent pipeline fed the record trace produces
        // the same raw segment CaptureSession's own exemplar was copied from.
        var independentRecordSegment: MotionExemplar? = null
        val independentPipeline =
            MotionPipeline(config, hasGyro = true) {
                independentRecordSegment =
                    MotionExemplar.fromSegment(it)
            }
        SensorModel()
            .generate(
                recordTrace,
                Quaternion.IDENTITY,
                hasGyro = true,
                NoiseSource(1),
            ).feedTo(independentPipeline)
        val independentSegment = checkNotNull(independentRecordSegment)
        assertEquals(recorded.exemplar.length, independentSegment.length)
        assertEquals(recorded.exemplar.tNanos.toList(), independentSegment.tNanos.toList())
        assertEquals(recorded.exemplar, independentSegment)
    }
}
