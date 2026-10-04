package cz.mightybities.mightygestures.platform.apps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidLauncherAppCatalogTest {
    private val context = RuntimeEnvironment.getApplication()
    private val catalog = AndroidLauncherAppCatalog(context)

    private fun addLauncherApp(
        packageName: String,
        label: String,
    ) {
        val activityInfo =
            ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                nonLocalizedLabel = label
            }
        val resolveInfo =
            ResolveInfo().apply {
                this.activityInfo = activityInfo
            }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // The List<ResolveInfo> overload *replaces* the list for an equal Intent (it calls
        // setResolveInfosForIntent, verified by reading its bytecode) and skips marking the app installed; the
        // single-ResolveInfo overload both appends (via addResolveInfoForIntentNoDefaults) and sets the
        // installed flag our manually built ApplicationInfo needs to be found by queryIntentActivities. It is
        // deprecated in favor of the (non-appending) List overload, which does not fit repeated calls here.
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
        shadowOf(context.packageManager).addActivityIfNotPresent(
            ComponentName(packageName, "$packageName.MainActivity"),
        )
    }

    @Test
    fun `excludes Mighty Gestures itself`() {
        assertFalse(catalog.launcherApps().any { it.packageName == context.packageName })
    }

    @Test
    fun `includes other launchable apps sorted by label`() {
        addLauncherApp("com.example.zeta", "Zeta App")
        addLauncherApp("com.example.alpha", "Alpha App")

        val apps = catalog.launcherApps().filter { it.packageName.startsWith("com.example.") }

        assertEquals(
            listOf("Alpha App", "Zeta App"),
            apps.map { it.label },
        )
    }
}
