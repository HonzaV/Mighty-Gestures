package cz.mightybities.mightygestures.domain.trigger.motion

import cz.mightybities.mightygestures.motion.synthetic.GesturePrimitives
import cz.mightybities.mightygestures.motion.synthetic.NoiseSource
import cz.mightybities.mightygestures.motion.synthetic.PerformerVariation
import cz.mightybities.mightygestures.motion.synthetic.Quaternion
import cz.mightybities.mightygestures.motion.synthetic.SensorModel
import cz.mightybities.mightygestures.motion.synthetic.toTraceCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** ADR 0008 "Trace format": header, mandatory `# source=` metadata, one sample per line. */
class MotionTraceCsvTest {
    @Test
    fun `parses metadata and samples`() {
        val text =
            """
            # source=synthetic; generator=Test@1; seed=42
            timestamp_ns,sensor,x,y,z
            0,ACC,0.0,9.81,0.0
            5000000,GYRO,0.1,0.0,0.0
            """.trimIndent()
        val trace = MotionTraceCsv.parse(text)
        assertEquals("synthetic", trace.source)
        assertEquals("Test@1", trace.metadata["generator"])
        assertEquals("42", trace.metadata["seed"])
        assertEquals(2, trace.samples.size)
        assertEquals(SensorKind.ACC, trace.samples[0].kind)
        assertEquals(9.81f, trace.samples[0].y, 1e-6f)
        assertEquals(SensorKind.GYRO, trace.samples[1].kind)
    }

    @Test
    fun `rejects a trace with no source metadata`() {
        val text =
            """
            # generator=Test@1
            timestamp_ns,sensor,x,y,z
            0,ACC,0.0,9.81,0.0
            """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { MotionTraceCsv.parse(text) }
    }

    @Test
    fun `rejects a missing header`() {
        val text =
            """
            # source=synthetic
            0,ACC,0.0,9.81,0.0
            """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { MotionTraceCsv.parse(text) }
    }

    @Test
    fun `rejects a malformed sample line`() {
        val text =
            """
            # source=synthetic
            timestamp_ns,sensor,x,y,z
            0,ACC,0.0,9.81
            """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { MotionTraceCsv.parse(text) }
    }

    @Test
    fun `rejects an unknown sensor kind`() {
        val text =
            """
            # source=synthetic
            timestamp_ns,sensor,x,y,z
            0,MAGNETOMETER,0.0,9.81,0.0
            """.trimIndent()
        assertThrows(IllegalStateException::class.java) { MotionTraceCsv.parse(text) }
    }

    @Test
    fun `round-trips a generator-produced trace through toTraceCsv and parse`() {
        val samples =
            SensorModel(rateHz = 50.0).generate(
                spec = GesturePrimitives.chop(PerformerVariation.NONE),
                initialOrientation = Quaternion.IDENTITY,
                hasGyro = true,
                noise = NoiseSource(seed = 3),
            )
        val csv =
            samples.toTraceCsv(
                mapOf(
                    MotionTraceCsv.METADATA_SOURCE_KEY to "synthetic",
                    "generator" to "SensorModel@1",
                    "seed" to "3",
                    "model" to "chop",
                ),
            )
        val parsed = MotionTraceCsv.parse(csv)
        assertEquals("synthetic", parsed.source)
        assertEquals(samples.size, parsed.samples.size)
        for (i in samples.indices) {
            assertEquals(samples[i].kind, parsed.samples[i].kind)
            assertEquals(samples[i].timestampNanos, parsed.samples[i].timestampNanos)
            assertEquals(samples[i].x, parsed.samples[i].x, 1e-6f)
            assertEquals(samples[i].y, parsed.samples[i].y, 1e-6f)
            assertEquals(samples[i].z, parsed.samples[i].z, 1e-6f)
        }
    }
}
