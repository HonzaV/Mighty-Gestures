package io.github.honzav.mightygestures.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MightyGesturesThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun captureScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
        var scheme: ColorScheme? = null
        composeRule.setContent {
            MightyGesturesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                scheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(scheme)
    }

    @Test
    fun staticLightScheme_isUsedWhenDynamicColorDisabled() {
        assertEquals(LightColors.primary, captureScheme(darkTheme = false, dynamicColor = false).primary)
    }

    @Test
    fun staticDarkScheme_isUsedWhenDynamicColorDisabled() {
        assertEquals(DarkColors.primary, captureScheme(darkTheme = true, dynamicColor = false).primary)
    }

    @Test
    @Config(sdk = [30])
    fun staticScheme_isUsedBelowAndroid12EvenWithDynamicColor() {
        assertEquals(LightColors.primary, captureScheme(darkTheme = false, dynamicColor = true).primary)
    }

    @Test
    fun dynamicDarkScheme_isUsedOnAndroid12Plus() {
        assertNotNull(captureScheme(darkTheme = true, dynamicColor = true))
    }

    @Test
    fun dynamicLightScheme_isUsedOnAndroid12Plus() {
        assertNotNull(captureScheme(darkTheme = false, dynamicColor = true))
    }
}
