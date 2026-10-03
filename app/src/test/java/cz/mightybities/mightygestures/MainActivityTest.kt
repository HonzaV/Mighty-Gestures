package cz.mightybities.mightygestures

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import cz.mightybities.mightygestures.ui.HomeScreenTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_showsHomeScreen() {
        val activity = composeRule.activity
        composeRule.onNodeWithText(activity.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeScreenTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeScreenTags.SUBTITLE).assertIsDisplayed()
    }

    /**
     * Security review (PR #4 fix round): `MainActivity` is reachable via `AUTOMATIC_ZEN_RULE` (decision 8)
     * with no extras trusted. Starting it with that action plus arbitrary extras and data must be a normal
     * start with no side effects — not a crash, and nothing acted on from the untrusted intent.
     */
    @Test
    fun `starting via the zen-rule action with arbitrary extras and data has no side effects`() {
        val context = RuntimeEnvironment.getApplication()
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = "android.app.action.AUTOMATIC_ZEN_RULE"
                data = Uri.parse("content://evil.example/payload")
                putExtra("android.service.notification.extra.RULE_ID", "not-ours")
                putExtra("arbitrary_extra", "untrusted-value")
            }

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
