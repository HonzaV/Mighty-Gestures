package cz.mightybities.mightygestures.domain.trigger.motion

import kotlin.math.ceil

/**
 * The adapter-facing entry point of the motion pipeline (ADR 0008): turns raw
 * [MotionSampleSink.onSample] calls into gravity-removed frames (sample-and-hold for gyroscope) and
 * drives the shared [Segmenter]. One instance is used for exactly one of: recording, confirming, or
 * live detection — never shared across them concurrently — so that `AC-M2` holds trivially: the
 * same code processes every frame the same way regardless of which of the three it is used for.
 *
 * A timestamp gap larger than [MotionConfig.maxTimestampGapNanos] on the ACC stream, or a
 * non-monotonic ACC timestamp (any backwards jump beyond an exact duplicate — a clock rebase, not
 * normal jitter), resets the gravity filter and the segmenter (ADR 0008): either usually means the
 * sensor was unregistered and re-registered, so the old gravity estimate and any in-progress window
 * are stale. An exact duplicate timestamp is dropped instead (no new information). A non-monotonic
 * GYRO timestamp resets the same way (see the GYRO-pairing bullets below): its own clock rebase is
 * just as stale a signal as the ACC one, even though GYRO is not what the segmenter reads time from.
 *
 * Non-finite (`NaN`/`Infinity`) axis values are dropped outright, for both ACC and GYRO: a single
 * bad sample would otherwise poison [GravityFilter]'s running estimate (and the segmenter's
 * hysteresis thresholds) with `NaN` permanently — `NaN` comparisons are never true, so the pipeline
 * could get stuck unable to read "active" or "quiet" ever again.
 *
 * **GYRO pairing is keyed by sensor timestamp, not arrival order** (implementation note added
 * 2026-10-03, ADR 0008 is unchanged: "gyro = latest GYRO sample (sample-and-hold)" already meant
 * "at this timestamp", this just makes the code honor it under batched/reordered delivery). The
 * platform may deliver a whole block of ACC samples before the GYRO block covering the same
 * window (or vice versa). [gyroHistory] and [pendingAcc] — both small, preallocated
 * [TimestampedSampleRing]s — let an ACC frame wait for the GYRO sample it actually belongs with:
 * - If GYRO has already "caught up" to an ACC frame's timestamp (the common, interleaved case),
 *   it is paired and fed to the segmenter immediately — zero buffering, zero added latency.
 * - Otherwise the ACC frame is buffered in [pendingAcc] until either a GYRO sample arrives that
 *   proves no earlier one is still coming (gyro timestamps are assumed non-decreasing, barring a
 *   gyro-side reset below), or [gyroSkewToleranceNanos] elapses without one — at which point it is
 *   released using the best GYRO value available, rather than waiting forever (bounding latency,
 *   not just buffer size; a device with `hasGyro = true` that simply never delivers a GYRO sample
 *   must not stall the ACC stream indefinitely).
 * - A GYRO sample with a *backwards* timestamp (its own clock rebase) resets the **whole** pipeline
 *   — the same [reset] path an ACC rebase takes, not just [gyroHistory] — before the rebased sample
 *   is accepted. Clearing only [gyroHistory] would leave [pendingAcc], the gravity estimate and any
 *   in-progress `ACTIVE` segment behind: a buffered pre-rebase ACC frame could then be released once
 *   a later, post-rebase GYRO sample makes it look "resolvable" (`oldestT <= gyroMaxTimestampNanos`),
 *   pairing it with a GYRO value from after the clock rebase, and an `ACTIVE` segment could keep
 *   accumulating across the discontinuity. A full reset makes that impossible: there is nothing left
 *   to pair or continue.
 *
 * Both rings are sized like [Segmenter.ringCapacity]: generously above the ~200 Hz platform cap, so
 * realistic batching (ADR 0008's `maxReportLatencyUs`) never overflows them in normal operation.
 */
internal class MotionPipeline(
    private val config: MotionConfig,
    private val hasGyro: Boolean,
    onSegment: (SegmentFrames) -> Unit,
) : MotionSampleSink {
    private val gravityFilter = GravityFilter(config.gravityTimeConstantNanos)
    private val segmenter = Segmenter(config, hasGyro, Segmenter.Listener { onSegment(it) })
    private val maxGapNanos = config.maxTimestampGapNanos

    /** How long an ACC frame waits for GYRO to catch up before being released with a best-effort
     * (possibly stale) GYRO value. Reuses [MotionConfig.maxTimestampGapNanos]: both are "how long a
     * delay is still normal operation, not something stale", and introducing a second tunable for
     * the same underlying judgement call would be a distinction without a difference. */
    private val gyroSkewToleranceNanos = maxGapNanos

    private var lastAccTimestampNanos: Long = Long.MIN_VALUE
    private var lastGyroTimestampNanos: Long = Long.MIN_VALUE
    private var gyroMaxTimestampNanos: Long = Long.MIN_VALUE

    private val gyroHistory = TimestampedSampleRing(ringCapacity(config))
    private val pendingAcc = TimestampedSampleRing(ringCapacity(config))

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
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return // never let NaN/Inf poison the filter
        when (kind) {
            SensorKind.GYRO -> handleGyro(timestampNanos, x, y, z)
            SensorKind.ACC -> handleAcc(timestampNanos, x, y, z)
        }
    }

    /** Drops the gravity estimate, any in-progress window, and all GYRO/ACC pairing state; the
     * next ACC sample reseeds everything. */
    fun reset() {
        gravityFilter.reset()
        segmenter.reset()
        lastAccTimestampNanos = Long.MIN_VALUE
        lastGyroTimestampNanos = Long.MIN_VALUE
        gyroMaxTimestampNanos = Long.MIN_VALUE
        gyroHistory.clear()
        pendingAcc.clear()
    }

    private fun handleGyro(
        t: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (!hasGyro) return
        if (lastGyroTimestampNanos != Long.MIN_VALUE && t < lastGyroTimestampNanos) {
            // This sensor's own clock rebased: not just gyroHistory is stale. pendingAcc, the
            // gravity estimate and any in-progress ACTIVE segment all predate this rebase too, so a
            // partial clear (gyroHistory only) could let a buffered pre-rebase ACC frame survive to
            // be paired with a post-rebase GYRO value once it looks "resolvable", or let an ACTIVE
            // segment keep accumulating across the discontinuity. Reset the whole pipeline, the same
            // path an ACC rebase takes, before accepting the rebased sample below.
            reset()
        }
        lastGyroTimestampNanos = t
        gyroHistory.push(t, x, y, z)
        if (t > gyroMaxTimestampNanos) gyroMaxTimestampNanos = t
        flushReadyPendingAcc()
    }

    private fun handleAcc(
        t: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val last = lastAccTimestampNanos
        val isDuplicate = last != Long.MIN_VALUE && t == last // exact duplicate: no new information
        if (!isDuplicate) {
            if (last != Long.MIN_VALUE) {
                val dt = t - last
                if (dt < 0L || dt > maxGapNanos) reset() // backwards jump or a real gap: both are "stale state"
            }
            lastAccTimestampNanos = t
            when {
                !hasGyro -> processAcc(t, x, y, z, 0f, 0f, 0f)

                // GYRO has already caught up to this timestamp: pair and process immediately, the
                // common case, with no buffering at all.
                t <= gyroMaxTimestampNanos -> pairWithHistoryAndProcess(t, x, y, z)

                else -> bufferPendingAcc(t, x, y, z)
            }
        }
    }

    private fun pairWithHistoryAndProcess(
        t: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val gyroIndex = gyroHistory.latestAtOrBeforeIndex(t)
        if (gyroIndex >= 0) {
            processAcc(t, x, y, z, gyroHistory.x[gyroIndex], gyroHistory.y[gyroIndex], gyroHistory.z[gyroIndex])
        } else {
            processAcc(t, x, y, z, 0f, 0f, 0f)
        }
    }

    private fun bufferPendingAcc(
        t: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (pendingAcc.isFull) releaseOldestPendingAcc() // bounded: never grows past ringCapacity
        pendingAcc.push(t, x, y, z)
        flushReadyPendingAcc()
    }

    /** Releases every buffered ACC frame that is now definitively resolvable (a GYRO sample with
     * timestamp at or after it has arrived) or has waited past [gyroSkewToleranceNanos] relative to
     * the most recent ACC sample seen, so a device that never delivers GYRO at all cannot stall the
     * ACC stream forever. */
    private fun flushReadyPendingAcc() {
        while (!pendingAcc.isEmpty) {
            val oldestIndex = pendingAcc.oldestIndex()
            val oldestT = pendingAcc.tNanos[oldestIndex]
            val resolvable = oldestT <= gyroMaxTimestampNanos
            val timedOut = oldestT <= lastAccTimestampNanos - gyroSkewToleranceNanos
            if (!resolvable && !timedOut) break
            releaseOldestPendingAcc()
        }
    }

    private fun releaseOldestPendingAcc() {
        val i = pendingAcc.oldestIndex()
        val accT = pendingAcc.tNanos[i]
        val accX = pendingAcc.x[i]
        val accY = pendingAcc.y[i]
        val accZ = pendingAcc.z[i]
        pendingAcc.dropOldest()
        pairWithHistoryAndProcess(accT, accX, accY, accZ)
    }

    @Suppress("LongParameterList") // one axis value per raw/processed channel (hot path; see FrameBuffers.kt).
    private fun processAcc(
        t: Long,
        accX: Float,
        accY: Float,
        accZ: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
    ) {
        gravityFilter.update(t, accX, accY, accZ)
        val linX = accX - gravityFilter.gravityX
        val linY = accY - gravityFilter.gravityY
        val linZ = accZ - gravityFilter.gravityZ
        segmenter.onFrame(t, accX, accY, accZ, linX, linY, linZ, gyroX, gyroY, gyroZ)
    }

    private companion object {
        /** Double the ~200 Hz platform cap (ADR 0008), matching [Segmenter.ringCapacity]'s margin. */
        const val MAX_EXPECTED_SAMPLE_RATE_HZ = 400.0
        const val RING_MARGIN_FRAMES = 8
        const val RING_MIN_CAPACITY = 32
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Sized for [MotionConfig.maxTimestampGapNanos] worth of samples at the expected cap: a
         * real batching delay this pipeline is designed to tolerate must never overflow the ring. */
        fun ringCapacity(config: MotionConfig): Int {
            val frames = ceil(config.maxTimestampGapNanos * MAX_EXPECTED_SAMPLE_RATE_HZ / NANOS_PER_SECOND).toInt()
            return maxOf(RING_MIN_CAPACITY, frames + RING_MARGIN_FRAMES)
        }
    }
}
