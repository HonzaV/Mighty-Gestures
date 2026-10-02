package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionSampleSink
import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceCsv
import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceSample

/**
 * Delivers every sample to [sink] in list order, exactly like [SensorModel.generate]'s output is
 * meant to be consumed: GYRO before ACC at a shared timestamp, so the "sample-and-hold" GYRO value
 * a [cz.mightybities.mightygestures.domain.trigger.motion.MotionPipeline] uses for an ACC sample is
 * always the one reported *at* that instant, never one step stale.
 */
fun List<MotionTraceSample>.feedTo(sink: MotionSampleSink) {
    for (sample in this) {
        sink.onSample(sample.kind, sample.timestampNanos, sample.x, sample.y, sample.z)
    }
}

/**
 * Serializes [this] as the ADR 0008 trace CSV (header + `#`-prefixed metadata, mandatory
 * `source=synthetic`), the inverse of [MotionTraceCsv.parse]. Used by generator round-trip tests;
 * v1 commits no fixture files generated this way (spec 0001 decision 5), so this lives next to the
 * generator rather than as a committed resource.
 */
fun List<MotionTraceSample>.toTraceCsv(metadata: Map<String, String>): String {
    require(metadata.containsKey(MotionTraceCsv.METADATA_SOURCE_KEY)) {
        "metadata must include \"${MotionTraceCsv.METADATA_SOURCE_KEY}\" (ADR 0008)"
    }
    val builder = StringBuilder()
    builder.append("# ")
    builder.append(metadata.entries.joinToString("; ") { (k, v) -> "$k=$v" })
    builder.append('\n')
    builder.append(MotionTraceCsv.HEADER)
    builder.append('\n')
    for (sample in this) {
        builder.append(sample.timestampNanos).append(',')
        builder.append(sample.kind.name).append(',')
        builder.append(sample.x).append(',')
        builder.append(sample.y).append(',')
        builder.append(sample.z).append('\n')
    }
    return builder.toString()
}
