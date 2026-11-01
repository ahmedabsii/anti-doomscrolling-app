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
import com.absi.ebb.session.GroupState
import com.absi.ebb.session.StableClock
import com.absi.ebb.watcher.AppWatcherService
import com.absi.ebb.watcher.SessionNotifications
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

    /** The timeline session rules run on, which phone clock changes can't move. */
    fun stableNow(): Long {
        val boots = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        val elapsed = SystemClock.elapsedRealtime()
        val anchor = StableClock.anchor(settings.bootAnchor, boots, System.currentTimeMillis(), elapsed)
        if (anchor != settings.bootAnchor) settings.bootAnchor = anchor
        return StableClock.now(anchor, elapsed)
    }

    fun zoneOffsetMs(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
}

/** The latest session state, shared from the watcher to any open screen. */
object LiveSession {
    val states = MutableStateFlow<Map<Long, GroupState>>(emptyMap())
}

val Context.ebb: EbbApp get() = applicationContext as EbbApp

fun Context.isWatcherEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    if (TextUtils.isEmpty(enabled)) return false
    val watcher = ComponentName(this, AppWatcherService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == watcher }
}
