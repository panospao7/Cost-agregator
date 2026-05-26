package com.yourname.expensetracker.domain.recurring.lifecycle

/**
 * Explicit options controlling whether occurrence generation may create
 * reminder deliveries as a side effect.
 *
 * Callers MUST decide whether they want reminder creation. Empty reminder
 * windows no longer implicitly mean "create default reminders".
 */
data class OccurrenceGenerationOptions(
    /** Whether reminder deliveries should be created for PLANNED occurrences. */
    val createReminderDeliveries: Boolean,
    /** Reminder window names to schedule when [createReminderDeliveries] is true. */
    val reminderWindows: List<String> = RecurringLifecycleCoordinator.DEFAULT_REMINDER_WINDOWS,
    /** The source/purpose of this generation pass (for diagnostics and lifecycle events). */
    val generationSource: OccurrenceGenerationSource,
    /** If false (default), reminders with scheduledAt in the past are skipped. */
    val allowPastDueReminderDeliveries: Boolean = false
)

enum class OccurrenceGenerationSource {
    /** Projection service scheduling future reminders for a rule. */
    REMINDER_PROJECTION,
    /** Initial generation when a rule is created. */
    RULE_CREATE,
    /** Regeneration after a rule update changes amount/date/frequency. */
    RULE_UPDATE_REGENERATION,
    /** User opens the recurring screen and triggers a projection. */
    USER_RECURRING_SCREEN,
    /** Reconciliation or report generation — must NOT create reminders. */
    RECONCILIATION_REPORT,
    /** Cashflow or forecast calculation — must NOT create reminders. */
    CASHFLOW_FORECAST,
    /** Debug/repair tool — must NOT create reminders by default. */
    DEBUG_REPAIR,
    /** Test code. */
    TEST
}
