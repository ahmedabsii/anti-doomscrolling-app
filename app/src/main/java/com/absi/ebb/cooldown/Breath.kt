package com.absi.ebb.cooldown

private const val BREATH_IN_MS = 4_000L
private const val BREATH_CYCLE_MS = 10_000L

fun isBreathingIn(timeMs: Long): Boolean = timeMs.mod(BREATH_CYCLE_MS) < BREATH_IN_MS

/** How full the breath is on a shared clock, from 0 to 1. The sea and the sound both follow it. */
fun breathLevel(timeMs: Long): Float {
    val at = timeMs.mod(BREATH_CYCLE_MS)
    val progress = if (at < BREATH_IN_MS) {
        at.toFloat() / BREATH_IN_MS
    } else {
        1f - (at - BREATH_IN_MS).toFloat() / (BREATH_CYCLE_MS - BREATH_IN_MS)
    }
    return progress * progress * (3f - 2f * progress)
}
