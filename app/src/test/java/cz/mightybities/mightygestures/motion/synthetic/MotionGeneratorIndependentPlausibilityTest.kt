package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceSample
import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AC-M11 plausibility checks for the synthetic generator, deliberately **independent** of its own
 * rotation/kinematics code (tester task item C12). [MotionPlausibilityTest] reads [SensorModel]'s
 * own `finalOrientation` ground truth and reuses [Quaternion.integrate] /
 * [Quaternion.rotateWorldToBody] to compute its expected values; a bug shared between
 * [SensorModel]'s kinematics and [Quaternion] could make both agree by construction. Every expected
 * value in this file is instead a literal physical constant or computed from plain trigonometry
 * written directly here (see `rotateWorldToBodyAboutX/Y/Z` below), never by calling [Quaternion] or
 * reading [SensorModel.GRAVITY_WORLD]/`finalOrientation`.
 */
class MotionGeneratorIndependentPlausibilityTest {
    // ------------------------------------------------------------------------------------------
    // 1. "Rotate N degrees and hold": the ACC quiet-window mean, read straight off the generated
    //    trace, must agree with gravity rotated by the angle independently integrated from the
    //    generated GYRO trace -- using this file's own rotation formulas, not the generator's.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `rotate 90 degrees about body X and hold -- ACC mean matches independently gyro-integrated gravity`() {
        val rotateSeconds = ROTATE_SECONDS
        val spec = concat(listOf(rotationAboutX(NINETY_DEGREES, rotateSeconds), stillness(HOLD_SECONDS)))
        val samples = render(spec, seed = 1L)
        val thetaRadians = integrateAxis(samples.gyro()) { it.x }
        val expected = rotateWorldToBodyAboutX(faceUpGravity(), thetaRadians)
        val measured = meanAccOverLastWindow(samples, windowSeconds = QUIET_WINDOW_SECONDS)

        assertMovedAwayFromStart(measured)
        assertWithinBudget(
            expected,
            measured,
            motionSeconds = rotateSeconds,
            thetaDegreesForLog = Math.toDegrees(thetaRadians),
        )
        // Android-convention sign check (ADR 0008 kinematics; face-up device, +90 deg about X):
        // gravity should now read mostly on +Y, not -Y -- an angle-only comparison could miss a
        // sign flip that happens to preserve angular distance in a more symmetric case.
        assertTrue("expected measured.y > 0 (gravity mostly on +Y), was $measured", measured.y > 0)
    }

    @Test
    fun `rotate 90 degrees about body Y and hold -- ACC mean matches independently gyro-integrated gravity`() {
        val rotateSeconds = ROTATE_SECONDS
        val spec = concat(listOf(rotationAboutY(NINETY_DEGREES, rotateSeconds), stillness(HOLD_SECONDS)))
        val samples = render(spec, seed = 2L)
        val thetaRadians = integrateAxis(samples.gyro()) { it.y }
        val expected = rotateWorldToBodyAboutY(faceUpGravity(), thetaRadians)
        val measured = meanAccOverLastWindow(samples, windowSeconds = QUIET_WINDOW_SECONDS)

        assertMovedAwayFromStart(measured)
        assertWithinBudget(
            expected,
            measured,
            motionSeconds = rotateSeconds,
            thetaDegreesForLog = Math.toDegrees(thetaRadians),
        )
        // Android-convention sign check: +90 deg about Y from face-up should land mostly on -X.
        assertTrue("expected measured.x < 0 (gravity mostly on -X), was $measured", measured.x < 0)
    }

    @Test
    fun `sequential 90 degree rotations about X then Z -- ACC mean matches independently gyro-integrated gravity`() {
        val phaseSeconds = ROTATE_SECONDS
        val spec =
            concat(
                listOf(
                    rotationAboutX(NINETY_DEGREES, phaseSeconds),
                    stillness(BETWEEN_PHASES_SECONDS),
                    rotationAboutZ(NINETY_DEGREES, phaseSeconds),
                    stillness(HOLD_SECONDS),
                ),
            )
        val samples = render(spec, seed = 3L)
        val gyro = samples.gyro()
        val thetaX = integrateAxis(gyro) { it.x }
        val thetaZ = integrateAxis(gyro) { it.z }
        // Each GYRO sample reports the angular rate in the *then-current* body frame, so the two
        // incremental rotations compose by applying each in turn to the running body-frame vector
        // (not by re-rotating the original world vector by some combined single rotation) -- this
        // is exactly what a sequential-rotation false positive (treating the two phases as
        // independent single-axis rotations of the *world* vector) would get wrong, and it is why
        // this case cannot be reduced to the single-axis test above.
        val afterX = rotateWorldToBodyAboutX(faceUpGravity(), thetaX)
        val expected = rotateWorldToBodyAboutZ(afterX, thetaZ)
        val measured = meanAccOverLastWindow(samples, windowSeconds = QUIET_WINDOW_SECONDS)

        assertMovedAwayFromStart(measured)
        assertWithinBudget(expected, measured, motionSeconds = phaseSeconds + phaseSeconds, thetaDegreesForLog = null)
        // Android-convention sign check: X-then-Z, each +90 deg, lands back on +X (see class KDoc
        // worked example) -- a world-frame composition bug would instead leave it near +Y.
        assertTrue("expected measured.x > 0 (gravity back on +X), was $measured", measured.x > 0)
    }

    // ------------------------------------------------------------------------------------------
    // 2. Noise-free 200 Hz DFT: the generator's documented model (ADR 0008 "Kinematics", AC-M11)
    //    is that intentional motion content stays below HUMAN_MOVEMENT_BAND_LIMIT_HZ. Tremor
    //    (8-12 Hz) is the declared exception, so these use PerformerVariation.NONE (tremor = 0,
    //    per its own KDoc), scaled to the fastest (hardest) end of the tempo range.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `shake ACC content (x axis) is band-limited below 15 Hz, noise-free at 200 Hz`() {
        val variation = PerformerVariation.NONE.copy(tempoScale = WORST_CASE_TEMPO_SCALE)
        val samples = renderNoiseFree(GesturePrimitives.shake(variation), seed = 10L)
        val fraction = dftBelowCutoffFraction(samples.acc().map { it.x }, DFT_RATE_HZ, BAND_CUTOFF_HZ)
        println("DFT shake ACC-x: $fraction of energy below $BAND_CUTOFF_HZ Hz")
        assertTrue(
            "only $fraction of shake's ACC-x energy is below $BAND_CUTOFF_HZ Hz",
            fraction >= MIN_BELOW_CUTOFF_FRACTION,
        )
    }

    @Test
    fun `chop ACC (y axis) and GYRO (z axis) content is band-limited below 15 Hz, noise-free at 200 Hz`() {
        val variation = PerformerVariation.NONE.copy(tempoScale = WORST_CASE_TEMPO_SCALE)
        val samples = renderNoiseFree(GesturePrimitives.chop(variation), seed = 11L)
        val accFraction = dftBelowCutoffFraction(samples.acc().map { it.y }, DFT_RATE_HZ, BAND_CUTOFF_HZ)
        val gyroFraction = dftBelowCutoffFraction(samples.gyro().map { it.z }, DFT_RATE_HZ, BAND_CUTOFF_HZ)
        println("DFT chop ACC-y: $accFraction, GYRO-z: $gyroFraction of energy below $BAND_CUTOFF_HZ Hz")
        assertTrue(
            "only $accFraction of chop's ACC-y energy is below $BAND_CUTOFF_HZ Hz",
            accFraction >= MIN_BELOW_CUTOFF_FRACTION,
        )
        assertTrue(
            "only $gyroFraction of chop's GYRO-z energy is below $BAND_CUTOFF_HZ Hz",
            gyroFraction >= MIN_BELOW_CUTOFF_FRACTION,
        )
    }

    @Test
    fun `twist GYRO content (x axis) is band-limited below 15 Hz, noise-free at 200 Hz`() {
        val variation = PerformerVariation.NONE.copy(tempoScale = WORST_CASE_TEMPO_SCALE)
        val samples = renderNoiseFree(GesturePrimitives.twist(variation), seed = 12L)
        val fraction = dftBelowCutoffFraction(samples.gyro().map { it.x }, DFT_RATE_HZ, BAND_CUTOFF_HZ)
        println("DFT twist GYRO-x: $fraction of energy below $BAND_CUTOFF_HZ Hz")
        assertTrue(
            "only $fraction of twist's GYRO-x energy is below $BAND_CUTOFF_HZ Hz",
            fraction >= MIN_BELOW_CUTOFF_FRACTION,
        )
    }

    @Test
    fun `negative control -- a pure 30 Hz tone is correctly reported as not band-limited below 15 Hz`() {
        // Proves dftBelowCutoffFraction's bin mapping is not trivially passing everything: an
        // exact integer number of cycles across the window (30 Hz * 0.5 s = 15 cycles at 200 Hz),
        // so there is no spectral leakage to explain away a failure here.
        val n = 100
        val signal = (0 until n).map { t -> sin(2.0 * PI * THIRTY_HZ * t / DFT_RATE_HZ).toFloat() }
        val fraction = dftBelowCutoffFraction(signal, DFT_RATE_HZ, BAND_CUTOFF_HZ)
        println("DFT 30 Hz tone: $fraction of energy below $BAND_CUTOFF_HZ Hz (expected near 0)")
        assertTrue(
            "expected a 30 Hz tone to NOT be reported as below $BAND_CUTOFF_HZ Hz, was $fraction",
            fraction < MIN_BELOW_CUTOFF_FRACTION,
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. Stillness: mean |ACC| = g +/- 0.05 m/s^2 (tighter than MotionPlausibilityTest's existing
    //    per-sample +/- noise-inflated check), across several orientations and rates.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `mean ACC magnitude at rest is 9point81 within 0point05 ms2, across orientations and rates`() {
        val orientations =
            listOf(
                "face up" to Quaternion.IDENTITY,
                "face down" to Quaternion.aboutX(ONE_EIGHTY_DEGREES),
                "tilted 90 about X" to Quaternion.aboutX(NINETY_DEGREES),
                "tilted 90 about Y" to Quaternion.aboutY(NINETY_DEGREES),
                "oblique" to Quaternion.aboutY(OBLIQUE_Y_DEGREES) * Quaternion.aboutX(OBLIQUE_X_DEGREES),
            )
        for ((label, orientation) in orientations) {
            for (rateHz in listOf(50.0, 100.0, 200.0)) {
                val samples =
                    SensorModel(rateHz = rateHz).generate(
                        stillness(STILLNESS_SECONDS),
                        orientation,
                        hasGyro = true,
                        noise = NoiseSource(seed = 20L),
                    )
                val meanMagnitude =
                    samples
                        .acc()
                        .map {
                            magnitude(
                                it.x.toDouble(),
                                it.y.toDouble(),
                                it.z.toDouble(),
                            )
                        }.average()
                println("stillness mean |ACC| [$label @ ${rateHz}Hz] = $meanMagnitude")
                val tolerance = "+/- $MEAN_ACC_TOLERANCE"
                assertTrue(
                    "mean |ACC| $meanMagnitude far from $GRAVITY_MAGNITUDE $tolerance at $label, ${rateHz}Hz",
                    abs(meanMagnitude - GRAVITY_MAGNITUDE) <= MEAN_ACC_TOLERANCE,
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------------------------------

    private fun render(
        spec: MotionSegmentSpec,
        seed: Long,
    ): List<MotionTraceSample> =
        SensorModel(
            rateHz = ROTATE_SAMPLE_RATE_HZ,
        ).generate(spec, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(seed))

    private fun renderNoiseFree(
        spec: MotionSegmentSpec,
        seed: Long,
    ): List<MotionTraceSample> =
        SensorModel(rateHz = DFT_RATE_HZ, accNoiseStdDev = 0f, gyroNoiseStdDev = 0f)
            .generate(spec, Quaternion.IDENTITY, hasGyro = true, noise = NoiseSource(seed))

    private fun List<MotionTraceSample>.acc() = filter { it.kind == SensorKind.ACC }

    private fun List<MotionTraceSample>.gyro() = filter { it.kind == SensorKind.GYRO }

    private fun meanAccOverLastWindow(
        samples: List<MotionTraceSample>,
        windowSeconds: Float,
    ): RawVec {
        val acc = samples.acc()
        val lastTimestamp = acc.last().timestampNanos
        val windowStart = lastTimestamp - (windowSeconds.toDouble() * NANOS_PER_SECOND).toLong()
        val windowed = acc.filter { it.timestampNanos >= windowStart }
        check(
            windowed.size > MIN_WINDOW_SAMPLES,
        ) { "expected several ACC samples in the quiet window, got ${windowed.size}" }
        val x = windowed.sumOf { it.x.toDouble() } / windowed.size
        val y = windowed.sumOf { it.y.toDouble() } / windowed.size
        val z = windowed.sumOf { it.z.toDouble() } / windowed.size
        return RawVec(x, y, z)
    }

    /** Trapezoidal integral of one GYRO axis over the whole trace, using reported timestamps (so
     * jitter or a different rate changes nothing) -- this file's own integration, not [Quaternion.integrate]. */
    private fun integrateAxis(
        gyroSamples: List<MotionTraceSample>,
        axis: (MotionTraceSample) -> Float,
    ): Double {
        var theta = 0.0
        for (i in 0 until gyroSamples.size - 1) {
            val a = gyroSamples[i]
            val b = gyroSamples[i + 1]
            val dtSeconds = (b.timestampNanos - a.timestampNanos) / NANOS_PER_SECOND
            theta += HALF * (axis(a) + axis(b)) * dtSeconds
        }
        return theta
    }

    private fun faceUpGravity() = RawVec(0.0, 0.0, GRAVITY_MAGNITUDE)

    private fun assertMovedAwayFromStart(measured: RawVec) {
        val movedDegrees = angleBetweenDegrees(faceUpGravity(), measured)
        assertTrue(
            "vacuity guard: expected gravity to move at least $VACUITY_MIN_DEGREES deg from face-up, " +
                "moved $movedDegrees deg",
            movedDegrees > VACUITY_MIN_DEGREES,
        )
    }

    private fun assertWithinBudget(
        expected: RawVec,
        measured: RawVec,
        motionSeconds: Float,
        thetaDegreesForLog: Double?,
    ) {
        val errorDegrees = angleBetweenDegrees(expected, measured)
        val budgetDegrees = MAX_ERROR_DEGREES_PER_SECOND_OF_MOTION * motionSeconds
        println(
            "gravity direction error $errorDegrees deg (budget $budgetDegrees deg for ${motionSeconds}s of motion); " +
                "expected=$expected measured=$measured" + (thetaDegreesForLog?.let { ", thetaDeg=$it" } ?: ""),
        )
        assertTrue(
            "gravity direction error $errorDegrees deg exceeds budget $budgetDegrees deg " +
                "(expected=$expected measured=$measured)",
            errorDegrees <= budgetDegrees,
        )
    }

    // ------------------------------------------------------------------------------------------
    // Independent rotation math (plain trigonometry, no Quaternion): world-to-body for a device
    // that has rotated by thetaRadians about its *own* body X/Y/Z axis (right-hand rule),
    // i.e. the vector is rotated by -thetaRadians using the standard active-rotation matrices.
    // Derivation and the resulting hard-coded sanity values are in the task notes; summarized:
    //   +90 deg about X: (0,0,g) -> (0,+g,0)     +90 deg about Y: (0,0,g) -> (-g,0,0)
    //   +90 deg about Z: (0,g,0) -> (+g,0,0)
    // ------------------------------------------------------------------------------------------

    private data class RawVec(
        val x: Double,
        val y: Double,
        val z: Double,
    )

    private fun rotateWorldToBodyAboutX(
        v: RawVec,
        thetaRadians: Double,
    ): RawVec {
        val c = cos(thetaRadians)
        val s = sin(thetaRadians)
        return RawVec(v.x, v.y * c + v.z * s, -v.y * s + v.z * c)
    }

    private fun rotateWorldToBodyAboutY(
        v: RawVec,
        thetaRadians: Double,
    ): RawVec {
        val c = cos(thetaRadians)
        val s = sin(thetaRadians)
        return RawVec(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    private fun rotateWorldToBodyAboutZ(
        v: RawVec,
        thetaRadians: Double,
    ): RawVec {
        val c = cos(thetaRadians)
        val s = sin(thetaRadians)
        return RawVec(v.x * c + v.y * s, -v.x * s + v.y * c, v.z)
    }

    private fun magnitude(
        x: Double,
        y: Double,
        z: Double,
    ) = sqrt(x * x + y * y + z * z)

    private fun angleBetweenDegrees(
        a: RawVec,
        b: RawVec,
    ): Double {
        val dot = a.x * b.x + a.y * b.y + a.z * b.z
        val cosTheta = (dot / (magnitude(a.x, a.y, a.z) * magnitude(b.x, b.y, b.z))).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    // ------------------------------------------------------------------------------------------
    // Rotation-profile builders: a half-sine angular-rate pulse about a single axis whose time
    // integral is exactly totalAngleDegrees (out, no "back"; unlike GesturePrimitives' biphasic
    // gestures, a held rotation must leave a *permanent* reorientation).
    // ------------------------------------------------------------------------------------------

    private fun rotationAboutX(
        totalAngleDegrees: Float,
        durationSeconds: Float,
    ): MotionSegmentSpec {
        val peak = halfSinePeakForTotalAngle(totalAngleDegrees, durationSeconds)
        return MotionSegmentSpec(
            durationSeconds,
            VectorProfile { Vector3.ZERO },
            VectorProfile { t -> Vector3(halfSineOmega(peak, t, durationSeconds), 0f, 0f) },
        )
    }

    private fun rotationAboutY(
        totalAngleDegrees: Float,
        durationSeconds: Float,
    ): MotionSegmentSpec {
        val peak = halfSinePeakForTotalAngle(totalAngleDegrees, durationSeconds)
        return MotionSegmentSpec(
            durationSeconds,
            VectorProfile { Vector3.ZERO },
            VectorProfile { t -> Vector3(0f, halfSineOmega(peak, t, durationSeconds), 0f) },
        )
    }

    private fun rotationAboutZ(
        totalAngleDegrees: Float,
        durationSeconds: Float,
    ): MotionSegmentSpec {
        val peak = halfSinePeakForTotalAngle(totalAngleDegrees, durationSeconds)
        return MotionSegmentSpec(
            durationSeconds,
            VectorProfile { Vector3.ZERO },
            VectorProfile { t -> Vector3(0f, 0f, halfSineOmega(peak, t, durationSeconds)) },
        )
    }

    private fun halfSinePeakForTotalAngle(
        totalAngleDegrees: Float,
        durationSeconds: Float,
    ): Float {
        val totalAngleRadians = Math.toRadians(totalAngleDegrees.toDouble()).toFloat()
        return totalAngleRadians * PI.toFloat() / (2f * durationSeconds)
    }

    private fun halfSineOmega(
        peak: Float,
        t: Float,
        durationSeconds: Float,
    ): Float {
        if (t < 0f || t > durationSeconds) return 0f
        return peak * sin(PI.toFloat() * t / durationSeconds)
    }

    // ------------------------------------------------------------------------------------------
    // DFT (direct O(n * n/2), no FFT library): a one-sided power spectrum, used only as a ratio
    // (energy below cutoff / total energy), so no normalization/windowing convention matters.
    // ------------------------------------------------------------------------------------------

    private fun dftBelowCutoffFraction(
        signal: List<Float>,
        sampleRateHz: Double,
        cutoffHz: Double,
    ): Double {
        val n = signal.size
        require(n >= MIN_DFT_SAMPLES) { "need several samples for a meaningful DFT, had $n" }
        val halfN = n / 2
        var total = 0.0
        var below = 0.0
        for (k in 0..halfN) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * PI * k * t / n
                re += signal[t] * cos(angle)
                im += signal[t] * sin(angle)
            }
            val power = re * re + im * im
            total += power
            if (k * sampleRateHz / n < cutoffHz) below += power
        }
        check(total > DFT_ENERGY_FLOOR) {
            "signal has no meaningful energy (total=$total); this channel is not a useful band-limit probe"
        }
        return below / total
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val HALF = 0.5

        // Rotate-and-hold
        const val ROTATE_SAMPLE_RATE_HZ = 200.0
        const val ROTATE_SECONDS = 0.5f
        const val HOLD_SECONDS = 1.0f
        const val BETWEEN_PHASES_SECONDS = 0.3f
        const val QUIET_WINDOW_SECONDS = 0.5f
        const val NINETY_DEGREES = 90f
        const val ONE_EIGHTY_DEGREES = 180f
        const val GRAVITY_MAGNITUDE = 9.81
        const val VACUITY_MIN_DEGREES = 80.0

        /** AC-M11: "device-frame gravity direction rotates consistently with the integrated gyro
         * (<= 3 deg error per second of motion)". Only the actively-rotating phases count as
         * "motion"; the held/quiet tail does not add to the budget. */
        const val MAX_ERROR_DEGREES_PER_SECOND_OF_MOTION = 3.0
        const val MIN_WINDOW_SAMPLES = 5

        // DFT band-limit
        const val DFT_RATE_HZ = 200.0

        /** AC-M11 / ADR 0008 "Kinematics": "motion components are band-limited to human movement
         * (< 15 Hz, except modeled impacts and vibration)" -- the generator's declared model. */
        const val BAND_CUTOFF_HZ = 15.0
        const val MIN_BELOW_CUTOFF_FRACTION = 0.95

        /** Fastest (hardest) end of the spec's +/-30% performer tempo range (ADR 0008 "Performer
         * variability"): the worst case for staying under the band limit. */
        const val WORST_CASE_TEMPO_SCALE = 1.3f
        const val THIRTY_HZ = 30.0
        const val MIN_DFT_SAMPLES = 8
        const val DFT_ENERGY_FLOOR = 1e-6

        // Stillness mean
        const val STILLNESS_SECONDS = 2.0f
        const val MEAN_ACC_TOLERANCE = 0.05
        const val OBLIQUE_X_DEGREES = 23f
        const val OBLIQUE_Y_DEGREES = 37f
    }
}
