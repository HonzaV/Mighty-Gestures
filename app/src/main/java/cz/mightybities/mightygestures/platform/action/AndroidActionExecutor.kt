package cz.mightybities.mightygestures.platform.action

import cz.mightybities.mightygestures.domain.action.ActionContext
import cz.mightybities.mightygestures.domain.action.ActionExecutor
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.ActionSpec
import cz.mightybities.mightygestures.domain.time.AppDispatchers
import kotlinx.coroutines.withContext

/**
 * Dispatching [ActionExecutor]: one class per action (ADR 0004), routed here by `ActionSpec` type. Framework
 * calls are made on `dispatchers.main` (spec 0001, "Threading / lifecycle": safe on any thread, but main keeps
 * the implementation simple).
 */
class AndroidActionExecutor(
    private val launchApp: LaunchAppActionExecutor,
    private val toggleTorch: ToggleTorchActionExecutor,
    private val lockScreen: LockScreenActionExecutor,
    private val toggleDoNotDisturb: ToggleDoNotDisturbActionExecutor,
    private val setRingerMode: SetRingerModeActionExecutor,
    private val dispatchers: AppDispatchers,
) : ActionExecutor {
    override suspend fun execute(
        action: ActionSpec,
        context: ActionContext,
    ): ActionResult =
        withContext(dispatchers.main) {
            when (action) {
                is ActionSpec.LaunchApp -> launchApp.execute(action.packageName, context.keyguardLocked)
                ActionSpec.ToggleTorch -> toggleTorch.execute()
                ActionSpec.LockScreen -> lockScreen.execute()
                ActionSpec.ToggleDoNotDisturb -> toggleDoNotDisturb.execute()
                is ActionSpec.SetRingerMode -> setRingerMode.execute(action.mode)
            }
        }
}
