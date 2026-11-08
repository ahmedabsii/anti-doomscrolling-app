package com.absi.ebb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.absi.ebb.ebb
import kotlin.math.round

/** The only colours in the app: one sea family and one warm accent, used sparingly. */
object EbbColors {
    val Foam = Color(0xFFF2F3EE)
    val Mist = Color(0xFFAFCAD0)
    val Sea = Color(0xFF366576)
    val Deep = Color(0xFF17333F)
    val Night = Color(0xFF0A202A)
    val Sand = Color(0xFFF1DDB8)

    val Text = Foam
    val Soft = Foam.copy(alpha = 0.8f)
    val Muted = Mist
    val Line = Foam.copy(alpha = 0.12f)
    val Fill = Foam.copy(alpha = 0.1f)
}

/** The only text sizes in the app. */
object EbbType {
    /** The saying on the cover screens, the first thing the eye should land on. */
    val Quote = TextStyle(fontSize = 34.sp, lineHeight = 1.24.em, fontWeight = FontWeight.Normal)
    val Hero = TextStyle(fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light)
    val Number = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Light)
    val Headline = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light)
    val Value = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
    val Title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
}

@Composable
fun EbbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EbbColors.Sea,
            background = EbbColors.Night,
            surface = EbbColors.Night,
            onSurface = EbbColors.Text,
        ),
        content = content,
    )
}

fun clock(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun minutesText(ms: Long): String = "${(ms + 30_000) / 60_000} min"

fun hoursMinutes(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
}

/** The one card in the app: a sheet of dark paper with a soft shadow. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.shadow(5.dp, shape).background(EbbColors.Deep, shape).border(0.5.dp, EbbColors.Line, shape),
        content = content,
    )
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(EbbColors.Line))
}

@Composable
fun StatStrip(stats: List<Pair<String, String>>) {
    PaperCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            stats.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.size(0.5.dp, 32.dp).background(EbbColors.Line))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = EbbColors.Muted, style = EbbType.Caption)
                    Text(value, color = EbbColors.Text, style = EbbType.Value)
                }
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val apps = LocalContext.current.ebb.apps
    val icon by produceState(apps.cachedIcon(packageName), packageName) { value = apps.icon(packageName) }
    icon?.let { Image(it, contentDescription = null, modifier = modifier) }
        ?: Box(modifier.background(EbbColors.Fill))
}

@Composable
fun IconStack(packages: List<String>, size: Dp = 36.dp) {
    Row {
        packages.take(3).forEachIndexed { index, name ->
            AppIcon(
                name,
                Modifier.offset(x = (-13).dp * index).size(size).clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** A line that falls as a session's time runs out, and refills as a cooldown passes. */
@Composable
fun Waterline(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(1.dp)
    Box(modifier.fillMaxWidth().height(2.dp).clip(shape).background(EbbColors.Line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color, shape))
    }
}

private const val BOATS_ON_SHORE = 7

/** Your shore: one paper boat for every calmer day in the current run of seven. */
@Composable
fun Shore(streakDays: Int, modifier: Modifier = Modifier) {
    val lit = if (streakDays == 0) 0 else (streakDays - 1) % BOATS_ON_SHORE + 1
    val boat = remember {
        Path().apply {
            moveTo(12f, 1f)
            lineTo(19f, 12f)
            lineTo(5f, 12f)
            close()
            moveTo(0f, 13.5f)
            lineTo(24f, 13.5f)
            lineTo(19f, 21f)
            lineTo(5f, 21f)
            close()
        }
    }
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val unit = 20.dp.toPx() / 24f
        val gap = size.width / BOATS_ON_SHORE
        repeat(BOATS_ON_SHORE) { index ->
            val color = when {
                index >= lit -> EbbColors.Line
                index == lit - 1 -> EbbColors.Sand
                else -> EbbColors.Mist
            }
            translate(gap * index + (gap - 20.dp.toPx()) / 2, 8.dp.toPx()) {
                scale(unit, pivot = Offset.Zero) { drawPath(boat, color) }
            }
        }
    }
}

enum class Glyph(val path: String) {
    GRID("M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"),
    BARS("M5 20v-7M12 20V5M19 20v-10"),
    GEAR("M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"),
    PLUS("M12 5v14M5 12h14"),
    BACK("M15 5l-7 7 7 7"),
    CHECK("M5 13l4 4 10-10"),
    NEXT("M9 5l7 7-7 7"),
    UP("M6 14l6-6 6 6"),
    LOCK("M7.500 11h9a2.500 2.500 0 0 1 2.500 2.500v5a2.500 2.500 0 0 1-2.500 2.500h-9a2.500 2.500 0 0 1-2.500-2.500v-5a2.500 2.500 0 0 1 2.500-2.500zM8 11V8a4 4 0 0 1 8 0v3"),
}

@Composable
fun EbbIcon(glyph: Glyph, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun EbbSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val latest by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    fun pick(x: Float, width: Int) {
        val raw = range.start + (x / width).coerceIn(0f, 1f) * span
        latest((round(raw / step) * step).coerceIn(range))
    }
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { pick(it.x, size.width) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) }
            },
    ) {
        val middle = size.height / 2
        val knob = 10.dp.toPx()
        val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
        val x = knob + fraction * (size.width - knob * 2)
        val line = 4.dp.toPx()
        drawLine(EbbColors.Line, Offset(knob, middle), Offset(size.width - knob, middle), line, StrokeCap.Round)
        drawLine(EbbColors.Mist, Offset(knob, middle), Offset(x, middle), line, StrokeCap.Round)
        drawCircle(EbbColors.Mist, knob, Offset(x, middle))
    }
}

@Composable
fun SwipeUpHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EbbIcon(Glyph.UP, EbbColors.Soft)
        Text(text, color = EbbColors.Soft, style = EbbType.Caption)
    }
}

enum class Tab(val label: String, val glyph: Glyph) {
    GROUPS("Groups", Glyph.GRID),
    PROGRESS("Progress", Glyph.BARS),
    SETTINGS("Settings", Glyph.GEAR),
}

@Composable
fun EbbTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(99.dp)
    Row(modifier.fillMaxWidth().shadow(8.dp, shape).background(EbbColors.Deep, shape).border(0.5.dp, EbbColors.Line, shape).padding(5.dp)) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier.weight(1f).height(54.dp).clip(shape)
                    .background(if (active) EbbColors.Fill else Color.Transparent)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val color = if (active) EbbColors.Text else EbbColors.Muted
                EbbIcon(tab.glyph, color, 22.dp)
                Text(tab.label, color = color, style = EbbType.Caption)
            }
        }
    }
}
