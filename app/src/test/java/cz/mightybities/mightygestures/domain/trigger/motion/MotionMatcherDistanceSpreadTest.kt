package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.MotionSegmentSpec
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias GestureFactory = (PerformerVariation, Boolean) -> MotionSegmentSpec

/**
 * One reference gesture for the spread test. [mirrorIsNegative] is `false` for [GesturePrimitives.shake]:
 * a multi-cycle oscillation mirrored (sign-flipped) is just the same waveform phase-shifted by
 * half a cycle, which the DTW band can often re-align almost for free — it is **not** a meaningful
 * "different gesture" for a periodic primitive, only for the single-lobe ones (chop, twist). This
 * is a genuine property of device-frame DTW on symmetric periodic motion, not a test artifact; see
 * the class KDoc and the developer report's "what the tester should probe".
 */
private data class SpreadGesture(
    val name: String,
    val factory: GestureFactory,
    val mirrorIsNegative: Boolean,
)

/** Collects the two distance populations the spread test separates with one τ. */
private class SpreadDistances {
    val positive = mutableListOf<Float>()
    val negative = mutableListOf<Float>()
}

/**
 * Sanity-checks [MotionConfig.matchThreshold] (τ) against the synthetic corpus this PR ships
 * (ADR 0008: "a placeholder is used" before the real milestone-3 sweep). This is **not** the
 * calibration sweep (spec 0001 milestone 3, tuning + held-out seeds): it only checks that one
 * global τ can separate repeats of the same gesture (across performer-variation seeds) from
 * different gestures on a handful of primitives, in both the 6-D (ACC+GYRO) and 3-D (ACC-only)
 * cases (AC-M10). If it can't, that is a "threshold makes an AC impossible" finding to report, not
 * something to paper over by changing the cost function here.
 *
 * **Finding on this corpus** (PR #1 fix round, item C13, re-measured after the biphasic gesture
 * shapes of item C9 and the re-tuned peaks of item B7): max-positive/min-negative is about
 * 0.13/4.3 in 6-D (roughly 33x margin) and about 0.10/1.6 ACC-only (roughly 16x margin) at τ=1.0.
 * Both comfortably separated, but the 3-D (ACC-only) margin is still the thinner of the two —
 * twist's only ACC signature is gravity leaking through the gravity filter during its rotation
 * (ADR 0008 "Consequences"), which resembles chop's genuine translational signature more than a
 * gyro-equipped comparison would. This is the ADR's accepted "reduced discrimination" trade-off
 * for gyro-less devices showing up concretely, not a bug in this test or the matcher.
 */
class MotionMatcherDistanceSpreadTest {
    private val config = MotionConfig()
    private val preprocessor = Preprocessor(config)
    private val matcher = MotionMatcher(config)

    private val gestures =
        listOf(
            SpreadGesture("shake", GesturePrimitives::shake, mirrorIsNegative = false),
            SpreadGesture("chop", GesturePrimitives::chop, mirrorIsNegative = true),
            SpreadGesture("twist", GesturePrimitives::twist, mirrorIsNegative = true),
        )

    private fun processedOf(
        gesture: GestureFactory,
        variation: PerformerVariation,
        mirrored: Boolean,
        hasGyro: Boolean,
        seed: Long,
    ): ProcessedSegment {
        val recorded = RecordedPipeline(config, hasGyro)
        recorded.feed(settlingMargin(gesture(variation, mirrored)), noise = NoiseSource(seed))
        check(recorded.segments.size == 1) { "expected exactly one segment, got ${recorded.segments.size}" }
        return preprocessor.process(recorded.segments[0], config)
    }

    private fun seededVariation(seed: Long) = PerformerVariation.sample(NoiseSource(seed + VARIATION_SEED_OFFSET))

    @Test
    fun `positive pairs across seeds and negative pairs are separated by one threshold, 6-D`() {
        assertSpread(hasGyro = true)
    }

    @Test
    fun `positive pairs across seeds and negative pairs are separated by one threshold, 3-D ACC-only`() {
        assertSpread(hasGyro = false)
    }

    private fun assertSpread(hasGyro: Boolean) {
        val distances = SpreadDistances()

        for ((gi, gesture) in gestures.withIndex()) {
            val template =
                processedOf(
                    gesture.factory,
                    PerformerVariation.NONE,
                    mirrored = false,
                    hasGyro = hasGyro,
                    seed =
                        100L + gi,
                )
            collectRepeatsAndMirror(gesture, gi, hasGyro, template, distances)
            collectCrossGestureNegatives(gesture, gi, hasGyro, template, distances)
        }

        assertTrue("need positive distances to check separation", distances.positive.isNotEmpty())
        assertTrue("need negative distances to check separation", distances.negative.isNotEmpty())
        val maxPositive = distances.positive.max()
        val minNegative = distances.negative.min()
        val tau = config.matchThreshold
        assertTrue(
            "positive distances (max $maxPositive) must stay <= tau ($tau), all: ${distances.positive}",
            maxPositive <= tau,
        )
        assertTrue(
            "negative distances (min $minNegative) must stay > tau ($tau), all: ${distances.negative}",
            minNegative > tau,
        )
    }

    /** Repeats of [gesture] across performer-variation seeds (positive), plus its mirror image when
     * that is a meaningful negative (see [SpreadGesture.mirrorIsNegative]). */
    private fun collectRepeatsAndMirror(
        gesture: SpreadGesture,
        gi: Int,
        hasGyro: Boolean,
        template: ProcessedSegment,
        distances: SpreadDistances,
    ) {
        for (seed in 1L..REPEAT_SEEDS) {
            val repeatSeed = seed + gi * SEED_STRIDE
            val variation = seededVariation(repeatSeed)
            val repeat = processedOf(gesture.factory, variation, mirrored = false, hasGyro = hasGyro, seed = repeatSeed)
            val distance = matcher.distance(repeat, template)
            checkNotNull(distance) { "gates rejected a same-gesture repeat; spread test needs it to reach DTW" }
            distances.positive += distance

            if (gesture.mirrorIsNegative) {
                val mirrored =
                    processedOf(
                        gesture.factory,
                        PerformerVariation.NONE,
                        mirrored = true,
                        hasGyro = hasGyro,
                        seed = repeatSeed,
                    )
                val negDistance = matcher.distance(mirrored, template)
                if (negDistance != null) distances.negative += negDistance
            }
        }
    }

    /** A different primitive entirely is always a meaningful negative, regardless of
     * [SpreadGesture.mirrorIsNegative]. */
    private fun collectCrossGestureNegatives(
        gesture: SpreadGesture,
        gi: Int,
        hasGyro: Boolean,
        template: ProcessedSegment,
        distances: SpreadDistances,
    ) {
        for (other in gestures) {
            if (other === gesture) continue
            val otherProcessed =
                processedOf(
                    other.factory,
                    PerformerVariation.NONE,
                    mirrored = false,
                    hasGyro = hasGyro,
                    seed =
                        200L + gi,
                )
            val d = matcher.distance(otherProcessed, template)
            if (d != null) distances.negative += d
        }
    }

    private companion object {
        const val REPEAT_SEEDS = 5L
        const val SEED_STRIDE = 1000L
        const val VARIATION_SEED_OFFSET = 500L
    }
}
