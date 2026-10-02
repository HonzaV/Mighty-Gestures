package cz.mightybities.mightygestures.domain.trigger.motion

/** One row of a trace CSV (docs/engineering/testing.md, ADR 0008 "Trace format"). */
data class MotionTraceSample(
    val kind: SensorKind,
    val timestampNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * A parsed trace: its `#`-prefixed metadata (ADR 0008: `source=` is mandatory, `synthetic` or
 * `device`, so synthetic data can never pass as a human recording) and its samples in file order.
 */
data class MotionTrace(
    val metadata: Map<String, String>,
    val samples: List<MotionTraceSample>,
) {
    val source: String get() = metadata.getValue(MotionTraceCsv.METADATA_SOURCE_KEY)
}

/**
 * Parses the CSV trace format shared by committed fixtures, the synthetic generator's output and —
 * eventually — real-device recordings (ADR 0008 "Trace format"): header
 * `timestamp_ns,sensor,x,y,z`, one sample per subsequent line, `#`-prefixed metadata lines anywhere
 * (`key=value` pairs separated by `;`). Pure parsing only; this type never decides whether a trace
 * is trustworthy beyond requiring the mandatory `source=` field (AC-M11).
 */
object MotionTraceCsv {
    const val METADATA_SOURCE_KEY = "source"
    const val HEADER = "timestamp_ns,sensor,x,y,z"

    fun parse(text: String): MotionTrace {
        val metadata = mutableMapOf<String, String>()
        val samples = mutableListOf<MotionTraceSample>()
        var headerSeen = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> {
                    continue
                }

                line.startsWith("#") -> {
                    parseMetadataLine(line, metadata)
                }

                !headerSeen -> {
                    require(line == HEADER) { "expected header \"$HEADER\", found \"$line\"" }
                    headerSeen = true
                }

                else -> {
                    samples += parseSampleLine(line)
                }
            }
        }
        require(headerSeen) { "trace has no header line" }
        require(metadata.containsKey(METADATA_SOURCE_KEY)) {
            "trace is missing the mandatory \"# source=\" metadata (ADR 0008)"
        }
        return MotionTrace(metadata, samples)
    }

    private fun parseMetadataLine(
        line: String,
        into: MutableMap<String, String>,
    ) {
        val body = line.removePrefix("#").trim()
        for (rawEntry in body.split(';')) {
            val entry = rawEntry.trim()
            if (entry.isEmpty()) continue
            val eq = entry.indexOf('=')
            require(eq > 0) { "malformed metadata entry \"$entry\"" }
            into[entry.substring(0, eq).trim()] = entry.substring(eq + 1).trim()
        }
    }

    private fun parseSampleLine(line: String): MotionTraceSample {
        val parts = line.split(',')
        require(parts.size == CSV_COLUMN_COUNT) { "expected $CSV_COLUMN_COUNT columns, found ${parts.size}: \"$line\"" }
        val kind =
            when (val rawKind = parts[1].trim()) {
                "ACC" -> SensorKind.ACC
                "GYRO" -> SensorKind.GYRO
                else -> error("unknown sensor \"$rawKind\"")
            }
        return MotionTraceSample(
            kind = kind,
            timestampNanos = parts[0].trim().toLong(),
            x = parts[2].trim().toFloat(),
            y = parts[3].trim().toFloat(),
            z = parts[4].trim().toFloat(),
        )
    }

    private const val CSV_COLUMN_COUNT = 5
}
