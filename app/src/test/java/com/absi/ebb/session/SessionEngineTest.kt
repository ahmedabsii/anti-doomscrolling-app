package com.absi.ebb.session

import com.absi.ebb.session.SessionAction.CooldownEnded
import com.absi.ebb.session.SessionAction.RecordUsage
import com.absi.ebb.session.SessionAction.SessionStarted
import com.absi.ebb.session.SessionAction.ShowBlocked
import com.absi.ebb.session.SessionAction.StartCooldown
import com.absi.ebb.session.SessionAction.WarnTwoMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val INSTAGRAM = "com.instagram.android"
private const val YOUTUBE = "com.google.android.youtube"
private const val MUSIC = "com.google.android.apps.youtube.music"
private const val REDDIT = "com.reddit.frontpage"
private const val MAPS = "com.google.android.apps.maps"
private const val SOCIAL = 1L
private const val NEWS = 2L

class SessionEngineTest {

    private val social = GroupRules(SOCIAL, 10 * MIN, 30 * MIN, setOf(INSTAGRAM, YOUTUBE, MUSIC))
    private val news = GroupRules(NEWS, 5 * MIN, 60 * MIN, setOf(REDDIT))
    private val engine = SessionEngine(listOf(social, news))

    private fun List<SessionAction>.withoutUsage() = filterNot { it is RecordUsage }

    private fun used(group: Long = SOCIAL) = engine.state(group).usedMs

    @Test
    fun `opening a group app starts a session with the full timer`() {
        val actions = engine.onForeground(INSTAGRAM, now = 0)

        assertEquals(listOf(SessionStarted(SOCIAL)), actions)
        assertEquals(10 * MIN, engine.remainingMs(SOCIAL, now = 0))
    }

    @Test
    fun `switching apps inside a group keeps the same timer running`() {
        engine.onForeground(INSTAGRAM, now = 0)
        val actions = engine.onForeground(YOUTUBE, now = 3 * MIN)
        engine.onForeground(MUSIC, now = 7 * MIN)

        assertEquals(listOf(RecordUsage(SOCIAL, INSTAGRAM, 3 * MIN)), actions)
        assertEquals(7 * MIN, used())
    }

    @Test
    fun `leaving the group pauses the timer`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(MAPS, now = 4 * MIN)
        engine.onTimerDue(now = 10 * MIN)

        assertEquals(4 * MIN, used())
    }

    @Test
    fun `locking the phone pauses the timer and unlocking resumes it`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onScreen(on = false, now = 4 * MIN)
        engine.onTimerDue(now = 9 * MIN)
        assertEquals(4 * MIN, used())

        engine.onScreen(on = true, now = 9 * MIN)
        engine.onTimerDue(now = 11 * MIN)

        assertEquals(6 * MIN, used())
    }

    @Test
    fun `a chat screen pauses the timer and is not recorded as usage`() {
        engine.onForeground(INSTAGRAM, now = 0)
        val intoChat = engine.onForeground(INSTAGRAM, exempt = true, now = 2 * MIN)
        val outOfChat = engine.onForeground(INSTAGRAM, now = 8 * MIN)

        assertEquals(listOf(RecordUsage(SOCIAL, INSTAGRAM, 2 * MIN)), intoChat)
        assertEquals(emptyList<SessionAction>(), outOfChat)
        assertEquals(2 * MIN, used())
    }

    @Test
    fun `a break of twice the cooldown resets used time by default`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(MAPS, now = 2 * MIN)

        val actions = engine.onForeground(INSTAGRAM, now = 62 * MIN)

        assertEquals(listOf(SessionStarted(SOCIAL)), actions)
        assertEquals(0L, used())
    }

    @Test
    fun `coming back a moment before the reset break keeps the time you had left`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(MAPS, now = 9 * MIN)

        val actions = engine.onForeground(INSTAGRAM, now = 69 * MIN - 1)

        assertEquals(emptyList<SessionAction>(), actions)
        assertEquals(MIN, engine.remainingMs(SOCIAL, now = 69 * MIN - 1))
    }

    @Test
    fun `a chosen reset break replaces the default`() {
        val custom = GroupRules(SOCIAL, 10 * MIN, 30 * MIN, setOf(INSTAGRAM), resetBreakMs = 15 * MIN)
        val shortBreak = SessionEngine(listOf(custom))
        shortBreak.onForeground(INSTAGRAM, now = 0)
        shortBreak.onForeground(MAPS, now = 4 * MIN)

        val actions = shortBreak.onForeground(INSTAGRAM, now = 19 * MIN)

        assertEquals(listOf(SessionStarted(SOCIAL)), actions)
    }

    @Test
    fun `scattered use inside the break window starts no cooldown`() {
        val actions = buildList {
            repeat(5) { round ->
                val start = round * 10 * MIN
                addAll(engine.onForeground(INSTAGRAM, now = start))
                addAll(engine.onForeground(MAPS, now = start + MIN))
            }
        }

        assertTrue(actions.none { it is StartCooldown })
        assertEquals(5 * MIN, used())
    }

    @Test
    fun `a session that crosses the reset point keeps counting to the cooldown`() {
        val quick = SessionEngine(listOf(GroupRules(SOCIAL, 10 * MIN, MIN, setOf(INSTAGRAM))))
        quick.onForeground(INSTAGRAM, now = 0)

        quick.onTimerDue(now = 5 * MIN)
        val actions = quick.onTimerDue(now = 10 * MIN)

        assertTrue(actions.any { it is StartCooldown })
    }

    @Test
    fun `warns once when two minutes are left`() {
        engine.onForeground(INSTAGRAM, now = 0)

        val warning = engine.onTimerDue(now = 8 * MIN).withoutUsage()
        val later = engine.onTimerDue(now = 9 * MIN).withoutUsage()
        engine.onForeground(MAPS, now = 9 * MIN)
        val backAgain = engine.onForeground(INSTAGRAM, now = 9 * MIN + 10_000).withoutUsage()

        assertEquals(listOf(WarnTwoMinutes(SOCIAL)), warning)
        assertEquals(emptyList<SessionAction>(), later)
        assertEquals(emptyList<SessionAction>(), backAgain)
    }

    @Test
    fun `sessions of two minutes or less get no warning`() {
        val short = SessionEngine(listOf(GroupRules(SOCIAL, 2 * MIN, 30 * MIN, setOf(INSTAGRAM))))
        short.onForeground(INSTAGRAM, now = 0)

        val actions = short.onTimerDue(now = MIN).withoutUsage()

        assertEquals(emptyList<SessionAction>(), actions)
    }

    @Test
    fun `running out of time kicks you out and starts the cooldown`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(YOUTUBE, now = 4 * MIN)

        val actions = engine.onTimerDue(now = 10 * MIN)

        assertEquals(
            listOf(
                RecordUsage(SOCIAL, YOUTUBE, 6 * MIN),
                StartCooldown(SOCIAL, endsAt = 40 * MIN, showSoothing = true),
            ),
            actions,
        )
        assertEquals(40 * MIN, engine.state(SOCIAL).cooldownEndsAt)
    }

    @Test
    fun `a late timer never records more than the session allows`() {
        engine.onForeground(INSTAGRAM, now = 0)

        val actions = engine.onTimerDue(now = 12 * MIN)

        assertEquals(RecordUsage(SOCIAL, INSTAGRAM, 10 * MIN), actions.first())
        assertEquals(42 * MIN, engine.state(SOCIAL).cooldownEndsAt)
    }

    @Test
    fun `every app in the group is blocked during the cooldown`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onForeground(MAPS, now = 11 * MIN)

        val actions = engine.onForeground(MUSIC, now = 15 * MIN)

        assertEquals(listOf(ShowBlocked(SOCIAL, MUSIC, endsAt = 40 * MIN)), actions)
    }

    @Test
    fun `the end of a cooldown is announced and the next open is a fresh session`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onForeground(MAPS, now = 11 * MIN)

        val ended = engine.onTimerDue(now = 40 * MIN)
        val reopened = engine.onForeground(YOUTUBE, now = 41 * MIN)

        assertEquals(listOf(CooldownEnded(SOCIAL)), ended)
        assertEquals(listOf(SessionStarted(SOCIAL)), reopened)
        assertEquals(10 * MIN, engine.remainingMs(SOCIAL, now = 41 * MIN))
    }

    @Test
    fun `groups run independently`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(REDDIT, now = 2 * MIN)
        engine.onForeground(MAPS, now = 3 * MIN)

        assertEquals(2 * MIN, used(SOCIAL))
        assertEquals(MIN, used(NEWS))
    }

    @Test
    fun `apps outside every group are never timed`() {
        val actions = engine.onForeground(MAPS, now = 0) +
            engine.onTimerDue(now = 500 * MIN)

        assertEquals(emptyList<SessionAction>(), actions)
        assertNull(engine.nextDeadline())
    }

    @Test
    fun `a call delays the kick until it ends`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onCall(active = true, now = 9 * MIN)

        val during = engine.onTimerDue(now = 10 * MIN).withoutUsage()
        val after = engine.onCall(active = false, now = 13 * MIN).withoutUsage()

        assertEquals(emptyList<SessionAction>(), during)
        assertEquals(listOf(StartCooldown(SOCIAL, endsAt = 43 * MIN, showSoothing = true)), after)
    }

    @Test
    fun `a delayed kick skips the soothing screen if you already left the group`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onCall(active = true, now = 9 * MIN)
        engine.onTimerDue(now = 10 * MIN)
        engine.onForeground(MAPS, now = 11 * MIN)

        val after = engine.onCall(active = false, now = 13 * MIN).withoutUsage()

        assertEquals(listOf(StartCooldown(SOCIAL, endsAt = 43 * MIN, showSoothing = false)), after)
    }

    @Test
    fun `a call can use a blocked app until the call ends`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onCall(active = true, now = 12 * MIN)

        val during = engine.onForeground(YOUTUBE, now = 13 * MIN)
        val after = engine.onCall(active = false, now = 14 * MIN)

        assertEquals(emptyList<SessionAction>(), during)
        assertEquals(listOf(ShowBlocked(SOCIAL, YOUTUBE, endsAt = 40 * MIN)), after)
    }

    @Test
    fun `chats stay open during a cooldown and the feed does not`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)

        val inChat = engine.onForeground(INSTAGRAM, exempt = true, now = 12 * MIN)
        val onFeed = engine.onForeground(INSTAGRAM, now = 13 * MIN)

        assertEquals(emptyList<SessionAction>(), inChat)
        assertEquals(listOf(ShowBlocked(SOCIAL, INSTAGRAM, endsAt = 40 * MIN)), onFeed)
    }

    @Test
    fun `a restart keeps the timer and the cooldown`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(MAPS, now = 4 * MIN)
        engine.onForeground(REDDIT, now = 5 * MIN)
        engine.onTimerDue(now = 10 * MIN)

        val restored = SessionEngine(listOf(social, news), engine.states())

        assertEquals(6 * MIN, restored.remainingMs(SOCIAL, now = 11 * MIN))
        assertEquals(
            listOf(ShowBlocked(NEWS, REDDIT, endsAt = 70 * MIN)),
            restored.onForeground(REDDIT, now = 11 * MIN),
        )
    }

    @Test
    fun `a cooldown that ended while the app was stopped is cleared on the next event`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)

        val restored = SessionEngine(listOf(social, news), engine.states())
        val actions = restored.onForeground(INSTAGRAM, now = 90 * MIN)

        assertEquals(listOf(CooldownEnded(SOCIAL), SessionStarted(SOCIAL)), actions)
    }

    @Test
    fun `the next deadline is the warning then the kick then the cooldown end`() {
        engine.onForeground(INSTAGRAM, now = 0)
        assertEquals(8 * MIN, engine.nextDeadline())

        engine.onTimerDue(now = 8 * MIN)
        assertEquals(10 * MIN, engine.nextDeadline())

        engine.onTimerDue(now = 10 * MIN)
        assertEquals(40 * MIN, engine.nextDeadline())
    }

    @Test
    fun `a paused group's next deadline is when its timer refills`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(MAPS, now = 4 * MIN)

        assertEquals(64 * MIN, engine.nextDeadline())
    }

    @Test
    fun `groups can be edited except during a cooldown`() {
        assertTrue(engine.canEdit(SOCIAL, now = 0))

        engine.onForeground(INSTAGRAM, now = 0)
        assertTrue(engine.canEdit(SOCIAL, now = 5 * MIN))

        engine.onTimerDue(now = 10 * MIN)
        assertFalse(engine.canEdit(SOCIAL, now = 20 * MIN))
        assertTrue(engine.canEdit(SOCIAL, now = 40 * MIN))
    }

    @Test
    fun `an emergency opens a cooling group for two minutes without ending its cooldown`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)

        assertTrue(engine.canOpenForEmergency(SOCIAL, now = 12 * MIN))
        val opened = engine.onEmergency(SOCIAL, now = 12 * MIN)
        val inside = engine.onForeground(YOUTUBE, now = 13 * MIN)

        assertEquals(emptyList<SessionAction>(), opened)
        assertEquals(emptyList<SessionAction>(), inside)
        assertTrue(engine.isOpenForEmergency(SOCIAL, now = 13 * MIN))
        assertEquals(40 * MIN, engine.state(SOCIAL).cooldownEndsAt)
        assertEquals(10 * MIN, used())
    }

    @Test
    fun `the block comes back the moment the emergency runs out`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onEmergency(SOCIAL, now = 12 * MIN)
        engine.onForeground(INSTAGRAM, now = 12 * MIN)

        assertEquals(12 * MIN + EMERGENCY_MS, engine.nextDeadline())
        val actions = engine.onTimerDue(now = 12 * MIN + EMERGENCY_MS)

        assertEquals(listOf(ShowBlocked(SOCIAL, INSTAGRAM, 40 * MIN)), actions)
        assertFalse(engine.isOpenForEmergency(SOCIAL, now = 12 * MIN + EMERGENCY_MS))
    }

    @Test
    fun `an emergency only opens a group that is cooling down, and only once at a time`() {
        assertFalse(engine.canOpenForEmergency(SOCIAL, now = 0))
        engine.onEmergency(SOCIAL, now = 0)
        assertFalse(engine.isOpenForEmergency(SOCIAL, now = 0))

        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onEmergency(SOCIAL, now = 11 * MIN)
        assertFalse(engine.canOpenForEmergency(SOCIAL, now = 12 * MIN))
    }

    @Test
    fun `an emergency survives a restart`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)
        engine.onEmergency(SOCIAL, now = 12 * MIN)

        val restored = SessionEngine(listOf(social, news), engine.states())

        assertEquals(emptyList<SessionAction>(), restored.onForeground(INSTAGRAM, now = 13 * MIN))
        assertEquals(listOf(ShowBlocked(SOCIAL, INSTAGRAM, 40 * MIN)), restored.onTimerDue(now = 14 * MIN))
    }

    @Test
    fun `free screens stay open during a cooldown, and leaving them brings the block back`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onTimerDue(now = 10 * MIN)

        val inChats = engine.onForeground(INSTAGRAM, exempt = true, now = 12 * MIN)
        val leftChats = engine.onForeground(INSTAGRAM, exempt = false, now = 13 * MIN)

        assertEquals(emptyList<SessionAction>(), inChats)
        assertEquals(listOf(ShowBlocked(SOCIAL, INSTAGRAM, 40 * MIN)), leftChats)
    }

    @Test
    fun `time on a free screen never uses the session`() {
        engine.onForeground(INSTAGRAM, now = 0)
        engine.onForeground(INSTAGRAM, exempt = true, now = 2 * MIN)
        engine.onForeground(INSTAGRAM, exempt = false, now = 30 * MIN)
        engine.onTimerDue(now = 31 * MIN)

        assertEquals(3 * MIN, used())
    }
}
