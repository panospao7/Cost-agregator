package com.yourname.expensetracker.domain.model

/**
 * Domain-level planned expense.
 *
 * REC-21: Added `currency` field (default "EUR") to support multi-currency
 * planned expenses throughout the domain layer. Previously the currency was
 * only tracked at the database entity level, causing callers that construct
 * domain PlannedExpense objects to silently default to EUR regardless of the
 * actual expense currency.
 *
 * FCST-N4-FIXED: Added `status` field so that [SynthesisEngine] can filter
 * out FULFILLED expenses itself instead of relying on callers. The status
 * mirrors the entity status field: "PLANNED", "FULFILLED", "SKIPPED", "CANCELLED".
 */
data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    /** REC-21: Added currency support for multi-currency planned expenses. */
    val currency: String = "EUR",
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority,
    /** Key linking this planned expense to a recurring occurrence (occurrenceKey). */
    val sourceOccurrenceKey: String? = null,
    /** FCST-N4: Status of the planned expense. "PLANNED" by default. */
    val status: String = "PLANNED"
) {
    init {
        require(description.isNotBlank()) { "description cannot be blank" }
        require(amount.isFinite() && amount > 0.0) { "amount must be a positive finite number" }
    }
}

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}
