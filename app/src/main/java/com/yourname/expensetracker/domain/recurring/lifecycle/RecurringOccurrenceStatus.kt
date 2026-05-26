package com.yourname.expensetracker.domain.recurring.lifecycle

/**
 * Typed occurrence status replacing raw strings.
 *
 * DB storage remains String (no schema migration needed).
 * Use [dbValue] when writing to Room, [fromDb] when reading.
 */
enum class RecurringOccurrenceStatus(val dbValue: String) {
    PLANNED("PLANNED"),
    PAID("PAID"),
    SKIPPED("SKIPPED"),
    MISSED("MISSED"),
    CANCELLED("CANCELLED"),
    IGNORED("IGNORED");

    val isTerminal: Boolean
        get() = this in terminalStatuses

    companion object {
        val terminalStatuses = setOf(PAID, SKIPPED, MISSED, CANCELLED, IGNORED)
        val terminalDbValues = terminalStatuses.map { it.dbValue }.toSet()

        fun fromDb(value: String): RecurringOccurrenceStatus {
            return entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown recurring occurrence status: $value")
        }

        fun fromDbOrNull(value: String?): RecurringOccurrenceStatus? {
            if (value == null) return null
            return entries.firstOrNull { it.dbValue == value }
        }
    }
}

/**
 * Reason for a status transition, used to validate whether the transition is allowed.
 */
enum class RecurringOccurrenceTransitionReason {
    MATERIALIZER_RESOLUTION,
    ACTUAL_EXPENSE_LINKED,
    ACTUAL_EXPENSE_UNLINKED,
    USER_SKIPPED,
    USER_CANCELLED,
    SYSTEM_MARKED_MISSED,
    RULE_DEACTIVATED,
    RULE_DELETED,
    RESTORE_REPAIR,
    DEBUG_REPAIR
}

/**
 * Central transition policy for occurrence status changes.
 * Only approved transitions are allowed; everything else is rejected.
 */
object RecurringOccurrenceTransitionPolicy {
    fun canTransition(
        from: RecurringOccurrenceStatus,
        to: RecurringOccurrenceStatus,
        reason: RecurringOccurrenceTransitionReason
    ): Boolean {
        if (from == to) return true

        return when (reason) {
            RecurringOccurrenceTransitionReason.MATERIALIZER_RESOLUTION ->
                !from.isTerminal

            RecurringOccurrenceTransitionReason.ACTUAL_EXPENSE_LINKED ->
                from == RecurringOccurrenceStatus.PLANNED && to == RecurringOccurrenceStatus.PAID

            RecurringOccurrenceTransitionReason.ACTUAL_EXPENSE_UNLINKED ->
                from == RecurringOccurrenceStatus.PAID && to == RecurringOccurrenceStatus.PLANNED

            RecurringOccurrenceTransitionReason.USER_SKIPPED ->
                from == RecurringOccurrenceStatus.PLANNED && to == RecurringOccurrenceStatus.SKIPPED

            RecurringOccurrenceTransitionReason.USER_CANCELLED,
            RecurringOccurrenceTransitionReason.RULE_DEACTIVATED ->
                from == RecurringOccurrenceStatus.PLANNED && to == RecurringOccurrenceStatus.CANCELLED

            RecurringOccurrenceTransitionReason.SYSTEM_MARKED_MISSED ->
                from == RecurringOccurrenceStatus.PLANNED && to == RecurringOccurrenceStatus.MISSED

            RecurringOccurrenceTransitionReason.RULE_DELETED,
            RecurringOccurrenceTransitionReason.RESTORE_REPAIR,
            RecurringOccurrenceTransitionReason.DEBUG_REPAIR ->
                true
        }
    }

    fun requireAllowed(
        from: RecurringOccurrenceStatus,
        to: RecurringOccurrenceStatus,
        reason: RecurringOccurrenceTransitionReason
    ) {
        require(canTransition(from, to, reason)) {
            "Invalid recurring occurrence transition: ${from.dbValue} -> ${to.dbValue}, reason=$reason"
        }
    }
}
