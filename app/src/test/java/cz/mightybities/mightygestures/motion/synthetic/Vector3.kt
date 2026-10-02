package cz.mightybities.mightygestures.motion.synthetic

/**
 * A plain 3-D vector used by the synthetic generator (test source set only, AC-P6). Not shared
 * with `domain`, which uses flat primitive arrays on its hot path (AC-M9); generator code runs
 * once per fixture/test, so a small immutable value type is fine here.
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)

    operator fun times(scale: Float) = Vector3(x * scale, y * scale, z * scale)

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
    }
}

/** A time-domain profile, evaluated at a point in time relative to its own gesture window. */
fun interface VectorProfile {
    fun at(tSeconds: Float): Vector3
}
