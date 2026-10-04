package cz.mightybities.mightygestures.platform.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** One launchable app, as shown in the app picker (spec 0001, "Action" create step). */
data class LauncherApp(
    val packageName: String,
    val label: String,
)

/**
 * Catalog of launchable apps. Backed by the `<queries>` MAIN/LAUNCHER intent declared in the manifest
 * (AC-C9); never `QUERY_ALL_PACKAGES`.
 */
interface LauncherAppCatalog {
    /** Launchable apps, excluding Mighty Gestures itself, sorted by label. */
    fun launcherApps(): List<LauncherApp>
}

class AndroidLauncherAppCatalog(
    private val context: Context,
) : LauncherAppCatalog {
    override fun launcherApps(): List<LauncherApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved =
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        return resolved
            .asSequence()
            .map { resolveInfo ->
                resolveInfo.activityInfo.packageName to
                    resolveInfo.loadLabel(packageManager).toString()
            }.filter { (packageName, _) -> packageName != context.packageName }
            .distinctBy { (packageName, _) -> packageName }
            .map { (packageName, label) -> LauncherApp(packageName, label) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
