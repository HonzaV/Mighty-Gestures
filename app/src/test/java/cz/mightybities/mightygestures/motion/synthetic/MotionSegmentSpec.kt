package cz.mightybities.mightygestures.motion.synthetic

/**
 * One phase of simulated motion: world-frame linear acceleration (the hand's actual motion,
 * AC-M11) and body-frame angular velocity (what the gyroscope reads directly), each a function of
 * time within `[0, durationSeconds)`.
 */
data class MotionSegmentSpec(
    val durationSeconds: Float,
    val linWorld: VectorProfile,
    val angularBody: VectorProfile,
)

/** No motion at all: used as lead-in/lead-out stillness and as the quiet gap between gestures in timing tests. */
fun stillness(durationSeconds: Float) =
    MotionSegmentSpec(durationSeconds, VectorProfile { Vector3.ZERO }, VectorProfile { Vector3.ZERO })

/**
 * Concatenates [segments] end to end into one profile spanning their total duration, so a trace
 * can freely mix stillness and gestures (e.g. "quiet, chop, 400 ms quiet, chop, quiet" for a
 * back-to-back timing test).
 */
fun concat(segments: List<MotionSegmentSpec>): MotionSegmentSpec {
    val totalDuration = segments.sumOf { it.durationSeconds.toDouble() }.toFloat()
    val starts = FloatArray(segments.size)
    var acc = 0f
    for (i in segments.indices) {
        starts[i] = acc
        acc += segments[i].durationSeconds
    }

    fun segmentIndexAt(t: Float): Int {
        var index = segments.size - 1
        for (i in segments.indices) {
            if (t < starts[i] + segments[i].durationSeconds) {
                index = i
                break
            }
        }
        return index
    }
    val lin =
        VectorProfile { t ->
            val i = segmentIndexAt(t)
            segments[i].linWorld.at(t - starts[i])
        }
    val angular =
        VectorProfile { t ->
            val i = segmentIndexAt(t)
            segments[i].angularBody.at(t - starts[i])
        }
    return MotionSegmentSpec(totalDuration, lin, angular)
}
