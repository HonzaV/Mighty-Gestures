package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * The raw, un-gravity-removed samples of one recorded segment, rebased so the first sample is time
 * 0 (ADR 0008 trace format). Storing raw samples rather than the processed/normalized form lets a
 * future gravity-filter or preprocessor change re-derive the template from the user's original
 * movement instead of asking them to re-record it (ADR 0006, ADR 0008 "Consequences").
 *
 * [gravityAtStartX]/Y/Z is the gravity estimate at the first sample, captured once instead of
 * stored per frame: re-deriving simply re-seeds a [GravityFilter] with it instead of replaying from
 * zero (which would read the first ~250 ms, [MotionConfig.gravityTimeConstantNanos], as fake
 * linear acceleration).
 *
 * Holds primitive arrays, so [equals]/[hashCode] are overridden to compare contents, not identity.
 */
class MotionExemplar
    // Raw sample container: one array per channel/axis plus the start-gravity vector, so a future
    // re-derivation (ADR 0006) has exactly what it needs. Splitting it into sub-objects would only
    // move these fields around, not reduce them.
    @Suppress("LongParameterList")
    constructor(
        val tNanos: LongArray,
        val accX: FloatArray,
        val accY: FloatArray,
        val accZ: FloatArray,
        val gyroX: FloatArray,
        val gyroY: FloatArray,
        val gyroZ: FloatArray,
        val hasGyro: Boolean,
        val gravityAtStartX: Float,
        val gravityAtStartY: Float,
        val gravityAtStartZ: Float,
        /** Index of the first frame at or after the confirmed onset (see [SegmentFrames.onsetIndex]):
         * persisted here too, not just on the live [SegmentFrames] it was copied from, so that
         * [ExemplarReplay]'s re-derived [SegmentFrames] preserves the same pre-roll/active split
         * (ADR 0006 "templates can be re-derived from raw samples"). Without it, a re-derivation
         * defaults to 0 and silently breaks the [TemplateValidator] invariant that the pre-roll is
         * excluded from duration/peak/energy — though in practice the validator never runs on a
         * replayed exemplar (record-only, ADR 0008), this still keeps the two representations of
         * "the same segment" consistent for any future consumer that does read it.
         */
        val onsetIndex: Int,
    ) {
        val length: Int get() = tNanos.size

        init {
            // ExemplarReplay.toSegmentFrames reads tNanos[0] unconditionally, so an empty exemplar
            // would fail there instead of here (milestone 1 fix round, item 4).
            require(tNanos.isNotEmpty()) { "an exemplar must have at least one frame" }
            val arrays = listOf(accX, accY, accZ, gyroX, gyroY, gyroZ)
            require(arrays.all { it.size == tNanos.size }) {
                "all sample arrays must have the same length as tNanos (${tNanos.size})"
            }
            require(tNanos[0] == 0L) { "tNanos must be rebased so the first sample is 0" }
            require(onsetIndex in 0 until tNanos.size) {
                "onsetIndex ($onsetIndex) must be a valid index into a ${tNanos.size}-frame exemplar"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MotionExemplar) return false
            return hasGyro == other.hasGyro &&
                onsetIndex == other.onsetIndex &&
                gravityAtStartX == other.gravityAtStartX &&
                gravityAtStartY == other.gravityAtStartY &&
                gravityAtStartZ == other.gravityAtStartZ &&
                tNanos.contentEquals(other.tNanos) &&
                accX.contentEquals(other.accX) &&
                accY.contentEquals(other.accY) &&
                accZ.contentEquals(other.accZ) &&
                gyroX.contentEquals(other.gyroX) &&
                gyroY.contentEquals(other.gyroY) &&
                gyroZ.contentEquals(other.gyroZ)
        }

        override fun hashCode(): Int {
            var result = tNanos.contentHashCode()
            result = 31 * result + accX.contentHashCode()
            result = 31 * result + accY.contentHashCode()
            result = 31 * result + accZ.contentHashCode()
            result = 31 * result + gyroX.contentHashCode()
            result = 31 * result + gyroY.contentHashCode()
            result = 31 * result + gyroZ.contentHashCode()
            result = 31 * result + hasGyro.hashCode()
            result = 31 * result + onsetIndex
            result = 31 * result + gravityAtStartX.hashCode()
            result = 31 * result + gravityAtStartY.hashCode()
            result = 31 * result + gravityAtStartZ.hashCode()
            return result
        }

        companion object {
            /** Copies a segmenter's output window into an independent, rebased, immutable exemplar. */
            fun fromSegment(segment: SegmentFrames): MotionExemplar {
                val length = segment.length
                check(length >= 1) { "an emitted segment always has at least one frame" }
                val startT = segment.tNanos[0]
                val gravityAtStartX = segment.accX[0] - segment.linX[0]
                val gravityAtStartY = segment.accY[0] - segment.linY[0]
                val gravityAtStartZ = segment.accZ[0] - segment.linZ[0]
                return MotionExemplar(
                    tNanos = LongArray(length) { segment.tNanos[it] - startT },
                    accX = segment.accX.copyOf(length),
                    accY = segment.accY.copyOf(length),
                    accZ = segment.accZ.copyOf(length),
                    gyroX = segment.gyroX.copyOf(length),
                    gyroY = segment.gyroY.copyOf(length),
                    gyroZ = segment.gyroZ.copyOf(length),
                    hasGyro = segment.hasGyro,
                    gravityAtStartX = gravityAtStartX,
                    gravityAtStartY = gravityAtStartY,
                    gravityAtStartZ = gravityAtStartZ,
                    onsetIndex = segment.onsetIndex,
                )
            }
        }
    }

/**
 * The persisted configuration of a motion trigger (ADR 0004 `TriggerSpec.Motion`): the two
 * exemplars recorded during create-gesture (ADR 0008 "A template holds two exemplars").
 * [algorithmVersion] is bumped whenever the pipeline changes, so stored templates can be
 * re-derived from [MotionExemplar.accX]/etc. instead of invalidated (ADR 0008 "Consequences").
 */
data class MotionTemplate(
    val exemplars: List<MotionExemplar>,
    val channels: Set<SensorKind>,
    val algorithmVersion: Int,
) {
    init {
        require(exemplars.isNotEmpty()) { "a template needs at least one exemplar" }
        require(SensorKind.ACC in channels) { "the accelerometer channel is mandatory (ADR 0008)" }
    }
}
