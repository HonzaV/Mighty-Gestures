package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.concat
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.stillness

/**
 * Test-only driver: feeds synthetic [MotionSegmentSpec]s through a fresh [MotionPipeline] (exactly
 * the pipeline a live `MotionTriggerSource` (spec 0001 milestone 2; not implemented yet) or
 * [CaptureSession] would use, ADR 0008) and collects
 * every emitted segment as an independent [MotionExemplar]. A segment's backing [SegmentFrames]
 * buffer is reused by the segmenter on the very next sample, so every segment is copied
 * immediately via [MotionExemplar.fromSegment] — this class exists so every test gets that right.
 */
internal class RecordedPipeline(
    val config: MotionConfig = MotionConfig(),
    val hasGyro: Boolean = true,
) {
    val segments = mutableListOf<MotionExemplar>()
    val discards = mutableListOf<Segmenter.DiscardReason>()
    private val pipeline = MotionPipeline(config, hasGyro) { segments += MotionExemplar.fromSegment(it) }

    init {
        pipeline.discardListener = { discards += it }
    }

    val state: Segmenter.State get() = pipeline.state

    /** Renders [spec] with [sensorModel] and feeds every resulting sample straight into the pipeline. */
    fun feed(
        spec: MotionSegmentSpec,
        sensorModel: SensorModel = SensorModel(),
        noise: NoiseSource = NoiseSource(seed = 0),
        initialOrientation: Quaternion = Quaternion.IDENTITY,
    ) {
        sensorModel.generate(spec, initialOrientation, hasGyro, noise).feedTo(pipeline)
    }
}

/**
 * Enough silence for the segmenter to pass `SETTLING` (500 ms) before, and to finish the final
 * segment after. The tail is generous (several [MotionConfig.gravityTimeConstantNanos]): a
 * rotation-heavy gesture leaves the gravity estimate leaking for a few hundred ms after motion
 * stops (ADR 0008 "Consequences"), and the segment cannot go quiet until that settles. Test
 * gestures are wrapped in this, on top of their own short [PerformerVariation] lead-in/tail.
 */
fun settlingMargin(gesture: MotionSegmentSpec): MotionSegmentSpec =
    concat(listOf(stillness(SETTLING_LEAD_SECONDS), gesture, stillness(SETTLING_TAIL_SECONDS)))

private const val SETTLING_LEAD_SECONDS = 0.7f
private const val SETTLING_TAIL_SECONDS = 1.5f

/**
 * Hand-crafted frame, for tests that need exact, known numbers (gates, validator boundaries)
 * rather than a synthetic trace's physics. `lin`/`gyro` default to zero (quiet).
 */
data class FakeFrame(
    val tNanos: Long,
    val linX: Float = 0f,
    val linY: Float = 0f,
    val linZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
)

/** Builds a [SegmentFrames] directly from [frames], bypassing the segmenter, for tests that need
 * exact control over onset placement, duration and amplitude (e.g. [TemplateValidator] gates). */
internal fun fakeSegment(
    frames: List<FakeFrame>,
    onsetIndex: Int = 0,
    hasGyro: Boolean = true,
): SegmentFrames {
    val buffer = FrameLinearBuffer(frames.size, hasGyro)
    for (f in frames) {
        // acc = lin here: these fixtures describe already-gravity-removed signals directly, and no
        // test reads the raw acc channel back out of a fake segment.
        buffer.push(f.tNanos, f.linX, f.linY, f.linZ, f.linX, f.linY, f.linZ, f.gyroX, f.gyroY, f.gyroZ)
    }
    buffer.onsetIndex = onsetIndex
    return buffer
}
