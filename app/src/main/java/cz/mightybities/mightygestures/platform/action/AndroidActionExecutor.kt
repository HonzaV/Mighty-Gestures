package cz.mightybities.mightygestures.platform.action

import cz.mightybities.mightygestures.domain.action.ActionContext
import cz.mightybities.mightygestures.domain.action.ActionExecutor
import cz.mightybities.mightygestures.domain.action.ActionFailure
import cz.mightybities.mightygestures.domain.action.ActionResult
import cz.mightybities.mightygestures.domain.model.ActionSpec
import cz.mightybities.mightygestures.domain.time.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Dispatching [ActionExecutor]: one class per action (ADR 0004), routed here by `ActionSpec` type. Framework
 * calls are made on `dispatchers.main` (spec 0001, "Threading / lifecycle": safe on any thread, but main keeps
 * the implementation simple).
 *
 * Contract (PR #4 fix round, additive to ADR 0004): an exception thrown by any sub-executor becomes
 * `ActionResult.Failed(ActionFailure.Unexpected)` here rather than propagating to the caller (the rule
 * engine, a later PR) — a gesture firing at the wrong moment must never crash the host process.
 * `CancellationException` is rethrown so cooperative cancellation still works.
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
            try {
                when (action) {
                    is ActionSpec.LaunchApp -> launchApp.execute(action.packageName, context.keyguardLocked)
                    ActionSpec.ToggleTorch -> toggleTorch.execute()
                    ActionSpec.LockScreen -> lockScreen.execute()
                    ActionSpec.ToggleDoNotDisturb -> toggleDoNotDisturb.execute()
                    is ActionSpec.SetRingerMode -> setRingerMode.execute(action.mode)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                // No exception detail is kept: a message can contain a package name or other identifying
                // detail (AGENTS.md §5).
                ActionResult.Failed(ActionFailure.Unexpected)
            }
        }
}
