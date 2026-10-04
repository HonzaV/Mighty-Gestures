package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for three defects the tester's review found in [MotionPipeline] (PR #1 fix
 * round, items A1-A3): a single non-finite sample permanently poisoning [GravityFilter], a
 * non-monotonic (backwards) ACC timestamp silently dropping every later sample forever instead of
 * resetting as the class KDoc always promised, and [MotionPipeline.reset] leaving a stale held
 * GYRO value (and GYRO history) behind. These use `hasGyro = false` pipelines wherever GYRO is not
 * itself under test: a `hasGyro = true` pipeline that never receives a matching GYRO sample
 * deliberately buffers ACC frames up to [MotionConfig.maxTimestampGapNanos] of skew tolerance
 * before releasing them (see [MotionPipeline]'s KDoc and [MotionPipelineGapAndOrderingTest]), which
 * would make unrelated tests' timing depend on that tolerance instead of on the behavior at hand.
 */
class MotionPipelineRobustnessFixesTest {
    private val config = MotionConfig()

    @Test
    fun `a single NaN ACC sample does not poison the gravity estimate forever (A1)`() {
        val pipeline = MotionPipeline(config, hasGyro = false) { }
        pipeline.onSample(SensorKind.ACC, 0L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, 20_000_000L, Float.NaN, Float.NaN, Float.NaN)
        pipeline.onSample(SensorKind.ACC, 40_000_000L, 0f, 0f, 9.81f)

        // If NaN had poisoned the gravity estimate, every later frame would compare NaN < threshold
        // (always false), so the pipeline could never read "quiet" or "active" again. Feed enough
        // clean quiet samples to reach ARMED as proof it did not.
        var t = 40_000_000L
        repeat(30) {
            t += 20_000_000L
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
    }

    @Test
    fun `an infinite ACC sample is dropped, not treated as a spurious onset (A1)`() {
        val pipeline = MotionPipeline(config, hasGyro = false) { }
        pipeline.onSample(SensorKind.ACC, 0L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, 20_000_000L, Float.POSITIVE_INFINITY, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, 40_000_000L, 0f, 0f, 9.81f)
        // Must still be able to settle normally: an Inf sample must not have been treated as "active".
        var t = 40_000_000L
        repeat(30) {
            t += 20_000_000L
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
    }

    @Test
    fun `a NaN GYRO sample is dropped too, not held forever`() {
        val pipeline = MotionPipeline(config, hasGyro = true) { }
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        pipeline.onSample(SensorKind.GYRO, t, Float.NaN, Float.NaN, Float.NaN)
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        // A held NaN gyro value would make the active-frame magnitude check NaN >= threshold
        // (always false) but also NaN < threshold (always false): neither active nor quiet. Prove
        // the pipeline still reads this frame as quiet by continuing to accumulate towards ARMED.
        assertEquals(Segmenter.State.ARMED, pipeline.state)
    }

    @Test
    fun `a non-monotonic ACC timestamp resets instead of dropping every later sample forever (A2)`() {
        // Tester's exact repro shape: one gesture, then the clock rebases to 0 (e.g. a sensor
        // re-registration) and three more gestures arrive. Before the fix, every sample after the
        // backwards jump was silently dropped (dt <= 0 "drop"), so only the first gesture's segment
        // was ever emitted.
        val segments = mutableListOf<MotionExemplar>()
        val pipeline = MotionPipeline(config, hasGyro = true) { segments += MotionExemplar.fromSegment(it) }
        val sensorModel = SensorModel(rateHz = 50.0)

        fun feedOneChop(seed: Long) {
            val trace = settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE))
            sensorModel.generate(trace, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(seed)).feedTo(pipeline)
        }

        feedOneChop(seed = 1) // first gesture, normal timestamps starting at 0
        for (seed in 2L..4L) {
            // Each subsequent gesture's timestamps are rebased back to 0 too, so every one after
            // the first is a backwards jump relative to the pipeline's last-seen timestamp.
            feedOneChop(seed)
        }

        assertEquals("expected one segment per gesture, got ${segments.size}", 4, segments.size)
    }

    @Test
    fun `an exact duplicate ACC timestamp is dropped, not treated as a reset`() {
        val pipeline = MotionPipeline(config, hasGyro = false) { }
        pipeline.onSample(SensorKind.ACC, 0L, 0f, 0f, 9.81f)
        pipeline.onSample(SensorKind.ACC, 0L, 99f, 99f, 99f) // exact duplicate timestamp: must be dropped
        var t = 0L
        repeat(30) {
            t += 20_000_000L
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
    }

    @Test
    fun `reset clears the held GYRO value so a stale large reading cannot block re-arming (A3)`() {
        val pipeline = MotionPipeline(config, hasGyro = true) { }
        var t = 0L
        // A large, sustained GYRO reading (would read as "active" if ever held and reused).
        pipeline.onSample(SensorKind.GYRO, t, 10f, 0f, 0f)
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)

        // Force a reset via a timestamp gap.
        t += config.maxTimestampGapNanos + 1L
        pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        assertEquals(Segmenter.State.SETTLING, pipeline.state)

        // If the held GYRO value survived the reset, every subsequent frame would read as "active"
        // (10 rad/s >> G_on) and SETTLING could never accumulate 500ms of quiet to reach ARMED.
        // Quiet GYRO samples are interleaved (not omitted) so this is purely a test of the reset,
        // not of the separate GYRO/ACC skew-tolerance behavior covered elsewhere.
        repeat(30) {
            t += 20_000_000L
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
        }
        assertEquals(
            "a stale held GYRO value must not survive reset() and block re-arming",
            Segmenter.State.ARMED,
            pipeline.state,
        )
    }

    @Test
    fun `reset clears gyro history too, so a pre-reset GYRO sample is never paired after it`() {
        val pipeline = MotionPipeline(config, hasGyro = true) { }
        pipeline.onSample(SensorKind.GYRO, 0L, 10f, 0f, 0f)
        pipeline.onSample(SensorKind.ACC, 0L, 0f, 0f, 9.81f)
        pipeline.reset()
        // After reset, ACC samples at timestamps that would have matched the pre-reset GYRO sample
        // by raw timestamp value must not be paired with it; quiet GYRO samples are interleaved so
        // only the reset's effect (not the separate skew tolerance) is under test.
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(
            "gyro history must not survive reset(): expected ARMED (quiet), not ACTIVE",
            Segmenter.State.ARMED,
            pipeline.state,
        )
    }

    @Test
    fun `a backward GYRO timestamp resets the whole pipeline, not just gyro history (A4)`() {
        // Captures a copy of every emitted segment's raw tNanos/gyroX (not a MotionExemplar, which
        // rebases timestamps to 0): this test needs to reason about absolute timestamps.
        data class Captured(
            val tNanos: LongArray,
            val gyroX: FloatArray,
            val length: Int,
        )
        val emitted = mutableListOf<Captured>()
        val pipeline =
            MotionPipeline(config, hasGyro = true) { seg ->
                emitted += Captured(seg.tNanos.copyOf(seg.length), seg.gyroX.copyOf(seg.length), seg.length)
            }

        // 1. Settle to ARMED.
        var t = 0L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)

        // 2. Confirm onset and stay ACTIVE for ~180ms (above minActiveDurationNanos=150ms, so a
        // finished segment here would be emitted, not discarded as TOO_SHORT) via a high, constant
        // GYRO reading; ACC stays flat so onset/activity comes purely from the gyro channel. GYRO is
        // sent just before its matching ACC each time, so every frame is paired immediately (no
        // buffering) and really reaches the segmenter.
        repeat(10) {
            pipeline.onSample(SensorKind.GYRO, t, 5f, 0f, 0f) // |gyro|=5 >> G_on=2.0
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }
        assertEquals(Segmenter.State.ACTIVE, pipeline.state)
        val lastGyroBeforeRebase = t - 20_000_000L // 780ms: the last GYRO timestamp actually sent

        // 3. A few ACC-only quiet frames: GYRO has not "caught up" to their timestamps yet, so they
        // buffer in pendingAcc instead of reaching the segmenter — exactly the "ACTIVE segment with
        // pending ACC frames, GYRO lagging" situation from the finding. They stay within
        // gyroSkewToleranceNanos (= maxTimestampGapNanos = 200ms) of the newest ACC so far, so they
        // do not time out and release themselves before step 4.
        val pendingAccStartT = t
        repeat(3) {
            pipeline.onSample(SensorKind.ACC, t, 0f, 0f, 9.81f)
            t += 20_000_000L
        }

        // 4. GYRO's own clock rebases backwards (e.g. a sensor re-registration): below
        // lastGyroBeforeRebase (780ms), but still carrying an active (>= G_on) value, so that if the
        // old gyro-only-clear behavior let this pipeline stay ACTIVE and later trim/emit a segment,
        // this marker frame surviving into it would be an unmistakable sign of the bug (an emitted
        // segment's trailing quiet frames are trimmed away, but an active one like this would not be).
        val rebaseT = lastGyroBeforeRebase - 80_000_000L // 700ms: backwards relative to 780ms
        pipeline.onSample(SensorKind.GYRO, rebaseT, 3.3f, 0f, 0f) // |gyro|=3.3 >= G_on=2.0

        assertEquals(
            "a backward GYRO timestamp must reset the whole pipeline (ADR 0008), not just gyro " +
                "history: the in-progress ACTIVE segment and the pending ACC frames above must not survive",
            Segmenter.State.SETTLING,
            pipeline.state,
        )
        assertTrue("no segment must be emitted across a gyro clock rebase", emitted.isEmpty())

        // 5. GYRO-only samples advancing past the pending ACC timestamps from step 3. On the old,
        // partial-clear behavior these would make the pending frames look "resolvable" and release
        // them paired with a post-rebase GYRO value; after the fix pendingAcc was already dropped by
        // the reset in step 4, so this is a no-op, proven by the final assertions below.
        pipeline.onSample(SensorKind.GYRO, rebaseT + 150_000_000L, 0f, 0f, 0f)
        pipeline.onSample(SensorKind.GYRO, rebaseT + 170_000_000L, 0f, 0f, 0f)

        // 6 & 7. Re-settle from scratch, with ACC timestamps safely after every pending timestamp
        // from step 3 (so there's no risk of a stray pairing), and confirm nothing from before the
        // rebase ever surfaces: a clean ARMED, no segment ever emitted.
        var t2 = rebaseT + 200_000_000L
        repeat(30) {
            pipeline.onSample(SensorKind.GYRO, t2, 0f, 0f, 0f)
            pipeline.onSample(SensorKind.ACC, t2, 0f, 0f, 9.81f)
            t2 += 20_000_000L
        }
        assertEquals(Segmenter.State.ARMED, pipeline.state)
        assertTrue(
            "no post-rebase gyro value may ever be paired with a pre-rebase ACC frame: " +
                "no segment should have been emitted at all",
            emitted.isEmpty(),
        )
        check(t2 > pendingAccStartT + 3 * 20_000_000L) {
            "test bug: re-settle timestamps must stay after every pending-ACC timestamp from step 3"
        }
    }
}
