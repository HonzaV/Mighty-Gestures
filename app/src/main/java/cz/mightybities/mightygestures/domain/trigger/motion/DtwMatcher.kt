package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * Dependent multivariate DTW over resampled, normalized frames (ADR 0008 "Matcher"): squared
 * Euclidean local cost, a Sakoe–Chiba band of ±[bandRadius] frames. The returned distance is the
 * accumulated path cost divided by the warping-path length, so it is comparable across segments of
 * (slightly) different effective length within the band.
 *
 * The cost/path-length tables are preallocated for [frameCount] once and reused on every call: the
 * band shape is identical every time because both operands are always resampled to the same
 * [frameCount] (ADR 0008), so cells outside the band are never read again after construction.
 * Per-call cost is therefore O(frameCount · bandRadius) with zero allocation, but this only runs
 * at segment end (ADR 0008 performance budget), never per sample.
 */
internal class DtwMatcher(
    private val frameCount: Int,
    private val bandRadius: Int,
) {
    private val size = frameCount + 1
    private val cost = FloatArray(size * size) { Float.POSITIVE_INFINITY }
    private val pathLength = IntArray(size * size)

    init {
        cost[0] = 0f
        pathLength[0] = 0
    }

    /**
     * @param a live/template frames, flat `[frameCount * dim]`
     * @param b the other operand, flat `[frameCount * dim]`, same [dim]
     */
    fun distance(
        a: FloatArray,
        b: FloatArray,
        dim: Int,
    ): Float {
        for (i in 1..frameCount) {
            val loJ = maxOf(1, i - bandRadius)
            val hiJ = minOf(frameCount, i + bandRadius)
            for (j in loJ..hiJ) {
                cost[i * size + j] = stepCost(a, b, dim, i, j)
            }
        }
        val finalIndex = frameCount * size + frameCount
        val length = pathLength[finalIndex]
        return if (length <= 0) Float.POSITIVE_INFINITY else cost[finalIndex] / length
    }

    private fun stepCost(
        a: FloatArray,
        b: FloatArray,
        dim: Int,
        i: Int,
        j: Int,
    ): Float {
        var localCost = 0f
        val aOffset = (i - 1) * dim
        val bOffset = (j - 1) * dim
        for (d in 0 until dim) {
            val diff = a[aOffset + d] - b[bOffset + d]
            localCost += diff * diff
        }
        val diagIndex = (i - 1) * size + (j - 1)
        val upIndex = (i - 1) * size + j
        val leftIndex = i * size + (j - 1)
        var bestPrev = cost[diagIndex]
        var bestLength = pathLength[diagIndex]
        if (cost[upIndex] < bestPrev) {
            bestPrev = cost[upIndex]
            bestLength = pathLength[upIndex]
        }
        if (cost[leftIndex] < bestPrev) {
            bestPrev = cost[leftIndex]
            bestLength = pathLength[leftIndex]
        }
        pathLength[i * size + j] = bestLength + 1
        return localCost + bestPrev
    }
}
