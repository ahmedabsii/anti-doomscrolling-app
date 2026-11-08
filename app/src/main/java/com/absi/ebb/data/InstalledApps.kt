package com.absi.ebb.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_PX = 144
private const val ICON_CACHE_BYTES = 24 * 1024 * 1024

data class InstalledApp(val packageName: String, val label: String, val isProtected: Boolean)

/** Every app the phone can launch, with icons kept in a memory cache. */
class InstalledApps(private val context: Context) {

    private val packages: PackageManager = context.packageManager
    private val icons = object : LruCache<String, ImageBitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Two at a time, so a fast scroll on a slow phone can't bury the screen in icon work.
    private val iconWork = Dispatchers.IO.limitedParallelism(2)

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

    /** Draws the icon in the background if it isn't cached yet. */
    suspend fun icon(packageName: String): ImageBitmap? = cachedIcon(packageName) ?: withContext(iconWork) {
        cachedIcon(packageName) ?: runCatching {
            packages.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()?.also { icons.put(packageName, it) }
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
