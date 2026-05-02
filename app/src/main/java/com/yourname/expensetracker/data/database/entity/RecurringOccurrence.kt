package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern

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

/**
 * Converts a [RecurringOccurrence] entity to a domain [RecurringPattern].
 *
 * The conversion uses occurrence data:
 * - [merchant] as merchant name (defaults to "Unknown" if null)
 * - [expectedAmount] as the average amount
 * - [dueDate] as the next expected date
 * - Confidence is set to 1.0 (manual rules are authoritative)
 */
fun RecurringOccurrence.toRecurringPattern(): RecurringPattern {
    val merchantName = merchant ?: "Unknown"
    val freq = try {
        RecurrenceFrequency.valueOf(frequency)
    } catch (_: IllegalArgumentException) {
        RecurrenceFrequency.IRREGULAR
    }
    return RecurringPattern(
        merchantName = merchantName,
        averageAmount = expectedAmount,
        currency = expectedCurrency,
        frequency = freq,
        nextExpectedDate = dueDate,
        confidence = 1.0f,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        previousDates = emptyList(),
        categoryId = categoryId,
        id = sourceId
    )
}
