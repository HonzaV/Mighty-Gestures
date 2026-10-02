package cz.mightybities.mightygestures.domain.trigger.motion

import com.sun.management.ThreadMXBean
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.stillness
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * AC-M9: the per-sample path allocates nothing, and matching stays cheap even with many armed
 * rules. JVM-only (`com.sun.management.ThreadMXBean`'s allocation counter is HotSpot-specific, not
 * portable, but this test only ever runs on the dev/CI JVM, never on a device). Bounds are loose on
 * purpose (ADR 0008 performance budget: "< 2 ms for 50 rules on a JVM dev machine", 5x slower on a
 * phone) so this reports a regression, not a flaky timing assertion.
 */
class MotionPipelineBenchmarkTest {
    private val config = MotionConfig()

    @Test
    fun `onSample allocates nothing on the hot path after warm-up`() {
        val threadBean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        require(threadBean.isThreadAllocatedMemorySupported) { "allocation counter unsupported on this JVM" }
        threadBean.isThreadAllocatedMemoryEnabled = true

        val trace = render10MinuteTrace()
        val pipeline = MotionPipeline(config, hasGyro = true) { /* no-op: segment-end work is out of this benchmark */ }

        // Warm-up: let the JIT settle before measuring.
        feedTwice(pipeline, trace)

        val threadId = Thread.currentThread().id
        // Indexed access, not `for (sample in trace)`: a List's iterator()-based for-loop allocates
        // one Iterator object for the whole loop, which would show up as "allocation" here despite
        // having nothing to do with onSample's own hot path.
        val before = threadBean.getThreadAllocatedBytes(threadId)
        for (i in trace.indices) {
            val sample = trace[i]
            pipeline.onSample(sample.kind, sample.timestampNanos, sample.x, sample.y, sample.z)
        }
        val after = threadBean.getThreadAllocatedBytes(threadId)

        val samplesPerSecond = (trace.size / TRACE_DURATION_SECONDS).toInt()
        val allocatedBytes = after - before
        println("benchmark: ${trace.size} samples, $samplesPerSecond samples/s, allocated $allocatedBytes bytes")
        assertTrue("expected zero allocation, allocated $allocatedBytes bytes", allocatedBytes == 0L)
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
        println("MotionPipelineBenchmark: bestMatch over $RULE_COUNT rules, ${"%.1f".format(perCallMicros)} us/call")
        // ADR 0008 budget is < 2 ms on a dev JVM; this asserts a loose multiple so CI noise never
        // makes it flaky, while still catching a gross regression (e.g. an accidental O(n^2)).
        assertTrue("per-call time ${perCallMicros}us far exceeds the loose budget", perCallMicros < LOOSE_BUDGET_MICROS)
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
    private fun render10MinuteTrace(): List<MotionTraceSample> =
        SensorModel(rateHz = 50.0).generate(
            stillness(TRACE_DURATION_SECONDS),
            Quaternion.IDENTITY,
            hasGyro = true,
            noise = NoiseSource(seed = 42),
        )

    private companion object {
        const val TRACE_DURATION_SECONDS = 600f
        const val RULE_COUNT = 50
        const val BENCHMARK_ITERATIONS = 200
        const val LOOSE_BUDGET_MICROS = 20_000.0 // 20 ms; ADR budget is 2 ms, this is a 10x margin.
    }
}
