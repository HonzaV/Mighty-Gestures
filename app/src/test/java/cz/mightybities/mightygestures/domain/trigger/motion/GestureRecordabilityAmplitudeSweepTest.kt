package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock
import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GestureRecordabilityTest] sweeps only the gentle (0.7x, hardest-to-clear-the-gate) end of the
 * spec's +/-30% performer-amplitude range. Tester task item 3: the reference (1.0x) and the
 * vigorous (1.3x) ends must record too -- a false "unrecordable" at the nominal amplitude would be
 * a much worse bug than at the already-covered gentle end, and milestone 3's calibration sweep
 * needs to know recording holds across the *whole* range, not just one edge.
 *
 * A sibling file rather than an extension of [GestureRecordabilityTest], per tester task item 3,
 * to avoid touching a file the concurrent milestone #1 development branch may still be changing.
 * No gesture peak or gate constant is touched here (spec 0001 decision 15: peak/gate calibration
 * is milestone 3's job); this only asks whether *today's* values record at these amplitudes.
 */
class GestureRecordabilityAmplitudeSweepTest {
    private val config = MotionConfig()

    private val gestures: List<Pair<String, (PerformerVariation, Boolean) -> MotionSegmentSpec>> =
        listOf(
            "shake" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.shake(v, m) },
            "chop" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.chop(v, m) },
            "twist" to { v: PerformerVariation, m: Boolean -> GesturePrimitives.twist(v, m) },
        )

    @Test
    fun `every reference gesture records at the nominal (1point0x) amplitude in 6-D`() {
        assertAllRecordable(hasGyro = true, gestures, amplitudeScale = NOMINAL_AMPLITUDE_SCALE)
    }

    @Test
    fun `every reference gesture records at the vigorous (1point3x) amplitude in 6-D`() {
        assertAllRecordable(hasGyro = true, gestures, amplitudeScale = VIGOROUS_AMPLITUDE_SCALE)
    }

    @Test
    fun `shake and chop record at the nominal (1point0x) amplitude ACC-only (twist excluded, see class KDoc)`() {
        assertAllRecordable(
            hasGyro = false,
            gestures.filterNot { (name, _) -> name == "twist" },
            amplitudeScale = NOMINAL_AMPLITUDE_SCALE,
        )
    }

    @Test
    fun `shake and chop record at the vigorous (1point3x) amplitude ACC-only (twist excluded, see class KDoc)`() {
        assertAllRecordable(
            hasGyro = false,
            gestures.filterNot { (name, _) -> name == "twist" },
            amplitudeScale = VIGOROUS_AMPLITUDE_SCALE,
        )
    }

    // twist is excluded from the ACC-only sweeps for the same reason as GestureRecordabilityTest's
    // class KDoc (spec 0001 decision 14): it is a pure-rotation gesture, so ACC-only its only
    // signature is gravity leakage from the rotation, which no amplitude can fix.

    private fun assertAllRecordable(
        hasGyro: Boolean,
        gesturesToCheck: List<Pair<String, (PerformerVariation, Boolean) -> MotionSegmentSpec>>,
        amplitudeScale: Float,
    ) {
        val failures = mutableListOf<String>()
        for ((name, factory) in gesturesToCheck) {
            for (seed in SEEDS) {
                val variation = scaledVariation(seed, amplitudeScale)
                recordAndCheck(name to factory, variation, hasGyro, seed, failures)
            }
        }
        assertTrue("gestures failed to record: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    /** [PerformerVariation.sample] with its amplitude pinned to [amplitudeScale], everything else
     * (tempo, grip tilt, tremor, lead-in/tail) randomized per [seed], matching
     * [GestureRecordabilityTest]'s approach at the other end of the range. */
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
        const val NOMINAL_AMPLITUDE_SCALE = 1.0f
        const val VIGOROUS_AMPLITUDE_SCALE = 1.3f
        val SEEDS = (1L..10L).toList()
    }
}
