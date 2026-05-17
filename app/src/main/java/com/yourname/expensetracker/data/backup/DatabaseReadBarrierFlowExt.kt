package com.yourname.expensetracker.data.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Option B — checks read barrier once at collection start.
 * Throws [DatabaseAccessBlockedException] if reads are blocked.
 */
fun <T> Flow<T>.guardedDatabaseRead(
    readBarrier: DatabaseReadBarrier,
    operation: String,
    policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ
): Flow<T> = flow {
    readBarrier.checkReadAllowed(DatabaseAccessOperation(operation), policy)
    collect { emit(it) }
}

/**
 * Option C — cancels collection whenever mode is non-NORMAL.
 * Resumes automatically when mode returns to NORMAL.
 */
fun <T> Flow<T>.blockedDuringRestore(
    modeFlow: StateFlow<RestoreMaintenanceMode.Mode>,
    @Suppress("UNUSED_PARAMETER") operation: String
): Flow<T> = modeFlow.flatMapLatest { mode ->
    if (mode == RestoreMaintenanceMode.Mode.NORMAL) this else emptyFlow()
}
