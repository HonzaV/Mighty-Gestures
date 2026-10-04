package cz.mightybities.mightygestures.motion.synthetic

import kotlin.math.PI
import kotlin.math.sin

/**
 * A continuous walking gait (spec 0001 "Negative situations"; step frequency 1.6–2.2 Hz is a
 * widely cited normal human cadence range, *assumed* generic figure, not cited to one study).
 *
 * PR #1 fix round, item C11: amplitudes are **absolute and physically motivated** ("assumed", not
 * derived from `MotionConfig`, so this fixture does not tautologically test the config against
 * itself) — anterior-posterior (fore-aft) sway at the step frequency, plus a vertical bounce at
 * *twice* the step frequency (a real gait has two sub-steps — left, then right — per full stride
 * cycle, each contributing a heel-strike bump). Both are **zero-mean per axis**, a correction from
 * this PR's earlier, incorrect analysis of why a single-axis rectified bump failed (that failure
 * was the bump's *non-zero mean*, which the gravity filter is specifically built to absorb — not
 * its passband: a 1.6–2.2 Hz oscillation is comfortably above the filter's ~0.64 Hz cutoff, so a
 * zero-mean oscillation there is barely attenuated at all).
 *
 * At these amplitudes the oscillation never actually reaches `MotionConfig.accOnsetThreshold`
 * (3.0 m/s²), so this walking trace never leaves `ARMED` at all — AC-M7 ("never yields a segment")
 * holds trivially, not via the `TOO_LONG` discard the ADR's prose describes for continuous motion
 * that *does* cross onset. Both are real ways to satisfy "never segments"; which one actually
 * happens is a property of the amplitude, reported here rather than assumed.
 */
fun walking(
    durationSeconds: Float,
    stepFrequencyHz: Float = DEFAULT_STEP_FREQUENCY_HZ,
): MotionSegmentSpec {
    val lin =
        VectorProfile { t ->
            val stepPhase = 2f * PI.toFloat() * stepFrequencyHz * t
            val verticalPhase = 2f * PI.toFloat() * (2f * stepFrequencyHz) * t
            Vector3(ANTERIOR_POSTERIOR_PEAK * sin(stepPhase), 0f, VERTICAL_PEAK * sin(verticalPhase))
        }
    val angular =
        VectorProfile { t ->
            val phase = 2f * PI.toFloat() * stepFrequencyHz * t
            Vector3(ARM_SWAY_PEAK_RAD_PER_SECOND * sin(phase), 0f, 0f)
        }
    return MotionSegmentSpec(durationSeconds, lin, angular)
}

private const val DEFAULT_STEP_FREQUENCY_HZ = 1.8f

/** Fore-aft sway peak: *assumed*, order-of-magnitude plausible for phone-in-hand casual walking. */
private const val ANTERIOR_POSTERIOR_PEAK = 2.0f

/** Vertical bounce peak, at twice the step frequency: *assumed*, typically the larger of the two
 * components in phone accelerometer gait studies. */
private const val VERTICAL_PEAK = 2.6f

/** Ordinary arm/torso sway while walking: *assumed*, well under `MotionConfig.gyroOnsetThreshold`. */
private const val ARM_SWAY_PEAK_RAD_PER_SECOND = 0.3f
