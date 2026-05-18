package com.yourname.expensetracker.data.backup

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Formal scope for DB writes that are intentionally allowed during restore modes.
 *
 * Only [DatabaseBackupRepositoryImpl] should inject and use this.
 * Allowed modes: [RestoreMaintenanceMode.Mode.ASSETS_RESTORING] and
 * [RestoreMaintenanceMode.Mode.RESTORE_VERIFYING].
 *
 * This makes restore-internal writes explicit and auditable rather than
 * implicitly bypassing the global write barrier.
 */
@Singleton
class RestoreInternalWriteScope @Inject internal constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    suspend fun <T> run(operation: String, block: suspend () -> T): T {
        val mode = restoreMaintenanceMode.currentMode()
        require(
            mode == RestoreMaintenanceMode.Mode.ASSETS_RESTORING ||
            mode == RestoreMaintenanceMode.Mode.RESTORE_VERIFYING
        ) {
            "Restore-internal write '$operation' not allowed in mode $mode"
        }
        return block()
    }
}
