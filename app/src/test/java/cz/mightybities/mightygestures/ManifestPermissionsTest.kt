package cz.mightybities.mightygestures

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import cz.mightybities.mightygestures.platform.action.LaunchOverKeyguardActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the merged manifest back through Robolectric's `PackageManager`, rather than re-deriving the same
 * `<queries>` intent `AndroidLauncherAppCatalog` itself uses: a test built on that same intent would still
 * pass even if `QUERY_ALL_PACKAGES` were declared alongside the `<queries>` entry, because it would never
 * inspect `requestedPermissions`. AC-A2, AC-A4, AC-C9, AC-P1, AC-P2.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `manifest declares exactly the AC-P1 permission allowlist`() {
        // The exact allowlist, not just "none of the forbidden ones": catches an unexpected *addition* too
        // (e.g. a future dependency quietly declaring its own permission), not only the specific names this
        // spec calls out.
        val packageInfo =
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()

        assertEquals(
            setOf(
                "android.permission.ACCESS_NOTIFICATION_POLICY",
                "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
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
    fun `the trampoline activity excludes itself from recents and has its own task`() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                ComponentName(context, LaunchOverKeyguardActivity::class.java),
                0,
            )

        assertTrue(
            "expected FLAG_EXCLUDE_FROM_RECENTS",
            activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0,
        )
        // android:taskAffinity="" parses to a null ActivityInfo.taskAffinity, not an empty string (verified
        // empirically against this manifest): null means "no affinity", giving the trampoline its own task.
        assertNull(activityInfo.taskAffinity)
    }

    @Test
    fun `the trampoline activity is declared showWhenLocked`() {
        // android:showWhenLocked has no corresponding public ActivityInfo flag (verified: absent from the
        // compileSdk android.jar's ActivityInfo), so PackageManager cannot confirm it; read the manifest
        // source directly instead (AC-A2). Parsed as XML, not substringAfter("LaunchOverKeyguardActivity"):
        // that would match the class name inside the manifest's own XML *comment* about the trampoline
        // first, before ever reaching the real <activity> element.
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = documentBuilderFactory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val trampolineElement =
            (0 until activities.length)
                .map { activities.item(it) as Element }
                .first { it.getAttributeNS(ANDROID_NAMESPACE, "name").endsWith(".LaunchOverKeyguardActivity") }

        assertEquals("true", trampolineElement.getAttributeNS(ANDROID_NAMESPACE, "showWhenLocked"))
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

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
