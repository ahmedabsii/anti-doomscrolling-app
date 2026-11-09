package com.absi.ebb.session

/** A group of apps sharing one session timer and one cooldown. */
data class GroupRules(
    val id: Long,
    val sessionMs: Long,
    val cooldownMs: Long,
    val packages: Set<String>,
) {
    /** A break this long away from the group's apps refills the session. It's always twice the cooldown. */
    val resetBreakMs: Long get() = cooldownMs * 2
}

/** What survives a restart. Times are on the [StableClock] timeline. */
data class GroupState(
    val usedMs: Long = 0,
    val lastActiveAt: Long? = null,
    val cooldownEndsAt: Long? = null,
    val warned: Boolean = false,
    val emergencyEndsAt: Long? = null,
)

sealed interface SessionAction {
    data class SessionStarted(val groupId: Long) : SessionAction
    data class WarnTwoMinutes(val groupId: Long) : SessionAction
    data class StartCooldown(
        val groupId: Long,
        val endsAt: Long,
        val showSoothing: Boolean,
    ) : SessionAction
    data class ShowBlocked(
        val groupId: Long,
        val packageName: String,
        val endsAt: Long,
    ) : SessionAction
    data class CooldownEnded(val groupId: Long) : SessionAction
    data class RecordUsage(
        val groupId: Long,
        val packageName: String,
        val ms: Long,
    ) : SessionAction
}
