package com.yourname.expensetracker.data.backup

sealed interface AppOperationalState {
    data object Normal : AppOperationalState
    data object BackupExporting : AppOperationalState
    data class RestoreInProgress(val mode: RestoreMaintenanceMode.Mode) : AppOperationalState
    data object RestartRequiredAfterRestore : AppOperationalState
    data class CriticalRecoveryRequired(
        val reason: String? = null,
        val timestamp: Long? = null
    ) : AppOperationalState
}
