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
 *
 * **Tie-break and `distance(a, b) == distance(b, a)`.** The local cost `||a_i - b_j||²` is
 * symmetric under swapping the operands (it maps cell `(i, j)`'s cost to cell `(j, i)`'s), so the
 * raw minimum accumulated cost is always symmetric, by induction, regardless of how ties among
 * equal-cost predecessors are broken. The warping-**path length** at a tie is not automatically
 * symmetric, though: swapping `a` and `b` swaps the roles of "up" and "left" (a step that consumes
 * only an `a` frame becomes a step that consumes only a `b` frame) while "diag" stays diag. A
 * tie-break that prefers one *position* over another — e.g. "prefer diag, then up, then left" —
 * therefore does not survive the swap: on an up/left tie it would pick "up" for `distance(a, b)`
 * but the transposed cell's "left" for `distance(b, a)`, recording a different path length for an
 * identical-cost path and changing the cost/length normalization asymmetrically (this is exactly
 * the regression this matcher once had). The fix ties instead on the *value* of the candidates'
 * `(cost, length)` pairs, never on which neighbor they came from: among predecessors achieving the
 * minimum cost, keep the one with the **longer** accumulated path. Because that rule only looks at
 * `(cost, length)` values — and those values at `(i, j)`'s three predecessors are, by the same
 * induction, exactly the values at `(j, i)`'s three predecessors with "up" and "left" relabeled —
 * the predecessor it selects is symmetric too, so `pathLength[i][j] == pathLength[j][i]` and hence
 * `distance(a, b) == distance(b, a)` exactly (see [DtwMatcherTest] for a constructed equal-cost/
 * different-length regression case and a randomized symmetry sweep). Preferring the *longer* of two
 * equal-cost paths (rather than the shorter) is also the right choice for the metric itself: the
 * same total cost spread over more frames gives a lower-or-equal `cost / length`, i.e. it never
 * makes the normalized distance worse.
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
        // Tie-break by path length, not by which neighbor ("up" vs "left") we happen to look at
        // first: see the class KDoc for why a positional tie-break (e.g. "prefer diag, else up,
        // else left") breaks distance(a, b) == distance(b, a), even though it never changes the
        // minimum cost itself.
        if (isBetterPredecessor(cost[upIndex], pathLength[upIndex], bestPrev, bestLength)) {
            bestPrev = cost[upIndex]
            bestLength = pathLength[upIndex]
        }
        if (isBetterPredecessor(cost[leftIndex], pathLength[leftIndex], bestPrev, bestLength)) {
            bestPrev = cost[leftIndex]
            bestLength = pathLength[leftIndex]
        }
        pathLength[i * size + j] = bestLength + 1
        return localCost + bestPrev
    }

    /**
     * `true` if the predecessor `(candidateCost, candidateLength)` should replace the current best
     * `(bestCost, bestLength)`: strictly cheaper, or equally cheap but with a longer accumulated
     * path (see the class KDoc "Tie-break" paragraph for why "longer" is the right and
     * order-independent choice). Primitives only, no boxing, so this stays allocation-free in the
     * per-cell hot loop (ADR 0008 performance budget).
     */
    private fun isBetterPredecessor(
        candidateCost: Float,
        candidateLength: Int,
        bestCost: Float,
        bestLength: Int,
    ): Boolean = candidateCost < bestCost || (candidateCost == bestCost && candidateLength > bestLength)
}
