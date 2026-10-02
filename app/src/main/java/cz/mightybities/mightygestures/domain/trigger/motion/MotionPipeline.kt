package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * The adapter-facing entry point of the motion pipeline (ADR 0008): turns raw
 * [MotionSampleSink.onSample] calls into gravity-removed frames (sample-and-hold for gyroscope) and
 * drives the shared [Segmenter]. One instance is used for exactly one of: recording, confirming, or
 * live detection — never shared across them concurrently — so that [AC-M2] holds trivially: the
 * same code processes every frame the same way regardless of which of the three it is used for.
 *
 * A timestamp gap larger than [MotionConfig.maxTimestampGapNanos], or a non-monotonic timestamp,
 * resets the gravity filter and the segmenter (ADR 0008): a gap usually means the sensor was
 * unregistered and re-registered, so the old gravity estimate and any in-progress window are stale.
 */
internal class MotionPipeline(
    config: MotionConfig,
    hasGyro: Boolean,
    onSegment: (SegmentFrames) -> Unit,
) : MotionSampleSink {
    private val gravityFilter = GravityFilter(config.gravityTimeConstantNanos)
    private val segmenter = Segmenter(config, hasGyro, Segmenter.Listener { onSegment(it) })
    private val maxGapNanos = config.maxTimestampGapNanos

    private var lastAccTimestampNanos: Long = Long.MIN_VALUE
    private var heldGyroX = 0f
    private var heldGyroY = 0f
    private var heldGyroZ = 0f

    var discardListener: ((Segmenter.DiscardReason) -> Unit)?
        get() = segmenter.discardListener
        set(value) {
            segmenter.discardListener = value
        }

    val state: Segmenter.State get() = segmenter.state

    override fun onSample(
        kind: SensorKind,
        timestampNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        when (kind) {
            SensorKind.GYRO -> {
                heldGyroX = x
                heldGyroY = y
                heldGyroZ = z
            }

            SensorKind.ACC -> {
                handleAcc(timestampNanos, x, y, z)
            }
        }
    }

    /** Drops the gravity estimate and any in-progress window; the next ACC sample reseeds both. */
    fun reset() {
        gravityFilter.reset()
        segmenter.reset()
        lastAccTimestampNanos = Long.MIN_VALUE
    }

    private fun handleAcc(
        t: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val last = lastAccTimestampNanos
        if (last != Long.MIN_VALUE) {
            val dt = t - last
            if (dt <= 0L) return // non-monotonic or duplicate: drop this sample only
            if (dt > maxGapNanos) reset()
        }
        lastAccTimestampNanos = t
        gravityFilter.update(t, x, y, z)
        val linX = x - gravityFilter.gravityX
        val linY = y - gravityFilter.gravityY
        val linZ = z - gravityFilter.gravityZ
        segmenter.onFrame(t, x, y, z, linX, linY, linZ, heldGyroX, heldGyroY, heldGyroZ)
    }
}
