package com.yourname.expensetracker.domain.privacy.applock.pin

/**
 * Source of the device boot count (S24 design decision 3, domain-spec 6.2).
 *
 * The boot count lets the PIN attempt delay detect a reboot: a reboot restarts the current delay tier
 * in full, because `elapsedRealtime` also restarts at boot.
 */
fun interface BootCountSource {

    /**
     * The current boot count, or null when it cannot be read (any exception, or a negative value).
     * Null is never an error: [PinAttemptPolicy] fails closed by re-anchoring an active delay once per
     * process. Never throws for a read failure.
     */
    fun currentBootCount(): Int?
}
