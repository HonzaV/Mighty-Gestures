package cz.mightybities.mightygestures

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import cz.mightybities.mightygestures.platform.action.LaunchOverKeyguardActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Reads the merged manifest back through Robolectric's `PackageManager`, rather than re-deriving the same
 * `<queries>` intent the catalog itself uses (as `AndroidLauncherAppCatalogTest`'s "does not require
 * QUERY_ALL_PACKAGES" does): that test would still pass even if `QUERY_ALL_PACKAGES` were declared alongside
 * the `<queries>` entry, because it never inspects `requestedPermissions`. AC-A4, AC-C9, AC-P1, AC-P2.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `manifest declares none of CAMERA, QUERY_ALL_PACKAGES or INTERNET`() {
        val packageInfo =
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions?.toList().orEmpty()

        assertFalse(
            "CAMERA permission declared (AC-A4: torch needs none)",
            permissions.contains("android.permission.CAMERA"),
        )
        assertFalse(
            "QUERY_ALL_PACKAGES declared (AC-C9: package visibility must come only from <queries>)",
            permissions.contains("android.permission.QUERY_ALL_PACKAGES"),
        )
        assertFalse(
            "INTERNET permission declared (AGENTS.md #2: no network capability)",
            permissions.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `the trampoline activity is declared not exported`() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, LaunchOverKeyguardActivity::class.java),
                0,
            )

        assertFalse(activityInfo.exported)
    }

    @Test
    fun `the trampoline is not reachable through MAIN LAUNCHER`() {
        // A sanity check that the trampoline cannot be started from the launcher (it must only ever be
        // started by LaunchAppActionExecutor), independent of its exported flag.
        val launcherIntent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.packageName)
        val resolved =
            context.packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )

        assertNull(resolved.find { it.activityInfo.name == LaunchOverKeyguardActivity::class.java.name })
    }
}
