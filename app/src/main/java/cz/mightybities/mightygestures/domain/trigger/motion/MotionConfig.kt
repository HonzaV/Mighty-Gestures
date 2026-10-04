package cz.mightybities.mightygestures.domain.trigger.motion

/**
 * Every tunable constant of the motion pipeline (gravity filter, segmenter, preprocessor, matcher,
 * validator), gathered in one place so the whole pipeline is reconstructed from one value object
 * (ADR 0008 "`MotionConfig` holds all constants").
 *
 * **Provisional.** Every value here is the ADR 0008 *initial* value, not a calibration result: the
 * sweep over the full synthetic corpus (tuning seeds, then held-out verification) is spec 0001
 * milestone 3. Until then these are **unverified on real human motion** (spec 0001 AC-R1 release
 * gate). [matchThreshold] (τ) in particular has no ADR-given number ("a placeholder is used" until
 * milestone 3); the value here (lowered from an initial 1.6 guess) is the smallest round number
 * that keeps every positive distance below it and every negative distance above it on this PR's
 * own three-gesture, five-seed corpus (per gesture; `MotionMatcherDistanceSpreadTest.REPEAT_SEEDS`),
 * in **both** the 6-D (ACC+GYRO) and 3-D (ACC-only) cases, including each gesture's own grip tilt
 * (item C10). As of the PR #1 fix round (gesture shapes made biphasic, item C9; peaks re-tuned so
 * every gesture clears `TemplateValidator`'s gates, item B7), that test reports max-positive /
 * min-negative of about 0.17 / 4.3 in 6-D (min-negative is ~4.3x τ) and about 0.15 / 1.6 ACC-only
 * (min-negative is ~1.6x τ) — both still separated, with the ACC-only margin the thinner of the
 * two (reduced discrimination without a gyroscope is an accepted ADR 0008 trade-off, not a bug;
 * see also `MotionFalsePositiveCorpusTest`'s chop/ACC-only finding). milestone 3 may still find
 * separate τ per channel-set is needed on the full corpus. Not a calibration result.
 */
data class MotionConfig(
    /** **Gravity filter** (ADR 0008 decision G1: gravity removed in Kotlin). τg: the low-pass time
     * constant, specified as `τg = 0.25 s` in ADR 0008's "Pipeline" section (`FrameBuilder` step).
     * The motion-sensors guide ADR 0008 cites for the low-pass/high-pass approach itself (see ADR
     * 0008 References) only gives a demonstration `alpha = 0.8` with no fixed sample rate attached
     * and explicitly calls that value non-authoritative ("you may need to choose a different alpha
     * value") — it does not specify a cutoff this τg could be said to "follow". This 0.25 s is ADR
     * 0008's own initial choice, like every other value in this class (see the class KDoc
     * "Provisional"), not something derived from the guide's own numbers. */
    val gravityTimeConstantNanos: Long = 250_000_000L,
    /** **Segmenter.** A_on: a frame counts as "active" (onset candidate) once linear acceleration
     * reaches this. */
    val accOnsetThreshold: Float = 3.0f,
    /** G_on: a frame counts as "active" once angular rate reaches this. */
    val gyroOnsetThreshold: Float = 2.0f,
    /** A_off: a frame counts as "quiet" only below this (hysteresis gap vs [accOnsetThreshold]). */
    val accQuietThreshold: Float = 1.5f,
    /** G_off: a frame counts as "quiet" only below this (hysteresis gap vs [gyroOnsetThreshold]). */
    val gyroQuietThreshold: Float = 1.0f,
    /** Consecutive active frames required to confirm onset (debounces single-sample spikes). */
    val onsetConfirmFrames: Int = 2,
    /** Continuous quiet duration that ends `SETTLING` (→`ARMED`) and `ACTIVE` (→ emit segment). */
    val quietDebounceNanos: Long = 500_000_000L,
    /** Pre-roll kept before the confirmed onset, so a segment never starts mid-swing. */
    val preRollNanos: Long = 100_000_000L,
    /** Segments shorter than this (active portion only, tail trimmed) are a bump, not a gesture. */
    val minActiveDurationNanos: Long = 150_000_000L,
    /** `ACTIVE` longer than this without quiet is discarded and never segmented (AC-M7: walking). */
    val maxActiveDurationNanos: Long = 3_000_000_000L,
    /** A gap this large (or a non-monotonic sample) resets the segmenter to `SETTLING`. */
    val maxTimestampGapNanos: Long = 200_000_000L,
    /** **Template validator** ("distinctiveness gate", record only). Stricter than
     * [minActiveDurationNanos]: rejects segments the segmenter let through at 150 ms but that are
     * still too brief to be a deliberate gesture. */
    val validatorMinActiveDurationNanos: Long = 250_000_000L,
    /** Peak |lin| (m/s²) OR peak |gyro| (rad/s) must reach one of these. */
    val validatorPeakAccThreshold: Float = 8.0f,
    val validatorPeakGyroThreshold: Float = 5.0f,
    /** Mean of `|lin|²/A_on² + |gyro|²/G_on²` over active frames must reach this. */
    val validatorMinMeanEnergy: Float = 4.0f,
    /** **Preprocessor.** N: every segment is resampled to this many frames, uniform in time. */
    val resampledFrameCount: Int = 64,
    /** **Matcher.** Cheap gate: live/template duration ratio must fall in
     * `[durationRatioMin, durationRatioMax]`. */
    val durationRatioMin: Float = 0.5f,
    val durationRatioMax: Float = 2.0f,
    /** Cheap gate: live/template per-group RMS ratio must fall in `[rmsRatioMin, rmsRatioMax]`. */
    val rmsRatioMin: Float = 0.5f,
    val rmsRatioMax: Float = 2.0f,
    /** Sakoe–Chiba band half-width, in resampled frames (±8 of 64 = 12.5 %). */
    val dtwBandFrames: Int = 8,
    /** τ: match iff normalized DTW distance ≤ this. **Placeholder, see class KDoc.** */
    val matchThreshold: Float = 1.0f,
    /** Collision gate at confirmation: reject a new gesture within `1.2·τ` of an existing one. */
    val collisionDistanceMultiplier: Float = 1.2f,
    /** **Capture session.** No onset within this long after `startRecording`/`startConfirming` →
     * `NoMovement`. */
    val captureNoMovementTimeoutNanos: Long = 10_000_000_000L,
    /** Bumped whenever the pipeline changes so stored templates are re-derived (ADR 0008).
     * `2`: [DtwMatcher]'s equal-cost tie-break now picks the longer path instead of a fixed
     * diag/up/left position, which changes the normalized distance of any pair that happens to hit
     * an exact cost tie (GitHub Copilot PR #6 round-2 finding; no template is persisted yet in this
     * milestone, so there is nothing to re-derive, but the version still reflects that the pipeline
     * changed, per ADR 0008 "Consequences"). */
    val algorithmVersion: Int = 2,
) {
    init {
        require(gravityTimeConstantNanos > 0L) { "gravityTimeConstantNanos must be positive" }
        require(accOnsetThreshold > 0f) { "accOnsetThreshold must be positive" }
        require(gyroOnsetThreshold > 0f) { "gyroOnsetThreshold must be positive" }
        require(accQuietThreshold > 0f) { "accQuietThreshold must be positive" }
        require(gyroQuietThreshold > 0f) { "gyroQuietThreshold must be positive" }
        require(accQuietThreshold < accOnsetThreshold) { "accQuietThreshold must be < accOnsetThreshold (hysteresis)" }
        require(gyroQuietThreshold < gyroOnsetThreshold) {
            "gyroQuietThreshold must be < gyroOnsetThreshold (hysteresis)"
        }
        require(onsetConfirmFrames >= 1) { "onsetConfirmFrames must be >= 1" }
        require(quietDebounceNanos > 0L) { "quietDebounceNanos must be positive" }
        require(preRollNanos > 0L) { "preRollNanos must be positive" }
        // Underpins Segmenter.handleSettling's safety argument (milestone 1 fix round, item 1): a
        // pre-roll window can never reach back past the last non-quiet SETTLING frame only because
        // reaching ARMED always takes at least quietDebounceNanos *after* that frame, which this
        // inequality guarantees is more than the pre-roll window itself ever reaches back.
        require(preRollNanos < quietDebounceNanos) { "preRollNanos must be < quietDebounceNanos" }
        require(minActiveDurationNanos > 0L) { "minActiveDurationNanos must be positive" }
        require(maxActiveDurationNanos > minActiveDurationNanos) {
            "maxActiveDurationNanos must be > minActiveDurationNanos"
        }
        require(maxTimestampGapNanos > 0L) { "maxTimestampGapNanos must be positive" }
        require(validatorMinActiveDurationNanos > 0L) { "validatorMinActiveDurationNanos must be positive" }
        require(validatorPeakAccThreshold > 0f) { "validatorPeakAccThreshold must be positive" }
        require(validatorPeakGyroThreshold > 0f) { "validatorPeakGyroThreshold must be positive" }
        require(validatorMinMeanEnergy > 0f) { "validatorMinMeanEnergy must be positive" }
        require(dtwBandFrames > 0) { "dtwBandFrames must be positive" }
        require(resampledFrameCount in 1..MAX_RESAMPLED_FRAME_COUNT) {
            "resampledFrameCount must be in 1..$MAX_RESAMPLED_FRAME_COUNT (DtwMatcher's cost/pathLength " +
                "tables are Int-indexed, size * size where size = resampledFrameCount + 1)"
        }
        // GitHub Copilot PR #6 round-3 finding: this used to multiply in Int arithmetic
        // ("resampledFrameCount > 2 * dtwBandFrames"), so dtwBandFrames = Int.MAX_VALUE overflowed
        // "2 * dtwBandFrames" to -2, which any positive resampledFrameCount is ">" -- the invariant
        // silently passed for a band that then overflows DtwMatcher's own `i +/- bandRadius` index
        // math (see DtwMatcherTest), visiting zero cells and returning POSITIVE_INFINITY even for
        // identical inputs. Comparing in Long arithmetic closes that gap.
        require(resampledFrameCount.toLong() > 2L * dtwBandFrames) {
            "resampledFrameCount must exceed the DTW band"
        }
        require(durationRatioMin > 0f) { "durationRatioMin must be positive" }
        require(durationRatioMin < durationRatioMax) { "durationRatioMin must be < durationRatioMax" }
        require(rmsRatioMin > 0f) { "rmsRatioMin must be positive" }
        require(rmsRatioMin < rmsRatioMax) { "rmsRatioMin must be < rmsRatioMax" }
        require(matchThreshold > 0f) { "matchThreshold must be positive" }
        require(collisionDistanceMultiplier > 0f) { "collisionDistanceMultiplier must be positive" }
        require(captureNoMovementTimeoutNanos > 0L) { "captureNoMovementTimeoutNanos must be positive" }
        require(algorithmVersion >= 1) { "algorithmVersion must be >= 1" }
    }

    companion object {
        /** The largest [resampledFrameCount] [DtwMatcher] can hold without its own Int-indexed
         * `size * size` cost/path-length tables overflowing (GitHub Copilot PR #6 round-3 finding):
         * `size = resampledFrameCount + 1`, and `size * size` must stay `<= Int.MAX_VALUE`
         * (2,147,483,647). `floor(sqrt(Int.MAX_VALUE)) = 46_340` (46_340² = 2,147,395,600 fits;
         * 46_341² = 2,147,488,281 overflows), so `size <= 46_340`, i.e. `resampledFrameCount <=
         * 46_339`. Far above the default (64); this bound only rejects a pathological config. */
        const val MAX_RESAMPLED_FRAME_COUNT: Int = 46_339
    }
}
