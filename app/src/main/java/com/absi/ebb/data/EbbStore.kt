package com.absi.ebb.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.edit
import com.absi.ebb.session.BootAnchor
import com.absi.ebb.session.GroupRules
import com.absi.ebb.session.GroupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CooldownSoundChoice(val label: String) {
    RIVER("River"),
    RAIN("Rain"),
    PIANO("Classical piano"),
    SILENT("Silent"),
    ;

    companion object {
        /** Groups saved with a sound that no longer exists, such as the old ocean, get the river. */
        fun stored(name: String): CooldownSoundChoice = entries.firstOrNull { it.name == name } ?: RIVER
    }
}

data class Group(
    val id: Long = 0,
    val name: String,
    val sessionMs: Long,
    val cooldownMs: Long,
    val sound: CooldownSoundChoice = CooldownSoundChoice.RIVER,
    val keepChatsFree: Boolean = true,
    val packages: Set<String>,
    val resetBreakMs: Long? = null,
) {
    /** A chosen break, or twice the cooldown when none was chosen. */
    val effectiveResetBreakMs: Long get() = resetBreakMs ?: (cooldownMs * 2)

    fun rules() = GroupRules(id, sessionMs, cooldownMs, packages, effectiveResetBreakMs)
}

/** Everything Ebb remembers. It stays on the phone, and the app has no internet permission. */
class EbbStore(context: Context) : SQLiteOpenHelper(context, "ebb.db", null, 3) {

    private val _revision = MutableStateFlow(0L)
    private val _groupRevision = MutableStateFlow(0L)

    /** Moves on after any write, so screens know to read again. */
    val revision: StateFlow<Long> = _revision

    /** Moves on only when groups change, so the watcher knows to reload its rules. */
    val groupRevision: StateFlow<Long> = _groupRevision

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE app_groups(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
               session_ms INTEGER NOT NULL, cooldown_ms INTEGER NOT NULL, sound TEXT NOT NULL,
               chats_free INTEGER NOT NULL, reset_break_ms INTEGER)""",
        )
        db.execSQL("CREATE TABLE group_apps(package TEXT PRIMARY KEY, group_id INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE group_state(group_id INTEGER PRIMARY KEY, used_ms INTEGER NOT NULL,
               last_active INTEGER, cooldown_ends INTEGER, warned INTEGER NOT NULL, emergency_ends INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE daily_usage(day INTEGER NOT NULL, group_id INTEGER NOT NULL,
               package TEXT NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(day, group_id, package))""",
        )
        db.execSQL(
            """CREATE TABLE daily_counts(day INTEGER NOT NULL, group_id INTEGER NOT NULL,
               sessions INTEGER NOT NULL, cooldowns INTEGER NOT NULL, PRIMARY KEY(day, group_id))""",
        )
        db.execSQL("CREATE TABLE wins(day INTEGER NOT NULL, group_id INTEGER NOT NULL, PRIMARY KEY(day, group_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE app_groups ADD COLUMN reset_break_ms INTEGER")
        if (oldVersion < 3) db.execSQL("ALTER TABLE group_state ADD COLUMN emergency_ends INTEGER")
    }

    fun groups(): List<Group> {
        val members = mutableMapOf<Long, MutableSet<String>>()
        readableDatabase.rawQuery("SELECT group_id, package FROM group_apps", null).useRows {
            members.getOrPut(it.getLong(0)) { mutableSetOf() } += it.getString(1)
        }
        val groups = mutableListOf<Group>()
        readableDatabase.rawQuery(
            "SELECT id, name, session_ms, cooldown_ms, sound, chats_free, reset_break_ms FROM app_groups ORDER BY id",
            null,
        ).useRows {
            groups += Group(
                id = it.getLong(0),
                name = it.getString(1),
                sessionMs = it.getLong(2),
                cooldownMs = it.getLong(3),
                sound = CooldownSoundChoice.stored(it.getString(4)),
                keepChatsFree = it.getInt(5) == 1,
                packages = members[it.getLong(0)].orEmpty(),
                resetBreakMs = if (it.isNull(6)) null else it.getLong(6),
            )
        }
        return groups
    }

    fun group(id: Long): Group? = groups().firstOrNull { it.id == id }

    /** Saves a group. Each app belongs to one group, so its apps leave any other group. */
    fun saveGroup(group: Group): Long {
        val db = writableDatabase
        var id = group.id
        db.transaction {
            val row = ContentValues().apply {
                put("name", group.name)
                put("session_ms", group.sessionMs)
                put("cooldown_ms", group.cooldownMs)
                put("sound", group.sound.name)
                put("chats_free", if (group.keepChatsFree) 1 else 0)
                put("reset_break_ms", group.resetBreakMs)
            }
            if (id == 0L) {
                id = db.insertOrThrow("app_groups", null, row)
            } else {
                db.update("app_groups", row, "id = ?", arrayOf(id.toString()))
            }
            db.delete("group_apps", "group_id = ?", arrayOf(id.toString()))
            group.packages.forEach { packageName ->
                db.insertWithOnConflict(
                    "group_apps",
                    null,
                    ContentValues().apply {
                        put("package", packageName)
                        put("group_id", id)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        groupsChanged()
        return id
    }

    fun deleteGroup(id: Long) {
        val db = writableDatabase
        db.transaction {
            db.delete("app_groups", "id = ?", arrayOf(id.toString()))
            db.delete("group_apps", "group_id = ?", arrayOf(id.toString()))
            db.delete("group_state", "group_id = ?", arrayOf(id.toString()))
        }
        groupsChanged()
    }

    fun states(): Map<Long, GroupState> {
        val states = mutableMapOf<Long, GroupState>()
        readableDatabase.rawQuery(
            "SELECT group_id, used_ms, last_active, cooldown_ends, warned, emergency_ends FROM group_state",
            null,
        ).useRows {
            states[it.getLong(0)] = GroupState(
                usedMs = it.getLong(1),
                lastActiveAt = if (it.isNull(2)) null else it.getLong(2),
                cooldownEndsAt = if (it.isNull(3)) null else it.getLong(3),
                warned = it.getInt(4) == 1,
                emergencyEndsAt = if (it.isNull(5)) null else it.getLong(5),
            )
        }
        return states
    }

    fun saveStates(states: Map<Long, GroupState>) {
        val db = writableDatabase
        db.transaction {
            db.delete("group_state", null, null)
            states.forEach { (id, state) ->
                db.insertOrThrow(
                    "group_state",
                    null,
                    ContentValues().apply {
                        put("group_id", id)
                        put("used_ms", state.usedMs)
                        put("last_active", state.lastActiveAt)
                        put("cooldown_ends", state.cooldownEndsAt)
                        put("warned", if (state.warned) 1 else 0)
                        put("emergency_ends", state.emergencyEndsAt)
                    },
                )
            }
        }
    }

    fun addUsage(day: Long, groupId: Long, packageName: String, ms: Long) {
        writableDatabase.execSQL(
            """INSERT INTO daily_usage(day, group_id, package, ms) VALUES(?, ?, ?, ?)
               ON CONFLICT(day, group_id, package) DO UPDATE SET ms = ms + excluded.ms""",
            arrayOf<Any>(day, groupId, packageName, ms),
        )
        changed()
    }

    fun addSession(day: Long, groupId: Long) = addCount(day, groupId, sessions = 1, cooldowns = 0)

    fun addCooldown(day: Long, groupId: Long) = addCount(day, groupId, sessions = 0, cooldowns = 1)

    private fun addCount(day: Long, groupId: Long, sessions: Int, cooldowns: Int) {
        writableDatabase.execSQL(
            """INSERT INTO daily_counts(day, group_id, sessions, cooldowns) VALUES(?, ?, ?, ?)
               ON CONFLICT(day, group_id) DO UPDATE SET sessions = sessions + excluded.sessions,
               cooldowns = cooldowns + excluded.cooldowns""",
            arrayOf<Any>(day, groupId, sessions, cooldowns),
        )
        changed()
    }

    fun addWin(day: Long, groupId: Long) {
        writableDatabase.insertWithOnConflict(
            "wins",
            null,
            ContentValues().apply {
                put("day", day)
                put("group_id", groupId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        changed()
    }

    fun winDays(): Set<Long> {
        val days = mutableSetOf<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT day FROM wins", null).useRows { days += it.getLong(0) }
        return days
    }

    fun firstDay(): Long? {
        readableDatabase.rawQuery("SELECT MIN(day) FROM daily_usage", null).use {
            return if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    /** Milliseconds used on each day in the range, with zero for days with no use. */
    fun usageByDay(fromDay: Long, toDay: Long, groupId: Long? = null): List<Long> {
        val filter = if (groupId == null) "" else " AND group_id = $groupId"
        val totals = mutableMapOf<Long, Long>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(ms) FROM daily_usage WHERE day BETWEEN ? AND ?$filter GROUP BY day",
            arrayOf(fromDay.toString(), toDay.toString()),
        ).useRows { totals[it.getLong(0)] = it.getLong(1) }
        return (fromDay..toDay).map { totals[it] ?: 0L }
    }

    fun usageByApp(fromDay: Long, toDay: Long): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        readableDatabase.rawQuery(
            "SELECT package, SUM(ms) FROM daily_usage WHERE day BETWEEN ? AND ? GROUP BY package ORDER BY 2 DESC",
            arrayOf(fromDay.toString(), toDay.toString()),
        ).useRows { totals[it.getString(0)] = it.getLong(1) }
        return totals
    }

    fun countsOn(day: Long): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(sessions), 0), COALESCE(SUM(cooldowns), 0) FROM daily_counts WHERE day = ?",
            arrayOf(day.toString()),
        ).use {
            it.moveToFirst()
            return it.getInt(0) to it.getInt(1)
        }
    }

    fun resetProgress() {
        val db = writableDatabase
        db.transaction {
            db.delete("daily_usage", null, null)
            db.delete("daily_counts", null, null)
            db.delete("wins", null, null)
        }
        changed()
    }

    private fun changed() {
        _revision.value += 1
    }

    private fun groupsChanged() {
        _groupRevision.value += 1
        changed()
    }

    private inline fun SQLiteDatabase.transaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private inline fun Cursor.useRows(block: (Cursor) -> Unit) = use {
        while (it.moveToNext()) block(it)
    }
}

class EbbSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ebb", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit { putBoolean("onboarded", value) }

    var soothingSeconds: Int
        get() = prefs.getInt("soothing_seconds", 60)
        set(value) = prefs.edit { putInt("soothing_seconds", value) }

    var celebrationSounds: Boolean
        get() = prefs.getBoolean("celebration_sounds", true)
        set(value) = prefs.edit { putBoolean("celebration_sounds", value) }

    var lastBlockMessage: String?
        get() = prefs.getString("last_block_message", null)
        set(value) = prefs.edit { putString("last_block_message", value) }

    /** The day today's emergency token was spent, on the stable clock. */
    var emergencyTokenDay: Long
        get() = prefs.getLong("emergency_token_day", Long.MIN_VALUE)
        set(value) = prefs.edit { putLong("emergency_token_day", value) }

    var lastReviewDay: Long
        get() = prefs.getLong("last_review_day", Long.MIN_VALUE)
        set(value) = prefs.edit { putLong("last_review_day", value) }

    var bootAnchor: BootAnchor?
        get() = if (prefs.contains("boot_count")) {
            BootAnchor(prefs.getInt("boot_count", 0), prefs.getLong("wall_at_boot", 0))
        } else {
            null
        }
        set(value) = prefs.edit {
            if (value != null) {
                putInt("boot_count", value.bootCount)
                putLong("wall_at_boot", value.wallAtBootMs)
            }
        }
}
