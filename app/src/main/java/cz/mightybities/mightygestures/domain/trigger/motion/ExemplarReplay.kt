package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * Re-derives the [SegmentFrames] view of a stored [MotionExemplar] by replaying its raw samples
 * through a fresh [GravityFilter] seeded with [MotionExemplar.gravityAtStartX]/Y/Z (ADR 0006: raw
 * storage lets templates be re-derived after a pipeline change instead of invalidated). Allocates
 * a segment-sized buffer; called only at segment end (matching, confirmation, collision check),
 * never per sample.
 */
internal fun MotionExemplar.toSegmentFrames(config: MotionConfig): SegmentFrames {
    val buffer = FrameLinearBuffer(length, hasGyro)
    val gravityFilter = GravityFilter(config.gravityTimeConstantNanos)
    gravityFilter.seed(gravityAtStartX, gravityAtStartY, gravityAtStartZ, tNanos[0])
    for (i in 0 until length) {
        if (i > 0) gravityFilter.update(tNanos[i], accX[i], accY[i], accZ[i])
        val linX = accX[i] - gravityFilter.gravityX
        val linY = accY[i] - gravityFilter.gravityY
        val linZ = accZ[i] - gravityFilter.gravityZ
        buffer.push(tNanos[i], accX[i], accY[i], accZ[i], linX, linY, linZ, gyroX[i], gyroY[i], gyroZ[i])
    }
    buffer.onsetIndex = onsetIndex
    return buffer
}

/** Convenience: replays and preprocesses a stored exemplar in one call. */
internal fun Preprocessor.process(
    exemplar: MotionExemplar,
    config: MotionConfig,
): ProcessedSegment = process(exemplar.toSegmentFrames(config))
