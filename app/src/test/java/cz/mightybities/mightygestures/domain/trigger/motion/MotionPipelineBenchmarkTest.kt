package cz.mightybities.mightygestures.domain.trigger.motion

import com.sun.management.ThreadMXBean
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.Vector3
import cz.mightybities.mightygestures.motion.synthetic.VectorProfile
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.reorderedByBlock
import cz.mightybities.mightygestures.motion.synthetic.stillness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * AC-M9: the per-sample path allocates nothing, and matching stays cheap even with many armed
 * rules. JVM-only (`com.sun.management.ThreadMXBean`'s allocation counter is HotSpot-specific, not
 * portable, but this test only ever runs on the dev/CI JVM, never on a device). Bounds are loose on
 * purpose (ADR 0008 performance budget: "< 2 ms for 50 rules on a JVM dev machine", 5x slower on a
 * phone) so this reports a regression, not a flaky timing assertion.
 *
 * Milestone 1 fix round, item 5: the original "allocates nothing" measurement only ever fed a
 * stillness trace (`SETTLING`/`ARMED` only, never `ACTIVE`: `handleActive`/`confirmOnset`/
 * `copyTailInto`/`finishSegment` never ran) and never a GYRO-lagging-ACC delivery (`SensorModel`
 * always emits GYRO before ACC per timestamp, so `MotionPipeline.pendingAcc`/`bufferPendingAcc`/
 * `flushReadyPendingAcc`/`releaseOldestPendingAcc` never ran either). The two tests below close that
 * gap, and each also asserts -- via a counting listener, not just the trace's shape -- that the
 * path it exists to cover was actually taken; a counter that only increments a captured `var` is
 * itself allocation-free once the closure exists (the `Ref.IntRef` box is allocated once, at
 * closure-construction time, well before the measured loop), so this adds no allocation of its own.
 * Each measures `onSample` only, against a cheap listener: segment-end work (matching/
 * `MotionExemplar` copies) is allowed to allocate by design (ADR 0008 "DTW only at segment end")
 * and is deliberately excluded from every measurement here.
 */
class MotionPipelineBenchmarkTest {
    private val config = MotionConfig()

    @Test
    fun `onSample allocates nothing on a mostly-quiet steady state`() {
        val trace = renderStillnessTrace()
        val pipeline = MotionPipeline(config, hasGyro = true) { /* no-op */ }
        feedTwice(pipeline, trace)

        val measurement = measureOnSample(pipeline, trace)
        measurement.reportAndAssertZeroAllocation("stillness", trace.size)
    }

    @Test
    fun `onSample allocates nothing across SETTLING-ARMED-ACTIVE-ARMED cycles, including a TOO_LONG discard`() {
        val trace = renderGestureCycleTrace()
        var segmentCount = 0
        var tooLongCount = 0
        val pipeline = MotionPipeline(config, hasGyro = true) { segmentCount++ }
        pipeline.discardListener = { reason -> if (reason == Segmenter.DiscardReason.TOO_LONG) tooLongCount++ }
        feedTwice(pipeline, trace)

        // Reset after warm-up: only the measured pass below should count.
        segmentCount = 0
        tooLongCount = 0
        val measurement = measureOnSample(pipeline, trace)
        measurement.reportAndAssertZeroAllocation("gesture cycles + TOO_LONG burst", trace.size)

        // Proves the measured pass actually drove the segmenter through ACTIVE and TOO_LONG, not
        // just that the trace happened to contain samples shaped like it would (milestone 1 fix
        // round, item 5 follow-up): a future threshold/gesture-shape change that silently stopped
        // reaching ACTIVE would otherwise still pass this test as a (now-meaningless) stillness run.
        assertEquals("expected one emitted segment per chop cycle", GESTURE_CYCLE_REPEAT_COUNT, segmentCount)
        assertEquals("expected exactly one TOO_LONG discard (the final burst)", 1, tooLongCount)
    }

    @Test
    fun `onSample allocates nothing when GYRO lags or is missing relative to ACC`() {
        val built = renderGyroLagAndDropTrace()
        assertTrue(
            "expected the drop window to remove at least one GYRO sample",
            built.droppedGyroCount > 0,
        )
        assertTrue(
            "expected at least one ACC sample to arrive before its covering GYRO sample " +
                "(otherwise this trace never forces MotionPipeline.pendingAcc buffering at all)",
            countAccSamplesAheadOfGyro(built.samples) > 0,
        )

        var segmentCount = 0
        val pipeline = MotionPipeline(config, hasGyro = true) { segmentCount++ }
        feedTwice(pipeline, built.samples)

        segmentCount = 0
        val measurement = measureOnSample(pipeline, built.samples)
        measurement.reportAndAssertZeroAllocation("GYRO-lagging/dropped ACC pairing", built.samples.size)
        assertEquals("expected the one gesture in this trace to still be emitted as a segment", 1, segmentCount)
    }

    @Test
    fun `matching 50 rules at segment end stays within a loose budget`() {
        val matcher = MotionMatcher(config)
        val preprocessor = Preprocessor(config)
        val live = syntheticProcessedSegment(preprocessor, seed = 1)
        val templates =
            (1..RULE_COUNT).associate { i ->
                i to
                    listOf(
                        syntheticProcessedSegment(preprocessor, seed = i.toLong() + 1),
                        syntheticProcessedSegment(
                            preprocessor,
                            seed =
                                i.toLong() + 2,
                        ),
                    )
            }

        // Warm-up.
        repeat(20) { matcher.bestMatch(live, templates) }

        val start = System.nanoTime()
        repeat(BENCHMARK_ITERATIONS) { matcher.bestMatch(live, templates) }
        val elapsedNanos = System.nanoTime() - start
        val perCallMicros = elapsedNanos / BENCHMARK_ITERATIONS / 1000.0
        println(
            "MotionPipelineBenchmark (JVM): bestMatch over $RULE_COUNT rules, ${"%.1f".format(perCallMicros)} us/call",
        )
        // ADR 0008 budget is < 2 ms on a dev JVM; this asserts a loose multiple so CI noise never
        // makes it flaky, while still catching a gross regression (e.g. an accidental O(n^2)).
        assertTrue("per-call time ${perCallMicros}us far exceeds the loose budget", perCallMicros < LOOSE_BUDGET_MICROS)
    }

    /** Measures one pass of [trace] through [pipeline] (already warmed up by the caller): both
     * allocated bytes and wall-clock elapsed time. */
    private fun measureOnSample(
        pipeline: MotionPipeline,
        trace: List<MotionTraceSample>,
    ): Measurement {
        val threadBean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        require(threadBean.isThreadAllocatedMemorySupported) { "allocation counter unsupported on this JVM" }
        threadBean.isThreadAllocatedMemoryEnabled = true

        val threadId = Thread.currentThread().id
        // Indexed access, not `for (sample in trace)`: a List's iterator()-based for-loop allocates
        // one Iterator object for the whole loop, which would show up as "allocation" here despite
        // having nothing to do with onSample's own hot path.
        val before = threadBean.getThreadAllocatedBytes(threadId)
        val start = System.nanoTime()
        for (i in trace.indices) {
            val sample = trace[i]
            pipeline.onSample(sample.kind, sample.timestampNanos, sample.x, sample.y, sample.z)
        }
        val elapsedNanos = System.nanoTime() - start
        val after = threadBean.getThreadAllocatedBytes(threadId)
        return Measurement(after - before, elapsedNanos)
    }

    private data class Measurement(
        val allocatedBytes: Long,
        val elapsedNanos: Long,
    )

    /** Reports measured wall-clock throughput (milestone 1 fix round, item 6) -- not the trace's
     * own fixed input rate, which is easy to conflate with it but only reflects the synthetic
     * generator's chosen sample rate, not how fast `onSample` actually ran on this JVM -- and
     * asserts zero allocation. */
    private fun Measurement.reportAndAssertZeroAllocation(
        label: String,
        sampleCount: Int,
    ) {
        val elapsedSeconds = elapsedNanos / NANOS_PER_SECOND
        val samplesPerSecond = if (elapsedSeconds > 0.0) (sampleCount / elapsedSeconds).toLong() else Long.MAX_VALUE
        println(
            "MotionPipelineBenchmark (JVM): $label -- $sampleCount samples in " +
                "${"%.1f".format(elapsedNanos / 1e6)}ms ($samplesPerSecond samples/s measured), " +
                "allocated $allocatedBytes bytes",
        )
        assertTrue("expected zero allocation for \"$label\", allocated $allocatedBytes bytes", allocatedBytes == 0L)
    }

    private fun syntheticProcessedSegment(
        preprocessor: Preprocessor,
        seed: Long,
    ): ProcessedSegment {
        val recorded = RecordedPipeline(config)
        recorded.feed(settlingMargin(GesturePrimitives.chop()), noise = NoiseSource(seed))
        check(recorded.segments.size == 1)
        return preprocessor.process(recorded.segments[0], config)
    }

    private fun feedTwice(
        pipeline: MotionPipeline,
        trace: List<MotionTraceSample>,
    ) {
        repeat(2) {
            pipeline.reset()
            for (sample in trace) {
                pipeline.onSample(sample.kind, sample.timestampNanos, sample.x, sample.y, sample.z)
            }
        }
        pipeline.reset()
    }

    /** A long, mostly-quiet trace (idle holding): representative of the hot path's steady state. */
    private fun renderStillnessTrace(): List<MotionTraceSample> =
        SensorModel(rateHz = 50.0).generate(
            stillness(STILLNESS_TRACE_DURATION_SECONDS),
            Quaternion.IDENTITY,
            hasGyro = true,
            noise = NoiseSource(seed = 42),
        )

    /** Several full `SETTLING -> ARMED -> ACTIVE -> ARMED` cycles (each `chop()`, with its own
     * settling margin), plus one continuous, onset-crossing burst longer than
     * [MotionConfig.maxActiveDurationNanos] to exercise [Segmenter.discardTooLong] too. */
    private fun renderGestureCycleTrace(): List<MotionTraceSample> {
        val tooLongBurst =
            MotionSegmentSpec(
                durationSeconds = TOO_LONG_BURST_DURATION_SECONDS,
                linWorld =
                    VectorProfile { t ->
                        val phase = 2f * PI.toFloat() * 1.5f * t
                        Vector3(
                            TOO_LONG_BURST_PEAK_ACCELERATION * sin(phase),
                            TOO_LONG_BURST_PEAK_ACCELERATION * cos(phase),
                            0f,
                        )
                    },
                angularBody = VectorProfile { Vector3.ZERO },
            )
        val spec =
            concat(
                List(GESTURE_CYCLE_REPEAT_COUNT) { settlingMargin(GesturePrimitives.chop()) } +
                    listOf(settlingMargin(tooLongBurst)),
            )
        return SensorModel(
            rateHz = 50.0,
        ).generate(spec, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(seed = 43))
    }

    private data class GyroLagAndDropTrace(
        val samples: List<MotionTraceSample>,
        /** How many GYRO samples the drop window actually removed (asserted `> 0` by the test). */
        val droppedGyroCount: Int,
    )

    /** A moderate trace delivered the way a real batching flush could plausibly reorder it (ACC
     * block, then GYRO block, within each window -- [reorderedByBlock], same as
     * [MotionPipelineGapAndOrderingTest]), with one window's GYRO samples dropped outright
     * (longer than [MotionConfig.maxTimestampGapNanos]'s skew tolerance) so some ACC frames can
     * only resolve via [MotionPipeline]'s best-effort timeout release, not a late-arriving GYRO
     * sample. Together these drive `bufferPendingAcc`, `flushReadyPendingAcc` and
     * `releaseOldestPendingAcc`, none of which the GYRO-before-ACC convention every other trace in
     * this file uses would ever reach.
     */
    private fun renderGyroLagAndDropTrace(): GyroLagAndDropTrace {
        val trace =
            SensorModel(rateHz = 50.0).generate(
                settlingMargin(GesturePrimitives.chop()),
                Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(seed = 44),
            )
        val reordered = trace.reorderedByBlock(windowNanos = GYRO_LAG_WINDOW_NANOS, firstKind = SensorKind.ACC)
        val dropWindowStart =
            reordered.first { it.kind == SensorKind.GYRO }.timestampNanos + GYRO_DROP_WINDOW_OFFSET_NANOS
        val dropped =
            reordered.filterNot {
                it.kind == SensorKind.GYRO &&
                    it.timestampNanos in dropWindowStart until (dropWindowStart + GYRO_DROP_WINDOW_DURATION_NANOS)
            }
        val droppedGyroCount =
            reordered.count { it.kind == SensorKind.GYRO } - dropped.count { it.kind == SensorKind.GYRO }
        return GyroLagAndDropTrace(dropped, droppedGyroCount)
    }

    /** Mirrors [MotionPipeline.handleAcc]'s own `t <= gyroMaxTimestampNanos` buffering condition
     * (without touching the pipeline itself): counts ACC samples that arrive, in list order,
     * before any GYRO sample with an equal or later timestamp -- exactly the samples that would
     * force [MotionPipeline]'s `pendingAcc` buffering path. */
    private fun countAccSamplesAheadOfGyro(trace: List<MotionTraceSample>): Int {
        var gyroMaxSeenNanos = Long.MIN_VALUE
        var count = 0
        for (sample in trace) {
            when (sample.kind) {
                SensorKind.GYRO -> {
                    if (sample.timestampNanos >
                        gyroMaxSeenNanos
                    ) {
                        gyroMaxSeenNanos = sample.timestampNanos
                    }
                }

                SensorKind.ACC -> {
                    if (sample.timestampNanos > gyroMaxSeenNanos) count++
                }
            }
        }
        return count
    }

    private companion object {
        const val STILLNESS_TRACE_DURATION_SECONDS = 600f
        const val GESTURE_CYCLE_REPEAT_COUNT = 3
        const val TOO_LONG_BURST_DURATION_SECONDS = 4f // > MotionConfig.maxActiveDurationNanos (3s)
        const val TOO_LONG_BURST_PEAK_ACCELERATION = 6f // comfortably above accOnsetThreshold=3.0
        const val GYRO_LAG_WINDOW_NANOS = 100_000_000L
        const val GYRO_DROP_WINDOW_OFFSET_NANOS = 500_000_000L
        const val GYRO_DROP_WINDOW_DURATION_NANOS = 300_000_000L // > MotionConfig.maxTimestampGapNanos (200ms)
        const val RULE_COUNT = 50
        const val BENCHMARK_ITERATIONS = 200
        const val LOOSE_BUDGET_MICROS = 20_000.0 // 20 ms; ADR budget is 2 ms, this is a 10x margin.
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
