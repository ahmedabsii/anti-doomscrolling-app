package com.absi.ebb.ui

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.absi.ebb.LiveSession
import com.absi.ebb.R
import com.absi.ebb.data.CooldownSoundChoice
import com.absi.ebb.data.Group
import com.absi.ebb.data.InstalledApp
import com.absi.ebb.ebb
import com.absi.ebb.isWatcherEnabled
import com.absi.ebb.progress.ProgressRules
import com.absi.ebb.session.GroupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MINUTE_MS = 60_000L
private const val MORNING_SEA_SHARE = 0.34f
private const val FADE_OVER_SCROLL = 160f

private sealed interface Route {
    data object Welcome : Route
    data object Permission : Route
    data class Main(val tab: Tab = Tab.GROUPS) : Route
    data class Editor(val groupId: Long?) : Route
    data object Celebrate : Route
}

@Composable
fun EbbRoot(celebrate: Boolean) {
    val settings = LocalContext.current.ebb.settings
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
        Route.Permission -> PermissionScreen {
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
                Tab.SETTINGS -> SettingsScreen(tabs = { selected -> route = Route.Main(selected) })
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
            Text(
                buildAnnotatedString {
                    append("Let it ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("ebb") }
                },
                color = EbbColors.Ink,
                style = EbbType.Hero,
            )
            Spacer(Modifier.height(8.dp))
            Text("Less scroll. More sea.", color = EbbColors.Ink, style = EbbType.Body)
            Spacer(Modifier.weight(1f))
            Text(
                "Ebb times your sessions, then lets the feed drift out with the tide until you're ready to come back.",
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
                    Text("What Ebb sees", color = EbbColors.Text, style = EbbType.Title)
                    Text(
                        "Ebb uses Android's Accessibility service to see which app is open, so it can time sessions " +
                            "and cover an app when your time is up. For Instagram, it also checks which screen is " +
                            "open so your chats can stay free.",
                        color = EbbColors.Soft,
                        style = EbbType.Body,
                    )
                    Text(
                        "It never reads or saves your messages. Your data stays on this phone, and Ebb has no " +
                            "internet access.",
                        color = EbbColors.Soft,
                        style = EbbType.Body,
                    )
                }
            }
            PaperCard(Modifier.fillMaxWidth()) {
                SettingRow("I agree, turn on Ebb", "Opens Android settings. Find Ebb and switch it on.") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
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
    val today = ProgressRules.dayOf(System.currentTimeMillis(), app.zoneOffsetMs())
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
            Text("ebb", color = EbbColors.Ink, style = EbbType.Value, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable { onOpenGroup(null) }, contentAlignment = Alignment.Center) {
                EbbIcon(Glyph.PLUS, EbbColors.Ink, 26.dp)
            }
        }
        if (!watcherOn) {
            PaperCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SettingRow("Ebb is switched off", "Blocking won't work until you turn it back on.") {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        Spacer(Modifier.height(seaSpace))
        // Light text rests on the dark water, so it fades before scrolling carries it up into the sky.
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
    var sessionMinutes by remember { mutableStateOf(((existing?.sessionMs ?: (10 * MINUTE_MS)) / MINUTE_MS).toFloat()) }
    var cooldownMinutes by remember { mutableStateOf(((existing?.cooldownMs ?: (30 * MINUTE_MS)) / MINUTE_MS).toFloat()) }
    var resetBreakMinutes by remember { mutableStateOf(existing?.resetBreakMs?.let { (it / MINUTE_MS).toFloat() }) }
    var sound by remember { mutableStateOf(existing?.sound ?: CooldownSoundChoice.OCEAN) }
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
                                resetBreakMs = resetBreakMinutes?.let { it.toLong() * MINUTE_MS },
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
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
                                TimeSlider("Session", sessionMinutes, 1f..60f, 1f, locked) { sessionMinutes = it }
                                Spacer(Modifier.height(8.dp))
                                TimeSlider("Cooldown", cooldownMinutes, 5f..240f, 5f, locked) { cooldownMinutes = it }
                                Spacer(Modifier.height(8.dp))
                                TimeSlider("Reset after a break of", resetBreakMinutes ?: (cooldownMinutes * 2), 5f..480f, 5f, locked) { resetBreakMinutes = it }
                            }
                        }
                        EbbField(query, "Search all your apps") { query = it }
                    }
                }
                items(shown, key = { it.packageName }) { item ->
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
                                    Text("Chats never use your session", color = EbbColors.Muted, style = EbbType.Caption)
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

@Composable
private fun ebbSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = EbbColors.Sea,
    checkedThumbColor = EbbColors.Foam,
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

@Composable
private fun TimeSlider(
    label: String,
    minutes: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    locked: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = EbbColors.Soft, style = EbbType.Body, modifier = Modifier.weight(1f))
        Text("${minutes.toInt()} min", color = EbbColors.Text, style = EbbType.Value)
    }
    EbbSlider(minutes, range, step, enabled = !locked, onChange = onChange)
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
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(EbbColors.Foam),
                    contentAlignment = Alignment.Center,
                ) {
                    EbbIcon(if (selected) Glyph.CHECK else Glyph.LOCK, EbbColors.Ink, 13.dp)
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
    val today = ProgressRules.dayOf(System.currentTimeMillis(), app.zoneOffsetMs())
    val week = remember(revision, today) { store.usageByDay(today - 6, today) }
    val lastWeek = remember(revision, today) { store.usageByDay(today - 13, today - 7) }
    val byApp = remember(revision, today) { store.usageByApp(today - 6, today) }
    val previousByApp = remember(revision, today) { store.usageByApp(today - 13, today - 7) }
    val streak = remember(revision, today) { ProgressRules.streak(store.winDays(), today - 1) }
    val weekMs = week.sum()
    val lastWeekMs = lastWeek.sum()
    val reclaimed = (lastWeekMs - weekMs).coerceAtLeast(0)
    val percent = ProgressRules.percentLess(weekMs, lastWeekMs)
    val tallest = (week.maxOrNull() ?: 0).coerceAtLeast(1)

    TabbedScreen(Sea.LOW_TIDE, Tab.PROGRESS, tabs) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Reclaimed this week", color = EbbColors.Ink, style = EbbType.Caption)
            Text(hoursMinutes(reclaimed), color = EbbColors.Ink, style = EbbType.Number)
            Text(
                if (lastWeekMs == 0L) "Building your first week" else "$percent% less scrolling than last week",
                color = EbbColors.Ink,
                style = EbbType.Caption,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row {
            Text("Time in your groups", color = EbbColors.Ink, style = EbbType.Caption, modifier = Modifier.weight(1f))
            Text(hoursMinutes(weekMs), color = EbbColors.Ink, style = EbbType.Caption)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(170.dp), verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { index, ms ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    val isToday = index == week.lastIndex
                    val shape = RoundedCornerShape(11.dp)
                    Box(
                        Modifier.width(30.dp).height((ms.toFloat() / tallest * 140).dp.coerceAtLeast(6.dp))
                            .shadow(3.dp, shape)
                            .background(if (isToday) EbbColors.Deep else lerp(EbbColors.Mist, EbbColors.Sea, 0.45f), shape),
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(dayLetter(week.size - 1 - index), color = EbbColors.Ink, style = EbbType.Caption)
                }
            }
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

private fun dayLetter(daysAgo: Int): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.HOUR_OF_DAY, -4)
    calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
    return "SMTWTFS"[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1].toString()
}

@Composable
private fun SettingsScreen(tabs: (Tab) -> Unit) {
    val context = LocalContext.current
    val app = context.ebb
    var soothing by remember { mutableStateOf(app.settings.soothingSeconds.toFloat()) }
    var sounds by remember { mutableStateOf(app.settings.celebrationSounds) }
    var watcherOn by remember { mutableStateOf(context.isWatcherEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { watcherOn = context.isWatcherEnabled() }

    TabbedScreen(Sea.DUSK, Tab.SETTINGS, tabs) {
        Text("Settings", color = EbbColors.Text, style = EbbType.Headline, modifier = Modifier.padding(top = 18.dp, bottom = 16.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            SettingRow(if (watcherOn) "Blocking is on" else "Blocking is off", "Android accessibility setting") {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        Spacer(Modifier.height(12.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Calm screen length", color = EbbColors.Soft, style = EbbType.Body, modifier = Modifier.weight(1f))
                    Text("${soothing.toInt()} sec", color = EbbColors.Text, style = EbbType.Value)
                }
                EbbSlider(soothing, 30f..180f, 10f) {
                    soothing = it
                    app.settings.soothingSeconds = it.toInt()
                }
            }
            Divider()
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Celebration sounds", color = EbbColors.Text, style = EbbType.Title, modifier = Modifier.weight(1f))
                Switch(
                    checked = sounds,
                    onCheckedChange = {
                        sounds = it
                        app.settings.celebrationSounds = it
                    },
                    colors = ebbSwitchColors(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PaperCard(Modifier.fillMaxWidth()) {
            SettingRow("Reset progress", "Clears your history and boats. Groups stay.") { app.store.resetProgress() }
        }
    }
}

@Composable
private fun CelebrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.ebb
    val today = ProgressRules.dayOf(System.currentTimeMillis(), app.zoneOffsetMs())
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
        if (app.settings.celebrationSounds && ProgressRules.isDailyWin(used, earlier)) {
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
            Text("Calm waters", color = EbbColors.Ink, style = EbbType.Title)
            Text("$percent% less", color = EbbColors.Ink, style = EbbType.Number)
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
