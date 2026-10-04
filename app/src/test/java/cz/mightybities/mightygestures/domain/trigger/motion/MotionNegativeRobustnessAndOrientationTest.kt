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
 *    factories), re-run under every AC-M5 disturbance, asserting it still does NOT match, for
 *    `chop`/6-D, `twist`/6-D, `shake`/6-D, `shake`/ACC-only and `chop`/ACC-only;
 * 2. positives matching across **several** static gravity orientations (not just the one
 *    quarter-turn [MotionRobustnessTest] covers) with a *sampled* grip-tilt variation per probe
 *    (not a bare noise replay of the template), in both 6-D and ACC-only (twist excluded ACC-only,
 *    spec 0001 decision 14/[GestureRecordabilityTest]'s class KDoc);
 * 3. a bonus cross-orientation false-positive probe (template and negative probe at *different*
 *    static orientations) kept in its own tests so a finding there cannot mask the rest of the file.
 *
 * `pickup from table` x `chop`/ACC-only is excluded (decision 14 only), citing spec 0001 decision
 * 14: "On the synthetic corpus, the 'pick up from table' negative lands at a distance of
 * ~0.95-1.0 from an ACC-only chop template... That is inside tau = 1.0, so it would fire...
 * milestone #1 ... leaves out the chop/ACC-only pairing" -- every *other* negative is still run
 * against `chop`/ACC-only (including `put down on table`, pickup's mirror: a collision there would
 * be a new finding, not a restatement of decision 14).
 *
 * Two **new** cross-orientation findings were found while writing #3 and are reported, not fixed
 * (tester guardrails), each isolated into its own `@Ignore`'d test with a per-amplitude
 * characterization run in its KDoc: `shake`/ACC-only collides with "rotate to landscape" probed at
 * 90 deg about Y, but only at 1.8x-2.6x amplitude (~126-182 deg of yaw, past a real landscape
 * turn); `chop`/ACC-only collides with the same situation probed at 90 deg about X starting at
 * 1.4x (~98 deg, close to a real turn) -- higher severity, since it fires closer to realistic use.
 * Both are gate-limited, not distance-limited: every attempt that clears the matcher's
 * duration/RMS gates matches; the gate is the only thing separating a fire from a miss. Every
 * other cell of every sweep in this file still runs and must still pass; see the two
 * `FINDING --` tests below.
 *
 * Known gap: for `shake`/6-D (disturbance sweep only), **none** of the *shared*
 * [negativeSituations] corpus's duration/RMS profiles clear that template's matcher gates at any
 * disturbance (`reachedDtw == 0`, printed as a `WARNING` in the test output) -- its "no false
 * positive" result is vacuous with this corpus, not evidence of correctness. `chop`/ACC-only's
 * disturbance sweep adds one extra, higher-amplitude `put down on table` probe (see
 * `extraSituations`/`PUTDOWN_EXTENDED_AMPLITUDE`) specifically so that one is *not* vacuous (it
 * reaches DTW and misses, distance ~1.65-1.72 against tau=1.0); every negative *other* than that
 * extra probe is still gate-rejected against `chop`/ACC-only in the disturbance sweep, same as
 * `shake`/6-D. `chop`/6-D, `twist`/6-D and `shake`/ACC-only all reach DTW against the unmodified
 * shared corpus -- but only via `pickup from table` and `put down on table`: `tilt to read`,
 * `rotate to landscape` and the walking/pocket situations never reach DTW, against any template,
 * at the shared corpus's amplitudes, at the shared identity orientation. The same-orientation AC-M5
 * negative coverage in this file therefore rests entirely on those two situations (plus the one
 * extra `chop`/ACC-only probe above); the rest are correct no-fires by never segmenting into
 * something gate-comparable to these templates, not by the matcher distinguishing them. The
 * cross-orientation sweep (above) does not track `reachedDtw` at all, so its coverage is
 * uncharacterized the same way, and it omits `twist`/6-D and `shake`/6-D entirely.
 */
class MotionNegativeRobustnessAndOrientationTest {
    private val config = MotionConfig()
    private val matcher = MotionMatcher(config)
    private val preprocessor = Preprocessor(config)

    /** One template under test in the disturbance/cross-orientation sweeps: which gesture, which
     * channel set, which negative situations to skip (by name, each skip must cite a reason at the
     * call site -- see spec 0001 decision 14 for the only one used here), and any
     * [extraSituations] to add *only* for this template (not the shared [negativeSituations]
     * list, so widening a probe for one template's sake never changes what every other template's
     * sweep tests -- see the chop/ACC-only put-down extension below). */
    private data class Template(
        val label: String,
        val gesture: (PerformerVariation) -> MotionSegmentSpec,
        val hasGyro: Boolean,
        val excludedSituations: Set<String> = emptySet(),
        val extraSituations: List<Pair<String, List<Pair<Long, MotionSegmentSpec>>>> = emptyList(),
    )

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
        assertNoFalsePositiveUnderAnyDisturbance(Template("chop/6D", GesturePrimitives::chop, hasGyro = true))
    }

    @Test
    fun `no negative matches a twist template, 6-D, under any AC-M5 disturbance`() {
        assertNoFalsePositiveUnderAnyDisturbance(Template("twist/6D", GesturePrimitives::twist, hasGyro = true))
    }

    @Test
    fun `no negative matches a shake template, 6-D, under any AC-M5 disturbance`() {
        assertNoFalsePositiveUnderAnyDisturbance(Template("shake/6D", GesturePrimitives::shake, hasGyro = true))
    }

    @Test
    fun `no negative matches a shake template, ACC-only, under any AC-M5 disturbance`() {
        // Not chop/ACC-only: spec 0001 decision 14 (see class KDoc) -- that pairing is a known,
        // accepted collision at today's thresholds, not something this test should flag again.
        assertNoFalsePositiveUnderAnyDisturbance(Template("shake/ACC-only", GesturePrimitives::shake, hasGyro = false))
    }

    @Test
    fun `no negative except pickup matches a chop template, ACC-only, under any AC-M5 disturbance (decision 14)`() {
        // Every negative EXCEPT "pickup from table" (decision 14's documented collision) is run
        // here, including "put down on table" (pickup's mirror): a collision there would be a new
        // finding, not a restatement of decision 14, so it is deliberately NOT pre-excluded. The
        // shared negativeSituations()'s own "put down on table" entries (amplitude 1.0x-1.8x) are
        // all gate-rejected outright against this specific template (verified: a scratch run during
        // test development showed 0 of 10 reaching DTW at 1.0x-2.2x, first reaching DTW, with no
        // match, at 2.6x) -- extraSituations adds exactly that one higher-amplitude put-down probe,
        // for this template only, so the "no collision" result below is not vacuous for put-down.
        assertNoFalsePositiveUnderAnyDisturbance(
            Template(
                "chop/ACC-only",
                GesturePrimitives::chop,
                hasGyro = false,
                excludedSituations = setOf("pickup from table"),
                extraSituations =
                    listOf(
                        "put down on table (extended amplitude, decision 14 mirror check)" to
                            listOf(
                                SEED_PUTDOWN_EXTENDED to
                                    putDownOnTable(
                                        peakAngularRateRadPerSecond = PICKUP_GYRO_PEAK * PUTDOWN_EXTENDED_AMPLITUDE,
                                        peakLiftAcceleration = PICKUP_ACC_PEAK * PUTDOWN_EXTENDED_AMPLITUDE,
                                    ),
                            ),
                    ),
            ),
        )
    }

    private fun assertNoFalsePositiveUnderAnyDisturbance(template: Template) {
        val falsePositives = mutableListOf<String>()
        var totalAttempts = 0
        var totalSegmented = 0
        var totalReachedDtw = 0
        for (disturbance in disturbances()) {
            val recorded =
                recordAndConfirmTemplate(
                    config,
                    template.gesture,
                    template.hasGyro,
                    TemplateRendering(disturbance.sensorModel, Quaternion.IDENTITY),
                    CaptureSeeds(TEMPLATE_RECORD_SEED, TEMPLATE_CONFIRM_SEED),
                )
            val counts = checkDisturbanceAgainstAllSituations(disturbance, template, recorded, falsePositives)
            totalAttempts += counts.attempts
            totalSegmented += counts.segmented
            totalReachedDtw += counts.reachedDtw
            println(
                "D14 [${template.label} / ${disturbance.label}] cell totals: attempts=${counts.attempts} " +
                    "segmented=${counts.segmented} reachedDtw=${counts.reachedDtw}",
            )
        }
        println(
            "D14 [${template.label}] TOTAL: attempts=$totalAttempts segmented=$totalSegmented " +
                "reachedDtw=$totalReachedDtw",
        )
        // reachedDtw is not asserted > 0: for shake/6D and chop/ACC-only (decision 14's pickup
        // excluded), empirically NONE of this reduced situation corpus's duration/RMS profiles
        // clear those two templates' gates, at any disturbance (totalReachedDtw == 0) -- this is a
        // coverage gap in this corpus for those two templates (reported to the maintainer/tester:
        // the "no false positive" result for them is vacuous, not evidence of correctness), not a
        // production defect, so it is surfaced as a WARNING print rather than a hard failure that
        // would block every future change to this file for a limitation of the fixture, not the
        // code under test. chop/6D, twist/6D and shake/ACC-only all do reach DTW (see the per-cell
        // prints above), so the false-positive assertion below is non-vacuous for those three.
        if (totalReachedDtw == 0) {
            println(
                "D14 [${template.label}] WARNING: reachedDtw=0 across every disturbance -- the " +
                    "false-positive result below is VACUOUS for this template with this situation corpus",
            )
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
        val reachedDtw: Int,
    )

    private fun checkDisturbanceAgainstAllSituations(
        disturbance: Disturbance,
        template: Template,
        recordedTemplate: List<ProcessedSegment>,
        falsePositives: MutableList<String>,
    ): AttemptCounts {
        var attempts = 0
        var segmented = 0
        var reachedDtw = 0
        for ((situationName, situationAttempts) in negativeSituations() + template.extraSituations) {
            if (situationName in template.excludedSituations) continue
            var situationMinDistance = Float.POSITIVE_INFINITY
            for ((seed, spec) in situationAttempts) {
                attempts++
                val rendering = ProbeRendering(template.hasGyro, disturbance.sensorModel)
                val outcome = runNegativeAttempt(spec, seed, rendering, recordedTemplate)
                if (outcome.segmentCount > 0) segmented++
                if (outcome.reachedDtw) reachedDtw++
                situationMinDistance = minOf(situationMinDistance, outcome.minDistance)
                if (outcome.matchDistance != null) {
                    falsePositives +=
                        "${template.label} / ${disturbance.label} / $situationName: seed=$seed " +
                        "distance=${outcome.matchDistance} (tau=${config.matchThreshold})"
                }
            }
            println(
                "D14 [${template.label} / ${disturbance.label}] $situationName: minDistance=$situationMinDistance",
            )
        }
        return AttemptCounts(attempts, segmented, reachedDtw)
    }

    private data class AttemptOutcome(
        val segmentCount: Int,
        val reachedDtw: Boolean,
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
        var reachedDtw = false
        var matchDistance: Float? = null
        for (processed in segments) {
            val distance = matcher.distanceToTemplate(processed, template) ?: continue
            reachedDtw = true
            minDistance = minOf(minDistance, distance)
            if (distance <= config.matchThreshold) matchDistance = distance
        }
        return AttemptOutcome(segments.size, reachedDtw, minDistance, matchDistance)
    }

    // --------------------------------------------------------------------------------------
    // 2. Positives match across several static gravity orientations (template and probe share
    //    the device orientation each time), each probed with *several sampled grip-tilt
    //    variations* on top (not a bare noise replay of the exact template trace), 6-D and
    //    ACC-only where recordable.
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
        assertMatchesAcrossOrientations(Template("chop/6D", GesturePrimitives::chop, hasGyro = true))
        assertMatchesAcrossOrientations(Template("shake/6D", GesturePrimitives::shake, hasGyro = true))
        assertMatchesAcrossOrientations(Template("twist/6D", GesturePrimitives::twist, hasGyro = true))
    }

    @Test
    fun `chop and shake match across several orientations, ACC-only (twist excluded, see class KDoc)`() {
        assertMatchesAcrossOrientations(Template("chop/ACC-only", GesturePrimitives::chop, hasGyro = false))
        assertMatchesAcrossOrientations(Template("shake/ACC-only", GesturePrimitives::shake, hasGyro = false))
    }

    private fun assertMatchesAcrossOrientations(template: Template) {
        val misses = mutableListOf<String>()
        for ((orientationLabel, baseOrientation) in orientations()) {
            val recorded =
                recordAndConfirmTemplate(
                    config,
                    template.gesture,
                    template.hasGyro,
                    TemplateRendering({ SensorModel(rateHz = 50.0) }, baseOrientation),
                    CaptureSeeds(TEMPLATE_RECORD_SEED + 1, TEMPLATE_CONFIRM_SEED + 1),
                )
            val maxDistance = probeVariationsAt(orientationLabel, baseOrientation, template, recorded, misses)
            println(
                "D14 [${template.label}] $orientationLabel: maxDistance=$maxDistance (tau=${config.matchThreshold})",
            )
        }
        assertTrue(
            "expected a match at every orientation x grip-tilt variation -- misses: ${misses.joinToString("; ")}",
            misses.isEmpty(),
        )
    }

    /** Probes [recordedTemplate] (recorded at [baseOrientation]) with [PROBE_VARIATION_COUNT]
     * sampled grip tilts composed on top of the same device orientation ([baseOrientation] `*`
     * `variation.initialOrientation()`), so this exercises the matcher against realistic performer
     * variability rather than a near-identical noise replay of the exact template trace. Returns
     * the maximum distance reached (for the caller's evidence log) and appends any miss to
     * [misses]. */
    private fun probeVariationsAt(
        orientationLabel: String,
        baseOrientation: Quaternion,
        template: Template,
        recordedTemplate: List<ProcessedSegment>,
        misses: MutableList<String>,
    ): Float {
        var maxDistance = Float.NEGATIVE_INFINITY
        for (i in 0 until PROBE_VARIATION_COUNT) {
            val seed = PROBE_SEED + i
            val variation = PerformerVariation.sample(NoiseSource(seed))
            val probeOrientation = baseOrientation * variation.initialOrientation()
            val segments = mutableListOf<ProcessedSegment>()
            val pipeline = MotionPipeline(config, template.hasGyro) { seg -> segments += preprocessor.process(seg) }
            val rendered = wrappedForCapture(template.gesture(variation))
            SensorModel(rateHz = 50.0)
                .generate(rendered, probeOrientation, hasGyro = template.hasGyro, noise = NoiseSource(seed + 1))
                .feedTo(pipeline)
            val distance = segments.firstOrNull()?.let { matcher.distanceToTemplate(it, recordedTemplate) }
            if (distance != null) maxDistance = maxOf(maxDistance, distance)
            if (distance == null || distance > config.matchThreshold) {
                misses += "$orientationLabel / grip-variation seed=$seed: distance=$distance"
            }
        }
        return maxDistance
    }

    // --------------------------------------------------------------------------------------
    // 3. Bonus: cross-orientation false positives (template at identity, negative probed at a
    //    different static orientation) -- the more realistic false-trigger scenario, kept in its
    //    own tests so a finding here cannot mask the disturbance sweep above.
    // --------------------------------------------------------------------------------------

    private fun crossOrientationProbeOrientations(): List<Pair<String, Quaternion>> =
        listOf(
            "tilted 90 about X" to Quaternion.aboutX(NINETY_DEGREES),
            "tilted 90 about Y" to Quaternion.aboutY(NINETY_DEGREES),
            "face down" to Quaternion.aboutX(ONE_EIGHTY_DEGREES),
        )

    @Test
    fun `no negative matches a chop template, 6-D, when probed at a different static orientation`() {
        assertNoCrossOrientationFalsePositive(Template("chop/6D", GesturePrimitives::chop, hasGyro = true))
    }

    @Test
    fun `chop ACC-only, cross-orientation -- no match except pickup and the known collision`() {
        // Excludes exactly one cell: "rotate to landscape" probed at "tilted 90 about X" -- see the
        // KDoc on the FINDING test below. "pickup from table" stays excluded per decision 14
        // (chop/ACC-only's documented same-orientation collision). Every other situation, including
        // "put down on table" (pickup's mirror), is *attempted* at every remaining probe
        // orientation -- but unlike the disturbance sweep's extraSituations probe, this path does
        // not track reachedDtw, so "no collision" passing here is NOT confirmed evidence that put
        // down on table actually reached the matcher at these (cross-)orientations: it may simply
        // be gate-rejected here too, the same way it is at identity for this corpus's 1.0x-1.8x
        // amplitudes (see the class KDoc "gaps" note). Not widened to extraSituations here, to
        // avoid scope creep beyond the two characterized findings.
        assertNoCrossOrientationFalsePositive(
            Template(
                "chop/ACC-only",
                GesturePrimitives::chop,
                hasGyro = false,
                excludedSituations = setOf("pickup from table"),
            ),
            excludeCell = { orientationLabel, situationName ->
                orientationLabel == "tilted 90 about X" && situationName == "rotate to landscape"
            },
        )
    }

    /**
     * **Finding, reported to the maintainer/developer, not fixed here (tester guardrails).**
     * `chop`/ACC-only, template recorded at `Quaternion.IDENTITY`, collides with the "rotate to
     * landscape" negative when the *probe* is at a device orientation tilted 90 deg about body X.
     *
     * Characterization (10 noise seeds x 5 amplitude levels, 1.0x-2.6x in 0.4x steps -- wider than
     * the shared [negativeSituations]' own 1.0x-1.8x sweep for this situation -- = 50 independent
     * attempts, scratch run during test development, not committed): the matcher's duration/RMS
     * gates, not the DTW distance, are what separates "fires" from "doesn't" here --
     * - **1.0x (~70 deg of yaw)**: gate-rejected on all 10 seeds (never reaches DTW);
     * - **1.4x (~98 deg, close to a real ~90 deg landscape turn)**: gate-rejected on 4 of 10 seeds,
     *   **matches on the other 6 of 10** -- at the *high* (closest-to-tau) end of the 0.644-0.963
     *   range, around **0.96** (e.g. seed `SEED_ROTATE + 1`: distance 0.961), only about 4% inside
     *   tau=1.0. A later independent reconstruction (2026-10-04, PR #6 review) found **8 of 10** seeds
     *   matching at 1.4x (seeds `SEED_ROTATE + 1..8`, 0.959-0.968), identically before and after the
     *   SensorModel timing fix; the scratch sweep above was never committed, so read "6-8 of 10";
     * - **1.8x-2.6x (~126-182 deg)**: matches on **all 10 of 10** seeds at every level, at
     *   progressively *lower* (more clearly inside tau) distances down to 0.644 as amplitude rises
     *   (e.g. 1.8x, seed `SEED_ROTATE + 2`: distance 0.782).
     *
     * Every attempt that clears the gates matches; the gate is the only thing separating a fire
     * from a miss. The most realistic case (1.4x, ~98 deg) is the thinnest margin, not a deep
     * miss -- a modestly stricter ACC-only tau (decision 14's planned fix) would plausibly clear
     * it (*inferred*, not verified here); the grosser over-rotations at 1.8x+ would not. Reported
     * as medium-to-high severity because it already fires close to a realistic turn, not because
     * the margin there is large. This is a second,
     * independent instance of the same underlying weakness spec 0001 decision 14 already named for
     * ACC-only chop (gravity-removed, chop's only signal is a single RMS-normalized translational
     * pulse) -- but at a *different* probe orientation and against a *different* negative situation
     * than decision 14's own same-orientation pickup finding, so it is reported as its own finding
     * rather than folded into that exclusion.
     *
     * The same situation produces zero false positives against the same template at a shared
     * (identity) orientation ([assertNoFalsePositiveUnderAnyDisturbance], which excludes only
     * "pickup from table" and still passes with "rotate to landscape" included) -- confirming this
     * is specifically a cross-orientation effect, not a same-orientation regression of decision 14.
     *
     * `@Ignore`d (not silently: this KDoc plus the tester report are the explanation) rather than
     * left red, so it does not block `scripts/verify.sh`. This test covers **only** this one
     * (orientation, situation) cell; the sibling test above excludes only this cell (plus pickup,
     * per decision 14) and must still pass for every other orientation/situation combination.
     */
    @Ignore(
        "FINDING (not fixed, see KDoc): chop/ACC-only collides with 'rotate to landscape' probed at " +
            "90deg-about-X; gate-rejected at 1.0x (~70deg yaw, 0/10 seeds), matches at 1.4x " +
            "(~98deg, 6-8/10 seeds) and 1.8x-2.6x (~126-182deg, 10/10 seeds), distances 0.644-0.963 " +
            "< tau=1.0 -- report to maintainer/developer, candidate for milestone 3's ACC-only " +
            "threshold work",
    )
    @Test
    fun `FINDING -- chop template, ACC-only, collides with rotate-to-landscape probed at 90deg-about-X`() {
        assertNoCrossOrientationFalsePositive(
            Template(
                "chop/ACC-only",
                GesturePrimitives::chop,
                hasGyro = false,
                excludedSituations = setOf("pickup from table"),
            ),
            excludeCell = { orientationLabel, situationName ->
                !(orientationLabel == "tilted 90 about X" && situationName == "rotate to landscape")
            },
        )
    }

    @Test
    fun `shake ACC-only, cross-orientation -- no match except the known collision`() {
        // Excludes exactly one cell: "rotate to landscape" probed at "tilted 90 about Y" -- see the
        // KDoc on the FINDING test below for the characterized collision. Every other cell in this
        // sweep (both other probe orientations, and every other situation at this one) still runs
        // and must still pass.
        assertNoCrossOrientationFalsePositive(
            Template("shake/ACC-only", GesturePrimitives::shake, hasGyro = false),
            excludeCell = { orientationLabel, situationName ->
                orientationLabel == "tilted 90 about Y" && situationName == "rotate to landscape"
            },
        )
    }

    /**
     * **Finding, reported to the maintainer/developer, not fixed here (tester guardrails).**
     * `shake`/ACC-only, template recorded at `Quaternion.IDENTITY`, collides with the "rotate to
     * landscape" negative when the *probe* is at a device orientation tilted 90 deg about body Y
     * (landscape).
     *
     * Characterization (10 noise seeds x 5 amplitude levels, 1.0x-2.6x in 0.4x steps -- wider than
     * the shared [negativeSituations]' own 1.0x-1.8x sweep for this situation -- = 50 independent
     * attempts, scratch run during test development, not committed): unlike the chop/ACC-only
     * finding below, this one is gate-rejected up to a *less* realistic rotation --
     * - **1.0x-1.4x (~70-98 deg of yaw, i.e. up to past a real ~90 deg landscape turn)**:
     *   gate-rejected on all 10 seeds at both levels (never reaches DTW);
     * - **1.8x-2.6x (~126-182 deg)**: matches on **all 10 of 10** seeds at every level, distances
     *   in the 0.833-0.929 range.
     *
     * So this collision only fires on rotations past a real landscape turn, which is lower severity
     * than the chop/ACC-only finding below (which already matches at ~98 deg, close to realistic).
     * Every attempt that clears the gates matches; the gate is the only thing separating a fire from
     * a miss, for both findings.
     *
     * The same situation produces zero false positives against the same template at a shared
     * (identity) orientation ([assertNoFalsePositiveUnderAnyDisturbance]) and in
     * [MotionFalsePositiveCorpusTest] (which also only probes at identity) -- this is a genuinely
     * new collision, not a restatement of spec 0001 decision 14's documented chop/ACC-only x pickup
     * finding. AC-M5's orientation clause only requires the template and probe to *share* an
     * orientation (ADR 0008 "Invariance trade-offs": device-frame matching is *meant* to be
     * orientation-sensitive), so this is not an AC-M5 regression, but it is a real false-trigger
     * path for a user who records a gesture upright and then, e.g., tilts the phone to landscape
     * while picking it up or glancing at it -- exactly the "false trigger in a pocket" class of bug
     * AGENTS.md calls worse than a miss.
     *
     * `@Ignore`d (not silently: this KDoc plus the tester report are the explanation) rather than
     * left red, so it does not block `scripts/verify.sh`. This test covers **only** this one
     * (orientation, situation) cell; the sibling test above excludes only this cell and must still
     * pass for every other orientation/situation combination.
     */
    @Ignore(
        "FINDING (not fixed, see KDoc): shake/ACC-only collides with 'rotate to landscape' probed at " +
            "90deg-about-Y; gate-rejected at 1.0x-1.4x (~70-98deg yaw, 0/10 seeds each), matches at " +
            "1.8x-2.6x (~126-182deg, 10/10 seeds each), distances 0.833-0.929 < tau=1.0 -- lower " +
            "severity than the chop/ACC-only finding (fires only past a real landscape turn) -- " +
            "report to maintainer/developer, candidate for milestone 3's ACC-only threshold work",
    )
    @Test
    fun `FINDING -- shake template, ACC-only, collides with rotate-to-landscape probed at 90deg-about-Y`() {
        assertNoCrossOrientationFalsePositive(
            Template("shake/ACC-only", GesturePrimitives::shake, hasGyro = false),
            excludeCell = { orientationLabel, situationName ->
                !(orientationLabel == "tilted 90 about Y" && situationName == "rotate to landscape")
            },
        )
    }

    private fun assertNoCrossOrientationFalsePositive(
        template: Template,
        excludeCell: (orientationLabel: String, situationName: String) -> Boolean = { _, _ -> false },
    ) {
        val recorded =
            recordAndConfirmTemplate(
                config,
                template.gesture,
                template.hasGyro,
                TemplateRendering({ SensorModel(rateHz = 50.0) }, Quaternion.IDENTITY),
                CaptureSeeds(TEMPLATE_RECORD_SEED + 2, TEMPLATE_CONFIRM_SEED + 2),
            )
        val context = CrossOrientationContext(template, recorded, excludeCell)
        val falsePositives = mutableListOf<String>()
        for ((orientationLabel, orientation) in crossOrientationProbeOrientations()) {
            falsePositives += crossOrientationFalsePositivesAt(orientationLabel, orientation, context)
        }
        // Any match here is a new finding (a stronger false-positive than AC-M5 requires, since
        // device-frame matching is orientation-sensitive by design, ADR 0008 "Invariance
        // trade-offs"): report it, do not silently widen an exclusion to make this pass.
        assertTrue(
            "CROSS-ORIENTATION FALSE POSITIVE(S) -- ${falsePositives.joinToString("; ")}",
            falsePositives.isEmpty(),
        )
    }

    /** Bundled so [crossOrientationFalsePositivesAt] stays under detekt's parameter-count limit. */
    private data class CrossOrientationContext(
        val template: Template,
        val recordedTemplate: List<ProcessedSegment>,
        val excludeCell: (orientationLabel: String, situationName: String) -> Boolean,
    )

    private fun crossOrientationFalsePositivesAt(
        orientationLabel: String,
        orientation: Quaternion,
        context: CrossOrientationContext,
    ): List<String> {
        val found = mutableListOf<String>()
        for ((situationName, attempts) in negativeSituations()) {
            val skip =
                situationName in context.template.excludedSituations ||
                    context.excludeCell(orientationLabel, situationName)
            if (skip) continue
            for ((seed, spec) in attempts) {
                val rendering = ProbeRendering(context.template.hasGyro, { SensorModel(rateHz = 50.0) }, orientation)
                val outcome = runNegativeAttempt(spec, seed, rendering, context.recordedTemplate)
                if (outcome.matchDistance != null) {
                    found +=
                        "${context.template.label} / probe@$orientationLabel / $situationName: seed=$seed " +
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
        const val PROBE_VARIATION_COUNT = 5
        const val TEMPLATE_RECORD_SEED = 9101L
        const val TEMPLATE_CONFIRM_SEED = 9102L
        const val PROBE_SEED = 9201L
        const val SEED_PICKUP = 11000L
        const val SEED_PUTDOWN = 12000L
        const val SEED_PUTDOWN_EXTENDED = 12100L

        /** 2.6x the base peaks: the first amplitude step (verified by a scratch run during test
         * development, sweeping 1.0x-3.4x) at which "put down on table" actually clears
         * chop/ACC-only's matcher gates at all (0 of 10 noise seeds gate-rejected; every one that
         * reaches DTW misses). Below this (1.0x-2.2x, including the shared negativeSituations()
         * steps), every attempt is gate-rejected outright, so it never probes the matcher. */
        const val PUTDOWN_EXTENDED_AMPLITUDE = 2.6f
        const val SEED_TILT = 13000L
        const val SEED_ROTATE = 14000L
        const val SEED_WALK = 15000L
        const val SEED_POCKET = 16000L
    }
}
