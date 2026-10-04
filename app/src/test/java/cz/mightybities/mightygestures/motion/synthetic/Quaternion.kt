package cz.mightybities.mightygestures.motion.synthetic

import kotlin.math.sqrt

/**
 * A unit quaternion representing the device's orientation: rotates a **body-frame** vector into
 * the **world frame** (AC-M11: "device pose as a quaternion driven by an angular-velocity
 * profile"). [integrate] advances it by one gyro sample, so the orientation used to rotate gravity
 * into the body frame is always consistent with the same angular-velocity stream the generator
 * emits as `GYRO` samples — this is what the plausibility test checks.
 */
data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun normalized(): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    private fun multiply(other: Quaternion) =
        Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w,
        )

    /** Composes two rotations: `(a * b)` applies [other] first, then `a` (standard quaternion
     * composition order), used by [PerformerVariation.initialOrientation] to combine an X-tilt and
     * a Y-tilt into one static grip orientation. */
    operator fun times(other: Quaternion): Quaternion = multiply(other)

    /** Rotates a body-frame vector into the world frame. */
    fun rotateBodyToWorld(v: Vector3): Vector3 {
        val qv = Vector3(x, y, z)
        val t = cross(qv, v) * 2f
        return v + (t * w) + cross(qv, t)
    }

    /** Rotates a world-frame vector into the body frame (the inverse of [rotateBodyToWorld]). */
    fun rotateWorldToBody(v: Vector3): Vector3 = conjugate().rotateBodyToWorld(v)

    private fun conjugate() = Quaternion(w, -x, -y, -z)

    /** Advances orientation by one step of body-frame angular velocity [omegaBody] over [dtSeconds]. */
    fun integrate(
        omegaBody: Vector3,
        dtSeconds: Float,
    ): Quaternion {
        val omegaQuat = Quaternion(0f, omegaBody.x, omegaBody.y, omegaBody.z)
        val derivative = multiply(omegaQuat)
        return Quaternion(
            w = w + HALF * derivative.w * dtSeconds,
            x = x + HALF * derivative.x * dtSeconds,
            y = y + HALF * derivative.y * dtSeconds,
            z = z + HALF * derivative.z * dtSeconds,
        ).normalized()
    }

    companion object {
        private const val HALF = 0.5f
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /** A rotation of [degrees] about the body X axis. */
        fun aboutX(degrees: Float) = axisRotation(degrees, x = 1f, y = 0f, z = 0f)

        /** A rotation of [degrees] about the body Y axis. */
        fun aboutY(degrees: Float) = axisRotation(degrees, x = 0f, y = 1f, z = 0f)

        private fun axisRotation(
            degrees: Float,
            x: Float,
            y: Float,
            z: Float,
        ): Quaternion {
            val halfRadians = Math.toRadians(degrees.toDouble()).toFloat() / 2f
            val s = kotlin.math.sin(halfRadians)
            return Quaternion(kotlin.math.cos(halfRadians), x * s, y * s, z * s)
        }
    }
}

private fun cross(
    a: Vector3,
    b: Vector3,
) = Vector3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x,
)
