package cz.mightybities.mightygestures.domain.time

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected coroutine dispatchers (AGENTS.md §4: never hard-code `Dispatchers.*` in logic, so tests are
 * deterministic). `main` is used by [cz.mightybities.mightygestures.domain.action.ActionExecutor]
 * implementations: `startActivity`, `performGlobalAction`, `CameraManager`, `NotificationManager` and
 * `AudioManager` calls are *inferred* to be safe on any thread, but running them on `main` keeps the
 * implementation simple (spec 0001, "Threading / lifecycle").
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
)
