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
