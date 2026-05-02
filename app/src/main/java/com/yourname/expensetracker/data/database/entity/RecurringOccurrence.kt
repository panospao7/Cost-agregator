package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

@Entity(
    tableName = "recurring_occurrences",
    indices = [
        Index(value = ["sourceType", "sourceId"]),
        Index(value = ["dueDate"]),
        Index(value = ["status"]),
        Index(value = ["occurrenceKey"], unique = true),
        Index(value = ["linkedExpenseId"])
    ]
)
data class RecurringOccurrence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,         // "RECURRING_RULE", "DETECTED_PATTERN", "SUBSCRIPTION", "PLANNED"
    val sourceId: Long,             // ruleId or patternSignature hash
    val occurrenceKey: String,      // unique key: ruleId|normalizedDueDate|frequency
    val dueDate: Long,              // epoch millis of due date start of day
    val status: String,             // "PLANNED", "PAID", "SKIPPED", "MISSED", "CANCELLED", "IGNORED"
    val linkedExpenseId: Long? = null,
    val expectedAmount: Double,
    val expectedCurrency: String,
    val paidAt: Long? = null,
    val paidAmount: Double? = null,
    val paidCurrency: String? = null,
    val frequency: String,          // RecurrenceFrequency.name
    val merchant: String?,
    val categoryId: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() on update. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
) {
    @get:Ignore
    val expectedMoneyAmount: MoneyAmount get() = MoneyAmount(expectedAmount, CurrencyCode(expectedCurrency))

    @get:Ignore
    val paidMoneyAmount: MoneyAmount? get() =
        if (paidAmount != null && paidCurrency != null)
            MoneyAmount(paidAmount, CurrencyCode(paidCurrency))
        else null
}
