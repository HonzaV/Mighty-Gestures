package cz.mightybities.mightygestures.platform.action

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
class ToggleTorchActionExecutorTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun addTorchCamera(
        id: String,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    ) {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowCharacteristics = shadowOf(characteristics)
        shadowCharacteristics.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowCharacteristics.set(CameraCharacteristics.LENS_FACING, lensFacing)
        val cameraManager = context.getSystemService(CameraManager::class.java)
        shadowOf(cameraManager).addCamera(id, characteristics)
    }

    @Test
    fun `toggles the torch on then off`() {
        addTorchCamera("0")
        val executor = ToggleTorchActionExecutor(context)
        val cameraManager = context.getSystemService(CameraManager::class.java)

        assertEquals(ActionResult.Success, executor.execute())
        assertEquals(true, shadowOf(cameraManager).getTorchMode("0"))

        assertEquals(ActionResult.Success, executor.execute())
        assertEquals(false, shadowOf(cameraManager).getTorchMode("0"))
    }

    @Test
    fun `reflects torch state changed externally, e g from Quick Settings`() {
        addTorchCamera("0")
        val executor = ToggleTorchActionExecutor(context)
        val cameraManager = context.getSystemService(CameraManager::class.java)

        // Something other than this executor (e.g. a Quick Settings tile) turns the torch on first. The
        // executor has never called execute() yet, so it must not assume the torch starts off.
        cameraManager.setTorchMode("0", true)

        executor.execute()

        assertEquals(false, shadowOf(cameraManager).getTorchMode("0"))
    }

    @Test
    fun `prefers a back-facing camera over a front-facing one`() {
        addTorchCamera("front", lensFacing = CameraCharacteristics.LENS_FACING_FRONT)
        addTorchCamera("back", lensFacing = CameraCharacteristics.LENS_FACING_BACK)
        val executor = ToggleTorchActionExecutor(context)
        val cameraManager = context.getSystemService(CameraManager::class.java)

        executor.execute()

        // The front camera's torch was never toggled, so its shadow state is irrelevant; only "back" matters.
        assertEquals(true, shadowOf(cameraManager).getTorchMode("back"))
    }

    @Test
    fun `no camera reports a flash unit fails as unavailable`() {
        val executor = ToggleTorchActionExecutor(context)

        assertEquals(ActionResult.Failed(ActionFailure.TorchUnavailable), executor.execute())
    }

    @Test
    @Config(shadows = [ThrowingCameraManagerShadow::class])
    fun `a CameraAccessException while toggling fails as unavailable, not a crash`() {
        val executor = ToggleTorchActionExecutor(context)

        assertEquals(ActionResult.Failed(ActionFailure.TorchUnavailable), executor.execute())
    }

    /**
     * Simulates `setTorchMode` throwing `CameraAccessException` (e.g. `CAMERA_IN_USE`, AC-A4). The real
     * `ShadowCameraManager.setTorchMode` only throws `IllegalArgumentException` for an unregistered camera id
     * (verified by reading its bytecode), not `CameraAccessException`, so this dedicated shadow stands in for
     * the one framework call under test (docs/engineering/testing.md).
     */
    @Implements(CameraManager::class)
    class ThrowingCameraManagerShadow {
        @Implementation
        fun getCameraIdList(): Array<String> = arrayOf(CAMERA_ID)

        // Unused parameters below exist only to match CameraManager's real method signatures, which Robolectric
        // needs for reflective dispatch to this shadow; the test only cares about setTorchMode's failure path.
        @Suppress("UnusedParameter")
        @Implementation
        fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            val shadowCharacteristics = shadowOf(characteristics)
            shadowCharacteristics.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
            shadowCharacteristics.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            return characteristics
        }

        @Suppress("UnusedParameter")
        @Implementation
        fun registerTorchCallback(
            callback: CameraManager.TorchCallback,
            handler: android.os.Handler?,
        ) {
            // No-op: this test only exercises setTorchMode's failure path.
        }

        @Suppress("UnusedParameter")
        @Implementation
        fun setTorchMode(
            cameraId: String,
            enabled: Boolean,
        ): Unit = throw CameraAccessException(CameraAccessException.CAMERA_IN_USE)

        companion object {
            private const val CAMERA_ID = "0"
        }
    }
}
