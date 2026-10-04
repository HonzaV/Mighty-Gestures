package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Test

/** The `init` invariants (hysteresis ordering, positive τ, band-vs-resample-count) fail fast on a
 * misconfigured [MotionConfig] instead of producing a silently-broken pipeline. */
class MotionConfigTest {
    @Test(expected = IllegalArgumentException::class)
    fun `rejects an accQuietThreshold that is not below accOnsetThreshold`() {
        MotionConfig(accOnsetThreshold = 1f, accQuietThreshold = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a gyroQuietThreshold that is not below gyroOnsetThreshold`() {
        MotionConfig(gyroOnsetThreshold = 1f, gyroQuietThreshold = 2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive onsetConfirmFrames`() {
        MotionConfig(onsetConfirmFrames = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a resample count that does not exceed the DTW band`() {
        MotionConfig(resampledFrameCount = 10, dtwBandFrames = 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive matchThreshold`() {
        MotionConfig(matchThreshold = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a preRollNanos that is not below quietDebounceNanos`() {
        // Underpins Segmenter.handleSettling's safety argument (milestone 1 fix round, item 1):
        // without this, a pre-roll window could reach back past the last non-quiet SETTLING frame.
        MotionConfig(preRollNanos = 500_000_000L, quietDebounceNanos = 500_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a minActiveDurationNanos that is not below maxActiveDurationNanos`() {
        MotionConfig(minActiveDurationNanos = 3_000_000_000L, maxActiveDurationNanos = 3_000_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive gravityTimeConstantNanos`() {
        MotionConfig(gravityTimeConstantNanos = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive validatorMinMeanEnergy`() {
        MotionConfig(validatorMinMeanEnergy = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a durationRatioMin that is not below durationRatioMax`() {
        MotionConfig(durationRatioMin = 2f, durationRatioMax = 2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive collisionDistanceMultiplier`() {
        MotionConfig(collisionDistanceMultiplier = 0f)
    }

    @Test
    fun `defaults satisfy every invariant`() {
        MotionConfig() // must not throw
    }
}
