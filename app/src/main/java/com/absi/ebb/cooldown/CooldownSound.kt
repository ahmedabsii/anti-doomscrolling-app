package com.absi.ebb.cooldown

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RawRes
import com.absi.ebb.R
import com.absi.ebb.data.CooldownSoundChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 22_050
private const val WAV_HEADER_BYTES = 44
private const val MAX_VOLUME = 0.7f
private const val FADE_IN_MS = 3_000L
private const val FADE_OUT_MS = 900L
private const val FADE_STEP_MS = 50L
private const val EMPTY_BREATH_LEVEL = 0.55f

/** Plays one looped sound while the cooldown screen is showing, and only then. */
class CooldownSound(private val context: Context) {

    // Not tied to the screen: closing it must not cancel the fade that stops the sound.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var focus: AudioFocusRequest? = null
    private var track: AudioTrack? = null
    private var fade: Job? = null
    private var volume = 0f

    fun start(choice: CooldownSoundChoice) {
        // Taking focus also stops whatever the kicked-out app was playing.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).build()
        focus = request
        val granted = audio.requestAudioFocus(request)
        Log.i(TAG, "audio focus result=$granted sound=$choice")
        val sound = rawFor(choice) ?: return
        val samples = load(sound)
        val player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        player.write(samples, 0, samples.size)
        player.setLoopPoints(0, samples.size, -1)
        player.setVolume(0f)
        player.play()
        track = player
        fade = scope.launch { followBreath(player) }
    }

    fun stop() {
        fade?.cancel()
        val player = track
        track = null
        val request = focus
        focus = null
        scope.launch {
            if (player != null) {
                ramp(player, volume, 0f, FADE_OUT_MS)
                player.stop()
                player.release()
            }
            request?.let(audio::abandonAudioFocusRequest)
        }
    }

    /** Swells with the breath shown on screen, so the sound and the tide rise together. */
    private suspend fun followBreath(player: AudioTrack) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val fadeIn = ((now - startedAt).toFloat() / FADE_IN_MS).coerceAtMost(1f)
            volume = MAX_VOLUME * fadeIn * (EMPTY_BREATH_LEVEL + (1f - EMPTY_BREATH_LEVEL) * breathLevel(now))
            player.setVolume(volume)
            delay(FADE_STEP_MS)
        }
    }

    private suspend fun ramp(player: AudioTrack, from: Float, to: Float, durationMs: Long) {
        val steps = (durationMs / FADE_STEP_MS).toInt()
        for (step in 1..steps) {
            player.setVolume(from + (to - from) * step / steps)
            delay(FADE_STEP_MS)
        }
    }

    private fun load(@RawRes sound: Int): ShortArray {
        val bytes = context.resources.openRawResource(sound).use { it.readBytes() }
        val samples = ShortArray((bytes.size - WAV_HEADER_BYTES) / Short.SIZE_BYTES)
        ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return samples
    }

    @RawRes
    private fun rawFor(choice: CooldownSoundChoice): Int? = when (choice) {
        CooldownSoundChoice.OCEAN -> R.raw.sound_ocean
        CooldownSoundChoice.RAIN -> R.raw.sound_rain
        CooldownSoundChoice.PIANO -> R.raw.sound_piano
        CooldownSoundChoice.SILENT -> null
    }

    private companion object {
        const val TAG = "EbbSound"
    }
}
