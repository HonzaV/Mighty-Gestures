package cz.mightybities.mightygestures.domain.trigger.motion

/** One key's lowest-distance match, returned by [MotionMatcher.bestMatch]. */
data class MotionMatch<K>(
    val key: K,
    val distance: Float,
)

/**
 * Cheap gates followed by DTW (ADR 0008 "Matcher"): duration and per-group RMS ratios reject gross
 * tempo/amplitude mismatches before the O(frameCount·band) DTW ever runs. Shared by capture
 * confirmation, the collision check and live detection, all with the same τ (AC-M2).
 */
class MotionMatcher(
    private val config: MotionConfig,
) {
    private val dtw = DtwMatcher(config.resampledFrameCount, config.dtwBandFrames)

    /**
     * Normalized DTW distance between [live] and [template], or `null` if either gate fails or the
     * two have a different channel set (gyro present on one but not the other — not expected in
     * practice, since a device's channel set does not change, but handled defensively).
     */
    fun distance(
        live: ProcessedSegment,
        template: ProcessedSegment,
    ): Float? {
        val durationGate = config.durationRatioMin..config.durationRatioMax
        val rmsGate = config.rmsRatioMin..config.rmsRatioMax
        val gatesPass =
            live.hasGyro == template.hasGyro &&
                live.dim == template.dim &&
                passesRatioGate(live.durationNanos.toFloat(), template.durationNanos.toFloat(), durationGate) &&
                passesRatioGate(live.linRms, template.linRms, rmsGate) &&
                (!live.hasGyro || passesRatioGate(live.gyroRms, template.gyroRms, rmsGate))
        return if (gatesPass) dtw.distance(live.frames, template.frames, live.dim) else null
    }

    /** Minimum distance over every [exemplars] entry, or `null` if none pass the gates. */
    fun distanceToTemplate(
        live: ProcessedSegment,
        exemplars: List<ProcessedSegment>,
    ): Float? {
        var best: Float? = null
        for (exemplar in exemplars) {
            val d = distance(live, exemplar) ?: continue
            if (best == null || d < best) best = d
        }
        return best
    }

    /**
     * The best-matching key among [templates] (ADR 0008: "if several armed rules match one
     * segment, only the lowest-distance rule fires", AC-M8), or `null` if nothing is within τ.
     */
    fun <K> bestMatch(
        live: ProcessedSegment,
        templates: Map<K, List<ProcessedSegment>>,
    ): MotionMatch<K>? {
        var best: MotionMatch<K>? = null
        for ((key, exemplars) in templates) {
            val d = distanceToTemplate(live, exemplars) ?: continue
            if (d <= config.matchThreshold && (best == null || d < best.distance)) {
                best = MotionMatch(key, d)
            }
        }
        return best
    }

    /**
     * The collision gate at confirmation (ADR 0008 "Collision check", decision 6): `true` if [new]
     * comes within `1.2·τ` of any exemplar in [existing] — regardless of gates, since a
     * near-duplicate gesture should collide even at a different tempo/amplitude.
     */
    fun collidesWith(
        new: List<ProcessedSegment>,
        existing: List<ProcessedSegment>,
    ): Boolean {
        val collisionThreshold = config.matchThreshold * config.collisionDistanceMultiplier
        for (n in new) {
            for (e in existing) {
                if (n.hasGyro != e.hasGyro || n.dim != e.dim) continue
                if (dtw.distance(n.frames, e.frames, n.dim) <= collisionThreshold) return true
            }
        }
        return false
    }

    private fun passesRatioGate(
        live: Float,
        template: Float,
        gate: ClosedFloatingPointRange<Float>,
    ): Boolean {
        if (template == 0f) return false
        return (live / template) in gate
    }
}
