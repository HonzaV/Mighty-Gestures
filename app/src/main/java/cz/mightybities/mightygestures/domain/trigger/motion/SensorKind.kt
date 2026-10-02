package cz.mightybities.mightygestures.domain.trigger.motion

/** The two raw sensor streams the motion pipeline consumes (ADR 0008). */
enum class SensorKind { ACC, GYRO }

/**
 * The adapter → domain boundary (ADR 0003, ADR 0008): one primitive call per `SensorEvent`, no
 * object allocation, no reference to Android types. Implementations must be safe to call
 * repeatedly from one background thread only (ADR 0008 "mg-sensors" `HandlerThread`); there is no
 * synchronization inside the pipeline.
 */
interface MotionSampleSink {
    /**
     * @param kind which sensor this sample came from
     * @param timestampNanos the sensor's own monotonic timestamp (`SensorEvent.timestamp`), not
     *   wall-clock time; every component is driven by this value so that batching or wall-clock
     *   skew never changes a verdict (AC-M5).
     * @param x [y] [z] raw axis values: m/s² for [SensorKind.ACC] (gravity included),
     *   rad/s for [SensorKind.GYRO].
     */
    fun onSample(
        kind: SensorKind,
        timestampNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    )
}
