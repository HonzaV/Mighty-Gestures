package cz.mightybities.mightygestures.motion.synthetic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A handful of parametric reference gestures (ADR 0008 "Reference gestures"; spec 0001 lists six —
 * shake, double chop, twist, flip face-down, circle, tap-tap — the full set and ≥ 10 seeded
 * variants each is milestone 3's "full synthetic corpus"). PR #1 needs only enough variety to
 * exercise the pipeline's unit, robustness and timing tests (AC-M2, M5–M8, M10).
 *
 * Every factory applies [PerformerVariation] (tempo, amplitude, lead-in/tail, tremor) and returns a
 * ready-to-render [MotionSegmentSpec]. All intentional motion content is a single windowed
 * oscillation or a biphasic lobe, well under the AC-M11 15 Hz band limit; only [tremor] adds the
 * declared 8–12 Hz physiological component. [chop] and [twist] are **biphasic** (out-and-back,
 * [biphasicLobe]): a one-directional half-sine rotation/swing leaves a *permanent* reorientation or
 * net velocity, which [GravityFilter] then spends several time constants re-converging to (ADR
 * 0008 "Consequences": "rotation-heavy gestures end ~0.5s later") — real wrist gestures return
 * toward their starting pose, and a gesture primitive that does not do the same manufactures that
 * leakage artificially (PR #1 fix round, item C9).
 */
object GesturePrimitives {
    /**
     * A back-and-forth wrist shake along one axis: pure translation, usable ACC-only (AC-M10).
     * Hann-windowed (item C8), not a raw fixed-cycle-count sinusoid: the window tapers smoothly to
     * exactly zero at both ends regardless of [PerformerVariation.tempoScale] (which changes the
     * oscillation frequency, so "an integer number of cycles" could not be guaranteed across the
     * spec's ±30% tempo range anyway), instead of ending mid-swing with a step discontinuity.
     */
    fun shake(
        variation: PerformerVariation = PerformerVariation.NONE,
        mirrored: Boolean = false,
    ): MotionSegmentSpec {
        val durationSeconds = NOMINAL_SHAKE_DURATION_SECONDS / variation.tempoScale
        val amplitude = SHAKE_PEAK_ACCELERATION * variation.amplitudeScale * sign(mirrored)
        val frequencyHz = SHAKE_FREQUENCY_HZ * variation.tempoScale
        val core =
            MotionSegmentSpec(
                durationSeconds,
                VectorProfile { t ->
                    Vector3(amplitude * hannWindow(t, durationSeconds) * sinWave(t, frequencyHz), 0f, 0f)
                },
                VectorProfile { t -> tremor(variation, t) },
            )
        return withHandling(variation, core)
    }

    /** A single decisive chop: a biphasic linear swing (out, then back) plus a matching biphasic
     * wrist rotation — zero net velocity and zero net rotation (class KDoc, item C9). */
    fun chop(
        variation: PerformerVariation = PerformerVariation.NONE,
        mirrored: Boolean = false,
    ): MotionSegmentSpec {
        val durationSeconds = NOMINAL_CHOP_DURATION_SECONDS / variation.tempoScale
        val linPeak = CHOP_PEAK_ACCELERATION * variation.amplitudeScale * sign(mirrored)
        val gyroPeak = CHOP_PEAK_ANGULAR_RATE * variation.amplitudeScale * sign(mirrored)
        val core =
            MotionSegmentSpec(
                durationSeconds,
                VectorProfile { t -> Vector3(0f, biphasicLobe(t, durationSeconds, linPeak), 0f) },
                VectorProfile { t ->
                    Vector3(0f, 0f, biphasicLobe(t, durationSeconds, gyroPeak)) + tremor(variation, t)
                },
            )
        return withHandling(variation, core)
    }

    /** A wrist twist: rotation-dominant, little translation, out-and-back (item C9/B7: a realistic
     * twist peak clears the validator's gyro gate even at the ±30% range's gentle end). */
    fun twist(
        variation: PerformerVariation = PerformerVariation.NONE,
        mirrored: Boolean = false,
    ): MotionSegmentSpec {
        val durationSeconds = NOMINAL_TWIST_DURATION_SECONDS / variation.tempoScale
        val gyroPeak = TWIST_PEAK_ANGULAR_RATE * variation.amplitudeScale * sign(mirrored)
        val core =
            MotionSegmentSpec(
                durationSeconds,
                VectorProfile { Vector3.ZERO },
                VectorProfile { t ->
                    Vector3(biphasicLobe(t, durationSeconds, gyroPeak), 0f, 0f) + tremor(variation, t)
                },
            )
        return withHandling(variation, core)
    }

    private fun sign(mirrored: Boolean) = if (mirrored) -1f else 1f

    private fun sinWave(
        t: Float,
        frequencyHz: Float,
    ) = sin(TWO_PI * frequencyHz * t)

    /** A smooth taper to exactly zero at both `0` and [durationSeconds], `1` at the midpoint. */
    private fun hannWindow(
        t: Float,
        durationSeconds: Float,
    ): Float {
        if (t < 0f || t > durationSeconds) return 0f
        return HANN_HALF * (1f - cos(TWO_PI * t / durationSeconds))
    }

    /**
     * A full sine cycle over `[0, durationSeconds)`: out, then back, integrating to zero net
     * change over the whole span (item C9) — a simplified stand-in for a true minimum-jerk
     * trajectory (AC-M11 "physically plausible", not an exact biomechanical model), chosen for the
     * property that matters here, not the exact shape.
     */
    private fun biphasicLobe(
        t: Float,
        durationSeconds: Float,
        peak: Float,
    ): Float {
        if (t < 0f || t > durationSeconds) return 0f
        return peak * sin(TWO_PI * t / durationSeconds)
    }

    /** Physiological tremor (ADR 0008 "Performer variability"): small, fast, continuous angular jitter. */
    private fun tremor(
        variation: PerformerVariation,
        t: Float,
    ): Vector3 {
        val value = variation.tremorAmplitudeRadPerSecond * sin(TWO_PI * variation.tremorFrequencyHz * t)
        return Vector3(value, 0f, 0f)
    }

    /**
     * Wraps [core] with [PerformerVariation]'s lead-in/tail of ordinary handling: stillness, since a
     * gesture primitive's own job is the gesture, and the generic "handling before/after" shape
     * (kept below onset by construction, [stillness]) is shared by every gesture.
     */
    private fun withHandling(
        variation: PerformerVariation,
        core: MotionSegmentSpec,
    ): MotionSegmentSpec = concat(listOf(stillness(variation.leadInSeconds), core, stillness(variation.tailSeconds)))

    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val HANN_HALF = 0.5f
    private const val NOMINAL_SHAKE_DURATION_SECONDS = 0.6f
    private const val SHAKE_FREQUENCY_HZ = 4f

    // PR #1 fix round, item B7: peaks are sized so GestureRecordabilityTest's full sweep (every
    // gesture, both channel sets, the gentlest 0.7x end of the spec's +/-30% amplitude range,
    // across 10 performer-variation seeds) passes through the *real* CaptureSession.startRecording
    // flow, i.e. clears both TemplateValidator gates (peak AND mean energy), not merely the peak
    // gate a hand check against validatorPeakAccThreshold/validatorPeakGyroThreshold alone would
    // suggest. The Hann window (item C8) in particular costs shake much more mean energy than its
    // peak alone implies (tapering the edges lowers the mean square well below a bare sinusoid's),
    // which is why its peak needed the largest increase of the three.
    private const val SHAKE_PEAK_ACCELERATION = 24f
    private const val NOMINAL_CHOP_DURATION_SECONDS = 0.45f
    private const val CHOP_PEAK_ACCELERATION = 19f
    private const val CHOP_PEAK_ANGULAR_RATE = 4f
    private const val NOMINAL_TWIST_DURATION_SECONDS = 0.5f

    // A realistic wrist twist peak is plausibly 4-8 rad/s (assumed); 9 rad/s is a bit brisker than
    // that but still a stretch of the same order of magnitude, needed to clear the mean-energy gate
    // (not just the 5.0 rad/s peak gate) at the gentle end of the amplitude range. twist remains
    // unrecordable ACC-only regardless of this value (see GestureRecordabilityTest and
    // MotionFalsePositiveCorpusTest): its only ACC signature is gravity leakage from the rotation,
    // which peak alone cannot fix, and that is a property of being a pure-rotation gesture, not of
    // this constant.
    private const val TWIST_PEAK_ANGULAR_RATE = 9f
}
