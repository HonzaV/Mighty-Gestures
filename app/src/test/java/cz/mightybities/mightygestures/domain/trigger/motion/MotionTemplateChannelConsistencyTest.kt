package cz.mightybities.mightygestures.domain.trigger.motion

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * GitHub Copilot PR #6 round-2 finding: [MotionTemplate] used to accept `channels = {ACC}` with a
 * `hasGyro = true` exemplar, or a mix of gyro / non-gyro exemplars, silently desyncing the
 * declared channel set from what the exemplars actually recorded. The fix requires every
 * exemplar's [MotionExemplar.hasGyro] to equal `SensorKind.GYRO in channels`.
 */
class MotionTemplateChannelConsistencyTest {
    @Test
    fun `channels = ACC with a hasGyro exemplar is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionTemplate(
                exemplars = listOf(exemplar(hasGyro = true)),
                channels = setOf(SensorKind.ACC),
                algorithmVersion = 1,
            )
        }
    }

    @Test
    fun `channels = ACC + GYRO with a non-gyro exemplar is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionTemplate(
                exemplars = listOf(exemplar(hasGyro = false)),
                channels = setOf(SensorKind.ACC, SensorKind.GYRO),
                algorithmVersion = 1,
            )
        }
    }

    @Test
    fun `a mix of gyro and non-gyro exemplars is rejected regardless of the declared channels`() {
        val mixed = listOf(exemplar(hasGyro = true), exemplar(hasGyro = false))

        assertThrows(IllegalArgumentException::class.java) {
            MotionTemplate(exemplars = mixed, channels = setOf(SensorKind.ACC, SensorKind.GYRO), algorithmVersion = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionTemplate(exemplars = mixed, channels = setOf(SensorKind.ACC), algorithmVersion = 1)
        }
    }

    @Test
    fun `channels = ACC with only non-gyro exemplars is accepted`() {
        MotionTemplate(
            exemplars = listOf(exemplar(hasGyro = false), exemplar(hasGyro = false)),
            channels = setOf(SensorKind.ACC),
            algorithmVersion = 1,
        )
    }

    @Test
    fun `channels = ACC + GYRO with only gyro exemplars is accepted`() {
        MotionTemplate(
            exemplars = listOf(exemplar(hasGyro = true), exemplar(hasGyro = true)),
            channels = setOf(SensorKind.ACC, SensorKind.GYRO),
            algorithmVersion = 1,
        )
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
