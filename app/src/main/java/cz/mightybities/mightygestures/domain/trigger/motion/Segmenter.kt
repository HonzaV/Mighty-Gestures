package cz.mightybities.mightygestures.domain.trigger.motion

import kotlin.math.ceil

/**
 * The one segmenter shared by recording, confirmation and live detection (ADR 0008, AC-M2): a
 * state machine over gravity-removed frames that cuts "a movement" out of a continuous sensor
 * stream. States, per ADR 0008:
 *
 * ```
 * SETTLING (needs quietDebounce of quiet) -> ARMED -> ACTIVE -> (quietDebounce of quiet) emit -> ARMED
 * ```
 *
 * `ACTIVE` longer than [MotionConfig.maxActiveDurationNanos] drops back to `SETTLING` instead of
 * emitting (AC-M7: continuous motion like walking never produces a segment, because it never holds
 * still for [MotionConfig.quietDebounceNanos]). A segment shorter than
 * [MotionConfig.minActiveDurationNanos] is treated as a bump and discarded, but — since quiet was
 * already observed to end it — detection returns straight to `ARMED`, not `SETTLING`.
 *
 * Onset is **retroactive**: a frame only becomes "the onset" once a second consecutive active
 * frame confirms it was not a single-sample spike ([MotionConfig.onsetConfirmFrames]). The segment
 * emitted afterwards starts [MotionConfig.preRollNanos] before that onset, not before the
 * confirming frame.
 *
 * "Active" vs. "quiet" use different, overlapping thresholds (hysteresis): a frame can be neither.
 * Once `ACTIVE`, the segment's tail is trimmed to the **last non-quiet frame**, not the last frame
 * at or above the onset threshold — a frame between the quiet and onset thresholds is still part of
 * the movement, not of the trailing silence.
 *
 * Per-sample cost is O(1), allocation-free (AC-M9): two preallocated buffers, sized generously
 * above the ~200 Hz platform cap (ADR 0008) so a faster-than-requested device never truncates a
 * segment.
 */
internal class Segmenter(
    private val config: MotionConfig,
    hasGyro: Boolean,
    private val listener: Listener,
) {
    enum class State { SETTLING, ARMED, ACTIVE }

    enum class DiscardReason {
        /** Segment ended (quiet reached) but the active portion was under [MotionConfig.minActiveDurationNanos]. */
        TOO_SHORT,

        /** `ACTIVE` exceeded [MotionConfig.maxActiveDurationNanos] without quiet. */
        TOO_LONG,
    }

    fun interface Listener {
        fun onSegment(segment: SegmentFrames)
    }

    var discardListener: ((DiscardReason) -> Unit)? = null

    var state: State = State.SETTLING
        private set

    private val ring = FrameRingBuffer(ringCapacity(config))
    private val active = FrameLinearBuffer(activeCapacity(config), hasGyro)

    private val accOnSq = config.accOnsetThreshold * config.accOnsetThreshold
    private val gyroOnSq = config.gyroOnsetThreshold * config.gyroOnsetThreshold
    private val accOffSq = config.accQuietThreshold * config.accQuietThreshold
    private val gyroOffSq = config.gyroQuietThreshold * config.gyroQuietThreshold

    private var quietAccumNanos = 0L
    private var activeStreak = 0
    private var onsetCandidateTimestampNanos = 0L
    private var onsetTimestampNanos = 0L
    private var lastActiveIndex = -1
    private var lastFrameTimestampNanos = Long.MIN_VALUE

    /** Drops any in-progress window and returns to `SETTLING` (ADR 0008 "reset to SETTLING" on a gap). */
    fun reset() {
        state = State.SETTLING
        quietAccumNanos = 0L
        activeStreak = 0
        lastActiveIndex = -1
        lastFrameTimestampNanos = Long.MIN_VALUE
        ring.clear()
        active.clear()
    }

    @Suppress("LongParameterList") // one axis value per raw/processed channel (hot path; see FrameBuffers.kt).
    fun onFrame(
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
        val dt = if (lastFrameTimestampNanos == Long.MIN_VALUE) 0L else t - lastFrameTimestampNanos
        lastFrameTimestampNanos = t
        val linMagSq = lX * lX + lY * lY + lZ * lZ
        val gyroMagSq = gX * gX + gY * gY + gZ * gZ
        when (state) {
            State.SETTLING -> handleSettling(dt, linMagSq, gyroMagSq, t, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
            State.ARMED -> handleArmed(t, linMagSq, gyroMagSq, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
            State.ACTIVE -> handleActive(dt, t, linMagSq, gyroMagSq, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
        }
    }

    @Suppress("LongParameterList") // one axis value per raw/processed channel (hot path; see FrameBuffers.kt).
    private fun handleSettling(
        dt: Long,
        linMagSq: Float,
        gyroMagSq: Float,
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
        if (isQuietFrame(linMagSq, gyroMagSq, accOffSq, gyroOffSq)) {
            quietAccumNanos += dt
            if (quietAccumNanos >= config.quietDebounceNanos) {
                enterArmed()
                ring.push(t, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
            }
        } else {
            quietAccumNanos = 0L
        }
    }

    @Suppress("LongParameterList") // one axis value per raw/processed channel (hot path; see FrameBuffers.kt).
    private fun handleArmed(
        t: Long,
        linMagSq: Float,
        gyroMagSq: Float,
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
        ring.push(t, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
        if (isActiveFrame(linMagSq, gyroMagSq, accOnSq, gyroOnSq)) {
            if (activeStreak == 0) onsetCandidateTimestampNanos = t
            activeStreak++
            if (activeStreak >= config.onsetConfirmFrames) {
                onsetTimestampNanos = onsetCandidateTimestampNanos
                confirmOnset()
            }
        } else {
            activeStreak = 0
        }
    }

    private fun confirmOnset() {
        active.clear()
        ring.copyTailInto(onsetTimestampNanos - config.preRollNanos, active)
        // No ring.clear() here (fix for item D21): the ring keeps rolling through ACTIVE (see
        // handleActive's own ring.push) so that if the *next* gesture's onset follows quickly --
        // closer than preRollNanos after re-arming -- it still has real recent frames to draw a
        // pre-roll from, instead of only whatever has accumulated since re-arming cleared it.
        state = State.ACTIVE
        active.onsetIndex = indexOfOnset()
        lastActiveIndex = active.length - 1
        quietAccumNanos = 0L
        activeStreak = 0
    }

    /** First frame at or after [onsetTimestampNanos]: everything before it is pre-roll. */
    private fun indexOfOnset(): Int {
        for (i in 0 until active.length) {
            if (active.tNanos[i] >= onsetTimestampNanos) return i
        }
        return active.length - 1
    }

    @Suppress("LongParameterList") // one axis value per raw/processed channel (hot path; see FrameBuffers.kt).
    private fun handleActive(
        dt: Long,
        t: Long,
        linMagSq: Float,
        gyroMagSq: Float,
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
        if (!active.push(t, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)) {
            discardTooLong()
            return
        }
        // Also rolled into the ring (fix for item D21), so a quickly-following next gesture's
        // pre-roll can draw on real recent frames instead of only post-re-arm history; see
        // confirmOnset's comment.
        ring.push(t, aX, aY, aZ, lX, lY, lZ, gX, gY, gZ)
        if (isQuietFrame(linMagSq, gyroMagSq, accOffSq, gyroOffSq)) {
            quietAccumNanos += dt
        } else {
            quietAccumNanos = 0L
            lastActiveIndex = active.length - 1
        }
        // Measured to the last active (non-quiet) frame, not to the current (possibly quiet) frame:
        // otherwise a movement that finishes just under the limit gets discarded while its quiet
        // tail is still accumulating towards quietDebounceNanos (AC-C4).
        if (active.tNanos[lastActiveIndex] - onsetTimestampNanos > config.maxActiveDurationNanos) {
            discardTooLong()
            return
        }
        if (quietAccumNanos >= config.quietDebounceNanos) {
            finishSegment()
        }
    }

    private fun finishSegment() {
        val activeDurationNanos = active.tNanos[lastActiveIndex] - onsetTimestampNanos
        if (activeDurationNanos < config.minActiveDurationNanos) {
            discardListener?.invoke(DiscardReason.TOO_SHORT)
            enterArmed()
        } else {
            active.length = lastActiveIndex + 1
            listener.onSegment(active)
            enterArmed()
        }
    }

    private fun discardTooLong() {
        discardListener?.invoke(DiscardReason.TOO_LONG)
        state = State.SETTLING
        quietAccumNanos = 0L
        activeStreak = 0
        lastActiveIndex = -1
        ring.clear()
        active.clear()
    }

    private fun enterArmed() {
        state = State.ARMED
        quietAccumNanos = 0L
        activeStreak = 0
        lastActiveIndex = -1
        // No ring.clear() here either (fix for item D21, same reasoning as confirmOnset): the ring
        // already holds `active`'s own recent frames (handleActive now pushes into both), so
        // re-arming right after a segment keeps that rolling pre-roll history instead of wiping it.
        active.clear()
    }

    private companion object {
        /** Double the ~200 Hz platform cap (ADR 0008): a safety margin, not an expected rate. */
        const val MAX_EXPECTED_SAMPLE_RATE_HZ = 400.0
        const val RING_MARGIN_FRAMES = 8
        const val RING_MIN_CAPACITY = 32
        const val ACTIVE_MARGIN_FRAMES = 16

        fun ringCapacity(config: MotionConfig): Int {
            val frames = ceil(config.preRollNanos * MAX_EXPECTED_SAMPLE_RATE_HZ / NANOS_PER_SECOND).toInt()
            return maxOf(RING_MIN_CAPACITY, frames + RING_MARGIN_FRAMES)
        }

        fun activeCapacity(config: MotionConfig): Int {
            val windowNanos = config.maxActiveDurationNanos + config.quietDebounceNanos
            val frames = ceil(windowNanos * MAX_EXPECTED_SAMPLE_RATE_HZ / NANOS_PER_SECOND).toInt()
            return frames + ringCapacity(config) + ACTIVE_MARGIN_FRAMES
        }

        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/** A frame is "quiet" only below both hysteresis-low thresholds (ADR 0008). */
private fun isQuietFrame(
    linMagSq: Float,
    gyroMagSq: Float,
    accOffSq: Float,
    gyroOffSq: Float,
) = linMagSq < accOffSq && gyroMagSq < gyroOffSq

/** A frame is an onset candidate once either onset threshold is reached (ADR 0008). */
private fun isActiveFrame(
    linMagSq: Float,
    gyroMagSq: Float,
    accOnSq: Float,
    gyroOnSq: Float,
) = linMagSq >= accOnSq || gyroMagSq >= gyroOnSq
