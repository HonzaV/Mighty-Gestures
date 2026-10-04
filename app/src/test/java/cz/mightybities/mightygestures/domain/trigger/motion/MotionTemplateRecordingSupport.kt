package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.domain.time.MonotonicClock
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness

/** How to render both the recording and the confirmation trace of [recordAndConfirmTemplate]:
 * bundled so the function itself stays under detekt's parameter-count limit. */
internal data class TemplateRendering(
    val sensorModel: () -> SensorModel,
    val orientation: Quaternion,
)

/** A seed for recording, and a different one for confirming (two independent renders of the same
 * nominal gesture) -- bundled for the same reason as [TemplateRendering]. */
internal data class CaptureSeeds(
    val recordSeed: Long,
    val confirmSeed: Long,
)

/**
 * Records + confirms one gesture through the real [CaptureSession] flow (so [TemplateValidator]'s
 * gates apply, exactly like production's create-gesture flow -- AC-M2), rendering **both** the
 * recording and the confirmation with [rendering]'s sensor model and orientation: "this template
 * was recorded under this disturbance/orientation", not always the clean default every other
 * helper in this package uses. Added for tester task item D14
 * ([MotionNegativeRobustnessAndOrientationTest]), which needs templates recorded under the same
 * disturbance/orientation its probes use, unlike [MotionFalsePositiveCorpusTest]'s private
 * `recordedTemplate` (always the default clean [SensorModel] at [Quaternion.IDENTITY]).
 *
 * Uses [PerformerVariation.NONE] for both passes (not a sampled variation): the
 * disturbance/orientation under test is the variable here, so the gesture shape itself is kept at
 * its reference performance, matching [MotionRobustnessTest]'s own approach.
 */
internal fun recordAndConfirmTemplate(
    config: MotionConfig,
    gesture: (PerformerVariation) -> MotionSegmentSpec,
    hasGyro: Boolean,
    rendering: TemplateRendering,
    seeds: CaptureSeeds,
): List<ProcessedSegment> {
    val matcher = MotionMatcher(config)
    val preprocessor = Preprocessor(config)
    var clockNanos = 0L
    val clock = MonotonicClock { clockNanos }
    val session = CaptureSession(config, hasGyro, TemplateValidator(config), matcher, preprocessor, clock)

    session.startRecording(clockNanos)
    rendering
        .sensorModel()
        .generate(
            wrappedForCapture(gesture(PerformerVariation.NONE)),
            rendering.orientation,
            hasGyro = hasGyro,
            noise = NoiseSource(seeds.recordSeed),
        ).feedTo(session)
    val recorded = session.result
    check(recorded is CaptureResult.Recorded) { "expected Recorded, was $recorded" }

    clockNanos = CAPTURE_CONFIRM_DELAY_NANOS
    session.startConfirming(clockNanos)
    rendering
        .sensorModel()
        .generate(
            wrappedForCapture(gesture(PerformerVariation.NONE)),
            rendering.orientation,
            hasGyro = hasGyro,
            noise = NoiseSource(seeds.confirmSeed),
        ).feedTo(session)
    val confirmed = session.result
    check(confirmed is CaptureResult.Confirmed) { "expected Confirmed, was $confirmed" }

    return listOf(
        preprocessor.process(confirmed.recordExemplar, config),
        preprocessor.process(confirmed.confirmExemplar, config),
    )
}

internal fun wrappedForCapture(gesture: MotionSegmentSpec): MotionSegmentSpec =
    concat(listOf(stillness(CAPTURE_LEAD_SECONDS), gesture, stillness(CAPTURE_TAIL_SECONDS)))

private const val CAPTURE_CONFIRM_DELAY_NANOS = 5_000_000_000L
private const val CAPTURE_LEAD_SECONDS = 0.7f
private const val CAPTURE_TAIL_SECONDS = 1.5f
