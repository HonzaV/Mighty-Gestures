package cz.mightybities.mightygestures.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

internal val DarkColors =
    darkColorScheme(
        primary = Yellow80,
        secondary = YellowGrey80,
        tertiary = Amber80,
    )

internal val LightColors =
    lightColorScheme(
        primary = Yellow40,
        secondary = YellowGrey40,
        tertiary = Amber40,
    )

/**
 * Material 3 theme. Uses Material You dynamic colors when enabled (always available on minSdk 35);
 * they come from the platform, no Google Play services involved.
 */
@Composable
fun MightyGesturesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme =
        when {
            dynamicColor -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
