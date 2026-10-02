package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A continuous walking gait (spec 0001 "Negative situations"; step frequency 1.6–2.2 Hz is a
 * widely cited normal human cadence range, *assumed* generic figure, not cited to one study).
 *
 * Modeled as a constant-magnitude vector in the device's horizontal plane, rotating at the step
 * frequency, rather than an oscillation confined to one axis. Two reasons, both about
 * [cz.mightybities.mightygestures.domain.trigger.motion.GravityFilter] (τ = 250 ms), not realism:
 * - A single-axis oscillation above its own mean (e.g. a rectified "heel strike" bump) has a
 *   sustained non-zero mean, which the gravity filter is specifically built to absorb (it cannot
 *   distinguish that from a slow reorientation of the phone) — a few time constants in, most of
 *   the signal has simply become "gravity", leaving a residual "lin" far below the onset threshold.
 * - A single-axis, zero-mean sinusoid at the step frequency passes through zero magnitude twice
 *   per stride, which would read as "quiet" and let the segmenter re-arm well before 3 s (defeating
 *   the point of this fixture).
 *
 * A rotating vector is zero-mean on every individual axis (so the filter settles near zero, not
 * away from it) while its magnitude never approaches zero (so the segmenter never reads "quiet"):
 * this is what "never 500 ms of quiet" (ADR 0008 AC-M7) requires, modeled directly.
 */
fun walking(
    durationSeconds: Float,
    stepFrequencyHz: Float = DEFAULT_STEP_FREQUENCY_HZ,
    config: MotionConfig = MotionConfig(),
): MotionSegmentSpec {
    val amplitude = config.accOnsetThreshold + ONSET_MARGIN
    val swayAngularRate = config.gyroOnsetThreshold * SWAY_FRACTION_OF_ONSET
    val lin =
        VectorProfile { t ->
            val phase = 2f * PI.toFloat() * stepFrequencyHz * t
            Vector3(amplitude * sin(phase), amplitude * cos(phase), 0f)
        }
    val angular =
        VectorProfile { t ->
            val phase = 2f * PI.toFloat() * stepFrequencyHz * t
            Vector3(swayAngularRate * sin(phase), 0f, 0f)
        }
    return MotionSegmentSpec(durationSeconds, lin, angular)
}

private const val DEFAULT_STEP_FREQUENCY_HZ = 1.8f

/** How far the rotating vector's (constant) magnitude clears the onset threshold. */
private const val ONSET_MARGIN = 1.5f

/** Ordinary torso/arm sway: a fraction of the onset threshold, never an onset trigger on its own. */
private const val SWAY_FRACTION_OF_ONSET = 0.15f
