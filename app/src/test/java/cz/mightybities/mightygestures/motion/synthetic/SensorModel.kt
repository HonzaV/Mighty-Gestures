package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceSample
import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind
import kotlin.math.round

/**
 * Typical consumer-grade MEMS IMU noise/bias orders of magnitude (ADR 0008 "Sensor model";
 * AC-M11 "noise and bias levels are within the declared sensor model"). *Assumed*, not measured on
 * a specific part: v1 has no real-device data at all (spec 0001 decision 5). Each constant's
 * one-line comment is its rationale, not a citation.
 */
object SensorNoiseModel {
    /** Well below [cz.mightybities.mightygestures.domain.trigger.motion.MotionConfig.accQuietThreshold]
     * (1.5 m/s²), so noise alone never reads as "active". */
    const val ACC_NOISE_STD_DEV = 0.03f

    /** A plausible factory-calibration residual on one axis. */
    const val ACC_BIAS_STD_DEV = 0.05f

    /** Small relative to
     * [cz.mightybities.mightygestures.domain.trigger.motion.MotionConfig.gyroQuietThreshold] (1.0 rad/s). */
    const val GYRO_NOISE_STD_DEV = 0.01f

    /** A plausible uncalibrated gyro bias/drift offset. */
    const val GYRO_BIAS_STD_DEV = 0.01f

    /** ADC-style resolution: both channels resolve far finer than the gesture-scale thresholds. */
    const val ACC_QUANTIZATION_STEP = 0.001f
    const val GYRO_QUANTIZATION_STEP = 0.001f
}

/**
 * Renders a [MotionSegmentSpec] into a timestamped ACC/GYRO sample stream the way a real device
 * would report it (ADR 0008 "Kinematics", AC-M11): `ACC = R(t)ᵀ·(a_world + g)`, `GYRO = ω` in the
 * device frame. [Quaternion.integrate] drives `R(t)` from the **same** true angular-velocity
 * profile the gyroscope samples (noisily) report, so the gravity direction visible in ACC always
 * rotates consistently with GYRO by construction — this is what the plausibility tests check, not
 * a coincidence of chosen parameters.
 *
 * Sensor imperfections are applied only to the *reported* samples, never to the physics driving
 * orientation: a real IMU's own noise does not change where gravity actually points next.
 */
@Suppress("LongParameterList") // one independently-defaulted sensor-model knob per parameter; test-only config holder.
class SensorModel(
    private val rateHz: Double = DEFAULT_RATE_HZ,
    private val timestampJitterStdDevNanos: Float = 0f,
    private val dropProbability: Float = 0f,
    private val accBias: Vector3 = Vector3.ZERO,
    private val gyroBias: Vector3 = Vector3.ZERO,
    private val accNoiseStdDev: Float = SensorNoiseModel.ACC_NOISE_STD_DEV,
    private val gyroNoiseStdDev: Float = SensorNoiseModel.GYRO_NOISE_STD_DEV,
) {
    /**
     * @param hasGyro `false` simulates an accelerometer-only device (AC-M10): no GYRO rows are
     *   emitted, but the physics still uses [spec]'s angular-velocity profile to rotate gravity.
     * @param finalOrientation receives the orientation at the end of the window, for callers that
     *   render several specs back-to-back and need to carry orientation across the boundary.
     */
    @Suppress("LongParameterList") // one independently-defaulted rendering option per parameter; test-only.
    fun generate(
        spec: MotionSegmentSpec,
        initialOrientation: Quaternion,
        hasGyro: Boolean,
        noise: NoiseSource,
        startTimestampNanos: Long = 0L,
        finalOrientation: ((Quaternion) -> Unit)? = null,
    ): List<MotionTraceSample> {
        val dtSeconds = (1.0 / rateHz).toFloat()
        val dtNanos = (NANOS_PER_SECOND / rateHz).toLong()
        var orientation = initialOrientation
        var sampleIndex = 0L
        val samples = mutableListOf<MotionTraceSample>()
        while (true) {
            // GitHub Copilot PR #6 round-3 finding: this used to accumulate the profile-evaluation
            // time via repeated Float addition ("t += dtSeconds"), which drifts from the integer
            // sample index over a long trace (a 600 s trace at 200 Hz produced 119,915 frames
            // instead of 120,000, and 50 Hz produced 2 extra) -- so a late movement could land on a
            // rate-dependent sample instead of its true nominal time. Deriving t from the sample
            // index (in Double, cast to Float only once here) ties it exactly to the same integer
            // arithmetic that already drives nominalTimestamp below, at every rate and trace length.
            val t = (sampleIndex * dtNanos / NANOS_PER_SECOND).toFloat()
            if (t >= spec.durationSeconds) break
            val nominalTimestamp = startTimestampNanos + sampleIndex * dtNanos
            val angularBody = spec.angularBody.at(t)
            val linWorld = spec.linWorld.at(t)
            val accTrue = orientation.rotateWorldToBody(linWorld + GRAVITY_WORLD)
            if (!noise.chance(dropProbability)) {
                val timestamp =
                    (nominalTimestamp + jitterNanos(noise)).coerceAtLeast(0L)
                if (hasGyro) {
                    val gyro = reportedGyro(angularBody, noise)
                    samples += MotionTraceSample(SensorKind.GYRO, timestamp, gyro.x, gyro.y, gyro.z)
                }
                val acc = reportedAcc(accTrue, noise)
                samples += MotionTraceSample(SensorKind.ACC, timestamp, acc.x, acc.y, acc.z)
            }
            orientation = orientation.integrate(angularBody, dtSeconds)
            sampleIndex++
        }
        finalOrientation?.invoke(orientation)
        return samples
    }

    private fun jitterNanos(noise: NoiseSource): Long = noise.gaussian(timestampJitterStdDevNanos).toLong()

    private fun reportedAcc(
        trueAcc: Vector3,
        noise: NoiseSource,
    ): Vector3 =
        quantize(trueAcc + accBias + gaussianVector(noise, accNoiseStdDev), SensorNoiseModel.ACC_QUANTIZATION_STEP)

    private fun reportedGyro(
        trueGyro: Vector3,
        noise: NoiseSource,
    ): Vector3 =
        quantize(trueGyro + gyroBias + gaussianVector(noise, gyroNoiseStdDev), SensorNoiseModel.GYRO_QUANTIZATION_STEP)

    private fun gaussianVector(
        noise: NoiseSource,
        stdDev: Float,
    ) = Vector3(noise.gaussian(stdDev), noise.gaussian(stdDev), noise.gaussian(stdDev))

    private fun quantize(
        v: Vector3,
        step: Float,
    ): Vector3 {
        fun q(x: Float) = round(x / step) * step
        return Vector3(q(v.x), q(v.y), q(v.z))
    }

    companion object {
        const val DEFAULT_RATE_HZ = 50.0
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** World-frame "g" added before rotating into the body frame (ADR 0008 kinematics formula):
         * reproduces the documented Android convention that a device at rest, face up, reads ACC
         * z = +9.81 (verified, https://developer.android.com/reference/android/hardware/SensorEvent). */
        val GRAVITY_WORLD = Vector3(0f, 0f, 9.81f)
    }
}
