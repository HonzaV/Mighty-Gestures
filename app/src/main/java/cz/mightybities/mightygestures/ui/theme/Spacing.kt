package cz.mightybities.mightygestures.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens. Material 3 has no spacing scale, so layouts read these instead of hard-coding dp values,
 * keeping spacing consistent and adjustable in one place.
 */
@Immutable
data class Spacing(
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
)

internal val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Spacing tokens of the current [MightyGesturesTheme]. */
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
