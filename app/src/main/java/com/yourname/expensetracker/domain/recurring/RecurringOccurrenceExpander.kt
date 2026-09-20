package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import timber.log.Timber
import javax.inject.Inject

/**
 * Pure domain utility that expands a recurrence rule into concrete occurrence
 * candidates within a half-open date range [startDate, endDate).
 *
 * No DI needed — instantiable as a plain class or used as a singleton.
 *
 * ## REC-4: Irregular recurring manual confirmation (planned)
 * Currently, [RecurrenceFrequency.IRREGULAR] frequency returns [emptyList] because
 * irregular items cannot be predicted algorithmically. The planned future flow is:
 *
 * 1. User confirms an irregular item via the UI.
 * 2. The confirmation date and user-specified interval are recorded.
 * 3. After 2–3 confirmations, the system promotes the item to a detected frequency
 *    (e.g. WEEKLY or MONTHLY) using pattern-matching logic.
 *
 * Until this flow is implemented, IRREGULAR items are treated as non-repeating.
 */
class RecurringOccurrenceExpander @Inject constructor() {

    /**
     * Request to expand a single recurrence rule into concrete occurrences.
     *
     * @property merchant Display name of the merchant.
     * @property amount Expected amount per occurrence.
     * @property currency ISO-4217 currency code.
     * @property frequency Recurrence frequency (WEEKLY, MONTHLY, etc.).
     * @property categoryId Optional category to assign to generated occurrences.
     * @property startDate Range start (inclusive, epoch ms) — used to FILTER output.
     * @property endDate Range end (exclusive, half-open, epoch ms) — used to FILTER output.
     * @property anchorDate The date to start expansion from (e.g. rule.nextDate).
     *                      Occurrences before startDate are skipped but the iteration
     *                      anchor is independent of the filter range.
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
        val anchorDate: Long,
        val sourceType: String,
        val sourceId: Long
    )

    /**
     * A single concrete occurrence produced by expanding a recurrence rule.
     *
     * @property occurrenceKey Unique key for deduplication: `"$sourceType|$sourceId|<dayStart>|<frequencyName>"`.
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
     * Iteration starts from [anchorDate] (not [startDate]), so that callers can
     * control where the expansion begins (e.g. from [rule.nextDate]).
     * Only occurrences where `dueDate` is in `[startDate, endDate)` are returned.
     *
     * Uses calendar-aware advancement via [TimePeriodUtils.addDays],
     * [TimePeriodUtils.advanceMonthAnchor], and [TimePeriodUtils.addYears] to handle DST
     * transitions, leap years, and varying month lengths correctly.
     *
     * ## RP-04 P4-005 (D7 anchor semantics)
     * For MONTHLY/QUARTERLY/SEMI_ANNUALLY the anchor day of month is derived ONCE from
     * [anchorDate] and every subsequent period advances from that FIXED anchor (clamped to
     * each target month's length): Jan 31 -> Feb 28/29 -> Mar 31, never the cumulative
     * drift Jan 31 -> Feb 28 -> Mar 28. This prevents NEW drift from the current expansion
     * anchor; it does NOT restore an original anchor for an already-drifted persisted rule
     * (the schema has no original anchor day — an already-drifted `nextDate` necessarily
     * derives its drifted day as the anchor).
     *
     * ANNUALLY (RP-04 A3 reviewer fix): routed through the same fixed-anchor year
     * advance ([TimePeriodUtils.advanceMonthAnchor] with months=12), so a Feb-29 anchor
     * clamps to Feb 28 in non-leap years and recovers to Feb 29 in leap years, in
     * agreement with [com.yourname.expensetracker.domain.logic.RecurrenceCalculator.addFrequencyInterval].
     * Weekly/biweekly fixed-day behavior is unchanged.
     *
     * Returns an empty list for [RecurrenceFrequency.IRREGULAR].
     */
    fun expand(request: ExpandRequest): List<OccurrenceCandidate> {
        // IRREGULAR frequency cannot be predicted
        if (request.frequency == RecurrenceFrequency.IRREGULAR) {
            Timber.d("REC-4: IRREGULAR frequency for merchant='%s' — manual confirmation flow needed (planned future feature)", request.merchant)
            return emptyList()
        }

        // No valid range
        if (request.startDate >= request.endDate) return emptyList()

        // RP-04 P4-005: derive the fixed anchor day ONCE per expansion operation so all
        // month-based advances within this expansion agree on the same anchor day.
        val anchorDayOfMonth = TimePeriodUtils.getDayOfMonth(TimePeriodUtils.getStartOfDay(request.anchorDate))

        val candidates = mutableListOf<OccurrenceCandidate>()
        var currentDate = TimePeriodUtils.getStartOfDay(request.anchorDate)

        while (currentDate < request.endDate) {
            // Only include occurrences that fall within the filter range
            if (currentDate >= request.startDate) {
                val occurrenceKey = buildOccurrenceKey(request.sourceType, request.sourceId, currentDate, request.frequency)

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
            }

            currentDate = advance(currentDate, request.frequency, anchorDayOfMonth)
        }

        return candidates
    }

    /**
     * Advances [date] by one period according to [frequency].
     * Public so that callers (e.g. [RecurringLifecycleCoordinator]) can advance
     * an anchor date before constructing an [ExpandRequest].
     *
     * RP-04 P4-005: for month-based frequencies the anchor day is [anchorDayOfMonth]
     * when supplied, else derived from [date] itself. Callers that advance repeatedly
     * (e.g. the anchor catch-up loop) MUST derive the anchor day ONCE from the
     * operation's anchorDate (the persisted `rule.nextDate`) and pass it to every
     * step, so clamping never compounds (Jan 31 → Feb 28/29 → Mar 31). This
     * prevents NEW drift from the current expansion anchor; it does NOT restore an
     * original anchor for an already-drifted persisted rule (no original anchor day
     * exists in the schema — an already-drifted `nextDate` necessarily derives its
     * drifted day as the anchor). ANNUALLY uses the same fixed-anchor year advance
     * (12 months, clamp-and-recover). Weekly/biweekly fixed-day behavior is unchanged.
     */
    fun advanceDate(
        date: Long,
        frequency: RecurrenceFrequency,
        anchorDayOfMonth: Int? = null
    ): Long = advance(
        date,
        frequency,
        anchorDayOfMonth ?: TimePeriodUtils.getDayOfMonth(TimePeriodUtils.getStartOfDay(date))
    )

    /**
     * Builds the unique occurrence key: `"$sourceType|$sourceId|<dayStart>|<frequencyName>"`.
     * The day start is obtained via [TimePeriodUtils.getStartOfDay].
     * Amount is intentionally excluded from the key.
     * Source type is included to prevent collisions between different source types
     * with the same numeric sourceId.
     */
    private fun buildOccurrenceKey(
        sourceType: String,
        sourceId: Long,
        dueDate: Long,
        frequency: RecurrenceFrequency
    ): String {
        val dayStart = TimePeriodUtils.getStartOfDay(dueDate)
        return "$sourceType|$sourceId|$dayStart|${frequency.name}"
    }

    /**
     * Advances [currentDate] by one period according to [frequency].
     * All arithmetic is calendar-aware (not raw millisecond multiplication).
     *
     * RP-04 P4-005 (D7 anchor semantics): MONTHLY/QUARTERLY/SEMI_ANNUALLY/ANNUALLY
     * advance from the FIXED [anchorDayOfMonth] (derived once per expansion from the
     * operation's anchor date) instead of from the previous result's day, so a Jan-31
     * anchor produces Jan 31 -> Feb 28/29 -> Mar 31 without cumulative drift, and a
     * Feb-29 annual anchor clamps to Feb 28 in non-leap years and recovers in leap
     * years. This prevents NEW drift from the current expansion anchor; it does NOT
     * restore an original anchor for an already-drifted persisted rule (the schema has
     * no original anchor day). Weekly/biweekly fixed-day behavior is unchanged.
     */
    private fun advance(
        currentDate: Long,
        frequency: RecurrenceFrequency,
        anchorDayOfMonth: Int
    ): Long {
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> TimePeriodUtils.addDays(currentDate, 7)
            RecurrenceFrequency.BIWEEKLY -> TimePeriodUtils.addDays(currentDate, 14)
            RecurrenceFrequency.MONTHLY ->
                TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth, currentDate, 1)
            RecurrenceFrequency.QUARTERLY ->
                TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth, currentDate, 3)
            RecurrenceFrequency.SEMI_ANNUALLY ->
                TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth, currentDate, 6)
            // RP-04 A3 reviewer fix: fixed-anchor year advance (12 months) so the
            // annual path agrees with RecurrenceCalculator.addFrequencyInterval —
            // a Feb-29 anchor clamps to Feb 28 (non-leap) and recovers to Feb 29
            // (leap) instead of permanently drifting down via addYears.
            RecurrenceFrequency.ANNUALLY ->
                TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth, currentDate, 12)
            RecurrenceFrequency.IRREGULAR -> currentDate // unreachable; handled above
        }
    }
}
