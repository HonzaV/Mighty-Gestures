package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.reorderedByBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotionPipeline]'s own timestamp-gap threshold (exact boundary, missing elsewhere) and its
 * GYRO sample-and-hold ordering assumption (ADR 0008: "gyro = latest GYRO sample
 * (sample-and-hold)"; `MotionTraceCsv`/`SensorModel`/live sensor delivery normally present GYRO at
 * or just before the ACC sample it is held for, never *after* a batch of several ACC samples).
 */
class MotionPipelineGapAndOrderingTest {
    private val config = MotionConfig()

    @Test
    fun `a 200ms gap does not reset the pipeline`() {
        // hasGyro = false: this test is about the ACC gap/reset threshold, not GYRO pairing: a
        // hasGyro = true pipeline that never receives a GYRO sample would buffer every ACC frame
        // against that eventuality (see MotionPipeline's KDoc on gyroSkewToleranceNanos), which is
        // a real and separately-tested behavior, but would make this test's timing depend on that
        // skew tolerance instead of purely on the gap threshold.
        val pipeline = MotionPipeline(config, hasGyro = false) { }
        pipeline.onSample(SensorKind.ACC, 0L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, config.maxTimestampGapNanos, 0f, 0f, 9.81f)
        assertEquals(
            "a gap of exactly maxTimestampGapNanos (200ms) must not reset (the check is '> ', not '>=')",
            Segmenter.State.SETTLING, // one sample alone isn't enough quiet time to leave SETTLING anyway
            pipeline.state,
        )
        // Confirm indirectly that no reset happened: feed enough quiet samples to reach ARMED and
        // check the *total* elapsed quiet counts the full span, not just time since a reset.
        var t = config.maxTimestampGapNanos
        repeat(30) {
            t += 20_000_000L
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
    }

    @Test
    fun `a gap of maxTimestampGapNanos plus 1ns resets the pipeline to SETTLING`() {
        // hasGyro = false for the same reason as the test above: this is purely an ACC gap/reset test.
        var sawDiscard = false
        val pipeline = MotionPipeline(config, hasGyro = false) { }
        pipeline.discardListener = { sawDiscard = true }
        // Settle to ARMED first, so the reset is observable (SETTLING -> SETTLING would be a no-op look-alike).
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
        pipeline.onSample(SensorKind.ACC, t + config.maxTimestampGapNanos + 1L, 0f, 0f, 9.81f)
        assertEquals(
            "a gap one nanosecond over the threshold must reset to SETTLING",
            Segmenter.State.SETTLING,
            pipeline.state,
        )
        assertTrue("a gap-triggered reset is not a segmenter discard reason", !sawDiscard)
    }

    @Test
    fun `GYRO delivered after a matching ACC block still confirms onset once it arrives (timestamp pairing)`() {
        // Regression for the NaN/ordering fix round: this used to document a miss (every ACC frame
        // in the block fell back to a stale GYRO value because the fresh one arrived after both).
        // With GYRO/ACC paired by timestamp rather than arrival order, a batched "ACC block then
        // GYRO block" delivery of the *same two GYRO samples* the interleaved test below uses must
        // reach the same ACTIVE verdict, not a different one.
        val pipeline = MotionPipeline(config, hasGyro = true) { }
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)

        // Batched delivery: both ACC samples of this block arrive first, then both GYRO samples
        // (each above G_on=2.0) that nominally belong to them.
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, t + 20_000_000L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.GYRO, t, 5f, 0f, 0f)
        pipeline.onSample(SensorKind.GYRO, t + 20_000_000L, 5f, 0f, 0f)

        assertEquals(
            "timestamp-keyed pairing must resolve both buffered ACC frames once their GYRO " +
                "samples arrive, confirming onset exactly as the interleaved delivery below does",
            Segmenter.State.ACTIVE,
            pipeline.state,
        )
    }

    @Test
    fun `interleaved GYRO-then-ACC at the same timestamp sees the fresh GYRO value immediately`() {
        // The expected/documented delivery order (ADR 0008, and SensorModel/TraceFeed's own
        // convention): GYRO at a timestamp is delivered before the ACC sample that holds it.
        var pipelineState: Segmenter.State? = null
        val pipeline = MotionPipeline(config, hasGyro = true) { }
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        pipeline.onSample(SensorKind.GYRO, t, 5f, 0f, 0f) // above G_on=2.0
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.GYRO, t + 20_000_000L, 5f, 0f, 0f)
        pipeline.onSample(SensorKind.ACC, t + 20_000_000L, 0f, 0f, 9.81f)
        pipelineState = pipeline.state
        assertEquals(
            "with GYRO delivered before its matching ACC sample, two consecutive high-gyro frames must confirm onset",
            Segmenter.State.ACTIVE,
            pipelineState,
        )
    }

    @Test
    fun `a realistic chop gives identical exemplars and match distance under three delivery orders`() {
        val config = MotionConfig()
        val preprocessor = Preprocessor(config)
        val matcher = MotionMatcher(config)
        val trace =
            SensorModel(rateHz = 100.0).generate(
                settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE)),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(seed = 77),
            )

        fun runOrder(ordered: List<MotionTraceSample>): MotionExemplar {
            var captured: MotionExemplar? = null
            val pipeline = MotionPipeline(config, hasGyro = true) { seg -> captured = MotionExemplar.fromSegment(seg) }
            ordered.feedTo(pipeline)
            return checkNotNull(captured) { "expected exactly one segment" }
        }

        val interleaved = runOrder(trace)
        val accFirst = runOrder(trace.reorderedByBlock(windowNanos = 100_000_000L, firstKind = SensorKind.ACC))
        val gyroFirst = runOrder(trace.reorderedByBlock(windowNanos = 100_000_000L, firstKind = SensorKind.GYRO))

        for ((label, candidate) in listOf("ACC-first" to accFirst, "GYRO-first" to gyroFirst)) {
            assertEquals(
                "$label: tNanos must match interleaved delivery",
                interleaved.tNanos.toList(),
                candidate.tNanos.toList(),
            )
            assertEquals(
                "$label: gyroX must match interleaved delivery",
                interleaved.gyroX.toList(),
                candidate.gyroX.toList(),
            )
            assertEquals(
                "$label: gyroY must match interleaved delivery",
                interleaved.gyroY.toList(),
                candidate.gyroY.toList(),
            )
            assertEquals(
                "$label: gyroZ must match interleaved delivery",
                interleaved.gyroZ.toList(),
                candidate.gyroZ.toList(),
            )
            val interleavedProcessed = preprocessor.process(interleaved, config)
            val candidateProcessed = preprocessor.process(candidate, config)
            val distance = checkNotNull(matcher.distance(interleavedProcessed, candidateProcessed))
            assertEquals("$label: match distance against interleaved delivery must be 0", 0f, distance, 0f)
        }
    }
}
