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
 * verifies the claim the fix relies on — that [MotionExemplar]'s array-backed `List<Float>`
 * properties (`FloatArray.asList()`, boxed `Float.equals`/`hashCode`) already distinguish signed
 * zero and treat `NaN` as equal to itself, same as the `contentEquals`/`contentHashCode` they
 * replaced (milestone 1 fix round, item "defensively copy and expose MotionExemplar as read-only")
 * — then checks the scalar fields, and a whole exemplar, agree with that.
 */
class MotionExemplarEqualsHashCodeTest {
    @Test
    fun `asList Float equality distinguishes signed zero and equates NaN (equals must match)`() {
        // The claim the fix relies on, verified directly rather than assumed from memory.
        assertFalse(
            "0f and -0f must be distinct List<Float> elements",
            floatArrayOf(0f).asList() == floatArrayOf(-0f).asList(),
        )
        assertTrue(
            "every NaN must be a NaN List<Float> element, including itself",
            floatArrayOf(Float.NaN).asList() == floatArrayOf(Float.NaN).asList(),
        )
        assertTrue(
            "signed-zero lists that differ by equals must also differ in hashCode " +
                "(or this test's premise is wrong)",
            floatArrayOf(0f).asList().hashCode() != floatArrayOf(-0f).asList().hashCode(),
        )
    }

    @Test
    fun `exemplars differing only by signed zero in one accX element are not equal`() {
        val positiveZero = exemplarWithAccX(0f)
        val negativeZero = exemplarWithAccX(-0f)

        assertFalse(
            "0f and -0f in one accX element must make exemplars unequal, consistent with " +
                "List<Float>'s own signed-zero semantics",
            positiveZero == negativeZero,
        )
    }

    @Test
    fun `exemplars with a NaN accX element are equal to an identical twin and hash the same`() {
        val a = exemplarWithAccX(Float.NaN)
        val b = exemplarWithAccX(Float.NaN)

        assertEquals("a NaN accX element must equal itself across exemplars", a, b)
        assertEquals(
            "equals/hashCode contract: equal exemplars must hash the same, even with NaN present",
            a.hashCode(),
            b.hashCode(),
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

    private fun exemplarWithAccX(accX0: Float): MotionExemplar =
        MotionExemplar(
            tNanos = longArrayOf(0L, 10_000_000L),
            accX = floatArrayOf(accX0, 0f),
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
