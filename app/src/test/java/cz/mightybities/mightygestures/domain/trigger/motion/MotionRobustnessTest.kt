package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * AC-M5: identical verdicts (segmented exactly once, matches the same clean template) at
 * 50/100/200 Hz, with timestamp jitter, dropped samples, an accelerometer bias, and any static
 * gravity orientation. "Any static gravity orientation" means the *template and the probe share
 * that orientation* — device-frame matching is intentionally orientation-sensitive by design (ADR
 * 0008 "Invariance trade-offs"); a template recorded in one orientation and probed in a different
 * one is expected to miss, not match (that is a feature, not something this test checks).
 */
class MotionRobustnessTest {
    private val config = MotionConfig()
    private val preprocessor = Preprocessor(config)
    private val matcher = MotionMatcher(config)

    private val cleanTemplate = recordChop(SensorModel(rateHz = 50.0), Quaternion.IDENTITY, NoiseSource(1))

    private fun recordChop(
        sensorModel: SensorModel,
        orientation: Quaternion,
        noise: NoiseSource,
    ): ProcessedSegment {
        val recorded = RecordedPipeline(config)
        recorded.feed(settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE)), sensorModel, noise, orientation)
        check(recorded.segments.size == 1) { "expected exactly one segment, got ${recorded.segments.size}" }
        return preprocessor.process(recorded.segments[0], config)
    }

    private fun assertStillMatches(processed: ProcessedSegment) {
        val distance = matcher.distance(processed, cleanTemplate)
        assertTrue("expected a match, distance=$distance", distance != null && distance <= config.matchThreshold)
    }

    @Test
    fun `same verdict at 50, 100 and 200 Hz`() {
        for (rateHz in listOf(50.0, 100.0, 200.0)) {
            val processed = recordChop(SensorModel(rateHz = rateHz), Quaternion.IDENTITY, NoiseSource(2))
            assertStillMatches(processed)
        }
    }

    @Test
    fun `tolerates plus-minus 2ms timestamp jitter`() {
        val sensorModel = SensorModel(rateHz = 50.0, timestampJitterStdDevNanos = 2_000_000f)
        assertStillMatches(recordChop(sensorModel, Quaternion.IDENTITY, NoiseSource(3)))
    }

    @Test
    fun `tolerates 5 percent dropped samples`() {
        val sensorModel = SensorModel(rateHz = 50.0, dropProbability = 0.05f)
        assertStillMatches(recordChop(sensorModel, Quaternion.IDENTITY, NoiseSource(4)))
    }

    @Test
    fun `tolerates a plus-minus 0point2 ms2 accelerometer bias`() {
        val sensorModel = SensorModel(rateHz = 50.0, accBias = Vector3(0.2f, -0.2f, 0.2f))
        assertStillMatches(recordChop(sensorModel, Quaternion.IDENTITY, NoiseSource(5)))
    }

    @Test
    fun `matches when template and probe share any static gravity orientation`() {
        // A quarter-turn about the device X axis: gravity now reads mostly on Y instead of Z, and
        // the chop's own push lands on a different combination of body axes too — but template and
        // probe are both recorded in this same orientation, so device-frame matching still works.
        val tilted = Quaternion(w = QUARTER_TURN_W, x = QUARTER_TURN_W, y = 0f, z = 0f)
        val template = recordChop(SensorModel(rateHz = 50.0), tilted, NoiseSource(6))
        val probe = recordChop(SensorModel(rateHz = 50.0), tilted, NoiseSource(7))
        val distance = matcher.distance(probe, template)
        assertTrue("expected a match, distance=$distance", distance != null && distance <= config.matchThreshold)
    }

    @Test
    fun `a template and probe recorded in different orientations are expected to miss`() {
        // Documents the accepted trade-off (ADR 0008 "Invariance trade-offs: orientation"): this is
        // not a robustness requirement, it is the opposite — proof the matcher is orientation-sensitive.
        // (A tilt about the gesture's own translation axis would leave it invariant by coincidence;
        // the X axis is orthogonal to chop's Y-axis push and its Z-axis rotation, so it isn't.)
        val tilted = Quaternion(w = QUARTER_TURN_W, x = QUARTER_TURN_W, y = 0f, z = 0f)
        val template = recordChop(SensorModel(rateHz = 50.0), Quaternion.IDENTITY, NoiseSource(8))
        val probe = recordChop(SensorModel(rateHz = 50.0), tilted, NoiseSource(9))
        val distance = matcher.distance(probe, template)
        assertTrue(distance == null || distance > config.matchThreshold)
    }

    @Test
    fun `boundaries stay within about one frame period across rates`() {
        val durations =
            listOf(50.0, 100.0, 200.0).map { rateHz ->
                val recorded = RecordedPipeline(config)
                recorded.feed(
                    settlingMargin(GesturePrimitives.chop(PerformerVariation.NONE)),
                    SensorModel(rateHz = rateHz),
                    NoiseSource(10),
                )
                check(recorded.segments.size == 1)
                val exemplar = recorded.segments[0]
                exemplar.tNanos[exemplar.length - 1] - exemplar.tNanos[0]
            }
        val reference = durations[0]
        for (d in durations) {
            // "+/- 1 frame" at 50 Hz is +/- 20 ms; allow that much across all tested rates.
            assertTrue("duration $d too far from reference $reference", abs(d - reference) <= 20_000_000L)
        }
        assertEquals(3, durations.size)
    }

    private companion object {
        /** cos/sin(45 deg): a quaternion with w = x = component value for a 90 deg rotation about an axis. */
        const val QUARTER_TURN_W = 0.70710677f
    }
}
