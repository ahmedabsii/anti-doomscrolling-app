package com.absi.ebb.watcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.absi.ebb.LiveSession
import com.absi.ebb.cooldown.CooldownActivity
import com.absi.ebb.data.Group
import com.absi.ebb.ebb
import com.absi.ebb.progress.ProgressRules
import com.absi.ebb.session.SessionAction
import com.absi.ebb.session.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reports which app is open to the session engine, and carries out what the engine decides. */
class AppWatcherService : AccessibilityService() {

    private val app get() = ebb
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private var engine: SessionEngine? = null
    private var groups = emptyList<Group>()
    private var foreground: String? = null
    private var homePackage: String? = null
    private var screenInUse = true
    private var lastRealApp: String? = null
    private var floatingOwner: String? = null
    private var floatingAttempts = 0
    private val floatingCheck = Runnable {
        closeBlockedFloatingWindow()
        watchFloatingWindows()
    }
    private var chatsShown: Boolean? = null
    private var arrivedInInstagram = false
    private var sentToChatsAt = 0L
    private val instagramCheck = Runnable { checkInstagram() }

    private val wake = Runnable { step { it.onTimerDue(now()) } }
    private val modeWatcher by lazy {
        AudioManager.OnModeChangedListener { syncCall() }
    }

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> setScreen(false)
                Intent.ACTION_SCREEN_ON -> if (!isLocked()) phoneInUse()
                Intent.ACTION_USER_PRESENT -> phoneInUse()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
        homePackage = app.apps.homePackage()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // USER_PRESENT comes from System UI rather than the system itself, so a non-exported
        // receiver never gets it. These broadcasts are protected, so other apps can't fake them.
        ContextCompat.registerReceiver(this, screenEvents, filter, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnModeChangedListener(mainExecutor, modeWatcher)
        }
        // No event announces an app that was already open when the watcher started, such as after an update.
        rootInActiveWindow?.packageName?.toString()?.takeIf(::isFullApp)?.let {
            foreground = it
            lastRealApp = it.takeIf { open -> open != packageName && open != homePackage }
            Log.i(TAG, "already open=$it")
        }
        loadEngine()
        scope.launch { app.store.groupRevision.drop(1).collect { reloadGroups() } }
        scope.launch { LiveSession.emergencyRequests.collect(::openForEmergency) }
        reviewYesterday()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            closeBlockedFloatingWindow()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // A window change while the phone is unlocked proves it's in use, even if a broadcast was missed.
        if (!screenInUse && !isLocked() && isPowered()) phoneInUse()
        if (packageName == foreground || !isFullApp(packageName)) return
        // Apps behind the screen send this too, such as the launcher refreshing while another app is open.
        // Only the window in front counts, or the open app would look closed and run untimed.
        val front = rootInActiveWindow?.packageName?.toString()
        if (front != null && front != packageName) {
            Log.i(TAG, "ignored $packageName behind $front")
            return
        }
        foreground = packageName
        if (packageName != this.packageName && packageName != homePackage) lastRealApp = packageName
        Log.i(TAG, "foreground=$packageName")
        handler.removeCallbacks(instagramCheck)
        chatsShown = null
        if (keepsChatsFree(packageName)) {
            // Decided once Instagram has drawn its screen, so a chat opened from a notification stays free.
            arrivedInInstagram = true
            handler.postDelayed(instagramCheck, ARRIVAL_SETTLE_MS)
            return
        }
        step { it.onForeground(packageName, now = now()) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(wake)
        handler.removeCallbacks(floatingCheck)
        handler.removeCallbacks(instagramCheck)
        scope.cancel()
        runCatching { unregisterReceiver(screenEvents) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.removeOnModeChangedListener(modeWatcher)
        engine?.let { app.store.saveStates(it.states()) }
        super.onDestroy()
    }

    private fun now() = app.stableNow()

    private fun loadEngine() {
        groups = app.store.groups()
        val fresh = SessionEngine(groups.map(Group::rules), app.store.states())
        engine = fresh
        screenInUse = isPowered() && !isLocked()
        val time = now()
        val actions = buildList {
            if (!screenInUse) addAll(fresh.onScreen(false, time))
            if (isInCall()) addAll(fresh.onCall(true, time))
            // Instagram waits for the first look at its screen, so a chat open when the watcher starts stays free.
            addAll(fresh.onForeground(foreground?.takeUnless(::keepsChatsFree), now = time))
        }
        handle(actions)
        afterStep()
        chatsShown = null
        handler.removeCallbacks(instagramCheck)
        if (keepsChatsFree(foreground)) handler.post(instagramCheck)
    }

    private fun reloadGroups() {
        engine?.let { handle(it.onTimerDue(now())) }
        engine?.let { app.store.saveStates(it.states()) }
        loadEngine()
    }

    private fun step(event: (SessionEngine) -> List<SessionAction>) {
        val current = engine ?: return
        handle(event(current))
        afterStep()
    }

    private fun handle(actions: List<SessionAction>) {
        val store = app.store
        val day = ProgressRules.dayOf(System.currentTimeMillis(), app.zoneOffsetMs())
        actions.forEach { action ->
            Log.i(TAG, action.toString())
            when (action) {
                is SessionAction.SessionStarted -> store.addSession(day, action.groupId)
                is SessionAction.RecordUsage -> store.addUsage(day, action.groupId, action.packageName, action.ms)
                is SessionAction.WarnTwoMinutes ->
                    SessionNotifications.twoMinutesLeft(this, action.groupId, groupName(action.groupId))
                is SessionAction.CooldownEnded ->
                    SessionNotifications.cooldownFinished(this, action.groupId, groupName(action.groupId))
                is SessionAction.StartCooldown -> {
                    store.addCooldown(day, action.groupId)
                    if (action.showSoothing) {
                        startActivity(CooldownActivity.soothing(this, action.groupId, action.endsAt))
                    }
                }
                is SessionAction.ShowBlocked -> showBlocked(action)
            }
        }
    }

    private fun afterStep() {
        val current = engine ?: return
        app.store.saveStates(current.states())
        LiveSession.states.value = current.states()
        watchFloatingWindows()
        handler.removeCallbacks(wake)
        val time = now()
        val next = listOfNotNull(
            current.nextDeadline(),
            (time + SAVE_INTERVAL_MS).takeIf { current.isRunning },
        ).minOrNull() ?: return
        handler.postDelayed(wake, (next - time).coerceAtLeast(MIN_WAKE_MS))
    }

    private fun showBlocked(action: SessionAction.ShowBlocked) {
        val chatsOpen = keepsChatsFree(action.packageName)
        // Opening Instagram during a cooldown goes straight to the chats. Leaving them later shows the cover.
        val justSent = SystemClock.elapsedRealtime() - sentToChatsAt < SEND_TO_CHATS_GRACE_MS
        if (chatsOpen && arrivedInInstagram && !justSent && openInstagramChats()) {
            sentToChatsAt = SystemClock.elapsedRealtime()
            // Checked again on the next look, so a link Instagram ignores still ends at the cover.
            chatsShown = null
            return
        }
        startActivity(CooldownActivity.blocked(this, action.groupId, action.packageName, action.endsAt, chatsOpen))
    }

    private fun openInstagramChats(): Boolean = runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(InstagramScreens.CHATS_LINK))
                .setPackage(InstagramScreens.PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(TAG, "sent to instagram chats")
    }.isSuccess

    private fun keepsChatsFree(packageName: String?): Boolean =
        packageName == InstagramScreens.PACKAGE && groups.any { it.keepChatsFree && packageName in it.packages }

    /** Looks at Instagram's screen while it's open, and tells the engine when chats start or stop. */
    private fun checkInstagram() {
        if (foreground != InstagramScreens.PACKAGE || !screenInUse) return
        val root = rootInActiveWindow
        // A system dialog or the notification shade can be on top. Nothing changes until Instagram is back.
        if (root?.packageName == InstagramScreens.PACKAGE) {
            // Instagram keeps its other tabs loaded beside the one showing, so only views on screen count.
            val inChats = InstagramScreens.isChats { id ->
                root.findAccessibilityNodeInfosByViewId(InstagramScreens.viewId(id)).any(AccessibilityNodeInfo::isVisibleToUser)
            }
            // Just sent to the chats: the feed can still be showing while the inbox loads, so wait for it.
            val waitingForChats = !inChats && SystemClock.elapsedRealtime() - sentToChatsAt < SEND_TO_CHATS_GRACE_MS
            if (inChats != chatsShown && !waitingForChats) {
                chatsShown = inChats
                Log.i(TAG, "instagram chats=$inChats")
                step { it.onForeground(InstagramScreens.PACKAGE, exempt = inChats, now = now()) }
            }
            arrivedInInstagram = false
        }
        handler.postDelayed(instagramCheck, INSTAGRAM_CHECK_MS)
    }

    /** Spends today's token, if it's still there, to open a cooling group for a short while. */
    private fun openForEmergency(groupId: Long) {
        val current = engine ?: return
        if (!current.canOpenForEmergency(groupId, now()) || !app.useEmergencyToken()) return
        Log.i(TAG, "emergency group=$groupId")
        step { it.onEmergency(groupId, now()) }
    }

    private fun blockedPackages(): Set<String> {
        val current = engine ?: return emptySet()
        val time = now()
        return groups
            .filter { (current.state(it.id).cooldownEndsAt ?: 0) > time && !current.isOpenForEmergency(it.id, time) }
            .flatMapTo(mutableSetOf()) { it.packages }
    }

    /** Keeps looking during a cooldown, so a window reopened later is caught too. */
    private fun watchFloatingWindows() {
        handler.removeCallbacks(floatingCheck)
        if (screenInUse && blockedPackages().isNotEmpty()) handler.postDelayed(floatingCheck, FLOATING_CHECK_MS)
    }

    private fun closeBlockedFloatingWindow() {
        val floating = windows.firstOrNull { it.isInPictureInPictureMode }
        if (floating == null) {
            floatingOwner = null
            floatingAttempts = 0
            return
        }
        val root = floating.root
        // Android often hides the real app behind a System UI stand-in, so remember where it came from.
        val named = root?.packageName?.toString()?.takeIf { it != SYSTEM_UI && isFullApp(it) && it != homePackage }
        val owner = floatingOwner ?: (named ?: lastRealApp).also { floatingOwner = it } ?: return
        val blocked = blockedPackages()
        if (owner !in blocked) return
        floatingAttempts++
        val dismissed = root?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id) == true
        Log.i(TAG, "floating owner=$owner rootPackage=${root?.packageName} attempt=$floatingAttempts dismissed=$dismissed")
        if (!dismissed || floatingAttempts > 1) swipeAway(floating)
        if (floatingAttempts == 1) showBlockFor(owner)
    }

    /** Backup for phones that ignore the dismiss action: drags the window to the bottom edge. */
    private fun swipeAway(window: AccessibilityWindowInfo) {
        val bounds = Rect().also(window::getBoundsInScreen)
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            lineTo(metrics.widthPixels / 2f, metrics.heightPixels - 8f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_AWAY_MS)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun showBlockFor(packageName: String) {
        val current = engine ?: return
        val group = groups.firstOrNull { packageName in it.packages } ?: return
        val endsAt = current.state(group.id).cooldownEndsAt ?: return
        startActivity(CooldownActivity.blocked(this, group.id, packageName, endsAt))
    }

    private fun setScreen(inUse: Boolean) {
        screenInUse = inUse
        step { it.onScreen(inUse, now()) }
        handler.removeCallbacks(instagramCheck)
        if (inUse && keepsChatsFree(foreground)) handler.post(instagramCheck)
    }

    private fun phoneInUse() {
        setScreen(true)
        reviewYesterday()
    }

    private fun isPowered() = getSystemService(PowerManager::class.java).isInteractive

    private fun syncCall() = step { it.onCall(isInCall(), now()) }

    private fun isInCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL ||
        audio.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Keyboards, the notification shade and system pop-ups don't count as leaving an app. */
    private fun isFullApp(packageName: String): Boolean {
        if (packageName == this.packageName || packageName == homePackage) return true
        if (packageName == SYSTEM_UI || packageName == keyboardPackage()) return false
        return packageManager.getLaunchIntentForPackage(packageName) != null
    }

    private fun keyboardPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun groupName(id: Long) = groups.firstOrNull { it.id == id }?.name ?: "Your group"

    private fun reviewYesterday() {
        val store = app.store
        val settings = app.settings
        val today = ProgressRules.dayOf(System.currentTimeMillis(), app.zoneOffsetMs())
        if (settings.lastReviewDay == today) return
        settings.lastReviewDay = today
        val yesterday = today - 1
        val first = store.firstDay() ?: return
        val won = groups.mapNotNull { group ->
            val earlier = if (first >= yesterday) {
                emptyList()
            } else {
                store.usageByDay(maxOf(first, yesterday - 7), yesterday - 1, group.id)
            }
            val used = store.usageByDay(yesterday, yesterday, group.id).single()
            if (!ProgressRules.isDailyWin(used, earlier)) return@mapNotNull null
            store.addWin(yesterday, group.id)
            Triple(group.name, used, ProgressRules.usualMs(earlier))
        }
        if (won.isEmpty()) return
        val used = won.sumOf { it.second }
        val usual = won.sumOf { it.third }
        val percent = ProgressRules.percentLess(used, usual)
        val minutes = (used / 60_000.0).roundToInt()
        val where = won.singleOrNull()?.first ?: "your groups"
        SessionNotifications.win(
            this,
            "Calm waters, $percent% less",
            "Yesterday: $minutes min on $where, $percent% less than usual.",
            settings.celebrationSounds,
        )
    }

    private companion object {
        const val TAG = "EbbWatcher"
        const val SYSTEM_UI = "com.android.systemui"
        const val SAVE_INTERVAL_MS = 15_000L
        const val MIN_WAKE_MS = 50L
        const val FLOATING_CHECK_MS = 1_000L
        const val SWIPE_AWAY_MS = 350L
        const val ARRIVAL_SETTLE_MS = 350L
        const val INSTAGRAM_CHECK_MS = 700L
        const val SEND_TO_CHATS_GRACE_MS = 3_000L
    }
}
