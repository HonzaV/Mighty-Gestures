package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * Low-pass estimate of gravity in the device frame, subtracted from raw accelerometer samples to
 * produce linear (gravity-free) acceleration (ADR 0008 decision G1). Deterministic and
 * device-independent, unlike the platform's `TYPE_LINEAR_ACCELERATION` fusion.
 *
 * The estimate is **seeded from the first sample** seen after construction or [reset], rather than
 * starting at zero: starting at zero would read as a fake ~9.8 m/s² of linear acceleration until
 * the filter converges, regardless of how the phone happens to be oriented when sensing starts.
 *
 * Per-sample cost is O(1) with no allocation (AC-M9): three running floats updated in place.
 */
internal class GravityFilter(
    private val timeConstantNanos: Long,
) {
    var gravityX: Float = 0f
        private set
    var gravityY: Float = 0f
        private set
    var gravityZ: Float = 0f
        private set

    private var lastTimestampNanos: Long = Long.MIN_VALUE
    private var seeded: Boolean = false

    /** Drops the running estimate; the next [update] reseeds from its raw sample. */
    fun reset() {
        seeded = false
        lastTimestampNanos = Long.MIN_VALUE
    }

    /**
     * Seeds the estimate with a known value instead of a raw sample, so that re-deriving a stored
     * [MotionExemplar] reproduces its original gravity trajectory exactly (ADR 0006): the exemplar
     * keeps the filter's value at its first frame instead of raw frame 0, because that value
     * already reflects whatever warm-up happened before recording started.
     */
    fun seed(
        x: Float,
        y: Float,
        z: Float,
        timestampNanos: Long,
    ) {
        gravityX = x
        gravityY = y
        gravityZ = z
        lastTimestampNanos = timestampNanos
        seeded = true
    }

    /**
     * Updates the estimate from one raw ACC sample. Read [gravityX]/[gravityY]/[gravityZ]
     * afterwards; `lin = acc - gravity`.
     *
     * Non-monotonic or duplicate timestamps (`timestampNanos <= lastTimestampNanos`) are ignored:
     * the caller is expected to drop such samples before they reach the segmenter too, but the
     * filter stays safe on its own if it is ever driven directly.
     */
    fun update(
        timestampNanos: Long,
        accX: Float,
        accY: Float,
        accZ: Float,
    ) {
        if (!seeded) {
            gravityX = accX
            gravityY = accY
            gravityZ = accZ
            lastTimestampNanos = timestampNanos
            seeded = true
            return
        }
        val dtNanos = timestampNanos - lastTimestampNanos
        if (dtNanos <= 0L) return
        lastTimestampNanos = timestampNanos
        val dtSeconds = dtNanos / NANOS_PER_SECOND
        val tauSeconds = timeConstantNanos / NANOS_PER_SECOND
        val alpha = dtSeconds / (tauSeconds + dtSeconds)
        gravityX += alpha * (accX - gravityX)
        gravityY += alpha * (accY - gravityY)
        gravityZ += alpha * (accZ - gravityZ)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
