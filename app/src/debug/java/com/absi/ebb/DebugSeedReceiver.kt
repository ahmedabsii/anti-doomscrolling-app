package com.absi.ebb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.absi.ebb.data.Group

/** Debug builds only: lets end-to-end tests set up short sessions and past usage over adb. */
class DebugSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.ebb
        val today = app.today()
        when (intent.getStringExtra("command")) {
            "group" -> {
                app.settings.onboarded = true
                val id = app.store.saveGroup(
                    Group(
                        name = intent.getStringExtra("name") ?: "Social",
                        sessionMs = intent.getLongExtra("session", 10_000),
                        cooldownMs = intent.getLongExtra("cooldown", 20_000),
                        packages = intent.getStringExtra("apps").orEmpty().split(',').toSet(),
                    ),
                )
                Log.i(TAG, "group=$id")
            }
            // Shows first-time setup again on the next open, keeping groups and history.
            "setup" -> {
                app.settings.onboarded = false
                Log.i(TAG, "setup")
            }
            "history" -> {
                val group = app.store.groups().first()
                val app0 = group.packages.first()
                // Minutes for each past day, oldest first, ending yesterday.
                val minutes = intent.getStringExtra("minutes").orEmpty().split(',').map(String::toLong)
                minutes.forEachIndexed { index, value ->
                    app.store.addUsage(today - minutes.size + index, group.id, app0, value * 60_000)
                }
                app.settings.lastReviewDay = Long.MIN_VALUE
                Log.i(TAG, "history=${minutes.size}")
            }
        }
    }

    private companion object {
        const val TAG = "EbbSeed"
    }
}
