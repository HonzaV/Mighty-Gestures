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

    @Test
    fun `defaults satisfy every invariant`() {
        MotionConfig() // must not throw
    }
}
