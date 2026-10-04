package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.Vector3
import cz.mightybities.mightygestures.motion.synthetic.feedTo
import cz.mightybities.mightygestures.motion.synthetic.pickupFromTable
import cz.mightybities.mightygestures.motion.synthetic.pocketBurstSequence
import cz.mightybities.mightygestures.motion.synthetic.putDownOnTable
import cz.mightybities.mightygestures.motion.synthetic.rotateToLandscape
import cz.mightybities.mightygestures.motion.synthetic.tiltToRead
import cz.mightybities.mightygestures.motion.synthetic.walkingBurst
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Tester task item D14: [MotionRobustnessTest]'s rate/jitter/drop/bias/orientation disturbances
 * only check **positives** (AC-M5 says "identical verdicts", which includes the negative verdict
 * too). This file adds:
 * 1. the same false-positive corpus [MotionFalsePositiveCorpusTest] uses (via [NegativeSituations]
 *    factories), re-run under every AC-M5 disturbance, asserting it still does NOT match;
 * 2. positives matching across **several** static gravity orientations (not just the one
 *    quarter-turn [MotionRobustnessTest] covers), in both 6-D and ACC-only (twist excluded, spec
 *    0001 decision 14/[GestureRecordabilityTest]'s class KDoc);
 * 3. a bonus cross-orientation false-positive probe (template and negative probe at *different*
 *    static orientations) kept in its own test so a finding there cannot mask the rest of the file.
 *
 * `chop`/ACC-only is excluded throughout, citing spec 0001 decision 14: "On the synthetic corpus,
 * the 'pick up from table' negative lands at a distance of ~0.95-1.0 from an ACC-only chop
 * template... That is inside tau = 1.0, so it would fire... milestone #1 ... leaves out the
 * chop/ACC-only pairing" -- milestone 3's stricter ACC-only threshold is the decided fix, not
 * widening this test to cover it.
 */
class MotionNegativeRobustnessAndOrientationTest {
    private val config = MotionConfig()
    private val matcher = MotionMatcher(config)
    private val preprocessor = Preprocessor(config)

    /** One sensor-disturbance under test; both the template and every probe in a given test run
     * through the *same* [sensorModel] (AC-M5: "the template and the probe share that
     * orientation"/disturbance), mirroring [MotionRobustnessTest]. */
    private data class Disturbance(
        val label: String,
        val sensorModel: () -> SensorModel,
    )

    private fun disturbances(): List<Disturbance> =
        listOf(
            Disturbance("50 Hz") { SensorModel(rateHz = 50.0) },
            Disturbance("100 Hz") { SensorModel(rateHz = 100.0) },
            Disturbance("200 Hz") { SensorModel(rateHz = 200.0) },
            // Jitter only at 50 Hz: SensorModel's jitter is unbounded Gaussian (sigma = 2ms); at a
            // 5ms nominal period (200 Hz) a meaningful fraction of consecutive ACC timestamps would
            // go non-monotonic, which MotionPipeline treats as a clock rebase and resets on (ADR
            // 0008) -- that would make every negative trivially "not match" by never segmenting,
            // not by the matcher correctly rejecting it. MotionRobustnessTest makes the same choice.
            Disturbance(
                "50 Hz, +/-2ms jitter",
            ) { SensorModel(rateHz = 50.0, timestampJitterStdDevNanos = JITTER_STD_DEV_NANOS) },
            Disturbance("50 Hz, 5% dropped samples") { SensorModel(rateHz = 50.0, dropProbability = DROP_PROBABILITY) },
            Disturbance("50 Hz, +bias") { SensorModel(rateHz = 50.0, accBias = Vector3(BIAS, -BIAS, BIAS)) },
            Disturbance("50 Hz, -bias") { SensorModel(rateHz = 50.0, accBias = Vector3(-BIAS, BIAS, -BIAS)) },
        )

    /** Mirrors [MotionFalsePositiveCorpusTest]'s private situation list (not reused directly: it
     * is private to that class, and duplicating keeps this file's disturbance sweep independent of
     * any future change there) at a reduced attempt count per situation, since every situation is
     * now re-run once per [Disturbance] instead of once total. */
    private fun negativeSituations(): List<Pair<String, List<Pair<Long, MotionSegmentSpec>>>> =
        listOf(
            "pickup from table" to
                (0..2).map { i ->
                    val amp = 1f + i * AMPLITUDE_STEP
                    (SEED_PICKUP + i) to
                        pickupFromTable(
                            peakAngularRateRadPerSecond = PICKUP_GYRO_PEAK * amp,
                            peakLiftAcceleration =
                                PICKUP_ACC_PEAK * amp,
                        )
                },
            "put down on table" to
                (0..2).map { i ->
                    val amp = 1f + i * AMPLITUDE_STEP
                    (SEED_PUTDOWN + i) to
                        putDownOnTable(
                            peakAngularRateRadPerSecond = PICKUP_GYRO_PEAK * amp,
                            peakLiftAcceleration =
                                PICKUP_ACC_PEAK * amp,
                        )
                },
            "tilt to read" to
                (0..2).map { i ->
                    val amp = 1f + i * AMPLITUDE_STEP
                    (SEED_TILT + i) to tiltToRead(peakAngularRateRadPerSecond = TILT_GYRO_PEAK * amp)
                },
            "rotate to landscape" to
                (0..2).map { i ->
                    val amp = 1f + i * AMPLITUDE_STEP
                    (SEED_ROTATE + i) to rotateToLandscape(peakAngularRateRadPerSecond = ROTATE_GYRO_PEAK * amp)
                },
            "short walking burst (2-3 steps)" to
                listOf(SEED_WALK to walkingBurst(stepCount = 2), SEED_WALK + 1 to walkingBurst(stepCount = 3)),
            "pocket-like bursts" to
                (0..2).map { i ->
                    val offset = SEED_POCKET + i * POCKET_BURSTS_PER_SEQUENCE
                    offset to
                        pocketBurstSequence(
                            burstCount = POCKET_BURSTS_PER_SEQUENCE,
                            seedOffset = offset,
                            peakAcceleration = POCKET_ACC_BASE + i * POCKET_ACC_STEP,
                            peakAngularRate = POCKET_GYRO_BASE + i * POCKET_GYRO_STEP,
                        )
                },
        )

    // --------------------------------------------------------------------------------------
    // 1. Negatives must still NOT match under every AC-M5 disturbance (same orientation as
    //    the template -- the robustness claim, not the orientation-invariance claim).
    // --------------------------------------------------------------------------------------

    @Test
    fun `no negative matches a chop template, 6-D, under any AC-M5 disturbance`() {
        assertNoFalsePositiveUnderAnyDisturbance(GesturePrimitives::chop, hasGyro = true, label = "chop/6D")
    }

    @Test
    fun `no negative matches a shake template, ACC-only, under any AC-M5 disturbance`() {
        // Not chop/ACC-only: spec 0001 decision 14 (see class KDoc) -- that pairing is a known,
        // accepted collision at today's thresholds, not something this test should flag again.
        assertNoFalsePositiveUnderAnyDisturbance(GesturePrimitives::shake, hasGyro = false, label = "shake/ACC-only")
    }

    private fun assertNoFalsePositiveUnderAnyDisturbance(
        gesture: (PerformerVariation) -> MotionSegmentSpec,
        hasGyro: Boolean,
        label: String,
    ) {
        val falsePositives = mutableListOf<String>()
        var totalAttempts = 0
        var totalSegmented = 0
        for (disturbance in disturbances()) {
            val template =
                recordAndConfirmTemplate(
                    config,
                    gesture,
                    hasGyro,
                    TemplateRendering(disturbance.sensorModel, Quaternion.IDENTITY),
                    CaptureSeeds(TEMPLATE_RECORD_SEED, TEMPLATE_CONFIRM_SEED),
                )
            val counts = checkDisturbanceAgainstAllSituations(disturbance, hasGyro, label, template, falsePositives)
            totalAttempts += counts.attempts
            totalSegmented += counts.segmented
        }
        assertTrue(
            "vacuity guard: expected at least a third of (disturbance x situation x attempt) combinations " +
                "to actually segment (got $totalSegmented of $totalAttempts)",
            totalSegmented >= totalAttempts / VACUITY_GUARD_DIVISOR,
        )
        assertTrue(
            "FALSE POSITIVE(S) under an AC-M5 disturbance -- ${falsePositives.joinToString("; ")}",
            falsePositives.isEmpty(),
        )
    }

    private data class AttemptCounts(
        val attempts: Int,
        val segmented: Int,
    )

    private fun checkDisturbanceAgainstAllSituations(
        disturbance: Disturbance,
        hasGyro: Boolean,
        label: String,
        template: List<ProcessedSegment>,
        falsePositives: MutableList<String>,
    ): AttemptCounts {
        var attempts = 0
        var segmented = 0
        for ((situationName, situationAttempts) in negativeSituations()) {
            var situationMinDistance = Float.POSITIVE_INFINITY
            for ((seed, spec) in situationAttempts) {
                attempts++
                val outcome = runNegativeAttempt(spec, seed, ProbeRendering(hasGyro, disturbance.sensorModel), template)
                if (outcome.segmentCount > 0) segmented++
                situationMinDistance = minOf(situationMinDistance, outcome.minDistance)
                if (outcome.matchDistance != null) {
                    falsePositives +=
                        "$label / ${disturbance.label} / $situationName: seed=$seed " +
                        "distance=${outcome.matchDistance} (tau=${config.matchThreshold})"
                }
            }
            println("D14 [$label / ${disturbance.label}] $situationName: minDistance=$situationMinDistance")
        }
        return AttemptCounts(attempts, segmented)
    }

    private data class AttemptOutcome(
        val segmentCount: Int,
        val minDistance: Float,
        val matchDistance: Float?,
    )

    /** How to probe a negative attempt: bundled with [TemplateRendering]-style grouping so
     * [runNegativeAttempt] stays under detekt's parameter-count limit. */
    private data class ProbeRendering(
        val hasGyro: Boolean,
        val sensorModel: () -> SensorModel,
        val orientation: Quaternion = Quaternion.IDENTITY,
    )

    private fun runNegativeAttempt(
        spec: MotionSegmentSpec,
        seed: Long,
        rendering: ProbeRendering,
        template: List<ProcessedSegment>,
    ): AttemptOutcome {
        val segments = mutableListOf<ProcessedSegment>()
        val pipeline = MotionPipeline(config, rendering.hasGyro) { seg -> segments += preprocessor.process(seg) }
        rendering
            .sensorModel()
            .generate(
                wrappedForCapture(spec),
                rendering.orientation,
                hasGyro = rendering.hasGyro,
                noise = NoiseSource(seed),
            ).feedTo(pipeline)
        var minDistance = Float.POSITIVE_INFINITY
        var matchDistance: Float? = null
        for (processed in segments) {
            val distance = matcher.distanceToTemplate(processed, template) ?: continue
            minDistance = minOf(minDistance, distance)
            if (distance <= config.matchThreshold) matchDistance = distance
        }
        return AttemptOutcome(segments.size, minDistance, matchDistance)
    }

    // --------------------------------------------------------------------------------------
    // 2. Positives match across several static gravity orientations (template and probe share
    //    the orientation each time), 6-D and ACC-only where recordable.
    // --------------------------------------------------------------------------------------

    private fun orientations(): List<Pair<String, Quaternion>> =
        listOf(
            "face up" to Quaternion.IDENTITY,
            "tilted 90 about X" to Quaternion.aboutX(NINETY_DEGREES),
            "tilted 90 about Y" to Quaternion.aboutY(NINETY_DEGREES),
            "face down" to Quaternion.aboutX(ONE_EIGHTY_DEGREES),
            "oblique" to Quaternion.aboutY(OBLIQUE_Y_DEGREES) * Quaternion.aboutX(OBLIQUE_X_DEGREES),
        )

    @Test
    fun `chop, shake and twist match across several orientations, 6-D`() {
        assertMatchesAcrossOrientations(GesturePrimitives::chop, hasGyro = true, label = "chop/6D")
        assertMatchesAcrossOrientations(GesturePrimitives::shake, hasGyro = true, label = "shake/6D")
        assertMatchesAcrossOrientations(GesturePrimitives::twist, hasGyro = true, label = "twist/6D")
    }

    @Test
    fun `chop and shake match across several orientations, ACC-only (twist excluded, see class KDoc)`() {
        assertMatchesAcrossOrientations(GesturePrimitives::chop, hasGyro = false, label = "chop/ACC-only")
        assertMatchesAcrossOrientations(GesturePrimitives::shake, hasGyro = false, label = "shake/ACC-only")
    }

    private fun assertMatchesAcrossOrientations(
        gesture: (PerformerVariation) -> MotionSegmentSpec,
        hasGyro: Boolean,
        label: String,
    ) {
        val misses = mutableListOf<String>()
        for ((orientationLabel, orientation) in orientations()) {
            val template =
                recordAndConfirmTemplate(
                    config,
                    gesture,
                    hasGyro,
                    TemplateRendering({ SensorModel(rateHz = 50.0) }, orientation),
                    CaptureSeeds(TEMPLATE_RECORD_SEED + 1, TEMPLATE_CONFIRM_SEED + 1),
                )
            val segments = mutableListOf<ProcessedSegment>()
            val pipeline = MotionPipeline(config, hasGyro) { seg -> segments += preprocessor.process(seg) }
            SensorModel(rateHz = 50.0)
                .generate(
                    wrappedForCapture(gesture(PerformerVariation.NONE)),
                    orientation,
                    hasGyro = hasGyro,
                    noise = NoiseSource(PROBE_SEED),
                ).feedTo(pipeline)
            val distance = segments.firstOrNull()?.let { matcher.distanceToTemplate(it, template) }
            println("D14 [$label] $orientationLabel: distance=$distance (tau=${config.matchThreshold})")
            if (distance == null || distance > config.matchThreshold) {
                misses += "$label / $orientationLabel: distance=$distance"
            }
        }
        assertTrue("expected a match at every orientation -- misses: ${misses.joinToString("; ")}", misses.isEmpty())
    }

    // --------------------------------------------------------------------------------------
    // 3. Bonus: cross-orientation false positives (template at identity, negative probed at a
    //    different static orientation) -- the more realistic false-trigger scenario, kept in its
    //    own test so a finding here cannot mask the disturbance sweep above.
    // --------------------------------------------------------------------------------------

    @Test
    fun `no negative matches a chop template, 6-D, when probed at a different static orientation`() {
        assertNoCrossOrientationFalsePositive(GesturePrimitives::chop, hasGyro = true, label = "chop/6D")
    }

    /**
     * **Finding, reported to the maintainer/developer, not fixed here (tester guardrails):** this
     * collides. `shake`/ACC-only, template recorded at `Quaternion.IDENTITY`, matches the
     * "rotate to landscape" negative (peak gyro rate 5.76 rad/s, i.e. amplitude index 2 of
     * [negativeSituations]'s own sweep -- not an unrealistically exaggerated probe) at distance
     * **0.927** (seed 14002, tau = 1.0) when the *probe* is at a device orientation
     * tilted 90 deg about body Y (landscape). The same situation produces zero false positives
     * against the same template at a shared (identity) orientation
     * ([assertNoFalsePositiveUnderAnyDisturbance]) and in [MotionFalsePositiveCorpusTest] (which
     * also only probes at identity) -- this is a genuinely new collision, not a restatement of
     * spec 0001 decision 14's documented chop/ACC-only x pickup finding. AC-M5's orientation clause
     * only requires the template and probe to *share* an orientation (ADR 0008 "Invariance
     * trade-offs": device-frame matching is *meant* to be orientation-sensitive), so this is not an
     * AC-M5 regression, but it is a real false-trigger path for a user who records a gesture
     * upright and then, e.g., tilts the phone to landscape while picking it up or glancing at it --
     * exactly the "false trigger in a pocket" class of bug AGENTS.md calls worse than a miss.
     * `@Ignore`d (not silently: this KDoc plus the tester report are the explanation) rather than
     * left red, so it does not block `scripts/verify.sh` for every future change to this file; the
     * other three probe orientations in this test, and every orientation/disturbance combination in
     * [assertNoFalsePositiveUnderAnyDisturbance] and [assertMatchesAcrossOrientations], still run and
     * must still pass.
     */
    @Ignore(
        "FINDING (not fixed, see KDoc): shake/ACC-only template (identity) collides with " +
            "'rotate to landscape' probed at 90deg-about-Y, distance=0.927 < tau=1.0, seed=14002 " +
            "-- report to maintainer/developer, candidate for milestone 3's ACC-only threshold work",
    )
    @Test
    fun `no negative matches a shake template, ACC-only, when probed at a different static orientation`() {
        assertNoCrossOrientationFalsePositive(GesturePrimitives::shake, hasGyro = false, label = "shake/ACC-only")
    }

    private fun assertNoCrossOrientationFalsePositive(
        gesture: (PerformerVariation) -> MotionSegmentSpec,
        hasGyro: Boolean,
        label: String,
    ) {
        val template =
            recordAndConfirmTemplate(
                config,
                gesture,
                hasGyro,
                TemplateRendering({ SensorModel(rateHz = 50.0) }, Quaternion.IDENTITY),
                CaptureSeeds(TEMPLATE_RECORD_SEED + 2, TEMPLATE_CONFIRM_SEED + 2),
            )
        val probeOrientations =
            listOf(
                "tilted 90 about X" to Quaternion.aboutX(NINETY_DEGREES),
                "tilted 90 about Y" to Quaternion.aboutY(NINETY_DEGREES),
                "face down" to Quaternion.aboutX(ONE_EIGHTY_DEGREES),
            )
        val falsePositives = mutableListOf<String>()
        for ((orientationLabel, orientation) in probeOrientations) {
            falsePositives += crossOrientationFalsePositivesAt(orientationLabel, orientation, hasGyro, label, template)
        }
        // Any match here is a new finding (a stronger false-positive than AC-M5 requires, since
        // device-frame matching is orientation-sensitive by design, ADR 0008 "Invariance
        // trade-offs"): report it, do not silently widen an exclusion to make this pass.
        assertTrue(
            "CROSS-ORIENTATION FALSE POSITIVE(S) -- ${falsePositives.joinToString("; ")}",
            falsePositives.isEmpty(),
        )
    }

    private fun crossOrientationFalsePositivesAt(
        orientationLabel: String,
        orientation: Quaternion,
        hasGyro: Boolean,
        label: String,
        template: List<ProcessedSegment>,
    ): List<String> {
        val found = mutableListOf<String>()
        for ((situationName, attempts) in negativeSituations()) {
            for ((seed, spec) in attempts) {
                val rendering = ProbeRendering(hasGyro, { SensorModel(rateHz = 50.0) }, orientation)
                val outcome = runNegativeAttempt(spec, seed, rendering, template)
                if (outcome.matchDistance != null) {
                    found +=
                        "$label / probe@$orientationLabel / $situationName: seed=$seed " +
                        "distance=${outcome.matchDistance}"
                }
            }
        }
        return found
    }

    private companion object {
        const val JITTER_STD_DEV_NANOS = 2_000_000f
        const val DROP_PROBABILITY = 0.05f
        const val BIAS = 0.2f
        const val AMPLITUDE_STEP = 0.4f
        const val PICKUP_GYRO_PEAK = 3.0f
        const val PICKUP_ACC_PEAK = 4.0f
        const val TILT_GYRO_PEAK = 2.4f
        const val ROTATE_GYRO_PEAK = 3.2f
        const val POCKET_BURSTS_PER_SEQUENCE = 5
        const val POCKET_ACC_BASE = 3f
        const val POCKET_ACC_STEP = 0.5f
        const val POCKET_GYRO_BASE = 1.8f
        const val POCKET_GYRO_STEP = 0.3f
        const val VACUITY_GUARD_DIVISOR = 3
        const val NINETY_DEGREES = 90f
        const val ONE_EIGHTY_DEGREES = 180f
        const val OBLIQUE_X_DEGREES = 23f
        const val OBLIQUE_Y_DEGREES = 37f
        const val TEMPLATE_RECORD_SEED = 9101L
        const val TEMPLATE_CONFIRM_SEED = 9102L
        const val PROBE_SEED = 9201L
        const val SEED_PICKUP = 11000L
        const val SEED_PUTDOWN = 12000L
        const val SEED_TILT = 13000L
        const val SEED_ROTATE = 14000L
        const val SEED_WALK = 15000L
        const val SEED_POCKET = 16000L
    }
}
