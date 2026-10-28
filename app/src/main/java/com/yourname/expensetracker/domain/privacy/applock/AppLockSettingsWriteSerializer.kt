package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide, domain-owned serialiser of app-lock settings writes (S9b carry-forward, S12 review fix; moved from the
 * ui package into the domain by S18 part (ii-a), privacy review F4).
 *
 * One instance is shared by every writer, and only [AppLockSettingsOperations] paths take the lock (WRITE 3 lock
 * apply, WRITE 4 timeout apply and direct timeout write, WRITE 5 plain writes, WRITE 2b review-save). The settings
 * ViewModel reaches WRITE 1 (discreet toggle) and WRITE 2 (review-save) only through WRITE 5 and 2b, and must not
 * wrap them or take the lock itself. A write started by a previous ViewModel instance (screen left and re-entered)
 * must still exclude the new instance's writes, so the lock lives in a singleton instead of a static field. Tests
 * construct a fresh instance (or share one to prove the sharing).
 *
 * The lock is NOT reentrant: nothing run inside [withExclusiveWrite] may call [withExclusiveWrite] again (the
 * operations never call the ViewModel, and the ViewModel never wraps the operations). No prompt, and no wait for
 * one, may be held inside it.
 */
@Singleton
class AppLockSettingsWriteSerializer @Inject constructor() {

    private val mutex = Mutex()

    /** Runs [block] with exclusive access. Cancellation is not swallowed. */
    suspend fun <T> withExclusiveWrite(block: suspend () -> T): T = mutex.withLock { block() }
}
