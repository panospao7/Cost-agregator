package com.yourname.expensetracker.ui.screens.privacysettings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide serialiser of app-lock settings writes (S9b carry-forward, S12 review fix).
 *
 * A write started by a previous ViewModel instance (screen left and re-entered) must still exclude the
 * new instance's writes, so the lock lives in a singleton instead of a static field. Tests construct a
 * fresh instance. Interim owner: S18 moves write serialisation to the domain owner before any new writer
 * is added.
 */
@Singleton
class AppLockSettingsWriteSerializer @Inject constructor() {

    private val mutex = Mutex()

    /** Runs [block] with exclusive access. Cancellation is not swallowed. */
    suspend fun <T> withExclusiveWrite(block: suspend () -> T): T = mutex.withLock { block() }
}
