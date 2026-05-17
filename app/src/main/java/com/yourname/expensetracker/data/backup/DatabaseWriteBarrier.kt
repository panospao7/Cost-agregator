package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseWriteBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    /** Compatibility overload — kept for existing callers. */
    fun checkWritesAllowed(operation: String) {
        checkWritesAllowed(DatabaseAccessOperation(operation))
    }

    fun checkWritesAllowed(operation: DatabaseAccessOperation) {
        val mode = restoreMaintenanceMode.currentMode()
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            throw DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = operation,
                mode = mode
            )
        }
    }

    suspend fun <T> runWrite(
        operation: DatabaseAccessOperation,
        block: suspend () -> T
    ): T {
        checkWritesAllowed(operation)
        return block()
    }
}
