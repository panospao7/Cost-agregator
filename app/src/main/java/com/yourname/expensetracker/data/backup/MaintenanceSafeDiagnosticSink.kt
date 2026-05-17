package com.yourname.expensetracker.data.backup

/**
 * Diagnostic sink that is safe to use when DB writes may be blocked.
 *
 * Implementations must never attempt a Room write when the DB is in a
 * non-NORMAL maintenance mode. Use Timber, SharedPreferences, or a
 * DataStore ring buffer instead.
 */
interface MaintenanceSafeDiagnosticSink {
    /**
     * Record that a DB operation was blocked by the write/read barrier.
     *
     * @param operation  Name of the blocked operation (e.g. "saveExpense").
     * @param mode       The maintenance mode that caused the block.
     * @param pipeline   Optional pipeline identifier (e.g. "P1", "P2").
     * @param entity     Optional entity type (e.g. "Expense", "Receipt").
     */
    fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String? = null,
        entity: String? = null
    )
}
