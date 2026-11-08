package com.absi.ebb.cooldown

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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

// Kept low on purpose: the sound should sit in the background, never fill the room.
private const val MAX_VOLUME = 0.16f
// Starts at half volume so it is heard from the first second, then rises gently to the full level.
private const val START_LEVEL = 0.5f
private const val FADE_IN_MS = 12_000L
private const val FADE_OUT_MS = 900L
private const val FADE_STEP_MS = 50L
private const val EMPTY_BREATH_LEVEL = 0.8f

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
    private var track: MediaPlayer? = null
    private var fade: Job? = null
    private var volume = 0f

    fun start(choice: CooldownSoundChoice) {
        // Taking focus also stops whatever the kicked-out app was playing.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).build()
        focus = request
        val granted = audio.requestAudioFocus(request)
        Log.i(TAG, "audio focus result=$granted sound=$choice")
        val sound = rawFor(choice) ?: return
        val player = MediaPlayer()
        player.setAudioAttributes(attributes)
        context.resources.openRawResourceFd(sound).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        player.isLooping = true
        player.setVolume(0f, 0f)
        player.prepare()
        // The river loop is exactly ten breaths long, so starting here keeps its swells in step with the tide on screen.
        player.seekTo(SystemClock.elapsedRealtime().mod(player.duration.toLong()), MediaPlayer.SEEK_CLOSEST)
        player.start()
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
    private suspend fun followBreath(player: MediaPlayer) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val progress = ((now - startedAt).toFloat() / FADE_IN_MS).coerceAtMost(1f)
            val rise = START_LEVEL + (1f - START_LEVEL) * (1f - (1f - progress) * (1f - progress))
            volume = MAX_VOLUME * rise * (EMPTY_BREATH_LEVEL + (1f - EMPTY_BREATH_LEVEL) * breathLevel(now))
            player.setVolume(volume, volume)
            delay(FADE_STEP_MS)
        }
    }

    private suspend fun ramp(player: MediaPlayer, from: Float, to: Float, durationMs: Long) {
        val steps = (durationMs / FADE_STEP_MS).toInt()
        for (step in 1..steps) {
            val level = from + (to - from) * step / steps
            player.setVolume(level, level)
            delay(FADE_STEP_MS)
        }
    }

    @RawRes
    private fun rawFor(choice: CooldownSoundChoice): Int? = when (choice) {
        CooldownSoundChoice.RIVER -> R.raw.sound_river
        CooldownSoundChoice.RAIN -> R.raw.sound_rain
        CooldownSoundChoice.PIANO -> R.raw.sound_piano
        CooldownSoundChoice.SILENT -> null
    }

    private companion object {
        const val TAG = "EbbSound"
    }
}
