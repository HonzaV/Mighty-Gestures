package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * A read-only, allocation-free view of one segmenter window: parallel primitive arrays, valid for
 * indices `0 until length`. [tNanos] is the sensor timestamp; `acc*` is the raw (gravity-included)
 * accelerometer sample; `lin*` is gravity-removed; `gyro*` is the gyroscope sample held at the ACC
 * cadence (zero, and [hasGyro] false, on an ACC-only device).
 *
 * Implementations (see [FrameLinearBuffer]) reuse their backing arrays across segments (ADR 0008:
 * "DTW only at segment end" — allocating here is fine; the arrays themselves never are). A
 * listener receiving this view must finish reading it before returning, and before calling back
 * into the pipeline (e.g. `reset()`, which empties it): the next segment overwrites the same
 * backing storage.
 */
interface SegmentFrames {
    val length: Int

    /**
     * Index of the first frame at or after the confirmed onset: frames `0 until onsetIndex` are
     * the pre-roll, kept for matching context but not part of the movement itself (ADR 0008
     * "segment = `[onset − preRoll, lastActiveFrame]`"). The [TemplateValidator] measures duration,
     * peaks and energy over `onsetIndex until length`, not over the whole segment.
     */
    val onsetIndex: Int
    val hasGyro: Boolean
    val tNanos: LongArray
    val accX: FloatArray
    val accY: FloatArray
    val accZ: FloatArray
    val linX: FloatArray
    val linY: FloatArray
    val linZ: FloatArray
    val gyroX: FloatArray
    val gyroY: FloatArray
    val gyroZ: FloatArray
}

/**
 * Append-only frame storage with a fixed capacity, cleared (not reallocated) between segments. The
 * segmenter trims the tail by lowering [length] rather than discarding data, so emitting a segment
 * never allocates.
 */
internal class FrameLinearBuffer(
    private val capacity: Int,
    override val hasGyro: Boolean,
) : SegmentFrames {
    override val tNanos = LongArray(capacity)
    override val accX = FloatArray(capacity)
    override val accY = FloatArray(capacity)
    override val accZ = FloatArray(capacity)
    override val linX = FloatArray(capacity)
    override val linY = FloatArray(capacity)
    override val linZ = FloatArray(capacity)
    override val gyroX = FloatArray(capacity)
    override val gyroY = FloatArray(capacity)
    override val gyroZ = FloatArray(capacity)

    override var length: Int = 0
        internal set

    override var onsetIndex: Int = 0
        internal set

    fun clear() {
        length = 0
        onsetIndex = 0
    }

    /** Appends one frame. Returns false and leaves the buffer unchanged if it is already full. */
    @Suppress("LongParameterList") // one axis value per raw/processed channel; a struct would still allocate.
    fun push(
        t: Long,
        aX: Float,
        aY: Float,
        aZ: Float,
        lX: Float,
        lY: Float,
        lZ: Float,
        gX: Float,
        gY: Float,
        gZ: Float,
    ): Boolean {
        val i = length
        if (i >= capacity) return false
        tNanos[i] = t
        accX[i] = aX
        accY[i] = aY
        accZ[i] = aZ
        linX[i] = lX
        linY[i] = lY
        linZ[i] = lZ
        gyroX[i] = gX
        gyroY[i] = gY
        gyroZ[i] = gZ
        length++
        return true
    }
}

/**
 * Fixed-capacity circular buffer holding the most recent frames, used only to keep the pre-roll
 * window while the segmenter is `ARMED` (ADR 0008 "pre-roll kept before onset"). Old entries are
 * overwritten in place; nothing is ever allocated after construction.
 */
internal class FrameRingBuffer(
    private val capacity: Int,
) {
    private val tNanos = LongArray(capacity)
    private val accX = FloatArray(capacity)
    private val accY = FloatArray(capacity)
    private val accZ = FloatArray(capacity)
    private val linX = FloatArray(capacity)
    private val linY = FloatArray(capacity)
    private val linZ = FloatArray(capacity)
    private val gyroX = FloatArray(capacity)
    private val gyroY = FloatArray(capacity)
    private val gyroZ = FloatArray(capacity)

    private var writeIndex = 0
    private var count = 0

    fun clear() {
        writeIndex = 0
        count = 0
    }

    @Suppress("LongParameterList") // see FrameLinearBuffer.push
    fun push(
        t: Long,
        aX: Float,
        aY: Float,
        aZ: Float,
        lX: Float,
        lY: Float,
        lZ: Float,
        gX: Float,
        gY: Float,
        gZ: Float,
    ) {
        val i = writeIndex
        tNanos[i] = t
        accX[i] = aX
        accY[i] = aY
        accZ[i] = aZ
        linX[i] = lX
        linY[i] = lY
        linZ[i] = lZ
        gyroX[i] = gX
        gyroY[i] = gY
        gyroZ[i] = gZ
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    /** Appends, in chronological order, every buffered frame with `tNanos >= sinceInclusiveNanos`. */
    fun copyTailInto(
        sinceInclusiveNanos: Long,
        dest: FrameLinearBuffer,
    ) {
        val start = if (count < capacity) 0 else writeIndex
        for (k in 0 until count) {
            val i = (start + k) % capacity
            if (tNanos[i] >= sinceInclusiveNanos) {
                dest.push(
                    tNanos[i],
                    accX[i],
                    accY[i],
                    accZ[i],
                    linX[i],
                    linY[i],
                    linZ[i],
                    gyroX[i],
                    gyroY[i],
                    gyroZ[i],
                )
            }
        }
    }
}
