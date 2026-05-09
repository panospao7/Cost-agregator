package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseWriteBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    fun checkWritesAllowed(operation: String) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore: $operation")
        }
    }
}