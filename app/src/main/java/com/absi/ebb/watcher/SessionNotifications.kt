package com.absi.ebb.watcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.absi.ebb.MainActivity
import com.absi.ebb.R

object SessionNotifications {

    private const val CHANNEL_SESSIONS = "sessions"
    private const val CHANNEL_WINS = "wins"
    private const val CHANNEL_WINS_QUIET = "wins_quiet"
    private const val WIN_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val chime = Uri.parse(
            "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.chime_win}",
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SESSIONS, "Sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_WINS, "Wins", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(chime, attributes)
                },
                NotificationChannel(CHANNEL_WINS_QUIET, "Quiet wins", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                },
            ),
        )
    }

    fun twoMinutesLeft(context: Context, groupId: Long, groupName: String) = post(
        context,
        idFor(groupId),
        CHANNEL_SESSIONS,
        "2 minutes left",
        groupName,
    )

    fun cooldownFinished(context: Context, groupId: Long, groupName: String) = post(
        context,
        idFor(groupId),
        CHANNEL_SESSIONS,
        "Cooldown finished.",
        groupName,
    )

    fun win(context: Context, title: String, text: String, sound: Boolean) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CELEBRATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(
            context,
            WIN_ID,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(context, WIN_ID, if (sound) CHANNEL_WINS else CHANNEL_WINS_QUIET, title, text, tap)
    }

    private fun idFor(groupId: Long) = 1000 + groupId.toInt()

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        tap: PendingIntent? = null,
    ) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (allowed != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_ebb)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
