package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock

/** The outcome of one recording or confirmation attempt (spec 0001 "Create gesture", steps 2–3). */
sealed interface CaptureResult {
    /** Recording succeeded; `exemplar` is what [CaptureSession.startConfirming] will match against. */
    data class Recorded(
        val exemplar: MotionExemplar,
    ) : CaptureResult

    /** Confirmation matched the recording (AC-C5): the template is ready to save. */
    data class Confirmed(
        val recordExemplar: MotionExemplar,
        val confirmExemplar: MotionExemplar,
        val distance: Float,
    ) : CaptureResult

    /** Confirmation did not match (AC-C5): `distance` is `null` if a gate failed outright. */
    data class NotMatching(
        val distance: Float?,
    ) : CaptureResult

    /** No onset within [MotionConfig.captureNoMovementTimeoutNanos] (AC-C3). */
    data object NoMovement : CaptureResult

    /** The segment exceeded [MotionConfig.maxActiveDurationNanos] (AC-C4, "> 3 s"). */
    data object TooLong : CaptureResult

    /** The segment failed the [TemplateValidator] (AC-C4, "too short"/"too gentle"). */
    data class Invalid(
        val reason: ValidationFailure,
    ) : CaptureResult
}

/** Which of record/confirm is in progress; `IDLE` means neither [CaptureSession.startRecording] nor
 * [CaptureSession.startConfirming] has an attempt running. */
enum class CaptureStage { IDLE, RECORDING, CONFIRMING }

/**
 * Record → confirm, sharing the exact [MotionPipeline] (segmenter + gravity filter), [TemplateValidator]
 * and [MotionMatcher] that live detection uses (AC-M2), so that passing confirmation means "this
 * repeat would be detected live". The [TemplateValidator] runs on `RECORDING` only (ADR 0008
 * "Template validator (record only)"): confirmation is judged purely by [MotionMatcher], exactly
 * like live detection would judge the same repeat.
 *
 * A 10 s timeout from [startRecording]/[startConfirming] without a confirmed onset reports
 * [CaptureResult.NoMovement] (AC-C3). The pipeline keeps delivering samples through [onSample]
 * regardless of whether anything interesting is happening, so this class cannot tell "10 s elapsed"
 * from its own sample stream (sensor time) alone; the capture screen must also call [checkTimeout]
 * on its own wall-clock tick. See [checkTimeout] for why it ignores an attempt already in progress.
 *
 * Not thread-safe: driven from one thread only, like the rest of the motion pipeline (ADR 0008
 * `mg-sensors` `HandlerThread`).
 */
class CaptureSession(
    private val config: MotionConfig,
    hasGyro: Boolean,
    private val validator: TemplateValidator,
    private val matcher: MotionMatcher,
    private val preprocessor: Preprocessor,
    private val clock: MonotonicClock,
) : MotionSampleSink {
    var stage: CaptureStage = CaptureStage.IDLE
        private set

    /** The latest attempt's outcome, or `null` while [stage] is in progress and no segment has landed yet. */
    var result: CaptureResult? = null
        private set

    private val pipeline = MotionPipeline(config, hasGyro, ::handleSegment)
    private var pendingRecordExemplar: MotionExemplar? = null
    private var pendingRecordProcessed: ProcessedSegment? = null
    private var startClockNanos = 0L
    private var deadlineArmed = false

    init {
        pipeline.discardListener = ::handleDiscard
    }

    /** Starts (or restarts) a recording attempt; discards any previous recording. */
    fun startRecording(nowNanos: Long = clock.nanos()) {
        pendingRecordExemplar = null
        pendingRecordProcessed = null
        beginAttempt(CaptureStage.RECORDING, nowNanos)
    }

    /** Starts (or restarts) a confirmation attempt. Requires a prior successful [startRecording]. */
    fun startConfirming(nowNanos: Long = clock.nanos()) {
        checkNotNull(pendingRecordExemplar) { "startConfirming requires a successful recording first" }
        beginAttempt(CaptureStage.CONFIRMING, nowNanos)
    }

    private fun beginAttempt(
        stage: CaptureStage,
        nowNanos: Long,
    ) {
        pipeline.reset()
        result = null
        startClockNanos = nowNanos
        deadlineArmed = true
        this.stage = stage
    }

    override fun onSample(
        kind: SensorKind,
        timestampNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (!isRunning()) return
        pipeline.onSample(kind, timestampNanos, x, y, z)
    }

    /**
     * Advances the 10 s "no movement" deadline using [nowNanos] (real elapsed time, not sensor
     * time: the deadline must still fire if the sensor stops delivering samples entirely). Call
     * this from the capture screen's own timer/tick; it is a no-op once an attempt has already
     * produced a [result].
     *
     * Does nothing while the segmenter is `ACTIVE`: once an onset has been confirmed, a movement is
     * under way and AC-C3 ("no movement detected") no longer applies, even if it happens to start
     * right at the 10 s mark. The attempt then resolves through [onSample]/[handleSegment] or
     * [handleDiscard] ([CaptureResult.TooLong]) instead.
     */
    fun checkTimeout(nowNanos: Long = clock.nanos()) {
        if (!deadlineArmed) return
        if (pipeline.state == Segmenter.State.ACTIVE) return
        if (nowNanos - startClockNanos >= config.captureNoMovementTimeoutNanos) {
            deadlineArmed = false
            result = CaptureResult.NoMovement
        }
    }

    private fun isRunning() = result == null && stage != CaptureStage.IDLE

    private fun handleDiscard(reason: Segmenter.DiscardReason) {
        if (!isRunning()) return
        if (reason == Segmenter.DiscardReason.TOO_LONG) {
            deadlineArmed = false
            result = CaptureResult.TooLong
        }
        // TOO_SHORT (a bump) keeps the same attempt waiting, like live detection does.
    }

    private fun handleSegment(segment: SegmentFrames) {
        if (!isRunning()) return
        deadlineArmed = false
        if (stage == CaptureStage.RECORDING) {
            val failure = validator.validate(segment)
            if (failure != null) {
                result = CaptureResult.Invalid(failure)
                return
            }
        }
        val exemplar = MotionExemplar.fromSegment(segment)
        result =
            when (stage) {
                CaptureStage.RECORDING -> {
                    pendingRecordExemplar = exemplar
                    // Processed via the stored exemplar (gravity-replay), not the live segment
                    // directly: this is exactly how a later live match against the saved template
                    // will compute it, so confirmation's distance equals that future distance
                    // (AC-M2).
                    pendingRecordProcessed = preprocessor.process(exemplar, config)
                    CaptureResult.Recorded(exemplar)
                }

                CaptureStage.CONFIRMING -> {
                    confirmedResult(exemplar, preprocessor.process(segment))
                }

                CaptureStage.IDLE -> {
                    error("unreachable: isRunning() excludes IDLE")
                }
            }
    }

    private fun confirmedResult(
        confirmExemplar: MotionExemplar,
        confirmProcessed: ProcessedSegment,
    ): CaptureResult {
        val recordExemplar = checkNotNull(pendingRecordExemplar) { "confirming without a prior recording" }
        val recordProcessed = checkNotNull(pendingRecordProcessed)
        val distance = matcher.distance(confirmProcessed, recordProcessed)
        return if (distance != null && distance <= config.matchThreshold) {
            CaptureResult.Confirmed(recordExemplar, confirmExemplar, distance)
        } else {
            CaptureResult.NotMatching(distance)
        }
    }
}
