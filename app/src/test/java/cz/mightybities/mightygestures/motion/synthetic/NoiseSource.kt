package cz.mightybities.mightygestures.motion.synthetic

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic, seeded randomness for the synthetic generator (ADR 0008 "a seeded, deterministic
 * generator"): every call sequence for a given seed reproduces the exact same trace, so golden
 * fixtures and calibration are stable across runs and machines.
 */
class NoiseSource(
    seed: Long,
) {
    private val random = Random(seed)

    /** A standard-normal sample scaled by [stdDev], via Box–Muller (no external dependency). */
    fun gaussian(stdDev: Float): Float {
        val u1 = random.nextDouble().coerceAtLeast(MIN_UNIFORM)
        val u2 = random.nextDouble()
        val magnitude = sqrt(-2.0 * ln(u1))
        return (magnitude * cos(2.0 * Math.PI * u2) * stdDev).toFloat()
    }

    fun uniform(
        from: Float,
        until: Float,
    ): Float = random.nextDouble(from.toDouble(), until.toDouble()).toFloat()

    /** `true` with probability [probability] (used for dropped-sample simulation). */
    fun chance(probability: Float): Boolean = random.nextDouble() < probability

    private companion object {
        /** Avoids ln(0.0) without measurably biasing the distribution. */
        const val MIN_UNIFORM = 1e-12
    }
}
