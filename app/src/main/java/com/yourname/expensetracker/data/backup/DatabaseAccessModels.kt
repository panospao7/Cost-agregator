package com.yourname.expensetracker.data.backup

enum class DatabaseAccessType {
    READ,
    WRITE
}

enum class DatabaseReadPolicy {
    NORMAL_APP_READ,
    EXPORT_OR_BACKUP_SNAPSHOT_READ,
    /** Must NOT go through the app-wide Room singleton. Use a fresh DB handle. */
    RESTORE_INTERNAL_STAGED_DB_READ
}

data class DatabaseAccessOperation(
    val name: String,
    val pipeline: String? = null,
    val entity: String? = null,
    val reason: String? = null
)

class DatabaseAccessBlockedException(
    val accessType: DatabaseAccessType,
    val operation: DatabaseAccessOperation,
    val mode: RestoreMaintenanceMode.Mode
) : IllegalStateException(
    "$accessType blocked during $mode: ${operation.name}"
)
