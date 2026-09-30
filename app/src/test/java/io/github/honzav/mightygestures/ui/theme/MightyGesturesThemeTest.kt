package io.github.honzav.mightygestures.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    @Config(sdk = [35])
    fun dynamicDarkScheme_isUsedOnAndroid12Plus() {
        val expected = dynamicDarkColorScheme(RuntimeEnvironment.getApplication())
        val actual = captureScheme(darkTheme = true, dynamicColor = true)
        assertSameColors(expected, actual)
        assertNotEquals(DarkColors.primary, actual.primary)
    }

    @Test
    @Config(sdk = [35])
    fun dynamicLightScheme_isUsedOnAndroid12Plus() {
        val expected = dynamicLightColorScheme(RuntimeEnvironment.getApplication())
        val actual = captureScheme(darkTheme = false, dynamicColor = true)
        assertSameColors(expected, actual)
        assertNotEquals(LightColors.primary, actual.primary)
    }

    private fun assertSameColors(expected: ColorScheme, actual: ColorScheme) {
        assertEquals(expected.primary, actual.primary)
        assertEquals(expected.secondary, actual.secondary)
        assertEquals(expected.tertiary, actual.tertiary)
        assertEquals(expected.background, actual.background)
    }
}
