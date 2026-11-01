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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
                    is CoverScreen.Soothing -> SoothingScreen(current, ebb.settings.soothingSeconds, ::leave)
                    is CoverScreen.Blocked -> BlockedScreen(current, ::leave)
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
        val group = ebb.store.group(intent.getLongExtra(EXTRA_GROUP, 0))
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0)
        val blockedApp = intent.getStringExtra(EXTRA_APP)
        val message = BlockMessages.pick(BlockMessages.load(this), ebb.settings.lastBlockMessage)
        ebb.settings.lastBlockMessage = message.text
        return if (blockedApp == null) {
            CoverScreen.Soothing(group?.name ?: "Your group", endsAt, group?.sound ?: CooldownSoundChoice.OCEAN, message)
        } else {
            CoverScreen.Blocked(blockedApp, ebb.apps.label(blockedApp), endsAt, message)
        }
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
    data class Soothing(val groupName: String, val endsAt: Long, val sound: CooldownSoundChoice, val message: BlockMessage) : CoverScreen
    data class Blocked(val packageName: String, val label: String, val endsAt: Long, val message: BlockMessage) : CoverScreen
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
private val SWIPE_HINT_SPACE = 64.dp

@Composable
private fun SoothingScreen(screen: CoverScreen.Soothing, seconds: Int, onLeave: () -> Unit) {
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
        // Keeps the text readable when a long quote reaches up into the paler water.
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, EbbColors.Night.copy(alpha = 0.6f)))),
        )
        Text(
            "${screen.groupName} is cooling down",
            color = EbbColors.Ink,
            style = EbbType.Title,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 24.dp, start = 28.dp, end = 28.dp),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (breathingIn) "Breathe in" else "Breathe out", color = EbbColors.Text, style = EbbType.Value)
            Text(clock(left), color = EbbColors.Text, style = EbbType.Number)
            Spacer(Modifier.height(8.dp))
            CoverMessage(screen.message)
        }
        if (canClose) {
            Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to close") }
        }
    }
}

@Composable
private fun CoverMessage(message: BlockMessage) {
    Column(Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message.text, color = EbbColors.Soft, style = EbbType.Body, textAlign = TextAlign.Center)
        if (message.author != null) {
            Text("\u2014 ${message.author}", color = EbbColors.Muted, style = EbbType.Caption, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun BlockedScreen(screen: CoverScreen.Blocked, onLeave: () -> Unit) {
    val left = rememberTimeLeft(screen.endsAt)
    LaunchedEffect(left) {
        if (left <= 0) onLeave()
    }
    PaperSea(Sea.NIGHT, Modifier.swipeUp(true, onLeave)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = SWIPE_HINT_SPACE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // On a short screen these gaps close up first, so a long quote still fits.
            Spacer(Modifier.weight(84f, fill = false).height(84.dp))
            AppIcon(screen.packageName, Modifier.size(72.dp).clip(RoundedCornerShape(21.dp)))
            Spacer(Modifier.weight(24f, fill = false).height(24.dp))
            Text("${screen.label} is cooling down", color = EbbColors.Text, style = EbbType.Headline, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text("Back in", color = EbbColors.Soft, style = EbbType.Caption)
            Text(clock(left), color = EbbColors.Text, style = EbbType.Number)
            Spacer(Modifier.height(10.dp))
            CoverMessage(screen.message)
        }
        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) { SwipeUpHint("Swipe up to leave") }
    }
}
