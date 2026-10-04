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
 *
 * `cameraIdList`/`getCameraCharacteristics` (enumeration) and `setTorchMode` can all throw
 * `CameraAccessException` (e.g. a camera-service disconnect); `setTorchMode` additionally throws
 * `IllegalArgumentException` for a cached camera id that has since disappeared. Both are caught: the id is
 * re-resolved exactly once before giving up (AC-A4, no crash).
 *
 * **Must be constructed once as a long-lived singleton** (container start, not per `execute()` call): the
 * torch callback registered in [init] is never unregistered (`CameraManager` has no matching "while this
 * object is alive" lifecycle to hook), so a new instance per call would register an ever-growing set of
 * duplicate callbacks on the real `CameraManager`.
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
        // One retry: a stale cached camera id (IllegalArgumentException) is dropped and re-resolved exactly
        // once; a second failure of any kind gives up.
        var attemptsLeft = 2
        var result: ActionResult? = null
        while (result == null && attemptsLeft > 0) {
            attemptsLeft--
            result = attemptToggle()
        }
        return result ?: ActionResult.Failed(ActionFailure.TorchUnavailable)
    }

    /** Null means "stale camera id, drop it and let the caller retry once with a fresh lookup". */
    private fun attemptToggle(): ActionResult? =
        try {
            val cameraId =
                torchCameraId ?: findTorchCameraId() ?: return ActionResult.Failed(ActionFailure.TorchUnavailable)
            torchCameraId = cameraId
            val currentlyOn = torchStates[cameraId] ?: false
            cameraManager.setTorchMode(cameraId, !currentlyOn)
            // Optimistic: onTorchModeChanged is the source of truth once it arrives, but on a real device it
            // is asynchronous, so the very next execute() must not read a stale pre-call state.
            torchStates[cameraId] = !currentlyOn
            ActionResult.Success
        } catch (ignored: CameraAccessException) {
            ActionResult.Failed(ActionFailure.TorchUnavailable)
        } catch (ignored: IllegalArgumentException) {
            torchCameraId = null
            null
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
