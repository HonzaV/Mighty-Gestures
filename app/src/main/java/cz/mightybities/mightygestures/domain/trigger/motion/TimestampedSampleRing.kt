package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * A small, preallocated circular buffer of timestamped 3-axis samples, used by [MotionPipeline] to
 * pair ACC and GYRO samples by **sensor timestamp** rather than arrival order (ADR 0008
 * implementation note, 2026-10-03: "sample-and-hold is keyed by sensor timestamp"). One instance
 * holds recent GYRO history (to look up "the latest GYRO at or before time t"); another holds ACC
 * frames whose paired GYRO value is not yet known.
 *
 * Index-based, not callback-based: [latestAtOrBeforeIndex] returns a plain `Int` and the caller
 * reads the public arrays directly, so pairing a frame is allocation-free (AC-M9) — a lambda
 * capturing `var`s here would risk boxing if it were ever not inlined.
 */
internal class TimestampedSampleRing(
    private val capacity: Int,
) {
    val tNanos = LongArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val z = FloatArray(capacity)

    private var head = 0

    var count = 0
        private set

    val isEmpty: Boolean get() = count == 0
    val isFull: Boolean get() = count == capacity

    fun clear() {
        head = 0
        count = 0
    }

    /** Physical array index of the oldest entry; only valid when ![isEmpty]. */
    fun oldestIndex(): Int = head

    /** Appends the newest sample, overwriting the oldest one once [isFull]. */
    fun push(
        t: Long,
        xv: Float,
        yv: Float,
        zv: Float,
    ) {
        val index = (head + count) % capacity
        tNanos[index] = t
        x[index] = xv
        y[index] = yv
        z[index] = zv
        if (count < capacity) {
            count++
        } else {
            head = (head + 1) % capacity
        }
    }

    /** Advances past the oldest entry. The caller must have already read it (via [oldestIndex])
     * before calling this: the slot is not cleared, only marked free to be overwritten. */
    fun dropOldest() {
        head = (head + 1) % capacity
        count--
    }

    /**
     * Physical array index of the most recent entry with `tNanos <= atOrBefore`, or `-1` if the
     * ring holds no such entry (it does not reach back far enough, or is empty).
     */
    fun latestAtOrBeforeIndex(atOrBefore: Long): Int {
        for (k in count - 1 downTo 0) {
            val i = (head + k) % capacity
            if (tNanos[i] <= atOrBefore) return i
        }
        return -1
    }
}
