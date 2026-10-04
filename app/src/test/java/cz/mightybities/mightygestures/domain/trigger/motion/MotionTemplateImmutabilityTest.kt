package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Copilot PR #6 round-3 finding: [MotionTemplate] kept the caller's own `exemplars`/
 * `channels` collections (its invariants are only checked at construction, in `init`), so clearing
 * an input `MutableList` after construction emptied an already-accepted template, and removing
 * `GYRO` from an input `MutableSet` desynced `channels` from the exemplars that were validated
 * against it. The fix snapshots both to independent, read-only copies *before* `init` validates
 * them (mirroring [MotionExemplar]'s own "defensively copy and expose as read-only" fix).
 */
class MotionTemplateImmutabilityTest {
    @Test
    fun `clearing the caller's input exemplars list after construction does not change the template`() {
        val inputExemplars = mutableListOf(exemplar(hasGyro = true), exemplar(hasGyro = true))
        val template =
            MotionTemplate(
                exemplars = inputExemplars,
                channels = setOf(SensorKind.ACC, SensorKind.GYRO),
                algorithmVersion = 1,
            )
        val pristineExemplars = template.exemplars.toList()

        inputExemplars.clear()

        assertEquals(
            "clearing the caller's input list after construction must not change the template",
            pristineExemplars,
            template.exemplars,
        )
    }

    @Test
    fun `removing GYRO from the caller's input channels set after construction does not change the template`() {
        val inputChannels = mutableSetOf(SensorKind.ACC, SensorKind.GYRO)
        val template =
            MotionTemplate(
                exemplars = listOf(exemplar(hasGyro = true)),
                channels = inputChannels,
                algorithmVersion = 1,
            )
        val pristineChannels = template.channels.toSet()

        inputChannels.remove(SensorKind.GYRO)

        assertEquals(
            "removing GYRO from the caller's input set after construction must not change the template",
            pristineChannels,
            template.channels,
        )
    }

    @Test
    fun `mutating the caller's input collections after construction does not break the hasGyro invariant`() {
        // The channel-consistency invariant (49e80d1) is checked once, at construction, against the
        // snapshot -- so it must keep holding even after the caller mutates what they originally
        // passed in, not just immediately after the constructor returns.
        val inputExemplars = mutableListOf(exemplar(hasGyro = true))
        val inputChannels = mutableSetOf(SensorKind.ACC, SensorKind.GYRO)
        val template = MotionTemplate(inputExemplars, inputChannels, algorithmVersion = 1)

        inputExemplars.clear()
        inputExemplars += exemplar(hasGyro = false)
        inputChannels.remove(SensorKind.GYRO)

        assertEquals(1, template.exemplars.size)
        assertTrue(template.exemplars.single().hasGyro)
        assertTrue(SensorKind.GYRO in template.channels)
    }

    @Test
    fun `two independently constructed templates with the same values are equal`() {
        fun build() =
            MotionTemplate(
                exemplars = listOf(exemplar(hasGyro = true)),
                channels = setOf(SensorKind.ACC, SensorKind.GYRO),
                algorithmVersion = 1,
            )
        assertEquals(build(), build())
        assertEquals(build().hashCode(), build().hashCode())
    }

    @Test
    fun `templates differing in algorithmVersion are not equal`() {
        val a = MotionTemplate(listOf(exemplar(hasGyro = false)), setOf(SensorKind.ACC), algorithmVersion = 1)
        val b = MotionTemplate(listOf(exemplar(hasGyro = false)), setOf(SensorKind.ACC), algorithmVersion = 2)
        assertTrue(a != b)
    }

    @Test
    fun `equals returns false for a different type and true for the same instance`() {
        val template = MotionTemplate(listOf(exemplar(hasGyro = false)), setOf(SensorKind.ACC), algorithmVersion = 1)
        assertTrue(template.equals(template))
        @Suppress("EqualsIncompatibleType") // deliberately checking cross-type equals() for coverage
        assertTrue(!template.equals("not a template"))
    }

    private fun exemplar(hasGyro: Boolean): MotionExemplar =
        MotionExemplar(
            tNanos = longArrayOf(0L, 10_000_000L),
            accX = floatArrayOf(1f, 2f),
            accY = floatArrayOf(0f, 0f),
            accZ = floatArrayOf(9.81f, 9.81f),
            gyroX = floatArrayOf(0f, 0f),
            gyroY = floatArrayOf(0f, 0f),
            gyroZ = floatArrayOf(0f, 0f),
            hasGyro = hasGyro,
            gravityAtStartX = 0f,
            gravityAtStartY = 0f,
            gravityAtStartZ = 9.81f,
            onsetIndex = 0,
        )
}
