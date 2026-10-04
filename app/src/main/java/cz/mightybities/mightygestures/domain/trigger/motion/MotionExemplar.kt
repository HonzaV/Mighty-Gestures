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
 * **Immutable.** [tNanos]/[accX]/[accY]/[accZ]/[gyroX]/[gyroY]/[gyroZ] are read-only `List` views
 * over private, defensively-copied arrays: the constructor copies every input array before storing
 * it, so a caller that keeps mutating its own array after construction (or a caller — like
 * [CaptureSession]'s pending recording — that mutated the exemplar's own arrays by accident) cannot
 * change this exemplar after the fact. [CaptureResult.Recorded.exemplar] is the pending recording
 * while [CaptureSession] separately caches that same segment's *processed* (preprocessed/matched)
 * data; without this guarantee, mutating the returned arrays would make a later
 * [CaptureResult.Confirmed] report different raw samples than what was actually matched.
 *
 * [equals]/[hashCode] compare contents, not identity, via each `List`'s own structural equality —
 * which, like the `FloatArray.contentEquals`/`contentHashCode` this replaced, compares boxed
 * `Float`/`Long` elements by value and so already distinguishes `0f` from `-0f` and treats every
 * `NaN` as equal to itself. The scalar [gravityAtStartX]/Y/Z fields are compared via [Float.toBits]
 * rather than `==`, to match that same semantics (a plain `==` on the scalars would instead follow
 * IEEE 754: `0f == -0f`, `NaN != NaN`, breaking the equals/hashCode contract for a pair of exemplars
 * differing only in the sign of one gravity-at-start axis).
 */
class MotionExemplar
    // Raw sample container: one array per channel/axis plus the start-gravity vector, so a future
    // re-derivation (ADR 0006) has exactly what it needs. Splitting it into sub-objects would only
    // move these fields around, not reduce them. Constructor parameters are deliberately not `val`
    // here (except the scalars): the properties below are declared explicitly as defensive,
    // read-only copies of these arrays, under the same public names.
    @Suppress("LongParameterList")
    constructor(
        tNanos: LongArray,
        accX: FloatArray,
        accY: FloatArray,
        accZ: FloatArray,
        gyroX: FloatArray,
        gyroY: FloatArray,
        gyroZ: FloatArray,
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
        /** Rebased sample timestamps (first sample is 0), one per frame. A read-only view over a
         * private copy made at construction time (see class KDoc "Immutable"). */
        val tNanos: List<Long> = tNanos.copyOf().asList()
        val accX: List<Float> = accX.copyOf().asList()
        val accY: List<Float> = accY.copyOf().asList()
        val accZ: List<Float> = accZ.copyOf().asList()
        val gyroX: List<Float> = gyroX.copyOf().asList()
        val gyroY: List<Float> = gyroY.copyOf().asList()
        val gyroZ: List<Float> = gyroZ.copyOf().asList()

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
                gravityAtStartX.toBits() == other.gravityAtStartX.toBits() &&
                gravityAtStartY.toBits() == other.gravityAtStartY.toBits() &&
                gravityAtStartZ.toBits() == other.gravityAtStartZ.toBits() &&
                tNanos == other.tNanos &&
                accX == other.accX &&
                accY == other.accY &&
                accZ == other.accZ &&
                gyroX == other.gyroX &&
                gyroY == other.gyroY &&
                gyroZ == other.gyroZ
        }

        override fun hashCode(): Int {
            var result = tNanos.hashCode()
            result = 31 * result + accX.hashCode()
            result = 31 * result + accY.hashCode()
            result = 31 * result + accZ.hashCode()
            result = 31 * result + gyroX.hashCode()
            result = 31 * result + gyroY.hashCode()
            result = 31 * result + gyroZ.hashCode()
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
