package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide, domain-owned serialiser of app-lock settings writes (S9b carry-forward, S12 review fix; moved from the
 * ui package into the domain by S18 part (ii-a), privacy review F4).
 *
 * One instance is shared by every writer: the settings ViewModel's WRITE 1 (discreet toggle) and WRITE 2 (review-save)
 * and every path of [AppLockSettingsOperations] (WRITE 3 lock apply, WRITE 4 timeout apply and direct timeout write,
 * WRITE 5 plain writes). A write started by a previous ViewModel instance (screen left and re-entered) must still
 * exclude the new instance's writes, so the lock lives in a singleton instead of a static field. Tests construct a
 * fresh instance (or share one to prove the sharing).
 *
 * The lock is NOT reentrant: nothing run inside [withExclusiveWrite] may call [withExclusiveWrite] again (the
 * ViewModel's wrappers never call the operations, and the operations never call the ViewModel). No prompt, and no
 * wait for one, may be held inside it.
 */
@Singleton
class AppLockSettingsWriteSerializer @Inject constructor() {

    private val mutex = Mutex()

    /** Runs [block] with exclusive access. Cancellation is not swallowed. */
    suspend fun <T> withExclusiveWrite(block: suspend () -> T): T = mutex.withLock { block() }
}
