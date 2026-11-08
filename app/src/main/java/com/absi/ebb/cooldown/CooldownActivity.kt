package com.absi.ebb.cooldown

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.unit.sp
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
                when (val current = screen) {
                    is CoverScreen.Soothing ->
                        SoothingScreen(current, ebb.settings.soothingSeconds, ebb.hasEmergencyToken(), ::openForEmergency, ::leave)
                    is CoverScreen.Blocked -> BlockedScreen(current, ebb.hasEmergencyToken(), ::openForEmergency, ::leave)
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
        sound.start((screen as? CoverScreen.Soothing)?.sound ?: CooldownSoundChoice.SILENT)
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
            CoverScreen.Soothing(groupId, group?.name ?: "Your group", endsAt, group?.sound ?: CooldownSoundChoice.OCEAN, message)
        } else {
            CoverScreen.Blocked(groupId, blockedApp, ebb.apps.label(blockedApp), endsAt, message)
        }
    }

    /** Closing the cover uncovers the app, which the watcher now leaves open for two minutes. */
    private fun openForEmergency(groupId: Long) {
        LiveSession.emergencyRequests.tryEmit(groupId)
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

        fun soothing(context: Context, groupId: Long, endsAt: Long): Intent =
            Intent(context, CooldownActivity::class.java)
                // Otherwise Android reads the cover as the user leaving, and video apps start floating.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_GROUP, groupId)
                .putExtra(EXTRA_ENDS_AT, endsAt)

        fun blocked(context: Context, groupId: Long, app: String, endsAt: Long): Intent =
            soothing(context, groupId, endsAt).putExtra(EXTRA_APP, app)
    }
}

private sealed interface CoverScreen {
    val groupId: Long

    data class Soothing(
        override val groupId: Long,
        val groupName: String,
        val endsAt: Long,
        val sound: CooldownSoundChoice,
        val message: BlockMessage,
    ) : CoverScreen

    data class Blocked(
        override val groupId: Long,
        val packageName: String,
        val label: String,
        val endsAt: Long,
        val message: BlockMessage,
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
private const val QUOTE_ARRIVAL_MS = 1_400
private const val GLOW_STEP_MS = 100L
private const val CONFIRM_MS = 4_000L
private val SWIPE_HINT_SPACE = 64.dp
private val QUOTE_SMALLEST = 18.sp

@Composable
private fun SoothingScreen(
    screen: CoverScreen.Soothing,
    seconds: Int,
    tokenLeft: Boolean,
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
            Modifier.fillMaxSize().systemBarsPadding().padding(bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${screen.groupName} is cooling down",
                color = EbbColors.Soft,
                style = EbbType.Title,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, start = 28.dp, end = 28.dp),
            )
            // The saying sits in the open sky, above the waves, where it is read first.
            CoverMessage(screen.message, Modifier.weight(1f).padding(horizontal = 28.dp))
            Spacer(Modifier.weight(0.7f))
            Text(if (breathingIn) "Breathe in" else "Breathe out", color = EbbColors.Soft, style = EbbType.Body)
            Text(clock(left), color = EbbColors.Text, style = EbbType.Headline)
            Spacer(Modifier.height(14.dp))
            EmergencyToken(tokenLeft) { onEmergency(screen.groupId) }
        }
        if (canClose) {
            Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to close") }
        }
    }
}

/** The saying is the heart of the cover: large, bright, and it rises in softly, with a glow that breathes. */
@Composable
private fun CoverMessage(message: BlockMessage, modifier: Modifier = Modifier) {
    val arrival = remember(message) { Animatable(0f) }
    LaunchedEffect(message) { arrival.animateTo(1f, tween(QUOTE_ARRIVAL_MS, easing = LinearOutSlowInEasing)) }
    val glow by produceState(0f) {
        while (true) {
            value = breathLevel(SystemClock.elapsedRealtime())
            delay(GLOW_STEP_MS)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val radius = size.maxDimension * 0.6f
                val light = EbbColors.Mist.copy(alpha = (0.07f + 0.09f * glow) * arrival.value)
                drawCircle(Brush.radialGradient(listOf(light, Color.Transparent), center, radius), radius)
            }
            .graphicsLayer {
                alpha = arrival.value
                val scale = 0.94f + 0.06f * arrival.value
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            message.text,
            modifier = Modifier.weight(1f, fill = false),
            style = EbbType.Quote.copy(
                color = EbbColors.Text,
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 18f),
            ),
            autoSize = TextAutoSize.StepBased(minFontSize = QUOTE_SMALLEST, maxFontSize = EbbType.Quote.fontSize),
        )
        if (message.author != null) {
            Text(
                "\u2014 ${message.author}",
                color = EbbColors.Sand,
                style = EbbType.Title,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
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
    tokenLeft: Boolean,
    onEmergency: (Long) -> Unit,
    onLeave: () -> Unit,
) {
    val left = rememberTimeLeft(screen.endsAt)
    LaunchedEffect(left) {
        if (left <= 0) onLeave()
    }
    PaperSea(Sea.NIGHT, Modifier.swipeUp(true, onLeave)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(screen.packageName, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${screen.label} is cooling down", color = EbbColors.Soft, style = EbbType.Title)
                    Text("Back in ${clock(left)}", color = EbbColors.Muted, style = EbbType.Caption)
                }
            }
            CoverMessage(screen.message, Modifier.weight(1f).padding(vertical = 16.dp))
            EmergencyToken(tokenLeft) { onEmergency(screen.groupId) }
        }
        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to leave") }
    }
}
