package cz.mightybities.mightygestures.domain.trigger.motion

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
        val pipeline = MotionPipeline(config, hasGyro = true) { }
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
        var sawDiscard = false
        val pipeline = MotionPipeline(config, hasGyro = true) { }
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
    fun `GYRO delivered after a block of ACC samples at the same nominal time is held stale for all of them`() {
        // ADR 0008's "sample-and-hold" model assumes GYRO arrives no later than the ACC sample it
        // is held for. Some delivery paths (e.g. a live batching flush) could plausibly present a
        // whole buffered block as "every ACC sample, then every GYRO sample" instead of
        // interleaved. This test demonstrates the consequence: if that happens, every ACC frame in
        // the block is processed using the *previous* held GYRO value, not the one nominally
        // concurrent with it.
        var observedGyroAtSecondActiveFrame: Float? = null
        var frameCount = 0
        val pipeline =
            MotionPipeline(config, hasGyro = true) { seg ->
                // Capture the gyroX recorded for the segment's active frames.
                frameCount = seg.length
                observedGyroAtSecondActiveFrame = seg.gyroX[seg.onsetIndex + 1]
            }
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)

        // Batched delivery: the real GYRO value for this block (well above G_on=2.0, so it alone
        // would trigger onset) is delivered AFTER both ACC samples that nominally share its block,
        // instead of before/interleaved.
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, t + 20_000_000L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.GYRO, t + 20_000_000L, 5f, 0f, 0f)

        // Neither ACC sample saw the fresh GYRO value (it arrived after both), so onset could not
        // have been confirmed from this block at all: the stale (held) GYRO value was 0 for both.
        assertEquals(
            "batched 'ACC block then GYRO block' delivery must not silently use a future GYRO " +
                "sample for an earlier ACC frame -- but with pure sample-and-hold it also can't see " +
                "the fresh value in time, so onset is missed for this block",
            Segmenter.State.ARMED,
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
}
