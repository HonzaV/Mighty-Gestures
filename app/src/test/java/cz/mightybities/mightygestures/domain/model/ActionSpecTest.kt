package cz.mightybities.mightygestures.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSpecTest {
    @Test
    fun `launch app needs no special access`() {
        assertEquals(emptySet<SpecialAccess>(), ActionSpec.LaunchApp("pkg", "Label").requiredAccess())
    }

    @Test
    fun `toggle torch needs no special access`() {
        assertEquals(emptySet<SpecialAccess>(), ActionSpec.ToggleTorch.requiredAccess())
    }

    @Test
    fun `lock screen needs the accessibility service`() {
        assertEquals(setOf(SpecialAccess.ACCESSIBILITY_SERVICE), ActionSpec.LockScreen.requiredAccess())
    }

    @Test
    fun `toggle do not disturb needs notification policy access`() {
        assertEquals(setOf(SpecialAccess.NOTIFICATION_POLICY), ActionSpec.ToggleDoNotDisturb.requiredAccess())
    }

    @Test
    fun `set ringer mode needs notification policy access`() {
        assertEquals(
            setOf(SpecialAccess.NOTIFICATION_POLICY),
            ActionSpec.SetRingerMode(RingerMode.SILENT).requiredAccess(),
        )
    }
}
