package com.yourname.expensetracker.data.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R27-S3: process-scoped, deliberately NOT persisted "relaunch the app" request.
 *
 * Set only by the in-process [com.yourname.expensetracker.domain.backup.DatabaseImportResult.SuccessNeedsRestart]
 * branch of a restore the user just ran. The global restart-required lock screen
 * (MainActivity) waits on [requested] to relaunch automatically even when the restore screen
 * has already left composition, and even if the lock screen is composed slightly before the
 * flag is set. Because it lives only in memory, a fresh process (including a cold start that
 * reads a persisted restart-required maintenance mode) always sees `false`, so there is no
 * relaunch loop.
 */
@Singleton
class RestoreRestartSignal @Inject constructor() {
    private val _requested = MutableStateFlow(false)

    /** Emits `true` once a restart was requested in this process; never resets. */
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    fun markRequested() {
        _requested.value = true
    }

    fun isRequested(): Boolean = _requested.value
}
