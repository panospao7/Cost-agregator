package com.yourname.expensetracker.domain.model

sealed class UpcomingItem {
    abstract val id: String
    abstract val description: String
    abstract val amount: Double
    abstract val date: Long
    abstract val categoryId: Long?

    data class Recurring(
        val pattern: RecurringPattern
    ) : UpcomingItem() {
        override val id: String = "recurring_${pattern.merchantName}"
        override val description: String = pattern.merchantName
        override val amount: Double = pattern.averageAmount
        override val date: Long = pattern.nextExpectedDate
        override val categoryId: Long? = pattern.categoryId
    }

    /**
     * Upcoming item created from a materialised occurrence.
     * Prefer this over [Recurring] when [ConfirmedOccurrence] data is available,
     * because it captures each individual occurrence (e.g. multiple WEEKLY payments)
     * rather than relying on a single [RecurringPattern.nextExpectedDate].
     */
    data class Occurrence(
        val occurrence: ConfirmedOccurrence
    ) : UpcomingItem() {
        override val id: String = "occurrence_${occurrence.merchant ?: "unknown"}_${occurrence.dueDate}"
        override val description: String = occurrence.merchant ?: "Unknown"
        override val amount: Double = occurrence.expectedAmount
        override val date: Long = occurrence.dueDate
        override val categoryId: Long? = occurrence.categoryId
    }

    data class Planned(
        val expense: PlannedExpense
    ) : UpcomingItem() {
        override val id: String = "planned_${expense.id}"
        override val description: String = expense.description
        override val amount: Double = expense.amount
        override val date: Long = expense.date
        override val categoryId: Long? = expense.categoryId
    }
}
