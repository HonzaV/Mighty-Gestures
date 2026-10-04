package cz.mightybities.mightygestures.motion.synthetic

import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceCsv
import cz.mightybities.mightygestures.domain.trigger.motion.MotionTraceSample
import cz.mightybities.mightygestures.domain.trigger.motion.SensorKind
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Copilot PR #6 round-3 finding: [List.toTraceCsv] only checked that a `source` metadata
 * key was *present*, not its value, so `source=device` would label the synthetic generator's own
 * output as a (fictional) human recording -- exactly the mislabeling ADR 0008 "Trace format" /
 * [MotionTraceCsv.ALLOWED_SOURCES] exist to prevent. The generator can only ever produce synthetic
 * data, so [toTraceCsv] now requires the value to be exactly `"synthetic"`.
 */
class TraceFeedTest {
    private val samples = listOf(MotionTraceSample(SensorKind.ACC, 0L, 0f, 9.81f, 0f))

    @Test
    fun `accepts source=synthetic`() {
        val csv = samples.toTraceCsv(mapOf(MotionTraceCsv.METADATA_SOURCE_KEY to "synthetic"))
        assertTrue(csv.contains("source=synthetic"))
    }

    @Test
    fun `rejects source=device (would mislabel generated samples as a human recording)`() {
        assertThrows(IllegalArgumentException::class.java) {
            samples.toTraceCsv(mapOf(MotionTraceCsv.METADATA_SOURCE_KEY to "device"))
        }
    }

    @Test
    fun `rejects a missing source key`() {
        assertThrows(IllegalArgumentException::class.java) {
            samples.toTraceCsv(mapOf("generator" to "Test@1"))
        }
    }
}
