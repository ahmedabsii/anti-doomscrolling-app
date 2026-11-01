package com.absi.ebb.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(val packageName: String, val label: String, val isProtected: Boolean)

/** Every app the phone can launch, with icons kept in a memory cache. */
class InstalledApps(private val context: Context) {

    private val packages: PackageManager = context.packageManager
    private val icons = LruCache<String, ImageBitmap>(96)

    /** The list from the last [load], so screens can show it at once. */
    @Volatile
    var known: List<InstalledApp> = emptyList()
        private set

    /** Reads every app's name, which takes long enough to freeze the screen. Call it off the main thread. */
    fun load(): List<InstalledApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protectedApps = protectedPackages()
        val apps = packages.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packages).toString() }
            .distinctBy { it.first }
            .map { (name, label) -> InstalledApp(name, label, name in protectedApps) }
            .sortedBy { it.label.lowercase() }
        if (apps != known) known = apps
        return known
    }

    fun label(packageName: String): String = runCatching {
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun cachedIcon(packageName: String): ImageBitmap? = icons.get(packageName)

    /** Draws the icon if it isn't cached yet. Call it off the main thread. */
    fun icon(packageName: String): ImageBitmap? {
        cachedIcon(packageName)?.let { return it }
        val icon = runCatching {
            packages.getApplicationIcon(packageName).toBitmap(144, 144).asImageBitmap()
        }.getOrNull()
        if (icon != null) icons.put(packageName, icon)
        return icon
    }

    fun homePackage(): String? =
        packages.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName

    /** Blocking these could lock someone out of calling for help or fixing their phone. */
    private fun protectedPackages(): Set<String> = setOfNotNull(
        context.packageName,
        "com.android.settings",
        homePackage(),
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage,
    )
}
