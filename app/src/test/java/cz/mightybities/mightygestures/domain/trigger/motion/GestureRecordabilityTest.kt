package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #1 fix round, item B7: every reference gesture must be recordable through the *real*
 * [CaptureSession.startRecording] flow (so [TemplateValidator]'s gates actually apply, unlike
 * [MotionMatcherDistanceSpreadTest] and [MotionRobustnessTest], which build templates directly via
 * [RecordedPipeline]) across the spec's full ±30% performer-amplitude range. The tester found
 * `twist` could never be recorded at all (`TOO_GENTLE`: peak gyro 3.2 rad/s < the 5.0 rad/s gate),
 * and a realistic `chop` variation at the gentle end of the range also failed.
 *
 * Resolution: every gesture's peak was the outlier, not ADR 0008's absolute validator gates (see
 * [GesturePrimitives]'s constants and their rationale comments) — in particular, the *mean energy*
 * gate, not just the peak gate a surface reading of the numbers would suggest, turned out to be
 * the binding constraint once `shake`'s Hann window (item C8) was accounted for. Both gates stay
 * untouched. Values remain "provisional, calibrated in milestone 3" (`MotionConfig`'s KDoc) — this
 * only established that recording is *possible* across the range, not a calibration result.
 *
 * `twist` is excluded from the ACC-only (AC-M10) sweep: it is a pure-rotation gesture by design
 * (little-to-no translation), so its only ACC-only signature is gravity leakage from the rotation
 * (ADR 0008 "Consequences") — no peak value can fix that, since it is a property of having no
 * gyroscope to read the rotation directly, not of this gesture's parameters. This matches
 * [MotionFalsePositiveCorpusTest]'s existing precedent of excluding `twist`/ACC-only.
 */
class GestureRecordabilityTest {
    private val config = MotionConfig()

    private val gestures: List<Pair<String, (PerformerVariation, Boolean) -> MotionSegmentSpec>> =
        listOf(
            "shake" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.shake(v, m) },
            "chop" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.chop(v, m) },
            "twist" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.twist(v, m) },
        )

    @Test
    fun `every reference gesture records across the performer range in 6-D`() {
        assertAllRecordable(hasGyro = true, gestures)
    }

    @Test
    fun `shake and chop record across the performer range ACC-only (AC-M10, twist excluded -- see class KDoc)`() {
        assertAllRecordable(hasGyro = false, gestures.filterNot { (name, _) -> name == "twist" })
    }

    private fun assertAllRecordable(
        hasGyro: Boolean,
        gesturesToCheck: List<Pair<String, (PerformerVariation, Boolean) -> MotionSegmentSpec>>,
    ) {
        val failures = mutableListOf<String>()
        for ((name, factory) in gesturesToCheck) {
            // The spec's performer range is tempo/amplitude in [0.7, 1.3]; pin amplitude at the
            // gentle (hardest) end and sweep tempo/tremor/lead-in/tail across several seeds, since
            // PerformerVariation.sample randomizes all of those together.
            for (seed in SEEDS) {
                val variation = scaledVariation(seed, amplitudeScale = MIN_AMPLITUDE_SCALE)
                recordAndCheck(name to factory, variation, hasGyro, seed, failures)
            }
        }
        assertTrue("gestures failed to record: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    /** [PerformerVariation.sample] with its amplitude pinned to [amplitudeScale] (the gentle end of
     * the range is the hard case for the validator's absolute peak/energy gates), everything else
     * randomized per [seed]. */
    private fun scaledVariation(
        seed: Long,
        amplitudeScale: Float,
    ): PerformerVariation = PerformerVariation.sample(NoiseSource(seed)).copy(amplitudeScale = amplitudeScale)

    private fun recordAndCheck(
        gesture: Pair<String, (PerformerVariation, Boolean) -> MotionSegmentSpec>,
        variation: PerformerVariation,
        hasGyro: Boolean,
        seed: Long,
        failures: MutableList<String>,
    ) {
        val (name, factory) = gesture
        var clockNanos = 0L
        val clock = MonotonicClock { clockNanos }
        val session =
            CaptureSession(
                config,
                hasGyro,
                TemplateValidator(config),
                MotionMatcher(config),
                Preprocessor(config),
                clock,
            )
        session.startRecording(clockNanos)
        val trace = concat(listOf(stillness(0.7f), factory(variation, false), stillness(1.8f)))
        // Item C10: apply the variation's own grip tilt instead of always Quaternion.IDENTITY.
        SensorModel()
            .generate(trace, variation.initialOrientation(), hasGyro = hasGyro, noise = NoiseSource(seed))
            .feedTo(session)
        val result = session.result
        if (result !is CaptureResult.Recorded) {
            val amplitude = variation.amplitudeScale
            val tempo = variation.tempoScale
            failures += "$name seed=$seed hasGyro=$hasGyro amplitude=$amplitude tempo=$tempo: $result"
        }
    }

    private companion object {
        const val MIN_AMPLITUDE_SCALE = 0.7f
        val SEEDS = (1L..10L).toList()
    }
}
