package cz.mightybities.mightygestures.platform.action

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Flashlight toggle (spec 0001, "Actions"). `CameraManager.setTorchMode` documents no permission requirement,
 * so this needs **no CAMERA permission** (verified, CameraManager#setTorchMode docs). State is tracked via
 * `registerTorchCallback` because the API has no "get current torch mode" query.
 */
class ToggleTorchActionExecutor(
    context: Context,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    // Keyed by camera id, not just the one we last toggled: the torch can be turned on by something other
    // than this executor (e.g. a Quick Settings tile), and we must not assume it starts off.
    private val torchStates = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var torchCameraId: String? = null

    init {
        cameraManager.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(
                    cameraId: String,
                    enabled: Boolean,
                ) {
                    torchStates[cameraId] = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    torchStates[cameraId] = false
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun execute(): ActionResult {
        val cameraId =
            torchCameraId ?: findTorchCameraId() ?: return ActionResult.Failed(ActionFailure.TorchUnavailable)
        torchCameraId = cameraId
        val currentlyOn = torchStates[cameraId] ?: false
        return try {
            cameraManager.setTorchMode(cameraId, !currentlyOn)
            ActionResult.Success
        } catch (expected: CameraAccessException) {
            ActionResult.Failed(ActionFailure.TorchUnavailable)
        }
    }

    /** Prefers a back-facing camera, falling back to any camera that reports a flash unit. */
    private fun findTorchCameraId(): String? {
        val idsWithFlash =
            cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        return idsWithFlash.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: idsWithFlash.firstOrNull()
    }
}
