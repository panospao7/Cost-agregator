package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseReadBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    fun checkReadAllowed(operation: String) {
        val mode = restoreMaintenanceMode.currentMode()
        if (mode == RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED) {
            throw IllegalStateException("Read blocked during restart-required: $operation")
        }
    }
}
