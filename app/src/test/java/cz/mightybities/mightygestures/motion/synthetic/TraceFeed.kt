package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionSampleSink
import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceCsv
import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceSample
import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind

/** The only `source=` value [toTraceCsv] ever emits: this file serializes nothing but
 * [SensorModel]-generated samples, never a human recording. Checked against
 * [MotionTraceCsv.ALLOWED_SOURCES] at class-init time so it can never silently drift from that set
 * of valid values (GitHub Copilot PR #6 round-3 finding). */
val SOURCE_SYNTHETIC: String =
    "synthetic".also {
        require(it in MotionTraceCsv.ALLOWED_SOURCES) {
            "\"$it\" must be one of MotionTraceCsv.ALLOWED_SOURCES (${MotionTraceCsv.ALLOWED_SOURCES})"
        }
    }

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
 *
 * Requires the metadata's `source` value to be exactly [SOURCE_SYNTHETIC], not merely present:
 * this serializer only ever renders [SensorModel]-generated samples, never a human recording, so a
 * caller passing `source=device` (or any other [MotionTraceCsv.ALLOWED_SOURCES] value) would
 * mislabel generated data as a device trace -- exactly what `source=` exists to prevent (GitHub
 * Copilot PR #6 round-3 finding).
 */
fun List<MotionTraceSample>.toTraceCsv(metadata: Map<String, String>): String {
    require(metadata[MotionTraceCsv.METADATA_SOURCE_KEY] == SOURCE_SYNTHETIC) {
        "metadata must set \"${MotionTraceCsv.METADATA_SOURCE_KEY}=$SOURCE_SYNTHETIC\" (this " +
            "serializer only ever renders synthetic samples, ADR 0008)"
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

/**
 * Re-groups [this] (assumed sorted by timestamp, GYRO-before-ACC convention) into fixed-size
 * `[windowNanos]` windows, and within each window, reorders so every sample of [firstKind] comes
 * before every sample of the other kind — simulating a batched delivery path that flushes one
 * sensor's queue before the other's, instead of interleaving. Window boundaries and cross-window
 * order are preserved; this only reorders *within* a window, which is what a real batching flush
 * could plausibly do (ADR 0008 implementation note, 2026-10-03).
 */
fun List<MotionTraceSample>.reorderedByBlock(
    windowNanos: Long,
    firstKind: SensorKind,
): List<MotionTraceSample> {
    val result = mutableListOf<MotionTraceSample>()
    var windowStart = 0
    while (windowStart < size) {
        val windowEndExclusive = indexOfFirstWindowBoundary(windowStart, windowNanos)
        val window = subList(windowStart, windowEndExclusive)
        result += window.filter { it.kind == firstKind }
        result += window.filter { it.kind != firstKind }
        windowStart = windowEndExclusive
    }
    return result
}

private fun List<MotionTraceSample>.indexOfFirstWindowBoundary(
    windowStart: Int,
    windowNanos: Long,
): Int {
    val windowStartTimestamp = this[windowStart].timestampNanos
    var i = windowStart
    while (i < size && this[i].timestampNanos < windowStartTimestamp + windowNanos) i++
    return i
}
