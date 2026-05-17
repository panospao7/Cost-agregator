package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseReadBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    /** Compatibility overload — kept for existing callers. */
    fun checkReadAllowed(operation: String) {
        checkReadAllowed(DatabaseAccessOperation(operation), DatabaseReadPolicy.NORMAL_APP_READ)
    }

    fun checkReadAllowed(
        operation: DatabaseAccessOperation,
        policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ
    ) {
        val mode = restoreMaintenanceMode.currentMode()
        val allowed = when (policy) {
            DatabaseReadPolicy.NORMAL_APP_READ ->
                mode == RestoreMaintenanceMode.Mode.NORMAL

            DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ ->
                mode == RestoreMaintenanceMode.Mode.NORMAL ||
                mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING

            // Must NOT go through the app singleton — use a fresh one-shot DB handle.
            DatabaseReadPolicy.RESTORE_INTERNAL_STAGED_DB_READ -> false
        }
        if (!allowed) {
            throw DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.READ,
                operation = operation,
                mode = mode
            )
        }
    }

    suspend fun <T> runRead(
        operation: DatabaseAccessOperation,
        policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ,
        block: suspend () -> T
    ): T {
        checkReadAllowed(operation, policy)
        return block()
    }
}
