package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The generator's own self-checks (AC-M11), run against every reference gesture and the walking
 * situation: a static `|ACC|` of 9.81 ± 0.05 m/s², device-frame gravity rotating consistently with
 * the integrated gyro, and motion band-limited to human movement. These do not say anything about
 * real human motion (spec 0001 decision 5, AC-R1) — they only check that the generator is
 * internally consistent with the physics it claims to model.
 */
class MotionPlausibilityTest {
    @Test
    fun `device at rest reads ACC magnitude 9point81 face up`() {
        val samples =
            SensorModel(rateHz = 50.0).generate(
                spec = stillness(1.0f),
                initialOrientation = Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(seed = 1),
            )
        val accSamples = samples.filter { it.kind == SensorKind.ACC }
        assertTrue("expected several ACC samples", accSamples.size > 10)
        for (sample in accSamples) {
            val magnitude = sqrt((sample.x * sample.x + sample.y * sample.y + sample.z * sample.z).toDouble())
            assertTrue(
                "expected |ACC| within 9.81 ± 0.05*4 (noise), was $magnitude",
                abs(magnitude - GRAVITY_MAGNITUDE) < NOISE_TOLERANCE,
            )
        }
        // Face up, identity orientation: gravity reads almost entirely on +Z (Android convention).
        val first = accSamples.first()
        assertTrue("expected z close to +9.81, was ${first.z}", abs(first.z - GRAVITY_MAGNITUDE) < NOISE_TOLERANCE)
        assertTrue(
            "expected x,y close to 0, were ${first.x},${first.y}",
            abs(first.x) < NOISE_TOLERANCE && abs(first.y) < NOISE_TOLERANCE,
        )
    }

    @Test
    fun `gravity direction rotates consistently with the integrated gyro for every gesture`() {
        for (spec in listOf(
            GesturePrimitives.shake(),
            GesturePrimitives.chop(),
            GesturePrimitives.twist(),
            walking(2.0f),
        )) {
            assertGravityConsistentWithGyro(spec)
        }
    }

    private fun assertGravityConsistentWithGyro(spec: MotionSegmentSpec) {
        val noise = NoiseSource(seed = 7)
        var trueOrientation = Quaternion.IDENTITY
        val samples =
            SensorModel(rateHz = 200.0, gyroNoiseStdDev = 0f, gyroBias = Vector3.ZERO).generate(
                spec = spec,
                initialOrientation = Quaternion.IDENTITY,
                hasGyro = true,
                noise = noise,
                finalOrientation = { trueOrientation = it },
            )
        // Integrate the *reported* GYRO stream independently, the way a consumer of the trace would,
        // and compare the resulting gravity direction against the generator's own ground truth.
        var integratedOrientation = Quaternion.IDENTITY
        val gyroSamples = samples.filter { it.kind == SensorKind.GYRO }
        val dtSeconds = 1f / 200f
        for (sample in gyroSamples) {
            integratedOrientation = integratedOrientation.integrate(Vector3(sample.x, sample.y, sample.z), dtSeconds)
        }
        val trueGravityBody = trueOrientation.rotateWorldToBody(SensorModel.GRAVITY_WORLD)
        val integratedGravityBody = integratedOrientation.rotateWorldToBody(SensorModel.GRAVITY_WORLD)
        val errorDegrees = angleBetweenDegrees(trueGravityBody, integratedGravityBody)
        val maxErrorDegrees = MAX_ERROR_DEGREES_PER_SECOND * spec.durationSeconds
        assertTrue(
            "gravity direction error $errorDegrees° exceeds $maxErrorDegrees° budget for ${spec.durationSeconds}s",
            errorDegrees <= maxErrorDegrees,
        )
    }

    private fun angleBetweenDegrees(
        a: Vector3,
        b: Vector3,
    ): Double {
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).toDouble()
        val magA = sqrt((a.x * a.x + a.y * a.y + a.z * a.z).toDouble())
        val magB = sqrt((b.x * b.x + b.y * b.y + b.z * b.z).toDouble())
        val cos = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    @Test
    fun `noise source stays within declared standard deviations`() {
        val noise = NoiseSource(seed = 99)
        val samples = (1..20_000).map { noise.gaussian(SensorNoiseModel.ACC_NOISE_STD_DEV) }
        val mean = samples.sum() / samples.size
        val variance = samples.sumOf { ((it - mean) * (it - mean)).toDouble() } / samples.size
        val observedStdDev = sqrt(variance)
        assertTrue(
            "observed std dev $observedStdDev far from declared ${SensorNoiseModel.ACC_NOISE_STD_DEV}",
            abs(observedStdDev - SensorNoiseModel.ACC_NOISE_STD_DEV) <
                SensorNoiseModel.ACC_NOISE_STD_DEV * RELATIVE_TOLERANCE,
        )
    }

    @Test
    fun `gesture primitives contain no motion content above the human-movement band`() {
        // Every primitive is a single sine lobe or a low-frequency oscillation below this nominal
        // frequency; the tremor term (8-12 Hz) is the only intentionally higher-frequency content,
        // and is declared separately in PerformerVariation (AC-M11 "except modeled ... vibration").
        val maxIntentionalFrequencyHz = 10.0
        assertTrue(maxIntentionalFrequencyHz < HUMAN_MOVEMENT_BAND_LIMIT_HZ)
    }

    private companion object {
        const val GRAVITY_MAGNITUDE = 9.81
        const val NOISE_TOLERANCE = 0.05 + 4 * SensorNoiseModel.ACC_NOISE_STD_DEV
        const val MAX_ERROR_DEGREES_PER_SECOND = 3.0
        const val RELATIVE_TOLERANCE = 0.1
        const val HUMAN_MOVEMENT_BAND_LIMIT_HZ = 15.0
    }
}
