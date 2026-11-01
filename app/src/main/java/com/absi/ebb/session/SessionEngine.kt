package com.absi.ebb.session

private const val WARNING_LEAD_MS = 60_000L

private data class RunningUse(val groupId: Long, val packageName: String, val since: Long)

/**
 * The session rules, with no Android code. Callers report what happened and carry out the
 * returned actions, then wake it at [nextDeadline].
 */
class SessionEngine(
    groups: List<GroupRules>,
    states: Map<Long, GroupState> = emptyMap(),
) {
    private val groups = groups.associateBy(GroupRules::id)
    private val states = states.toMutableMap()

    private var foregroundPackage: String? = null
    private var foregroundExempt = false
    private var screenOn = true
    private var inCall = false
    private var running: RunningUse? = null

    val isRunning: Boolean get() = running != null

    fun onForeground(packageName: String?, exempt: Boolean = false, now: Long): List<SessionAction> =
        update(now) {
            foregroundPackage = packageName
            foregroundExempt = exempt
        }

    fun onScreen(on: Boolean, now: Long): List<SessionAction> = update(now) { screenOn = on }

    fun onCall(active: Boolean, now: Long): List<SessionAction> = update(now) { inCall = active }

    fun onTimerDue(now: Long): List<SessionAction> = update(now, coverBlocked = false) {}

    fun state(groupId: Long): GroupState = states[groupId] ?: GroupState()

    fun states(): Map<Long, GroupState> = states.toMap()

    fun remainingMs(groupId: Long, now: Long): Long {
        val rules = groups.getValue(groupId)
        val runningMs = running?.takeIf { it.groupId == groupId }?.let { now - it.since } ?: 0
        return (rules.sessionMs - state(groupId).usedMs - runningMs).coerceAtLeast(0)
    }

    fun canEdit(groupId: Long, now: Long): Boolean {
        val endsAt = state(groupId).cooldownEndsAt
        return endsAt == null || now >= endsAt
    }

    fun nextDeadline(): Long? = groups.values.mapNotNull(::deadlineFor).minOrNull()

    private fun update(
        now: Long,
        coverBlocked: Boolean = true,
        change: () -> Unit,
    ): List<SessionAction> = buildList {
        recordRunningUse(now)
        endCooldowns(now)
        refillRestedGroups(now)
        change()
        warnNearTheEnd()
        val kicked = startDueCooldowns(now)
        applyForeground(now, coverBlocked && kicked.isEmpty())
    }

    private fun MutableList<SessionAction>.recordRunningUse(now: Long) {
        val use = running ?: return
        val rules = groups.getValue(use.groupId)
        val state = state(use.groupId)
        val ms = (now - use.since).coerceAtMost(rules.sessionMs - state.usedMs)
        if (ms > 0) add(SessionAction.RecordUsage(use.groupId, use.packageName, ms))
        states[use.groupId] = state.copy(usedMs = state.usedMs + ms, lastActiveAt = now)
        running = use.copy(since = now)
    }

    private fun MutableList<SessionAction>.endCooldowns(now: Long) {
        states.filterValues { it.cooldownEndsAt != null && now >= it.cooldownEndsAt }.keys.forEach { id ->
            states[id] = GroupState()
            add(SessionAction.CooldownEnded(id))
        }
    }

    private fun refillRestedGroups(now: Long) {
        groups.values
            .filter { it.id != running?.groupId && hasRested(it, now) }
            .forEach { states[it.id] = GroupState() }
    }

    private fun hasRested(rules: GroupRules, now: Long): Boolean {
        val state = state(rules.id)
        val lastActiveAt = state.lastActiveAt ?: return false
        return state.cooldownEndsAt == null && now - lastActiveAt >= rules.resetBreakMs
    }

    private fun MutableList<SessionAction>.warnNearTheEnd() {
        val rules = groups[running?.groupId] ?: return
        val state = state(rules.id)
        val warnAt = rules.sessionMs - WARNING_LEAD_MS
        if (state.warned || warnAt <= 0 || state.usedMs < warnAt || state.usedMs >= rules.sessionMs) return
        states[rules.id] = state.copy(warned = true)
        add(SessionAction.WarnOneMinute(rules.id))
    }

    private fun MutableList<SessionAction>.startDueCooldowns(now: Long): List<GroupRules> {
        if (inCall) return emptyList()
        val due = groups.values.filter {
            val state = state(it.id)
            state.cooldownEndsAt == null && state.usedMs >= it.sessionMs
        }
        due.forEach { rules ->
            val endsAt = now + rules.cooldownMs
            states[rules.id] = state(rules.id).copy(cooldownEndsAt = endsAt)
            add(SessionAction.StartCooldown(rules.id, endsAt, showSoothing = groupOnScreen() == rules))
        }
        return due
    }

    private fun MutableList<SessionAction>.applyForeground(now: Long, coverBlocked: Boolean) {
        val rules = groupOnScreen()
        val packageName = foregroundPackage
        if (rules == null || packageName == null) {
            running = null
            return
        }
        val state = state(rules.id)
        if (state.cooldownEndsAt != null) {
            running = null
            if (coverBlocked && !inCall) {
                add(SessionAction.ShowBlocked(rules.id, packageName, state.cooldownEndsAt))
            }
            return
        }
        val alreadyRunning = running?.groupId == rules.id
        if (!alreadyRunning && state.usedMs == 0L) add(SessionAction.SessionStarted(rules.id))
        running = RunningUse(rules.id, packageName, running?.since?.takeIf { alreadyRunning } ?: now)
    }

    private fun groupOnScreen(): GroupRules? {
        val packageName = foregroundPackage ?: return null
        if (!screenOn || foregroundExempt) return null
        return groups.values.firstOrNull { packageName in it.packages }
    }

    private fun deadlineFor(rules: GroupRules): Long? {
        val state = state(rules.id)
        val use = running?.takeIf { it.groupId == rules.id }
        return when {
            state.cooldownEndsAt != null -> state.cooldownEndsAt
            use != null -> runningDeadline(rules, state, use)
            state.usedMs > 0 -> state.lastActiveAt?.plus(rules.resetBreakMs)
            else -> null
        }
    }

    private fun runningDeadline(rules: GroupRules, state: GroupState, use: RunningUse): Long? {
        if (inCall && state.usedMs >= rules.sessionMs) return null
        val startedAt = use.since - state.usedMs
        val warnAt = rules.sessionMs - WARNING_LEAD_MS
        return startedAt + if (!state.warned && warnAt > 0) warnAt else rules.sessionMs
    }
}
