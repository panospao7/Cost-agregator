package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseReadBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    fun checkReadAllowed(operation: String) {
        val mode = restoreMaintenanceMode.currentMode()
        when (mode) {
            RestoreMaintenanceMode.Mode.NORMAL,
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING -> { }
            else -> throw IllegalStateException("Read blocked during $mode: $operation")
        }
    }
}
