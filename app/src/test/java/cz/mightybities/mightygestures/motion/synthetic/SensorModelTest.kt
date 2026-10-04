package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * GitHub Copilot PR #6 round-3 finding: [SensorModel.generate] accumulated its profile-evaluation
 * time `t` via repeated Float addition (`t += dtSeconds`), which drifts from the integer sample
 * timestamps over a long trace -- Copilot's own repro: a 600 s trace at 200 Hz produced 119,915
 * frames instead of the exact 120,000, and 50 Hz produced 2 extra. `t` is now derived from the
 * nominal integer sample index instead (as a Double, only cast to Float once for the comparison/
 * profile lookup), so both the frame count and which sample a late event lands on track the
 * Long-arithmetic timestamps exactly, regardless of trace length or rate.
 */
class SensorModelTest {
    @Test
    fun `a 600s trace gives exactly the expected ACC frame count at 50, 100 and 200 Hz`() {
        for ((rateHz, expectedFrames) in listOf(50.0 to 30_000, 100.0 to 60_000, 200.0 to 120_000)) {
            val samples =
                SensorModel(rateHz = rateHz).generate(
                    spec = stillness(durationSeconds = LONG_TRACE_SECONDS),
                    initialOrientation = Quaternion.IDENTITY,
                    hasGyro = true,
                    noise = NoiseSource(seed = 1),
                )
            val accCount = samples.count { it.kind == SensorKind.ACC }
            assertEquals("ACC frame count at ${rateHz}Hz over ${LONG_TRACE_SECONDS}s", expectedFrames, accCount)
        }
    }

    @Test
    fun `a late event lands at the same nominal timestamp at every rate`() {
        val spec =
            MotionSegmentSpec(
                durationSeconds = LONG_TRACE_SECONDS,
                linWorld = VectorProfile { Vector3.ZERO },
                angularBody =
                    VectorProfile { t ->
                        if (t >= LATE_EVENT_SECONDS) Vector3(LATE_EVENT_AMPLITUDE, 0f, 0f) else Vector3.ZERO
                    },
            )
        for (rateHz in listOf(50.0, 100.0, 200.0)) {
            val samples =
                SensorModel(rateHz = rateHz).generate(
                    spec = spec,
                    initialOrientation = Quaternion.IDENTITY,
                    hasGyro = true,
                    noise = NoiseSource(seed = 1),
                )
            val firstEventSample =
                samples.first { it.kind == SensorKind.GYRO && abs(it.x) > LATE_EVENT_DETECTION_THRESHOLD }
            assertEquals(
                "first late-event GYRO timestamp at ${rateHz}Hz",
                LATE_EVENT_NANOS,
                firstEventSample.timestampNanos,
            )
        }
    }

    private companion object {
        const val LONG_TRACE_SECONDS = 600f
        const val LATE_EVENT_SECONDS = 590.0f
        const val LATE_EVENT_NANOS = 590_000_000_000L
        const val LATE_EVENT_AMPLITUDE = 5f
        const val LATE_EVENT_DETECTION_THRESHOLD = 1f
    }
}
