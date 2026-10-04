package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotionExemplar.equals] compared its scalar `gravityAtStart*` fields with `==` (IEEE 754: `0f ==
 * -0f`), while [MotionExemplar.hashCode] used `Float.hashCode()` (bit-based: distinguishes `0f`
 * from `-0f`). Two exemplars differing only in the sign of one gravity-at-start axis were therefore
 * `equals` but had different hashes, breaking the equals/hashCode contract. This test first
 * verifies the claim the fix relies on — that the array fields' own `contentEquals`/
 * `contentHashCode` already distinguish signed zero and treat `NaN` as equal to itself — then checks
 * the scalar fields now agree with that.
 */
class MotionExemplarEqualsHashCodeTest {
    @Test
    fun `FloatArray contentEquals and contentHashCode distinguish signed zero and equate NaN (equals must match)`() {
        // The claim the fix relies on, verified directly rather than assumed from memory.
        assertFalse("0f and -0f must be distinct array elements", floatArrayOf(0f).contentEquals(floatArrayOf(-0f)))
        assertTrue(
            "every NaN must be a NaN array element, including itself",
            floatArrayOf(Float.NaN).contentEquals(floatArrayOf(Float.NaN)),
        )
        assertTrue(
            "signed-zero arrays with different contentEquals must also differ in contentHashCode " +
                "(or this test's premise is wrong)",
            floatArrayOf(0f).contentHashCode() != floatArrayOf(-0f).contentHashCode(),
        )
    }

    @Test
    fun `exemplars differing only in the sign of gravityAtStartX are not equal`() {
        val positiveZero = exemplar(gravityAtStartX = 0f)
        val negativeZero = exemplar(gravityAtStartX = -0f)

        assertFalse(
            "0f and -0f gravityAtStartX must make exemplars unequal, consistent with the array " +
                "fields' own signed-zero semantics",
            positiveZero == negativeZero,
        )
    }

    @Test
    fun `two exemplars that are equal have equal hash codes`() {
        val a = exemplar(gravityAtStartX = 1.5f)
        val b = exemplar(gravityAtStartX = 1.5f)

        assertEquals(a, b)
        assertEquals(
            "equals/hashCode contract: equal exemplars must hash the same",
            a.hashCode(),
            b.hashCode(),
        )
    }

    @Test
    fun `exemplars differing only in the sign of gravityAtStartX have different hash codes too`() {
        // Not required by the equals/hashCode contract on its own (unequal objects may collide),
        // but confirms hashCode now actually reflects the sign distinction equals makes, rather
        // than the two overrides merely happening not to violate the contract by coincidence.
        val positiveZero = exemplar(gravityAtStartX = 0f)
        val negativeZero = exemplar(gravityAtStartX = -0f)

        assertTrue(
            "hashCode must distinguish 0f and -0f gravityAtStartX, as it already did before this fix",
            positiveZero.hashCode() != negativeZero.hashCode(),
        )
    }

    private fun exemplar(gravityAtStartX: Float): MotionExemplar =
        MotionExemplar(
            tNanos = longArrayOf(0L, 10_000_000L),
            accX = floatArrayOf(0f, 0f),
            accY = floatArrayOf(0f, 0f),
            accZ = floatArrayOf(9.81f, 9.81f),
            gyroX = floatArrayOf(0f, 0f),
            gyroY = floatArrayOf(0f, 0f),
            gyroZ = floatArrayOf(0f, 0f),
            hasGyro = true,
            gravityAtStartX = gravityAtStartX,
            gravityAtStartY = 0f,
            gravityAtStartZ = 9.81f,
            onsetIndex = 0,
        )
}
