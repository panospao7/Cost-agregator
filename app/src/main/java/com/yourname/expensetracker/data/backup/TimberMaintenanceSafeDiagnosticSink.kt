package com.yourname.expensetracker.data.backup

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logs blocked operations to Timber only — never touches Room.
 * Safe to use in any maintenance mode.
 */
@Singleton
class TimberMaintenanceSafeDiagnosticSink @Inject constructor() : MaintenanceSafeDiagnosticSink {
    override fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String?,
        entity: String?
    ) {
        Timber.w(
            "BLOCKED[%s] op=%s pipeline=%s entity=%s",
            mode.label, operation, pipeline ?: "-", entity ?: "-"
        )
    }
}
