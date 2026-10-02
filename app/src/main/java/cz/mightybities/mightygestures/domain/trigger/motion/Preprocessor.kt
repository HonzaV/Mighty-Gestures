package cz.mightybities.mightygestures.domain.trigger.motion

import kotlin.math.sqrt

/**
 * One segment resampled to a fixed frame count and normalized per sensor group (ADR 0008
 * "Preprocessor"), ready for the [MotionMatcher]'s gates and DTW. [frames] is a flat,
 * row-major `[resampledFrameCount * dim]` array; `dim` is 6 ([lin.x,y,z, gyro.x,y,z]) or 3
 * (`lin` only) depending on [hasGyro].
 *
 * [durationNanos], [linRms] and [gyroRms] describe the **original, un-resampled** segment and feed
 * the matcher's duration/RMS gates; they are not recoverable from [frames] alone, which is
 * normalized to unit RMS by construction.
 */
class ProcessedSegment internal constructor(
    val frames: FloatArray,
    val dim: Int,
    val hasGyro: Boolean,
    val durationNanos: Long,
    val linRms: Float,
    val gyroRms: Float,
)

/**
 * Resamples a segmenter window to [MotionConfig.resampledFrameCount] frames, uniform in time by
 * linear interpolation, and scales each sensor group to unit RMS (ADR 0008 "Preprocessor"). Runs
 * once per segment, not per sample, so the small allocations here are outside the AC-M9 hot path.
 *
 * Record, confirm and live all call the same [process]: AC-M2 requires that a given trace yields
 * identical [ProcessedSegment]s regardless of which of the three it was captured for.
 */
class Preprocessor(
    private val config: MotionConfig,
) {
    fun process(segment: SegmentFrames): ProcessedSegment {
        val length = segment.length
        require(length >= MIN_FRAMES_TO_RESAMPLE) {
            "segment needs at least $MIN_FRAMES_TO_RESAMPLE frames to resample, had $length"
        }
        val durationNanos = segment.tNanos[length - 1] - segment.tNanos[0]
        val linRms =
            rms(segment.linX, segment.linY, segment.linZ, length)
                .coerceAtLeast(config.accQuietThreshold)
        val hasGyro = segment.hasGyro
        val gyroRms =
            if (hasGyro) {
                rms(segment.gyroX, segment.gyroY, segment.gyroZ, length).coerceAtLeast(config.gyroQuietThreshold)
            } else {
                config.gyroQuietThreshold
            }
        val dim = if (hasGyro) DIM_WITH_GYRO else DIM_ACC_ONLY
        val frames = resample(segment, dim, linRms, gyroRms)
        return ProcessedSegment(frames, dim, hasGyro, durationNanos, linRms, gyroRms)
    }

    private fun resample(
        segment: SegmentFrames,
        dim: Int,
        linRms: Float,
        gyroRms: Float,
    ): FloatArray {
        val length = segment.length
        val n = config.resampledFrameCount
        val out = FloatArray(n * dim)
        val startT = segment.tNanos[0]
        val spanNanos = (segment.tNanos[length - 1] - startT).coerceAtLeast(1L)
        var srcIndex = 0
        for (i in 0 until n) {
            val targetT = startT + spanNanos * i / (n - 1).coerceAtLeast(1)
            while (srcIndex < length - 2 && segment.tNanos[srcIndex + 1] < targetT) srcIndex++
            val next = (srcIndex + 1).coerceAtMost(length - 1)
            val t0 = segment.tNanos[srcIndex]
            val t1 = segment.tNanos[next]
            val frac = if (t1 == t0) 0f else (targetT - t0).toFloat() / (t1 - t0).toFloat()
            val base = i * dim
            out[base + LIN_X_OFFSET] = lerp(segment.linX[srcIndex], segment.linX[next], frac) / linRms
            out[base + LIN_Y_OFFSET] = lerp(segment.linY[srcIndex], segment.linY[next], frac) / linRms
            out[base + LIN_Z_OFFSET] = lerp(segment.linZ[srcIndex], segment.linZ[next], frac) / linRms
            if (dim == DIM_WITH_GYRO) {
                out[base + GYRO_X_OFFSET] = lerp(segment.gyroX[srcIndex], segment.gyroX[next], frac) / gyroRms
                out[base + GYRO_Y_OFFSET] = lerp(segment.gyroY[srcIndex], segment.gyroY[next], frac) / gyroRms
                out[base + GYRO_Z_OFFSET] = lerp(segment.gyroZ[srcIndex], segment.gyroZ[next], frac) / gyroRms
            }
        }
        return out
    }

    private fun lerp(
        a: Float,
        b: Float,
        frac: Float,
    ) = a + (b - a) * frac

    private fun rms(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        length: Int,
    ): Float {
        var sumSq = 0.0
        for (i in 0 until length) {
            sumSq += (x[i] * x[i] + y[i] * y[i] + z[i] * z[i]).toDouble()
        }
        return sqrt(sumSq / length).toFloat()
    }

    private companion object {
        const val MIN_FRAMES_TO_RESAMPLE = 2
        const val DIM_WITH_GYRO = 6
        const val DIM_ACC_ONLY = 3
        const val LIN_X_OFFSET = 0
        const val LIN_Y_OFFSET = 1
        const val LIN_Z_OFFSET = 2
        const val GYRO_X_OFFSET = 3
        const val GYRO_Y_OFFSET = 4
        const val GYRO_Z_OFFSET = 5
    }
}
