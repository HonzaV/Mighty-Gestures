package cz.mightybities.mightygestures.motion.synthetic

import kotlin.math.PI
import kotlin.math.sin

/**
 * A handful of parametric reference gestures (ADR 0008 "Reference gestures"; spec 0001 lists six —
 * shake, double chop, twist, flip face-down, circle, tap-tap — the full set and ≥ 10 seeded
 * variants each is milestone 3's "full synthetic corpus"). PR #1 needs only enough variety to
 * exercise the pipeline's unit, robustness and timing tests (AC-M2, M5–M8, M10).
 *
 * Every factory applies [PerformerVariation] (tempo, amplitude, lead-in/tail, tremor) and returns a
 * ready-to-render [MotionSegmentSpec]. All intentional motion content is a single sine lobe or a
 * low-frequency oscillation, well under the AC-M11 15 Hz band limit; only [tremor] adds the
 * declared 8–12 Hz physiological component.
 */
object GesturePrimitives {
    /** A back-and-forth wrist shake along one axis: pure translation, usable ACC-only (AC-M10). */
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
                VectorProfile { t -> Vector3(amplitude * sinWave(t, frequencyHz), 0f, 0f) },
                VectorProfile { t -> tremor(variation, t) },
            )
        return withHandling(variation, core)
    }

    /** A single decisive chop: a bell-shaped linear swing plus a matching wrist rotation. */
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
                VectorProfile { t -> Vector3(0f, singleLobe(t, durationSeconds, linPeak), 0f) },
                VectorProfile { t -> Vector3(0f, 0f, singleLobe(t, durationSeconds, gyroPeak)) + tremor(variation, t) },
            )
        return withHandling(variation, core)
    }

    /** A wrist twist: rotation-dominant, with little translation. */
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
                VectorProfile { t -> Vector3(singleLobe(t, durationSeconds, gyroPeak), 0f, 0f) + tremor(variation, t) },
            )
        return withHandling(variation, core)
    }

    private fun sign(mirrored: Boolean) = if (mirrored) -1f else 1f

    private fun sinWave(
        t: Float,
        frequencyHz: Float,
    ) = sin(TWO_PI * frequencyHz * t)

    /** A single smooth hump spanning `[0, durationSeconds)`, peaking at the midpoint (half-sine). */
    private fun singleLobe(
        t: Float,
        durationSeconds: Float,
        peak: Float,
    ): Float {
        if (t < 0f || t > durationSeconds) return 0f
        return peak * sin(PI.toFloat() * t / durationSeconds)
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
    private const val NOMINAL_SHAKE_DURATION_SECONDS = 0.6f
    private const val SHAKE_FREQUENCY_HZ = 4f
    private const val SHAKE_PEAK_ACCELERATION = 14f
    private const val NOMINAL_CHOP_DURATION_SECONDS = 0.45f
    private const val CHOP_PEAK_ACCELERATION = 16f

    // Peak angular rate is deliberately modest (total rotation over the half-sine lobe stays well
    // under 90°): the gravity filter's low-pass estimate (MotionConfig.gravityTimeConstantNanos)
    // needs several time constants to re-converge after a large reorientation (ADR 0008
    // "Consequences": "rotation-heavy gestures end ~0.5 s later"), and a too-large rotation leaves
    // the segment never quiet, i.e. it never ends within a realistic trace (AC-C4's own 3 s cap).
    private const val CHOP_PEAK_ANGULAR_RATE = 3f
    private const val NOMINAL_TWIST_DURATION_SECONDS = 0.5f
    private const val TWIST_PEAK_ANGULAR_RATE = 3.2f
}
