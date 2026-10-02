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
 * own three-gesture, five-seed corpus, in **both** the 6-D (ACC+GYRO) and 3-D (ACC-only) cases —
 * see `MotionMatcherDistanceSpreadTest`. That test found the ACC-only margin far thinner than the
 * 6-D one (reduced discrimination without a gyroscope is an accepted ADR 0008 trade-off, not a
 * bug), so this placeholder is *shared but asymmetric in how much headroom it leaves*; milestone 3
 * may find separate τ per channel-set is needed. Not a calibration result.
 */
data class MotionConfig(
    /** **Gravity filter** (ADR 0008 decision G1). τg: the low-pass time constant. 0.25 s follows
     * the motion-sensors guide's high-pass example cutoff for gesture-scale motion. */
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
    /** Bumped whenever the pipeline changes so stored templates are re-derived (ADR 0008). */
    val algorithmVersion: Int = 1,
) {
    init {
        require(accQuietThreshold < accOnsetThreshold) { "accQuietThreshold must be < accOnsetThreshold (hysteresis)" }
        require(gyroQuietThreshold < gyroOnsetThreshold) {
            "gyroQuietThreshold must be < gyroOnsetThreshold (hysteresis)"
        }
        require(onsetConfirmFrames >= 1) { "onsetConfirmFrames must be >= 1" }
        require(resampledFrameCount > 2 * dtwBandFrames) { "resampledFrameCount must exceed the DTW band" }
        require(matchThreshold > 0f) { "matchThreshold must be positive" }
    }
}
