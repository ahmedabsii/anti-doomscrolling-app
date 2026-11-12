package com.absi.ebb.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.absi.ebb.LiveSession
import com.absi.ebb.R
import com.absi.ebb.cooldown.CooldownSound
import com.absi.ebb.data.CooldownSoundChoice
import com.absi.ebb.data.Group
import com.absi.ebb.data.InstalledApp
import com.absi.ebb.ebb
import com.absi.ebb.isWatcherEnabled
import com.absi.ebb.progress.ProgressRules
import com.absi.ebb.session.GroupState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MINUTE_MS = 60_000L
private const val MORNING_SEA_SHARE = 0.34f
private const val FADE_OVER_SCROLL = 160f

private sealed interface Route {
    data object Welcome : Route
    data object Permission : Route
    data object KeepRunning : Route
    data class Main(val tab: Tab = Tab.GROUPS) : Route
    data class Editor(val groupId: Long?) : Route
    data object Celebrate : Route
}

@Composable
fun EbbRoot(celebrate: Boolean) {
    val settings = LocalContext.current.ebb.settings
    var appSound by remember { mutableStateOf(settings.appSound) }
    AppSound(appSound)
    var route by remember {
        mutableStateOf<Route>(
            when {
                !settings.onboarded -> Route.Welcome
                celebrate -> Route.Celebrate
                else -> Route.Main()
            },
        )
    }
    when (val current = route) {
        Route.Welcome -> WelcomeScreen { route = Route.Permission }
        Route.Permission -> PermissionScreen { route = Route.KeepRunning }
        Route.KeepRunning -> KeepRunningScreen {
            settings.onboarded = true
            route = Route.Main()
        }
        is Route.Main -> {
            when (current.tab) {
                Tab.GROUPS -> HomeScreen(
                    onOpenGroup = { route = Route.Editor(it) },
                    onProgress = { route = Route.Main(Tab.PROGRESS) },
                    tabs = { selected -> route = Route.Main(selected) },
                )
                Tab.PROGRESS -> ProgressScreen(
                    onCelebrate = { route = Route.Celebrate },
                    tabs = { selected -> route = Route.Main(selected) },
                )
                Tab.SETTINGS -> SettingsScreen(
                    appSound = appSound,
                    onAppSound = {
                        appSound = it
                        settings.appSound = it
                    },
                    tabs = { selected -> route = Route.Main(selected) },
                )
            }
        }
        is Route.Editor -> {
            BackHandler { route = Route.Main() }
            GroupEditorScreen(current.groupId) { route = Route.Main() }
        }
        Route.Celebrate -> {
            BackHandler { route = Route.Main(Tab.PROGRESS) }
            CelebrationScreen { route = Route.Main(Tab.PROGRESS) }
        }
    }
}

/** The river and zen, quietly, while Sip is on screen. It stays silent while other audio is playing. */
@Composable
private fun AppSound(on: Boolean) {
    val context = LocalContext.current
    val sound = remember { CooldownSound(context.applicationContext) }
    LifecycleStartEffect(on) {
        if (on) sound.start(CooldownSoundChoice.RIVER, makesWay = true)
        onStopOrDispose { sound.stop() }
    }
}

private fun Modifier.onSwipeUp(action: () -> Unit) = pointerInput(Unit) {
    var pulled = 0f
    detectVerticalDragGestures(
        onDragStart = { pulled = 0f },
        onVerticalDrag = { _, drag ->
            pulled += drag
            if (pulled < -140f) {
                pulled = 0f
                action()
            }
        },
    )
}

@Composable
private fun WelcomeScreen(onNext: () -> Unit) {
    PaperSea(Sea.WELCOME, Modifier.onSwipeUp(onNext).clickable(onClick = onNext)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Text("sip", color = EbbColors.Text, style = EbbType.Hero, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("The quiet between waves.", color = EbbColors.Soft, style = EbbType.Body)
            Spacer(Modifier.weight(1f))
            Text(
                "Sip times your sessions, then lets the feed drift out with the tide until you're ready to come back.",
                color = EbbColors.Text,
                style = EbbType.Body,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            SwipeUpHint("Swipe up to begin")
        }
    }
}

@Composable
private fun PermissionScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (context.isWatcherEnabled()) onDone()
    }
    PaperSea(Sea.DUSK) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(40.dp))
            Text("One permission keeps your sessions honest", color = EbbColors.Text, style = EbbType.Headline)
            PaperCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What Sip sees", color = EbbColors.Text, style = EbbType.Title)
                    Text(
                        "Sip uses Android's Accessibility service to see which app is open, so it can time sessions " +
                            "and cover an app when your time is up. For Instagram, it also checks which screen is " +
                            "open so your chats can stay free.",
                        color = EbbColors.Soft,
                        style = EbbType.Body,
                    )
                    Text(
                        "It never reads or saves your messages. Your data stays on this phone, and Sip has no " +
                            "internet access.",
                        color = EbbColors.Soft,
                        style = EbbType.Body,
                    )
                }
            }
            PaperCard(Modifier.fillMaxWidth()) {
                SettingRow("I agree, turn on Sip", "Opens Android settings. Find Sip and switch it on.") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
    }
}

/** Setup's second step: the permissions that keep Sip running on phones that save battery hard. */
@Composable
private fun KeepRunningScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    // Blocking is already on by this step, so it's left out.
    val permissions = remember(checks) { permissionsOf(context).drop(1) }
    PaperSea(Sea.DUSK) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Text("Keep Sip running", color = EbbColors.Text, style = EbbType.Headline)
            Text(
                "Phones close apps in the background to save battery. Turn these on so Sip keeps timing your " +
                    "sessions and covers apps on time. You can change them later in Settings.",
                color = EbbColors.Soft,
                style = EbbType.Body,
            )
            PaperCard(Modifier.fillMaxWidth()) {
                permissions.forEachIndexed { index, permission ->
                    if (index > 0) Divider()
                    PermissionRow(permission) { openSettings(context, permission.settings) }
                }
            }
            PaperCard(Modifier.fillMaxWidth()) {
                SettingRow("Continue", null, onDone)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = EbbColors.Text, style = EbbType.Title)
            if (detail != null) Text(detail, color = EbbColors.Muted, style = EbbType.Caption)
        }
        EbbIcon(Glyph.NEXT, EbbColors.Muted, 20.dp)
    }
}

/** Live clock for countdowns on screen. */
@Composable
private fun rememberNow(): Long {
    val app = LocalContext.current.ebb
    val now by produceState(app.stableNow()) {
        while (true) {
            delay(1000)
            value = app.stableNow()
        }
    }
    return now
}

/** Today's day, which moves on at midnight even while the screen stays open. */
@Composable
private fun rememberToday(): Long {
    val app = LocalContext.current.ebb
    val today by produceState(app.today()) {
        while (true) {
            delay(DAY_CHECK_MS)
            value = app.today()
        }
    }
    return today
}

private const val DAY_CHECK_MS = 15_000L

private data class GroupStatus(val time: String, val label: String, val color: Color, val water: Float)

private fun statusOf(group: Group, state: GroupState?, now: Long): GroupStatus {
    val endsAt = state?.cooldownEndsAt
    if (endsAt != null && endsAt > now) {
        val rested = 1f - (endsAt - now).toFloat() / group.cooldownMs
        return GroupStatus(clock(endsAt - now), "cooling down", EbbColors.Sand, rested)
    }
    val used = state?.usedMs ?: 0
    val left = (group.sessionMs - used).coerceAtLeast(0)
    return if (used == 0L || endsAt != null) {
        GroupStatus(clock(group.sessionMs), "ready", EbbColors.Text, 1f)
    } else {
        GroupStatus(clock(left), "left", EbbColors.Text, left.toFloat() / group.sessionMs)
    }
}

@Composable
private fun TabbedScreen(sea: Sea, tab: Tab, tabs: (Tab) -> Unit, body: @Composable (ScrollState) -> Unit) {
    val scroll = rememberScrollState()
    PaperSea(sea) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                body(scroll)
            }
            EbbTabBar(tab, tabs, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun HomeScreen(onOpenGroup: (Long?) -> Unit, onProgress: () -> Unit, tabs: (Tab) -> Unit) {
    val apps = LocalContext.current.ebb.apps
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { apps.load() } }
    val context = LocalContext.current
    val app = context.ebb
    val revision by app.store.revision.collectAsState()
    val live by LiveSession.states.collectAsState()
    val now = rememberNow()
    val groups = remember(revision) { app.store.groups() }
    val states = live.ifEmpty { remember(revision) { app.store.states() } }
    val today = rememberToday()
    val todayMs = remember(revision, today) { app.store.usageByDay(today, today).single() }
    val earlier = remember(revision, today) {
        val first = app.store.firstDay()
        if (first == null || first >= today) emptyList() else app.store.usageByDay(maxOf(first, today - 7), today - 1)
    }
    val (sessions, cooldowns) = remember(revision, today) { app.store.countsOn(today) }
    val streak = remember(revision, today) { ProgressRules.streak(app.store.winDays(), today - 1) }
    var watcherOn by remember { mutableStateOf(context.isWatcherEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { watcherOn = context.isWatcherEnabled() }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val seaSpace = with(LocalDensity.current) { (windowHeight * MORNING_SEA_SHARE).toDp() }

    TabbedScreen(Sea.MORNING, Tab.GROUPS, tabs) { scroll ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("sip", color = EbbColors.Text, style = EbbType.Value, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable { onOpenGroup(null) }, contentAlignment = Alignment.Center) {
                EbbIcon(Glyph.PLUS, EbbColors.Text, 26.dp)
            }
        }
        if (!watcherOn) {
            PaperCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SettingRow("Sip is switched off", "Blocking won't work until you turn it back on.") {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        Spacer(Modifier.height(seaSpace))
        // The number fades as scrolling carries it up over the waves, so the two never blur together.
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onProgress)
                .graphicsLayer { alpha = 1f - (scroll.value / FADE_OVER_SCROLL).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Today", color = EbbColors.Soft, style = EbbType.Caption)
            Text(minutesText(todayMs), color = EbbColors.Text, style = EbbType.Number)
            val reclaimed = ProgressRules.reclaimedMs(todayMs, earlier)
            Text(
                if (earlier.isEmpty()) "Your first day at sea" else "${minutesText(reclaimed)} reclaimed from the scroll",
                color = EbbColors.Soft,
                style = EbbType.Caption,
            )
        }
        Spacer(Modifier.height(20.dp))
        StatStrip(
            listOf(
                "Sessions" to sessions.toString(),
                "Cooldowns" to cooldowns.toString(),
                "Streak" to if (streak.current == 1) "1 day" else "${streak.current} days",
                "Tokens" to app.emergencyTokensLeft().let { if (it == 0) "Used" else "$it left" },
            ),
        )
        Spacer(Modifier.height(12.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            if (groups.isEmpty()) {
                SettingRow("Create your first group", "Pick apps that share one session timer.") { onOpenGroup(null) }
            }
            groups.forEachIndexed { index, group ->
                if (index > 0) Divider()
                val status = statusOf(group, states[group.id], now)
                Column(Modifier.fillMaxWidth().clickable { onOpenGroup(group.id) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(82.dp)) { IconStack(group.packages.sorted()) }
                        Column(Modifier.weight(1f)) {
                            Text(group.name, color = EbbColors.Text, style = EbbType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.sessionMs / MINUTE_MS} min sessions", color = EbbColors.Muted, style = EbbType.Caption)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(status.time, color = status.color, style = EbbType.Title)
                            Text(status.label, color = EbbColors.Muted, style = EbbType.Caption)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Waterline(status.water, if (status.color == EbbColors.Sand) EbbColors.Sand else EbbColors.Mist)
                }
            }
        }
    }
}

@Composable
private fun GroupEditorScreen(groupId: Long?, onDone: () -> Unit) {
    val app = LocalContext.current.ebb
    val existing = remember(groupId) { groupId?.let(app.store::group) }
    val allApps by produceState(app.apps.known) { value = withContext(Dispatchers.Default) { app.apps.load() } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sessionMinutes by remember { mutableStateOf(((existing?.sessionMs ?: (RECOMMENDED_SESSION_MIN * MINUTE_MS)) / MINUTE_MS).toFloat()) }
    var cooldownMinutes by remember { mutableStateOf(((existing?.cooldownMs ?: (RECOMMENDED_COOLDOWN_MIN * MINUTE_MS)) / MINUTE_MS).toFloat()) }
    var sound by remember { mutableStateOf(existing?.sound ?: CooldownSoundChoice.RIVER) }
    var keepChats by remember { mutableStateOf(existing?.keepChatsFree ?: true) }
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateSetOf<String>().apply { addAll(existing?.packages.orEmpty()) } }
    val state = remember(groupId) { groupId?.let { app.store.states()[it] } }
    val locked = (state?.cooldownEndsAt ?: 0) > app.stableNow()
    val canSave = !locked && name.isNotBlank() && picked.isNotEmpty()
    val shown = remember(allApps, query) { allApps.filter { it.label.contains(query.trim(), ignoreCase = true) } }

    PaperSea(Sea.DUSK) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
                    EbbIcon(Glyph.BACK, EbbColors.Text)
                }
                Text(
                    name.ifBlank { "New group" },
                    color = EbbColors.Text,
                    style = EbbType.Title,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(44.dp).clickable(enabled = canSave) {
                        app.store.saveGroup(
                            Group(
                                id = groupId ?: 0,
                                name = name.trim(),
                                sessionMs = sessionMinutes.toLong() * MINUTE_MS,
                                cooldownMs = cooldownMinutes.toLong() * MINUTE_MS,
                                sound = sound,
                                keepChatsFree = keepChats,
                                packages = picked.toSet(),
                            ),
                        )
                        onDone()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    EbbIcon(Glyph.CHECK, if (canSave) EbbColors.Sand else EbbColors.Line)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = EDITOR_SIDE, end = EDITOR_SIDE, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(span = { GridItemSpan(4) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (locked) {
                            Text("This group can't be changed until its cooldown ends.", color = EbbColors.Sand, style = EbbType.Caption)
                        }
                        EbbField(name, "Group name") { if (!locked) name = it }
                        PaperCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                TimeSlider("Session", sessionMinutes, 1f..60f, 1f, RECOMMENDED_SESSION_MIN, locked) { sessionMinutes = it }
                                Spacer(Modifier.height(8.dp))
                                TimeSlider("Cooldown", cooldownMinutes, 5f..240f, 5f, RECOMMENDED_COOLDOWN_MIN, locked) { cooldownMinutes = it }
                                // The engine refills a session after a break of twice the cooldown.
                                Text(
                                    "Your session resets after ${lengthInWords(cooldownMinutes.toInt() * 2)} away from these apps.",
                                    color = EbbColors.Muted,
                                    style = EbbType.Caption,
                                )
                            }
                        }
                    }
                }
                // The search stays pinned at the top, so any app is a few letters away without scrolling.
                stickyHeader(key = "search") {
                    Box(Modifier.edgeToEdge(EDITOR_SIDE).background(SEARCH_BAND).padding(start = EDITOR_SIDE, end = EDITOR_SIDE, top = 10.dp, bottom = 14.dp)) {
                        EbbField(query, "Search all your apps") { query = it }
                    }
                }
                if (shown.isEmpty() && allApps.isNotEmpty()) {
                    item(span = { GridItemSpan(4) }) {
                        Text("No apps match \u201c${query.trim()}\u201d", color = EbbColors.Muted, style = EbbType.Body, textAlign = TextAlign.Center)
                    }
                }
                items(shown, key = { it.packageName }, contentType = { "app" }) { item ->
                    AppTile(item, item.packageName in picked, enabled = !locked) {
                        if (item.packageName in picked) picked -= item.packageName else picked += item.packageName
                    }
                }
                item(span = { GridItemSpan(4) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PaperCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Keep Instagram chats free", color = EbbColors.Text, style = EbbType.Title)
                                    Text("Chats don't use your session, and stay open in a cooldown", color = EbbColors.Muted, style = EbbType.Caption)
                                }
                                Switch(checked = keepChats, onCheckedChange = { if (!locked) keepChats = it }, colors = ebbSwitchColors())
                            }
                            Divider()
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !locked) {
                                    sound = CooldownSoundChoice.entries[(sound.ordinal + 1) % CooldownSoundChoice.entries.size]
                                }.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Cooldown sound", color = EbbColors.Text, style = EbbType.Title, modifier = Modifier.weight(1f))
                                Text(sound.label, color = EbbColors.Muted, style = EbbType.Body)
                                EbbIcon(Glyph.NEXT, EbbColors.Muted, 18.dp)
                            }
                        }
                        if (existing != null && !locked) {
                            PaperCard(Modifier.fillMaxWidth()) {
                                SettingRow("Delete this group", null) {
                                    app.store.deleteGroup(existing.id)
                                    onDone()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val EDITOR_SIDE = 16.dp

// The sky's own shade, solid behind the search and fading at its foot, so apps slide softly under it.
private val SEARCH_BAND = lerp(EbbColors.Night, Color.Black, 0.4f).let {
    Brush.verticalGradient(0f to it, 0.8f to it, 1f to Color.Transparent)
}

/** Reaches past the list's side margins to the screen edges. */
private fun Modifier.edgeToEdge(margin: Dp) = layout { measurable, constraints ->
    val extra = margin.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun ebbSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = EbbColors.Sea,
    checkedThumbColor = EbbColors.Mist,
    uncheckedTrackColor = EbbColors.Night,
    uncheckedThumbColor = EbbColors.Mist,
    uncheckedBorderColor = EbbColors.Mist,
)

@Composable
private fun EbbField(value: String, hint: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(EbbColors.Deep).padding(horizontal = 16.dp, vertical = 13.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = EbbType.Body.copy(color = EbbColors.Text),
            cursorBrush = SolidColor(EbbColors.Mist),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) Text(hint, color = EbbColors.Muted, style = EbbType.Body)
    }
}

// What Sip suggests to someone new. The sliders mark these, and name them when they're picked.
private const val RECOMMENDED_SESSION_MIN = 10
private const val RECOMMENDED_COOLDOWN_MIN = 30

/** A length of time in words, such as "20 min", "1 hour" or "1 h 30 min". */
private fun lengthInWords(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

@Composable
private fun TimeSlider(
    label: String,
    minutes: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    recommended: Int,
    locked: Boolean,
    onChange: (Float) -> Unit,
) {
    Row {
        Text(label, color = EbbColors.Soft, style = EbbType.Body, modifier = Modifier.weight(1f).alignByBaseline())
        if (minutes.toInt() == recommended) {
            Text("Recommended", color = EbbColors.Sand, style = EbbType.Caption, modifier = Modifier.padding(end = 10.dp).alignByBaseline())
        }
        Text("${minutes.toInt()} min", color = EbbColors.Text, style = EbbType.Value, modifier = Modifier.alignByBaseline())
    }
    EbbSlider(minutes, range, step, enabled = !locked, mark = recommended.toFloat(), onChange = onChange)
}

@Composable
private fun AppTile(item: InstalledApp, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val usable = enabled && !item.isProtected
    Column(
        Modifier.alpha(if (item.isProtected) 0.5f else 1f).clickable(enabled = usable, onClick = onToggle).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(item.packageName, Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)))
            if (selected || item.isProtected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(EbbColors.Mist),
                    contentAlignment = Alignment.Center,
                ) {
                    EbbIcon(if (selected) Glyph.CHECK else Glyph.LOCK, EbbColors.Night, 13.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.label, color = EbbColors.Soft, style = EbbType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgressScreen(onCelebrate: () -> Unit, tabs: (Tab) -> Unit) {
    val app = LocalContext.current.ebb
    val store = app.store
    val revision by store.revision.collectAsState()
    val today = rememberToday()
    // The headline always compares the last seven days with the seven before them.
    val week = remember(revision, today) { store.usageByDay(today - 6, today) }
    val lastWeek = remember(revision, today) { store.usageByDay(today - 13, today - 7) }
    // The bars and the apps list show one week at a time, from this week back to the first recorded day.
    var weeksBack by remember { mutableIntStateOf(0) }
    val firstDay = remember(revision) { store.firstDay() }
    val oldestWeek = if (firstDay == null) 0 else ((today - firstDay).coerceAtLeast(0) / 7).toInt()
    val shownEnd = today - 7L * weeksBack
    val shown = remember(revision, shownEnd) { store.usageByDay(shownEnd - 6, shownEnd) }
    val shownCounts = remember(revision, shownEnd) { store.countsByDay(shownEnd - 6, shownEnd) }
    val byApp = remember(revision, shownEnd) { store.usageByApp(shownEnd - 6, shownEnd) }
    val previousByApp = remember(revision, shownEnd) { store.usageByApp(shownEnd - 13, shownEnd - 7) }
    val streak = remember(revision, today) { ProgressRules.streak(store.winDays(), today - 1) }
    val weekMs = week.sum()
    val lastWeekMs = lastWeek.sum()
    val reclaimed = (lastWeekMs - weekMs).coerceAtLeast(0)
    val percent = ProgressRules.percentLess(weekMs, lastWeekMs)

    TabbedScreen(Sea.LOW_TIDE, Tab.PROGRESS, tabs) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Reclaimed this week", color = EbbColors.Soft, style = EbbType.Caption)
            Text(hoursMinutes(reclaimed), color = EbbColors.Text, style = EbbType.Number)
            Text(
                when {
                    lastWeekMs == 0L -> "Building your first week"
                    percent >= 0 -> "$percent% less scrolling than last week"
                    else -> "${-percent}% more scrolling than last week"
                },
                color = EbbColors.Soft,
                style = EbbType.Caption,
            )
        }
        Spacer(Modifier.height(20.dp))
        // Time runs left to right in every language, so the arrows and bars match where a finger lands.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            WeekPicker(
                end = shownEnd,
                today = today,
                canGoBack = weeksBack < oldestWeek,
                canGoForward = weeksBack > 0,
                onBack = { weeksBack++ },
                onForward = { weeksBack-- },
            )
            DayBars(shown.mapIndexed { index, ms -> DayUse(shownEnd - 6 + index, ms, shownCounts[index].first, shownCounts[index].second) }, today)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Text("Total in your groups", color = EbbColors.Soft, style = EbbType.Body, modifier = Modifier.weight(1f).alignByBaseline())
            Text(hoursMinutes(shown.sum()), color = EbbColors.Text, style = EbbType.Value, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(18.dp))
        PaperCard(Modifier.fillMaxWidth().clickable(onClick = onCelebrate)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Your shore", color = EbbColors.Text, style = EbbType.Title)
                        Text("One boat for every calmer day", color = EbbColors.Muted, style = EbbType.Caption)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (streak.current == 1) "1 day" else "${streak.current} days", color = EbbColors.Text, style = EbbType.Title)
                        Text("best ${streak.best}", color = EbbColors.Muted, style = EbbType.Caption)
                    }
                }
                Shore(streak.current)
            }
        }
        if (byApp.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PaperCard(Modifier.fillMaxWidth()) {
                byApp.entries.take(5).forEachIndexed { index, (packageName, ms) ->
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(packageName, Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.apps.label(packageName), color = EbbColors.Text, style = EbbType.Title)
                            Text(hoursMinutes(ms), color = EbbColors.Muted, style = EbbType.Caption)
                        }
                        val change = ProgressRules.percentLess(ms, previousByApp[packageName] ?: 0)
                        if (previousByApp.containsKey(packageName)) {
                            Text(
                                if (change >= 0) "$change% less" else "${-change}% more",
                                color = if (change >= 0) EbbColors.Mist else EbbColors.Sand,
                                style = EbbType.Body,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The week the bars show, with arrows to older and newer weeks. */
@Composable
private fun WeekPicker(end: Long, today: Long, canGoBack: Boolean, canGoForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WeekArrow(Glyph.BACK, canGoBack, onBack)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekName(end, today), color = EbbColors.Text, style = EbbType.Title)
            Text(dateRange(end - 6, end, today), color = EbbColors.Muted, style = EbbType.Caption)
        }
        WeekArrow(Glyph.NEXT, canGoForward, onForward)
    }
}

@Composable
private fun WeekArrow(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        EbbIcon(glyph, if (enabled) EbbColors.Text else EbbColors.Line, 22.dp)
    }
}

private fun weekName(end: Long, today: Long): String = when (val weeksBack = (today - end) / 7) {
    0L -> "This week"
    1L -> "Last week"
    else -> "$weeksBack weeks ago"
}

/** Such as "4 – 10 Nov" or "28 Oct – 3 Nov", with the year when it isn't this year. */
private fun dateRange(from: Long, to: Long, today: Long): String {
    val start = LocalDate.ofEpochDay(from)
    val end = LocalDate.ofEpochDay(to)
    val startPattern = if (start.month == end.month && start.year == end.year) "d" else "d MMM"
    val endPattern = if (end.year == LocalDate.ofEpochDay(today).year) "d MMM" else "d MMM yyyy"
    return "${start.format(DateTimeFormatter.ofPattern(startPattern, Locale.ENGLISH))} – ${end.format(DateTimeFormatter.ofPattern(endPattern, Locale.ENGLISH))}"
}

/** One day under a bar: its time in the groups, and how many sessions and cooldowns it had. */
private data class DayUse(val day: Long, val ms: Long, val sessions: Int, val cooldowns: Int)

private val BAR_SHAPE = RoundedCornerShape(11.dp)

// Tall enough for a day's details (three lines) and the gap under them.
private val DETAILS_LANE = 84.dp

/**
 * A bar for each day. Holding a bar shows that day's details above it, and sliding along moves them
 * from day to day. A tap shows or hides one day's details.
 */
@Composable
private fun DayBars(days: List<DayUse>, today: Long) {
    // The chosen day, kept by date, so it simply isn't shown on another week's bars.
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    val picked = days.indexOfFirst { it.day == pickedDay }.takeIf { it >= 0 }
    val haptics = LocalHapticFeedback.current
    val tallest = days.maxOf(DayUse::ms).coerceAtLeast(1)
    fun dayAt(x: Float, width: Int) = days[(x / width * days.size).toInt().coerceIn(0, days.lastIndex)].day
    fun slideTo(day: Long) {
        if (day == pickedDay) return
        pickedDay = day
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // A clear lane above the bars, where a day's details appear without covering anything.
    Box(Modifier.fillMaxWidth().height(DETAILS_LANE), contentAlignment = Alignment.Center) {
        if (picked == null) Text("Hold a bar and slide to see each day", color = EbbColors.Muted, style = EbbType.Caption)
    }
    Box(
        // The touch handlers restart with each new week, so they always read the bars on screen.
        Modifier.fillMaxWidth().height(170.dp)
            .pointerInput(days) {
                detectTapGestures { offset ->
                    val day = dayAt(offset.x, size.width)
                    pickedDay = if (pickedDay == day) null else day
                }
            }
            // After the hold the slide belongs to the bars, so the screen doesn't scroll under the finger.
            .pointerInput(days) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        pickedDay = dayAt(offset.x, size.width)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        slideTo(dayAt(change.position.x, size.width))
                    },
                )
            },
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { index, use ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    // A thin line runs from the details down to the day they belong to.
                    if (index == picked) Box(Modifier.width(1.dp).weight(1f).background(EbbColors.Soft))
                    val color = when {
                        index == picked -> EbbColors.Sand
                        use.day == today -> EbbColors.Mist
                        else -> EbbColors.Sea
                    }
                    Box(
                        Modifier.width(30.dp).height((use.ms.toFloat() / tallest * 140).dp.coerceAtLeast(6.dp))
                            .alpha(if (picked == null || index == picked) 1f else 0.5f)
                            .shadow(3.dp, BAR_SHAPE)
                            .background(color, BAR_SHAPE),
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(dayLetter(use.day), color = if (index == picked) EbbColors.Text else EbbColors.Muted, style = EbbType.Caption)
                }
            }
        }
        picked?.let { index ->
            DayDetails(days[index], today, Modifier.above(centreFraction = (index + 0.5f) / days.size))
        }
    }
}

@Composable
private fun DayDetails(use: DayUse, today: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.shadow(8.dp, shape).background(EbbColors.Deep, shape).border(0.5.dp, EbbColors.Line, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(dayName(use.day, today), color = EbbColors.Muted, style = EbbType.Caption)
        Text(hoursMinutes(use.ms), color = EbbColors.Text, style = EbbType.Value)
        Text("${plural(use.sessions, "session")} · ${plural(use.cooldowns, "cooldown")}", color = EbbColors.Muted, style = EbbType.Caption)
    }
}

/** Sits this on its parent's top edge, centred on a point along it but kept inside its width. */
private fun Modifier.above(centreFraction: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val centre = centreFraction * constraints.maxWidth
    val x = (centre - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
    layout(0, 0) { placeable.place(x, -placeable.height) }
}

private fun plural(count: Int, word: String) = if (count == 1) "1 $word" else "$count ${word}s"

// Sip's words are all English, so its dates are too.
private fun dayName(day: Long, today: Long): String = when (day) {
    today -> "Today"
    today - 1 -> "Yesterday"
    else -> LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
}

private fun dayLetter(day: Long): String = LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)

@Composable
private fun SettingsScreen(appSound: Boolean, onAppSound: (Boolean) -> Unit, tabs: (Tab) -> Unit) {
    val context = LocalContext.current
    val app = context.ebb
    // Read again whenever Sip comes back, since each one is changed in Android's own settings.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val permissions = remember(checks) { permissionsOf(context) }

    TabbedScreen(Sea.DUSK, Tab.SETTINGS, tabs) {
        Text("Settings", color = EbbColors.Text, style = EbbType.Headline, modifier = Modifier.padding(top = 18.dp, bottom = 16.dp))
        Text("Permissions", color = EbbColors.Soft, style = EbbType.Caption, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            permissions.forEachIndexed { index, permission ->
                if (index > 0) Divider()
                PermissionRow(permission) { openSettings(context, permission.settings) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Sound", color = EbbColors.Soft, style = EbbType.Caption, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sound in Sip", color = EbbColors.Text, style = EbbType.Title)
                    Text("The river and zen while Sip is open. Cooldown sounds are set in each group.", color = EbbColors.Muted, style = EbbType.Caption)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = appSound, onCheckedChange = onAppSound, colors = ebbSwitchColors())
            }
        }
        Spacer(Modifier.height(20.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            SettingRow("Reset progress", "Clears your history and boats. Groups stay.") { app.store.resetProgress() }
        }
    }
}

/** One permission Sip needs or works better with. [on] is null where Android can't tell. */
private data class Permission(val title: String, val detail: String, val on: Boolean?, val settings: Intent)

private fun permissionsOf(context: Context): List<Permission> {
    val app = "package:${context.packageName}".toUri()
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    return listOf(
        Permission(
            "Blocking",
            "Required. Lets Sip see which app is open, and cover it when time is up.",
            context.isWatcherEnabled(),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        ),
        Permission(
            "Notifications",
            "For \u201c2 minutes left\u201d and \u201cCooldown finished.\u201d",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        ),
        Permission(
            "Run in the background",
            "Stops Android closing Sip to save battery.",
            unrestricted,
            // Android only asks while it's off. Once on, the battery list is where it can be changed.
            if (unrestricted) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app)
            },
        ),
        Permission(
            "Show over other apps",
            "Helps the cover screen open over any app, straight away.",
            Settings.canDrawOverlays(context),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app),
        ),
        Permission(
            "Auto launch",
            "Some phones, like realme, close apps anyway. In App info \u203a Battery usage, allow background activity and auto launch.",
            null,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        ),
    )
}

/** Opens the page, or Sip's App info if this phone doesn't have that page. */
private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }
}

@Composable
private fun PermissionRow(permission: Permission, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(permission.title, color = EbbColors.Text, style = EbbType.Title)
            Text(permission.detail, color = EbbColors.Muted, style = EbbType.Caption)
        }
        Spacer(Modifier.width(12.dp))
        when (permission.on) {
            true -> Text("On", color = EbbColors.Mist, style = EbbType.Body)
            false -> Text("Turn on", color = EbbColors.Sand, style = EbbType.Body)
            null -> EbbIcon(Glyph.NEXT, EbbColors.Muted, 20.dp)
        }
    }
}

@Composable
private fun CelebrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.ebb
    val today = app.today()
    val yesterday = today - 1
    val used = remember { app.store.usageByDay(yesterday, yesterday).single() }
    val earlier = remember {
        val first = app.store.firstDay()
        if (first == null || first >= yesterday) emptyList() else app.store.usageByDay(maxOf(first, yesterday - 7), yesterday - 1)
    }
    val streak = remember { ProgressRules.streak(app.store.winDays(), yesterday) }
    val usual = ProgressRules.usualMs(earlier)
    val percent = ProgressRules.percentLess(used, usual).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        if (app.settings.appSound && ProgressRules.isDailyWin(used, earlier)) {
            val tone = if (ProgressRules.isMilestone(streak.current)) R.raw.chime_milestone else R.raw.chime_win
            MediaPlayer.create(context, tone)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        }
    }
    PaperSea(Sea.WIN, Modifier.onSwipeUp(onDone)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Text("Calm waters", color = EbbColors.Soft, style = EbbType.Title)
            Text("$percent% less", color = EbbColors.Text, style = EbbType.Number)
            Spacer(Modifier.weight(1f))
            Text(
                "${minutesText(used)} yesterday.\nYou reclaimed ${minutesText((usual - used).coerceAtLeast(0))} from the scroll.",
                color = EbbColors.Text,
                style = EbbType.Body,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            PaperCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row {
                        Text("A new boat reached your shore", color = EbbColors.Text, style = EbbType.Title, modifier = Modifier.weight(1f))
                        Text(if (streak.current == 1) "1 day" else "${streak.current} days", color = EbbColors.Soft, style = EbbType.Caption)
                    }
                    Shore(streak.current)
                }
            }
            Spacer(Modifier.height(12.dp))
            SwipeUpHint("Swipe up to continue")
        }
    }
}
