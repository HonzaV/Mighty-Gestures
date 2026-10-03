package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionConfig
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hand-crafted "everyday handling" negative situations for the false-positive harness
 * ([cz.mightybities.mightygestures.domain.trigger.motion.MotionFalsePositiveCorpusTest]). PR #1
 * ships only the generator *core* (spec 0001 "Synthetic trace corpus and calibration",
 * Implementation order row #1: "synthetic generator core ... for unit, robustness and timing
 * tests"); the full negative-situation model library (walking, stairs, pocket, bag, car, etc.,
 * AC-M4) is milestone 3. These are deliberately simple, parametric stand-ins built from the same
 * primitives [GesturePrimitives] uses ([MotionSegmentSpec], [VectorProfile], [concat],
 * [stillness]) so this PR's own matcher/gates can be probed for false positives now, without
 * waiting for the full corpus. None of these claim literature-cited parameter ranges (spec 0001
 * "uncited ranges are labeled *assumed*") -- they are *assumed*, order-of-magnitude plausible
 * handling motions, intentionally swept across a few amplitudes to land inside the matcher's
 * RMS/duration gate range `[0.5, 2.0]` where a false positive would actually be possible; a motion
 * the gates already reject outright would not be a meaningful probe.
 *
 * Picking the phone up off a table: a wrist tilt (rotation about the body X axis) while lifting
 * it (world-Z translation), both a single half-sine hump. [peakAngularRateRadPerSecond] and
 * [peakLiftAcceleration] are swept by the caller to probe the matcher's RMS gate range.
 */
fun pickupFromTable(
    durationSeconds: Float = 0.6f,
    peakAngularRateRadPerSecond: Float = 3.0f,
    peakLiftAcceleration: Float = 4.0f,
): MotionSegmentSpec =
    MotionSegmentSpec(
        durationSeconds,
        VectorProfile { t -> Vector3(0f, 0f, halfSineLobe(t, durationSeconds, peakLiftAcceleration)) },
        VectorProfile { t -> Vector3(halfSineLobe(t, durationSeconds, peakAngularRateRadPerSecond), 0f, 0f) },
    )

/** A single smooth half-sine hump spanning `[0, durationSeconds)`, peaking at the midpoint. */
private fun halfSineLobe(
    t: Float,
    durationSeconds: Float,
    peak: Float,
): Float {
    if (t < 0f || t > durationSeconds) return 0f
    return peak * sin(PI.toFloat() * t / durationSeconds)
}

/** Putting the phone down on a table: the mirror image of [pickupFromTable] (tilt and lift reversed). */
fun putDownOnTable(
    durationSeconds: Float = 0.6f,
    peakAngularRateRadPerSecond: Float = 3.0f,
    peakLiftAcceleration: Float = 4.0f,
): MotionSegmentSpec =
    MotionSegmentSpec(
        durationSeconds,
        VectorProfile { t -> Vector3(0f, 0f, -halfSineLobe(t, durationSeconds, peakLiftAcceleration)) },
        VectorProfile { t -> Vector3(-halfSineLobe(t, durationSeconds, peakAngularRateRadPerSecond), 0f, 0f) },
    )

/** Tilting the phone towards your face to read it: a gentler, slower wrist tilt than a pickup. */
fun tiltToRead(
    durationSeconds: Float = 0.5f,
    peakAngularRateRadPerSecond: Float = 2.4f,
): MotionSegmentSpec =
    MotionSegmentSpec(
        durationSeconds,
        VectorProfile { Vector3.ZERO },
        VectorProfile { t -> Vector3(halfSineLobe(t, durationSeconds, peakAngularRateRadPerSecond), 0f, 0f) },
    )

/** Rotating the phone from portrait to landscape: a quarter-turn about the body Z (yaw) axis. */
fun rotateToLandscape(
    durationSeconds: Float = 0.6f,
    peakAngularRateRadPerSecond: Float = 3.2f,
): MotionSegmentSpec =
    MotionSegmentSpec(
        durationSeconds,
        VectorProfile { t -> Vector3(halfSineLobe(t, durationSeconds, peakAngularRateRadPerSecond * 0.3f), 0f, 0f) },
        VectorProfile { t -> Vector3(0f, 0f, halfSineLobe(t, durationSeconds, peakAngularRateRadPerSecond)) },
    )

/**
 * A short walking burst (2-3 steps) that stops, unlike the continuous 6 s walk already covered by
 * AC-M7's timing test: this one is short enough that it might actually *segment* (rather than be
 * discarded as too-long), which is the scenario that matters for a false-positive check.
 */
fun walkingBurst(
    stepCount: Int = 3,
    stepFrequencyHz: Float = 1.8f,
    config: MotionConfig = MotionConfig(),
): MotionSegmentSpec =
    walking(durationSeconds = stepCount / stepFrequencyHz, stepFrequencyHz = stepFrequencyHz, config = config)

/**
 * A single "pocket" burst: a short, low-passed pseudo-random wobble (sum of a handful of
 * sinusoids at different, non-harmonic frequencies/phases/axes), standing in for unstructured
 * incidental motion (walking jostle, bag sway, brushing against a pocket) until the full
 * situation model (milestone 3) exists. [seed] drives frequency/phase/axis-weight randomization
 * so many distinct bursts can be generated cheaply; [peakAcceleration]/[peakAngularRate] are swept
 * by the caller.
 */
fun pocketBurst(
    seed: Long,
    durationSeconds: Float,
    peakAcceleration: Float,
    peakAngularRate: Float,
): MotionSegmentSpec {
    val noise = NoiseSource(seed)
    val linTerms =
        (1..POCKET_HARMONIC_COUNT).map { PocketTerm.sample(noise, maxFrequencyHz = POCKET_MAX_LIN_FREQUENCY_HZ) }
    val angTerms =
        (1..POCKET_HARMONIC_COUNT).map { PocketTerm.sample(noise, maxFrequencyHz = POCKET_MAX_ANGULAR_FREQUENCY_HZ) }
    val chosenLinAxis = (noise.uniform(0f, 3f)).toInt().coerceIn(0, 2)
    val chosenAngAxis = (noise.uniform(0f, 3f)).toInt().coerceIn(0, 2)
    return MotionSegmentSpec(
        durationSeconds,
        VectorProfile { t ->
            val value = linTerms.sumOf { it.at(t).toDouble() }.toFloat() * peakAcceleration
            axisVector(chosenLinAxis, value)
        },
        VectorProfile { t ->
            val value = angTerms.sumOf { it.at(t).toDouble() }.toFloat() * peakAngularRate
            axisVector(chosenAngAxis, value)
        },
    )
}

private fun axisVector(
    axis: Int,
    value: Float,
): Vector3 =
    when (axis) {
        0 -> Vector3(value, 0f, 0f)
        1 -> Vector3(0f, value, 0f)
        else -> Vector3(0f, 0f, value)
    }

/** One randomized sinusoidal term of a [pocketBurst], normalized so the sum of
 * [POCKET_HARMONIC_COUNT] unit-amplitude terms stays roughly within `[-1, 1]`. */
private class PocketTerm(
    private val frequencyHz: Float,
    private val phaseRadians: Float,
    private val weight: Float,
) {
    fun at(tSeconds: Float): Float = weight * sin(2f * PI.toFloat() * frequencyHz * tSeconds + phaseRadians)

    companion object {
        fun sample(
            noise: NoiseSource,
            maxFrequencyHz: Float,
        ) = PocketTerm(
            frequencyHz = noise.uniform(POCKET_MIN_FREQUENCY_HZ, maxFrequencyHz),
            phaseRadians = noise.uniform(0f, 2f * PI.toFloat()),
            weight = noise.uniform(0.2f, 1f) / POCKET_HARMONIC_COUNT,
        )
    }
}

/** A sequence of [burstCount] independent pocket bursts separated by >= 600 ms of quiet each
 * (the AC-M6 two-segments threshold), so every burst is its own, independently-tested segment
 * attempt rather than one long run that the segmenter might merge or discard as too-long. */
fun pocketBurstSequence(
    burstCount: Int,
    seedOffset: Long,
    peakAcceleration: Float,
    peakAngularRate: Float,
): MotionSegmentSpec {
    val segments = mutableListOf<MotionSegmentSpec>()
    segments += stillness(POCKET_LEAD_SECONDS)
    for (i in 0 until burstCount) {
        val noise = NoiseSource(seedOffset + i)
        val duration = noise.uniform(POCKET_MIN_DURATION_SECONDS, POCKET_MAX_DURATION_SECONDS)
        segments += pocketBurst(seedOffset + i, duration, peakAcceleration, peakAngularRate)
        segments += stillness(POCKET_GAP_SECONDS)
    }
    return concat(segments)
}

private const val POCKET_HARMONIC_COUNT = 3
private const val POCKET_MIN_FREQUENCY_HZ = 0.5f
private const val POCKET_MAX_LIN_FREQUENCY_HZ = 3f
private const val POCKET_MAX_ANGULAR_FREQUENCY_HZ = 2f
private const val POCKET_LEAD_SECONDS = 0.7f
private const val POCKET_GAP_SECONDS = 0.7f
private const val POCKET_MIN_DURATION_SECONDS = 0.3f
private const val POCKET_MAX_DURATION_SECONDS = 1.6f
