package com.absi.ebb

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import com.absi.ebb.data.EbbSettings
import com.absi.ebb.data.EbbStore
import com.absi.ebb.data.InstalledApps
import com.absi.ebb.progress.ProgressRules
import com.absi.ebb.session.GroupState
import com.absi.ebb.session.StableClock
import com.absi.ebb.ui.releaseSeaArt
import com.absi.ebb.watcher.AppWatcherService
import com.absi.ebb.watcher.SessionNotifications
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.TimeZone

class EbbApp : Application() {
    lateinit var store: EbbStore
        private set
    lateinit var settings: EbbSettings
        private set
    lateinit var apps: InstalledApps
        private set

    override fun onCreate() {
        super.onCreate()
        store = EbbStore(this)
        settings = EbbSettings(this)
        apps = InstalledApps(this)
        SessionNotifications.createChannels(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Out of sight, Ebb keeps only its small watcher. A lean background app is less likely to be killed.
        if (level >= TRIM_MEMORY_UI_HIDDEN) releaseSeaArt()
    }

    /** The timeline session rules run on, which phone clock changes can't move. */
    fun stableNow(): Long {
        val boots = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        val elapsed = SystemClock.elapsedRealtime()
        val anchor = StableClock.anchor(settings.bootAnchor, boots, System.currentTimeMillis(), elapsed)
        if (anchor != settings.bootAnchor) settings.bootAnchor = anchor
        return StableClock.now(anchor, elapsed)
    }

    /** Emergency tokens left today. They come back at local midnight, and the stable clock keeps clock changes from bringing them back early. */
    fun emergencyTokensLeft(): Int =
        if (settings.emergencyTokenDay == tokenDay()) (EMERGENCY_TOKENS_A_DAY - settings.emergencyTokensUsed).coerceAtLeast(0) else EMERGENCY_TOKENS_A_DAY

    fun useEmergencyToken(): Boolean {
        if (emergencyTokensLeft() == 0) return false
        val day = tokenDay()
        settings.emergencyTokensUsed = if (settings.emergencyTokenDay == day) settings.emergencyTokensUsed + 1 else 1
        settings.emergencyTokenDay = day
        return true
    }

    private fun tokenDay(): Long = ProgressRules.dayOf(stableNow(), zoneOffsetMs())

    /** Today by the phone's clock, the day that usage and progress are filed under. */
    fun today(): Long = ProgressRules.dayOf(System.currentTimeMillis(), zoneOffsetMs())

    fun zoneOffsetMs(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
}

/** How many emergency tokens each day brings. */
const val EMERGENCY_TOKENS_A_DAY = 2

/** The latest session state, shared from the watcher to any open screen. */
object LiveSession {
    val states = MutableStateFlow<Map<Long, GroupState>>(emptyMap())

    /** Groups a cover screen asks the watcher to open with one of today's emergency tokens. */
    val emergencyRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
}

val Context.ebb: EbbApp get() = applicationContext as EbbApp

fun Context.isWatcherEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    if (TextUtils.isEmpty(enabled)) return false
    val watcher = ComponentName(this, AppWatcherService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == watcher }
}
