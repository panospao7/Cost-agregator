package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Pure domain utility that expands a recurrence rule into concrete occurrence
 * candidates within a half-open date range [startDate, endDate).
 *
 * No DI needed — instantiable as a plain class or used as a singleton.
 */
class RecurringOccurrenceExpander {

    /**
     * Request to expand a single recurrence rule into concrete occurrences.
     *
     * @property merchant Display name of the merchant.
     * @property amount Expected amount per occurrence.
     * @property currency ISO-4217 currency code.
     * @property frequency Recurrence frequency (WEEKLY, MONTHLY, etc.).
     * @property categoryId Optional category to assign to generated occurrences.
     * @property startDate First possible occurrence date (inclusive, epoch ms).
     * @property endDate Last possible occurrence date (exclusive, half-open, epoch ms).
     * @property sourceType Origin of this rule: "RECURRING_RULE", "DETECTED_PATTERN", or "SUBSCRIPTION".
     * @property sourceId ID of the rule or pattern signature that produced this expansion.
     */
    data class ExpandRequest(
        val merchant: String,
        val amount: Double,
        val currency: String,
        val frequency: RecurrenceFrequency,
        val categoryId: Long? = null,
        val startDate: Long,
        val endDate: Long,
        val sourceType: String,
        val sourceId: Long
    )

    /**
     * A single concrete occurrence produced by expanding a recurrence rule.
     *
     * @property occurrenceKey Unique key for deduplication: `"$sourceId|<dayStart>|<frequencyName>"`.
     * @property dueDate The calendar day this occurrence is due (epoch ms, start of day).
     * @property expectedAmount The expected amount for this occurrence.
     * @property expectedCurrency The expected currency.
     * @property frequency The frequency name (e.g. "MONTHLY").
     * @property merchant The merchant display name.
     * @property categoryId Optional category id.
     * @property sourceType Origin type, forwarded from the request.
     * @property sourceId Origin ID, forwarded from the request.
     */
    data class OccurrenceCandidate(
        val occurrenceKey: String,
        val dueDate: Long,
        val expectedAmount: Double,
        val expectedCurrency: String,
        val frequency: String,
        val merchant: String?,
        val categoryId: Long?,
        val sourceType: String,
        val sourceId: Long
    )

    /**
     * Expands a recurrence rule into concrete occurrence candidates within a date range.
     *
     * Uses calendar-aware advancement via [TimePeriodUtils.addDays], [TimePeriodUtils.addMonths],
     * and [TimePeriodUtils.addYears] to handle DST transitions, leap years, and varying month
     * lengths correctly.
     *
     * Returns occurrences where `dueDate` is in `[startDate, endDate)`.
     * Returns an empty list for [RecurrenceFrequency.IRREGULAR].
     */
    fun expand(request: ExpandRequest): List<OccurrenceCandidate> {
        // IRREGULAR frequency cannot be predicted
        if (request.frequency == RecurrenceFrequency.IRREGULAR) return emptyList()

        // No valid range
        if (request.startDate >= request.endDate) return emptyList()

        val candidates = mutableListOf<OccurrenceCandidate>()
        var currentDate = TimePeriodUtils.getStartOfDay(request.startDate)

        while (currentDate < request.endDate) {
            val occurrenceKey = buildOccurrenceKey(request.sourceId, currentDate, request.frequency)

            candidates.add(
                OccurrenceCandidate(
                    occurrenceKey = occurrenceKey,
                    dueDate = currentDate,
                    expectedAmount = request.amount,
                    expectedCurrency = request.currency,
                    frequency = request.frequency.name,
                    merchant = request.merchant,
                    categoryId = request.categoryId,
                    sourceType = request.sourceType,
                    sourceId = request.sourceId
                )
            )

            currentDate = advance(currentDate, request.frequency)
        }

        return candidates
    }

    /**
     * Builds the unique occurrence key: `"$sourceId|<dayStart>|<frequencyName>"`.
     * The day start is obtained via [TimePeriodUtils.getStartOfDay].
     * Amount is intentionally excluded from the key.
     */
    private fun buildOccurrenceKey(
        sourceId: Long,
        dueDate: Long,
        frequency: RecurrenceFrequency
    ): String {
        val dayStart = TimePeriodUtils.getStartOfDay(dueDate)
        return "$sourceId|$dayStart|${frequency.name}"
    }

    /**
     * Advances [currentDate] by one period according to [frequency].
     * All arithmetic is calendar-aware (not raw millisecond multiplication).
     */
    private fun advance(currentDate: Long, frequency: RecurrenceFrequency): Long {
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> TimePeriodUtils.addDays(currentDate, 7)
            RecurrenceFrequency.BIWEEKLY -> TimePeriodUtils.addDays(currentDate, 14)
            RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(currentDate, 1)
            RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(currentDate, 3)
            RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(currentDate, 6)
            RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(currentDate, 1)
            RecurrenceFrequency.IRREGULAR -> currentDate // unreachable; handled above
        }
    }
}
