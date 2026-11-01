package com.absi.ebb.session

data class BootAnchor(val bootCount: Int, val wallAtBootMs: Long)

/**
 * A timeline that ignores phone clock changes. Within one boot it follows time since boot,
 * and each new boot picks up real time again.
 */
object StableClock {

    fun anchor(stored: BootAnchor?, bootCount: Int, wallMs: Long, elapsedMs: Long): BootAnchor =
        if (stored?.bootCount == bootCount) stored else BootAnchor(bootCount, wallMs - elapsedMs)

    fun now(anchor: BootAnchor, elapsedMs: Long): Long = anchor.wallAtBootMs + elapsedMs
}
