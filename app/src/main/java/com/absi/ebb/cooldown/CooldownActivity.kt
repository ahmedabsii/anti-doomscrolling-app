package com.absi.ebb.cooldown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.absi.ebb.LiveSession
import com.absi.ebb.data.CooldownSoundChoice
import com.absi.ebb.ebb
import com.absi.ebb.ui.AppIcon
import com.absi.ebb.ui.EbbColors
import com.absi.ebb.ui.EbbTheme
import com.absi.ebb.ui.EbbType
import com.absi.ebb.ui.PaperSea
import com.absi.ebb.ui.Sea
import com.absi.ebb.ui.SwipeUpHint
import com.absi.ebb.ui.clock
import com.absi.ebb.watcher.InstagramScreens
import kotlinx.coroutines.delay

/** Covers an app when a session ends, or when someone opens an app that's cooling down. */
class CooldownActivity : ComponentActivity() {

    private lateinit var sound: CooldownSound
    private var screen by mutableStateOf<CoverScreen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        sound = CooldownSound(applicationContext)
        screen = coverFrom(intent)
        setContent {
            EbbTheme {
                BackHandler { leave() }
                // The screens ask about the token each second, so a cover still up at midnight offers the new one.
                when (val current = screen) {
                    is CoverScreen.Soothing ->
                        SoothingScreen(current, ebb.settings.soothingSeconds, ebb::hasEmergencyToken, ::openForEmergency, ::leave)
                    is CoverScreen.Blocked -> BlockedScreen(current, ebb::hasEmergencyToken, ::openForEmergency, ::openChats, ::leave)
                    null -> Unit
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The kicked app can redraw just after the kick. The calm screen must stay up over it.
        if (screen is CoverScreen.Soothing && intent.hasExtra(EXTRA_APP)) return
        setIntent(intent)
        screen = coverFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        // A locked screen plays nothing, but taking audio focus silences any app still playing.
        sound.start(screen?.sound ?: CooldownSoundChoice.SILENT)
    }

    override fun onStop() {
        sound.stop()
        // Leaving ends the cover: left running, its timers would pull you out of the next app.
        // Locking the phone doesn't, so the cover is still there on unlock.
        val leftForAnotherApp = getSystemService(PowerManager::class.java).isInteractive
        if (leftForAnotherApp && !isChangingConfigurations) finish()
        super.onStop()
    }

    private fun coverFrom(intent: Intent): CoverScreen {
        val groupId = intent.getLongExtra(EXTRA_GROUP, 0)
        val group = ebb.store.group(groupId)
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0)
        val blockedApp = intent.getStringExtra(EXTRA_APP)
        val message = BlockMessages.pick(BlockMessages.load(this), ebb.settings.lastBlockMessage)
        ebb.settings.lastBlockMessage = message.text
        return if (blockedApp == null) {
            CoverScreen.Soothing(groupId, group?.name ?: "Your group", endsAt, group?.sound ?: CooldownSoundChoice.RIVER, message)
        } else {
            CoverScreen.Blocked(groupId, blockedApp, ebb.apps.label(blockedApp), endsAt, message, intent.getBooleanExtra(EXTRA_CHATS_OPEN, false), group?.sound ?: CooldownSoundChoice.RIVER)
        }
    }

    /** Closing the cover uncovers the app, which the watcher now leaves open for two minutes. */
    private fun openForEmergency(groupId: Long) {
        LiveSession.emergencyRequests.tryEmit(groupId)
        finish()
    }

    /** Instagram's chats stay open during a cooldown, so this goes back to them instead of home. */
    private fun openChats() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(InstagramScreens.CHATS_LINK))
                    .setPackage(InstagramScreens.PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { leave() }
        finish()
    }

    private fun leave() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_ENDS_AT = "ends_at"
        private const val EXTRA_APP = "app"
        private const val EXTRA_CHATS_OPEN = "chats_open"

        fun soothing(context: Context, groupId: Long, endsAt: Long): Intent =
            Intent(context, CooldownActivity::class.java)
                // Otherwise Android reads the cover as the user leaving, and video apps start floating.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_GROUP, groupId)
                .putExtra(EXTRA_ENDS_AT, endsAt)

        fun blocked(context: Context, groupId: Long, app: String, endsAt: Long, chatsOpen: Boolean = false): Intent =
            soothing(context, groupId, endsAt).putExtra(EXTRA_APP, app).putExtra(EXTRA_CHATS_OPEN, chatsOpen)
    }
}

private sealed interface CoverScreen {
    val groupId: Long
    val sound: CooldownSoundChoice

    data class Soothing(
        override val groupId: Long,
        val groupName: String,
        val endsAt: Long,
        override val sound: CooldownSoundChoice,
        val message: BlockMessage,
    ) : CoverScreen

    data class Blocked(
        override val groupId: Long,
        val packageName: String,
        val label: String,
        val endsAt: Long,
        val message: BlockMessage,
        val chatsOpen: Boolean,
        override val sound: CooldownSoundChoice,
    ) : CoverScreen
}

@Composable
private fun rememberTimeLeft(endsAt: Long): Long {
    val app = androidx.compose.ui.platform.LocalContext.current.ebb
    val left by produceState((endsAt - app.stableNow()).coerceAtLeast(0), endsAt) {
        while (true) {
            value = (endsAt - app.stableNow()).coerceAtLeast(0)
            delay(500)
        }
    }
    return left
}

private fun Modifier.swipeUp(enabled: Boolean, onSwipe: () -> Unit) = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    var pulled = 0f
    detectVerticalDragGestures(
        onDragStart = { pulled = 0f },
        onVerticalDrag = { _, drag ->
            pulled += drag
            if (pulled < -SWIPE_DISTANCE_PX) {
                pulled = 0f
                onSwipe()
            }
        },
    )
}

private const val SWIPE_DISTANCE_PX = 140f
private const val CLOSE_AFTER_MS = 15_000L
private const val QUOTE_ARRIVAL_MS = 700
private val QUOTE_RISE = 28.dp
private const val GLOW_STEP_MS = 100L
private const val CONFIRM_MS = 4_000L
private val SWIPE_HINT_SPACE = 64.dp
private val SIDE = 28.dp
// Sayings up to this length use the larger of the two quote sizes.
private const val SHORT_QUOTE_CHARS = 80

@Composable
private fun SoothingScreen(
    screen: CoverScreen.Soothing,
    seconds: Int,
    tokenLeft: () -> Boolean,
    onEmergency: (Long) -> Unit,
    onLeave: () -> Unit,
) {
    val left = rememberTimeLeft(screen.endsAt)
    var canClose by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        delay(CLOSE_AFTER_MS)
        canClose = true
        delay((seconds * 1000L - CLOSE_AFTER_MS).coerceAtLeast(0))
        onLeave()
    }
    val breathingIn by produceState(true) {
        while (true) {
            value = isBreathingIn(SystemClock.elapsedRealtime())
            delay(200)
        }
    }
    PaperSea(Sea.DAWN, Modifier.swipeUp(canClose, onLeave)) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.4f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, EbbColors.Night.copy(alpha = 0.6f)))),
        )
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = SIDE, end = SIDE, bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${screen.groupName} is cooling down",
                color = EbbColors.Muted,
                style = EbbType.Title,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            // The group sits above the middle, in the open sky over the waves.
            Spacer(Modifier.weight(1f))
            CoverCenter(screen.message, left)
            Spacer(Modifier.weight(1.3f))
            Text(if (breathingIn) "Breathe in" else "Breathe out", color = EbbColors.Soft, style = EbbType.Body)
            Spacer(Modifier.height(14.dp))
            EmergencyToken(tokenLeft()) { onEmergency(screen.groupId) }
        }
        if (canClose) {
            Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to close") }
        }
    }
}

/**
 * The heart of both covers, read top to bottom: the saying, who said it, then how long is left.
 * The saying rises in with a glow that breathes. The countdown never moves, so it reads at a glance.
 */
@Composable
private fun CoverCenter(message: BlockMessage, left: Long) {
    val arrival = remember(message) { Animatable(0f) }
    LaunchedEffect(message) { arrival.animateTo(1f, tween(QUOTE_ARRIVAL_MS, easing = LinearOutSlowInEasing)) }
    val glow by produceState(0f) {
        while (true) {
            value = breathLevel(SystemClock.elapsedRealtime())
            delay(GLOW_STEP_MS)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val radius = size.maxDimension * 0.6f
                    val light = EbbColors.Mist.copy(alpha = (0.08f + 0.12f * glow) * arrival.value)
                    drawCircle(Brush.radialGradient(listOf(light, Color.Transparent), center, radius), radius)
                }
                .graphicsLayer {
                    alpha = arrival.value
                    val scale = 0.92f + 0.08f * arrival.value
                    translationY = QUOTE_RISE.toPx() * (1f - arrival.value)
                    scaleX = scale
                    scaleY = scale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message.text,
                style = (if (message.text.length <= SHORT_QUOTE_CHARS) EbbType.Quote else EbbType.QuoteLong).copy(
                    color = EbbColors.Text,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 18f),
                ),
            )
            if (message.author != null) {
                Text(
                    "\u2014 ${message.author}",
                    color = EbbColors.Sand,
                    style = EbbType.Caption,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(clock(left), color = EbbColors.Text, style = EbbType.Countdown)
        Text("until you're back", color = EbbColors.Muted, style = EbbType.Caption)
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(EbbColors.Sea).clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(label, color = EbbColors.Text, style = EbbType.Title)
    }
}

/** Today's single way back in. The first tap asks again, so a stray touch can't spend it. */
@Composable
private fun EmergencyToken(available: Boolean, onUse: () -> Unit) {
    if (!available) {
        Text("Token used \u00b7 a new one at midnight", color = EbbColors.Muted, style = EbbType.Caption)
        return
    }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(CONFIRM_MS)
            confirming = false
        }
    }
    val shape = RoundedCornerShape(50)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.clip(shape)
                .background(if (confirming) EbbColors.Sand else EbbColors.Fill)
                .border(0.5.dp, EbbColors.Line, shape)
                .clickable { if (confirming) onUse() else confirming = true }
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text(
                if (confirming) "Tap again for 2 minutes" else "Use emergency token",
                color = if (confirming) EbbColors.Night else EbbColors.Text,
                style = EbbType.Title,
            )
        }
        Text("1 token left today", color = EbbColors.Muted, style = EbbType.Caption, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BlockedScreen(
    screen: CoverScreen.Blocked,
    tokenLeft: () -> Boolean,
    onEmergency: (Long) -> Unit,
    onChats: () -> Unit,
    onLeave: () -> Unit,
) {
    val left = rememberTimeLeft(screen.endsAt)
    LaunchedEffect(left) {
        if (left <= 0) onLeave()
    }
    PaperSea(Sea.NIGHT, Modifier.swipeUp(true, onLeave)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = SIDE, end = SIDE, bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(screen.packageName, Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Text("${screen.label} is cooling down", color = EbbColors.Muted, style = EbbType.Title)
            }
            Spacer(Modifier.weight(1f))
            CoverCenter(screen.message, left)
            Spacer(Modifier.weight(1.2f))
            if (screen.chatsOpen) {
                PillButton("Back to chats", onChats)
                Spacer(Modifier.height(16.dp))
            }
            EmergencyToken(tokenLeft()) { onEmergency(screen.groupId) }
        }
        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to leave") }
    }
}
