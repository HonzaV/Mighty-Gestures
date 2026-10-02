package cz.mightybities.mightygestures.domain.trigger.motion

import kotlin.math.sqrt

/** Why a recorded segment was rejected as a template (ADR 0008 "Template validator"). */
enum class ValidationFailure {
    /** Active duration below [MotionConfig.validatorMinActiveDurationNanos] (stricter than the
     * segmenter's own gate). */
    TOO_SHORT,

    /** Neither the peak acceleration nor the peak angular rate gate was reached. */
    TOO_GENTLE,

    /** The mean-energy gate ([MotionConfig.validatorMinMeanEnergy]) was not reached. */
    TOO_GENTLE_ENERGY,
}

/**
 * The "distinctiveness gate" (ADR 0008, record only): rejects segments that passed the segmenter
 * but still resemble ordinary handling rather than a deliberate gesture. Operates on the raw
 * segment (not the resampled/normalized [ProcessedSegment]) because its thresholds are absolute
 * physical quantities (m/s², rad/s), not relative to the segment's own amplitude.
 *
 * Every gate is evaluated over `[SegmentFrames.onsetIndex, length)` only: the pre-roll
 * ([MotionConfig.preRollNanos]) is quiet by construction and must not count towards duration,
 * peak or energy, or a short, gentle pre-roll would pad a genuinely too-brief movement past the
 * gates (ADR 0008 "active duration", "over active frames").
 */
class TemplateValidator(
    private val config: MotionConfig,
) {
    /** Returns `null` if [segment] passes every gate, else the first failure encountered. */
    fun validate(segment: SegmentFrames): ValidationFailure? {
        val length = segment.length
        check(length >= 1) { "an emitted segment always has at least one frame" }
        val onsetIndex = segment.onsetIndex
        val durationNanos = segment.tNanos[length - 1] - segment.tNanos[onsetIndex]
        val accOnSq = config.accOnsetThreshold * config.accOnsetThreshold
        val gyroOnSq = config.gyroOnsetThreshold * config.gyroOnsetThreshold

        var peakLin = 0f
        var peakGyro = 0f
        var energySum = 0f
        for (i in onsetIndex until length) {
            val linMagSq =
                segment.linX[i] * segment.linX[i] + segment.linY[i] * segment.linY[i] +
                    segment.linZ[i] * segment.linZ[i]
            val gyroMagSq =
                segment.gyroX[i] * segment.gyroX[i] + segment.gyroY[i] * segment.gyroY[i] +
                    segment.gyroZ[i] * segment.gyroZ[i]
            peakLin = maxOf(peakLin, sqrt(linMagSq))
            peakGyro = maxOf(peakGyro, sqrt(gyroMagSq))
            energySum += linMagSq / accOnSq + gyroMagSq / gyroOnSq
        }
        val activeFrameCount = length - onsetIndex
        val meanEnergy = energySum / activeFrameCount
        val tooGentle = peakLin < config.validatorPeakAccThreshold && peakGyro < config.validatorPeakGyroThreshold

        return when {
            durationNanos < config.validatorMinActiveDurationNanos -> ValidationFailure.TOO_SHORT
            tooGentle -> ValidationFailure.TOO_GENTLE
            meanEnergy < config.validatorMinMeanEnergy -> ValidationFailure.TOO_GENTLE_ENERGY
            else -> null
        }
    }
}
