package com.absi.ebb.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.absi.ebb.cooldown.breathLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.foundation.Canvas as ComposeCanvas

// Art is laid out on a page this wide, whatever the phone, so every scene keeps its proportions.
private const val PAGE_WIDTH = 440f
private const val SIDE_MARGIN = 90f
private const val PIXELS_PER_SCREEN_PIXEL = 0.62f
private const val SKY_FADE_END = 0.6f
private const val SWAY_ROOM = 60f
// The sea moves slowly enough that thirty frames a second looks smooth and saves battery.
private const val FRAME_MS = 33L
private const val FRAME_SLACK_MS = 4L
private const val MAX_CACHE_BYTES = 80 * 1024 * 1024
private const val FADE_IN_MS = 450

private val DAWN_SKY = listOf(EbbColors.Foam, lerp(EbbColors.Foam, EbbColors.Mist, 0.25f), lerp(EbbColors.Foam, EbbColors.Mist, 0.45f))
private val DUSK_SKY = listOf(lerp(EbbColors.Night, Color.Black, 0.45f), lerp(EbbColors.Night, Color.Black, 0.25f), lerp(EbbColors.Night, EbbColors.Deep, 0.3f))

private val SEA_STOPS = listOf(
    0f to lerp(EbbColors.Foam, EbbColors.Mist, 0.45f),
    0.22f to EbbColors.Mist,
    0.6f to EbbColors.Sea,
    0.85f to EbbColors.Deep,
    1f to EbbColors.Night,
)

/** Colours from the pale far water to the near-black front sheet, all from one family. */
private fun seaSheets(count: Int): List<Color> = List(count) { index ->
    val at = index.toFloat() / (count - 1)
    val end = SEA_STOPS.indexOfFirst { it.first >= at }.coerceAtLeast(1)
    val (fromAt, from) = SEA_STOPS[end - 1]
    val (toAt, to) = SEA_STOPS[end]
    lerp(from, to, (at - fromAt) / (toAt - fromAt))
}

private fun nightSheets(count: Int): List<Color> = List(count) { index ->
    lerp(lerp(EbbColors.Deep, EbbColors.Sea, 0.12f), lerp(EbbColors.Night, Color.Black, 0.3f), index.toFloat() / (count - 1))
}

class SeaDisc(val x: Float, val y: Float, val radius: Float, val color: Color, val night: Boolean = false)

class SeaBoat(val sheet: Int, val x: Float)

enum class Sea(
    val sky: List<Color>,
    val sheets: List<Color>,
    val top: Float,
    val bottom: Float,
    val smallestWave: Float,
    val largestWave: Float,
    val rise: Float,
    val motion: Float,
    val seed: Float,
    val disc: SeaDisc? = null,
    val boat: SeaBoat? = null,
    val lightSky: Boolean = true,
    val shadow: Float = 0.46f,
) {
    DAWN(DAWN_SKY, seaSheets(11), 0.4f, 0.93f, 7f, 40f, 44f, 1f, 0f, SeaDisc(0.34f, 0.385f, 46f, EbbColors.Sand), SeaBoat(4, 0.8f)),
    NIGHT(DUSK_SKY, nightSheets(7), 0.68f, 0.95f, 6f, 26f, 14f, 0.55f, 2f, SeaDisc(0.76f, 0.08f, 20f, EbbColors.Foam, night = true), SeaBoat(2, 0.3f), lightSky = false, shadow = 0.6f),
    MORNING(DAWN_SKY, seaSheets(8), 0.15f, 0.43f, 5f, 24f, 12f, 0.7f, 1f, SeaDisc(0.74f, 0.15f, 24f, EbbColors.Sand), SeaBoat(3, 0.3f)),
    WELCOME(DAWN_SKY, seaSheets(9), 0.42f, 0.93f, 7f, 38f, 30f, 1f, 5f, SeaDisc(0.52f, 0.41f, 62f, EbbColors.Sand), SeaBoat(3, 0.36f)),
    WIN(
        listOf(EbbColors.Foam, lerp(EbbColors.Foam, EbbColors.Sand, 0.55f), EbbColors.Sand),
        seaSheets(11).mapIndexed { index, color -> lerp(color, EbbColors.Sand, (0.75f - index * 0.25f).coerceAtLeast(0f)) },
        0.42f, 0.93f, 7f, 40f, 30f, 0.9f, 3f, SeaDisc(0.5f, 0.4f, 72f, EbbColors.Foam), SeaBoat(4, 0.5f),
    ),
    LOW_TIDE(DAWN_SKY, seaSheets(5), 0.84f, 0.96f, 6f, 18f, 10f, 0.6f, 4f),
    DUSK(DUSK_SKY, nightSheets(3), 0.9f, 0.97f, 5f, 14f, 0f, 0f, 6f, lightSky = false, shadow = 0.6f),
    ;

    /** Stands in for the art while it's cut, with the same light and dark areas so text stays readable. */
    val backdrop: Brush
        get() = Brush.verticalGradient(0f to sky.first(), top to sky.last(), bottom to sheets.last(), 1f to sheets.last())
}

private val grainTile: Bitmap by lazy {
    val size = 256
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val strength = random.nextInt(13) + if (random.nextInt(30) == 0) 24 else 0
        (strength shl 24) or if (random.nextBoolean()) 0xFFFFFF else 0
    }
    val tile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(tile)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.7f }
    repeat(340) {
        paint.color = if (it % 2 == 0) 0x12FFFFFF else 0x14000000
        val x = random.nextFloat() * size
        val y = random.nextFloat() * size
        canvas.drawLine(x, y, x + random.nextInt(-12, 13), y + random.nextInt(-12, 13), paint)
    }
    tile
}

private fun Canvas.paperGrain(width: Int, height: Int) {
    val paint = Paint().apply {
        shader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
}

private class Sheet(val bitmap: Bitmap, val top: Float, val crest: (Float) -> Float)

/** A scene cut into separate bitmaps. Moving them is cheap, so animating never redraws the paper. */
private class SeaArt(
    val sea: Sea,
    private val pixels: Float,
    private val pageHeight: Float,
    private val sky: Bitmap,
    private val disc: Bitmap?,
    private val boat: Bitmap?,
    private val sheets: List<Sheet>,
) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boatRest = sea.boat?.let { spot ->
        val next = sheets[spot.sheet + 1]
        next.top + next.crest(PAGE_WIDTH * spot.x + SIDE_MARGIN) - 30f
    } ?: 0f

    val bytes: Int = listOfNotNull(sky, disc, boat).sumOf { it.allocationByteCount } + sheets.sumOf { it.bitmap.allocationByteCount }

    /** Sends the bitmaps to the graphics chip early, so the first frame doesn't stall on them. */
    fun prepare() {
        listOfNotNull(sky, disc, boat).forEach(Bitmap::prepareToDraw)
        sheets.forEach { it.bitmap.prepareToDraw() }
    }

    private fun Canvas.place(bitmap: Bitmap, x: Float, y: Float, degrees: Float = 0f, shiftX: Float = 0f, shiftY: Float = 0f) {
        save()
        translate(x, y)
        rotate(degrees)
        translate(shiftX, shiftY)
        scale(1f / pixels, 1f / pixels)
        drawBitmap(bitmap, 0f, 0f, paint)
        restore()
    }

    fun draw(canvas: Canvas, viewWidth: Float, timeMs: Long) {
        val t = timeMs / 1000.0
        val breath = if (sea.motion > 0f) breathLevel(timeMs) else 0.3f
        val motion = sea.motion
        canvas.save()
        canvas.scale(viewWidth / PAGE_WIDTH, viewWidth / PAGE_WIDTH)
        canvas.place(sky, 0f, 0f)
        sea.disc?.let { spot ->
            val edge = spot.radius * 2.5f
            canvas.place(disc!!, PAGE_WIDTH * spot.x - edge, pageHeight * spot.y - edge - breath * sea.rise * 0.3f)
        }
        val last = sheets.lastIndex.coerceAtLeast(1)
        sheets.forEachIndexed { index, sheet ->
            val depth = index.toFloat() / last
            val swayX = sin(t * (0.17 + 0.05 * (index % 3)) + index * 1.7).toFloat() * (7f + depth * 30f) * motion
            val swayY = sin(t * 0.42 + index * 0.9).toFloat() * (1f + depth * 3f) * motion +
                (0.5f - breath) * sea.rise * (0.25f + 0.75f * depth)
            val tilt = sin(t * 0.2 + index * 1.3).toFloat() * 0.2f * motion
            canvas.place(sheet.bitmap, PAGE_WIDTH / 2 + swayX, sheet.top + swayY, tilt, shiftX = -(PAGE_WIDTH / 2 + SIDE_MARGIN))
            if (index == sea.boat?.sheet) {
                val bob = sin(t * 0.8).toFloat() * 1.8f
                val roll = sin(t * 0.6).toFloat() * 3.4f
                canvas.place(boat!!, PAGE_WIDTH * sea.boat.x + swayX * 0.6f, boatRest + swayY + bob + 22f, roll, -40f, -34f)
            }
        }
        canvas.restore()
    }
}

private fun padAbove(wave: Float) = ceil(wave * 2 + 50)

private fun cutSheet(color: Color, wave: Float, seed: Float, crestLine: Float, band: Float, reach: Float, shadow: Float, pixels: Float): Sheet {
    val pad = padAbove(wave)
    val widthPx = ((PAGE_WIDTH + SIDE_MARGIN * 2) * pixels).roundToInt()
    val heightPx = ((pad + reach) * pixels).roundToInt()
    val frequency = 0.0088f - 0.0035f * (wave / 46f).coerceAtMost(1f)
    val phase = seed * 2.13f
    val crest = { x0: Float ->
        val x = x0 + sin(x0 * frequency + phase) * (0.38f / frequency)
        pad + wave * (
            0.72f * sin(x * frequency + phase) +
                0.3f * sin(x * frequency * 2.13f + phase * 1.7f) +
                0.07f * sin(x * frequency * 4.7f + phase * 0.6f)
            ) + sin(x0 * 0.07f + phase) * 0.35f
    }
    val outline = Path().apply {
        moveTo(0f, crest(0f) * pixels)
        var x = 3f
        while (x * pixels <= widthPx) {
            lineTo(x * pixels, crest(x) * pixels)
            x += 3f
        }
        lineTo(widthPx.toFloat(), heightPx.toFloat())
        lineTo(0f, heightPx.toFloat())
        close()
    }
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color.toArgb()
    paint.setShadowLayer(22f * pixels, 0f, -9f * pixels, Color(0xFF040E14).copy(alpha = shadow).toArgb())
    canvas.drawPath(outline, paint)
    paint.clearShadowLayer()
    paint.color = lerp(color, Color.White, 0.2f).toArgb()
    canvas.save()
    canvas.translate(0f, -1.6f * pixels)
    canvas.drawPath(outline, paint)
    canvas.restore()
    paint.shader = LinearGradient(
        0f, (pad - wave) * pixels, 0f, (pad + wave + band) * pixels,
        intArrayOf(lerp(color, Color.White, 0.17f).toArgb(), color.toArgb(), lerp(color, Color.Black, 0.34f).toArgb()),
        floatArrayOf(0f, 0.3f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawPath(outline, paint)
    canvas.paperGrain(widthPx, heightPx)
    return Sheet(bitmap, crestLine - pad, crest)
}

private fun cutDisc(spot: SeaDisc, pixels: Float): Bitmap {
    val middle = spot.radius * 2.5f * pixels
    val bitmap = Bitmap.createBitmap((middle * 2).roundToInt(), (middle * 2).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (ring in 2 downTo 0) {
        paint.color = when {
            ring == 0 -> spot.color
            spot.night -> EbbColors.Foam.copy(alpha = 0.08f / ring)
            else -> Color.White.copy(alpha = 0.4f / ring)
        }.toArgb()
        val shade = if (spot.night) Color.Black.copy(alpha = 0.5f) else EbbColors.Sea.copy(alpha = 0.22f)
        paint.setShadowLayer(12f * pixels, 0f, 0f, shade.toArgb())
        canvas.drawCircle(middle, middle, spot.radius * (1f + 0.34f * ring) * pixels, paint)
    }
    paint.clearShadowLayer()
    canvas.paperGrain(bitmap.width, bitmap.height)
    return bitmap
}

private fun cutBoat(pixels: Float): Bitmap {
    val scale = 1.3f * pixels
    val bitmap = Bitmap.createBitmap((80 * pixels).roundToInt(), (52 * pixels).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun fold(color: Color, vararg points: Float) {
        val path = Path().apply {
            moveTo(points[0] * scale, points[1] * scale)
            for (index in 2 until points.size step 2) lineTo(points[index] * scale, points[index + 1] * scale)
            close()
        }
        paint.color = color.toArgb()
        canvas.drawPath(path, paint)
    }
    paint.setShadowLayer(3.5f * pixels, 0f, 1.5f * pixels, EbbColors.Night.copy(alpha = 0.35f).toArgb())
    fold(EbbColors.Foam, 29f, 3f, 29f, 22f, 15f, 22f)
    fold(lerp(EbbColors.Foam, EbbColors.Mist, 0.45f), 29f, 3f, 43f, 22f, 29f, 22f)
    paint.clearShadowLayer()
    fold(lerp(EbbColors.Foam, EbbColors.Mist, 0.2f), 4f, 20f, 29f, 20f, 29f, 34f, 13f, 34f)
    fold(lerp(EbbColors.Foam, EbbColors.Mist, 0.7f), 29f, 20f, 54f, 20f, 45f, 34f, 29f, 34f)
    return bitmap
}

private fun cutSky(sea: Sea, widthPx: Int, heightPx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, heightPx * SKY_FADE_END, IntArray(sea.sky.size) { sea.sky[it].toArgb() }, null, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
    canvas.paperGrain(widthPx, heightPx)
    return bitmap
}

private suspend fun cutScene(sea: Sea, screen: IntSize): SeaArt = coroutineScope {
    val pixels = screen.width * PIXELS_PER_SCREEN_PIXEL / PAGE_WIDTH
    val pageHeight = PAGE_WIDTH * screen.height / screen.width
    val count = sea.sheets.size
    val band = pageHeight * (sea.bottom - sea.top) / count * 1.9f
    fun crestLine(index: Int) = pageHeight * (sea.top + (sea.bottom - sea.top) * (index.toFloat() / (count - 1)).pow(1.25f))
    fun waveAt(index: Int) = sea.smallestWave + index.toFloat() / (count - 1) * (sea.largestWave - sea.smallestWave)
    val sheets = sea.sheets.mapIndexed { index, color ->
        async {
            val y = crestLine(index)
            // A sheet only has to reach the top of the one in front, which keeps the bitmaps small.
            val reach = if (index == count - 1) {
                pageHeight - y + sea.rise + SWAY_ROOM
            } else {
                crestLine(index + 1) - y + waveAt(index + 1) * 2 + sea.rise + SWAY_ROOM
            }
            cutSheet(color, waveAt(index), index + 1.3f + sea.seed, y, band, reach, sea.shadow, pixels)
        }
    }.awaitAll()
    SeaArt(
        sea, pixels, pageHeight,
        cutSky(sea, (PAGE_WIDTH * pixels).roundToInt(), (pageHeight * pixels).roundToInt()),
        sea.disc?.let { cutDisc(it, pixels) },
        sea.boat?.let { cutBoat(pixels) },
        sheets,
    )
}

private object SeaCache : LruCache<String, SeaArt>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: String, value: SeaArt) = value.bytes
    fun keyFor(sea: Sea, screen: IntSize) = "${sea.name}:${screen.width}x${screen.height}"
}

/** The paper sea behind a screen. Only the frame clock changes, so nothing recomposes as it moves. */
@Composable
fun PaperSea(sea: Sea, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = sea.lightSky }
    }
    Box(modifier.fillMaxSize().background(sea.backdrop)) {
        SeaLayer(sea)
        content()
    }
}

@Composable
private fun SeaLayer(sea: Sea) {
    val screen = LocalWindowInfo.current.containerSize
    val key = remember(sea, screen) { SeaCache.keyFor(sea, screen) }
    var art by remember(key) { mutableStateOf<SeaArt?>(SeaCache[key]) }
    val fade = remember(key) { Animatable(if (art == null) 0f else 1f) }
    val clock = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(key) {
        if (art == null && screen.width > 0) {
            art = withContext(Dispatchers.Default) { cutScene(sea, screen).also { it.prepare(); SeaCache.put(key, it) } }
            fade.animateTo(1f, tween(FADE_IN_MS))
        }
    }
    LaunchedEffect(sea, lifecycle) {
        if (sea.motion == 0f) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val toClock = SystemClock.elapsedRealtime() - withFrameMillis { it }
            var lastFrame = 0L
            while (true) {
                withFrameMillis { frame ->
                    if (frame - lastFrame >= FRAME_MS - FRAME_SLACK_MS) {
                        lastFrame = frame
                        clock.longValue = frame + toClock
                    }
                }
            }
        }
    }
    ComposeCanvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val current = art ?: return@ComposeCanvas
        drawIntoCanvas { current.draw(it.nativeCanvas, size.width, clock.longValue) }
    }
}
