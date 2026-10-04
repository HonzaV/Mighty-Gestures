package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Copilot PR #6 round-2 finding: [MotionExemplar]'s public array properties used to be
 * writable and the constructor kept the caller's own arrays, contradicting [MotionExemplar.fromSegment]'s
 * "independent, rebased, immutable exemplar" contract. [CaptureSession.Recorded.exemplar] is the
 * pending recording while [CaptureSession] separately caches that same segment's processed/matched
 * data; mutating the returned arrays would silently desynchronize the two. The fix: the constructor
 * defensively copies every input array, and the public properties are read-only `List` views over
 * those private copies.
 */
class MotionExemplarImmutabilityTest {
    @Test
    fun `mutating the caller's input array after construction does not change the exemplar`() {
        val tNanos = longArrayOf(0L, 10_000_000L, 20_000_000L)
        val accX = floatArrayOf(1f, 2f, 3f)
        val accY = floatArrayOf(0f, 0f, 0f)
        val accZ = floatArrayOf(9.81f, 9.81f, 9.81f)
        val gyroX = floatArrayOf(0f, 0f, 0f)
        val gyroY = floatArrayOf(0f, 0f, 0f)
        val gyroZ = floatArrayOf(0f, 0f, 0f)

        val exemplar =
            MotionExemplar(
                tNanos = tNanos,
                accX = accX,
                accY = accY,
                accZ = accZ,
                gyroX = gyroX,
                gyroY = gyroY,
                gyroZ = gyroZ,
                hasGyro = true,
                gravityAtStartX = 0f,
                gravityAtStartY = 0f,
                gravityAtStartZ = 9.81f,
                onsetIndex = 0,
            )
        val pristineAccX = exemplar.accX.toList()
        val pristineTNanos = exemplar.tNanos.toList()

        // Mutate the caller's own arrays after handing them to the constructor.
        accX[1] = 999f
        tNanos[1] = 999_999_999L

        assertEquals(
            "mutating the caller's array after construction must not change the exemplar",
            pristineAccX,
            exemplar.accX,
        )
        assertEquals(
            "mutating the caller's array after construction must not change the exemplar",
            pristineTNanos,
            exemplar.tNanos,
        )
    }

    @Test
    fun `the exemplar's array properties cannot be mutated through the returned List`() {
        val exemplar = exemplar()

        assertTrue(
            "accX must not be a MutableList (it must be impossible to mutate the exemplar " +
                "through the List it returns)",
            exemplar.accX !is MutableList<*>,
        )
        assertTrue(
            "tNanos must not be a MutableList",
            exemplar.tNanos !is MutableList<*>,
        )
    }

    @Test
    fun `two independently constructed exemplars with the same values are equal`() {
        assertEquals(exemplar(), exemplar())
    }

    private fun exemplar(): MotionExemplar =
        MotionExemplar(
            tNanos = longArrayOf(0L, 10_000_000L),
            accX = floatArrayOf(1f, 2f),
            accY = floatArrayOf(0f, 0f),
            accZ = floatArrayOf(9.81f, 9.81f),
            gyroX = floatArrayOf(0f, 0f),
            gyroY = floatArrayOf(0f, 0f),
            gyroZ = floatArrayOf(0f, 0f),
            hasGyro = true,
            gravityAtStartX = 0f,
            gravityAtStartY = 0f,
            gravityAtStartZ = 9.81f,
            onsetIndex = 0,
        )
}
