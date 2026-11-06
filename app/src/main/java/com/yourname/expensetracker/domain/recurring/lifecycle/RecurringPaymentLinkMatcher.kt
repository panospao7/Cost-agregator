package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/**
 * FC-PAID-UNLINKED INV-4 / INV-5 / INV-6: the single owner of the expense-to-occurrence pair
 * predicate, the suggestion window S(P), the nearest-owner ranking N(E) and the decline test.
 *
 * Pure: no DAO access, no clock, no I/O. [RecurringLifecycleCoordinator] owns every read and write
 * and passes the rows and the merchant keys in. The auto window A(P) stays in the coordinator
 * companion ([RecurringLifecycleCoordinator.autoLinkWindow]); A(P) is always inside S(P).
 *
 * Money: amounts are compared as stored major units in the row's own currency, with no rounding
 * and no FX conversion.
 */
internal object RecurringPaymentLinkMatcher {

    /** Event type of the persisted "Not this bill" decision (metadata: `{"expenseId": <Long>}`). */
    const val LINK_SUGGESTION_DECLINED = "LINK_SUGGESTION_DECLINED"

    /** Upper bound of S(P), in days on each side (D-11). */
    const val MAX_SUGGESTION_WINDOW_DAYS = 7

    /** Relative amount tolerance, inclusive (±10%). */
    private const val AMOUNT_TOLERANCE = 0.10

    /** Days per month used for calendar-month frequencies: the shortest month, so a window never overreaches. */
    private const val DAYS_PER_CALENDAR_MONTH = 28

    /**
     * The rule's period in days: [RecurrenceFrequency.fixedIntervalDays], or `calendarMonths * 28`,
     * or 0 for an irregular or unknown frequency (which gives the same-day window).
     */
    fun resolvePeriodDays(frequency: RecurrenceFrequency?): Int =
        frequency?.let { it.fixedIntervalDays ?: it.calendarMonths?.times(DAYS_PER_CALENDAR_MONTH) } ?: 0

    /**
     * S(P): `s = min(7, floor((P - 1) / 2))` for P >= 1, else 0; early and late are both `s`.
     * Weekly 3/3, biweekly 6/6, monthly and longer 7/7, daily and irregular 0/0. Two neighbouring
     * occurrences of one rule never have overlapping S windows because `2s < P`.
     */
    fun suggestionWindow(periodDays: Int): AutoLinkWindow {
        if (periodDays < 1) return AutoLinkWindow.SAME_DAY
        val s = min(MAX_SUGGESTION_WINDOW_DAYS, (periodDays - 1) / 2)
        return AutoLinkWindow(s, s)
    }

    /** Whether the day delta [dayDelta] (positive = expense after the due day) is inside [window]. */
    fun withinWindow(dayDelta: Int, window: AutoLinkWindow): Boolean =
        dayDelta in -window.earlyDays..window.lateDays

    /**
     * An amount matches when [expectedAmount] is finite and non-zero, [actualAmount] is finite and
     * `|actual - expected| <= |expected * 0.10|` (inclusive).
     */
    fun amountMatches(expectedAmount: Double, actualAmount: Double): Boolean {
        if (!expectedAmount.isFinite() || !actualAmount.isFinite()) return false
        if (expectedAmount == 0.0) return false

        val tolerance = abs(expectedAmount * AMOUNT_TOLERANCE)
        val difference = abs(actualAmount - expectedAmount)
        return difference <= tolerance
    }

    /** ISO currency codes are equal, ignoring case. */
    fun currencyMatches(expectedCurrency: String, actualCurrency: String): Boolean =
        expectedCurrency.equals(actualCurrency, ignoreCase = true)

    /**
     * Calendar days from the occurrence due day to the expense day, device zone (positive when the
     * expense is after the due day).
     */
    fun dayDelta(expense: Expense, occurrence: RecurringOccurrence): Int =
        TimePeriodUtils.daysBetween(occurrence.dueDate, expense.date)

    /**
     * Pair (E, O) predicate of the spec, section 1.5, without the decline test ([isDeclined]) and
     * without expense eligibility (the caller owns [com.yourname.expensetracker.domain.recurring.RecurringLinkTypeEligibility]
     * and the installment gate). Holds when both merchant keys are equal and not blank, the amount
     * and the currency match, and the day delta is inside [window]. The caller chooses [window]: S(P)
     * for a rule occurrence, [AutoLinkWindow.SAME_DAY] for every other source.
     */
    fun pairMatches(
        expense: Expense,
        occurrence: RecurringOccurrence,
        expenseMerchantKey: String,
        occurrenceMerchantKey: String,
        window: AutoLinkWindow
    ): Boolean {
        if (expenseMerchantKey.isBlank() || occurrenceMerchantKey.isBlank()) return false
        if (expenseMerchantKey != occurrenceMerchantKey) return false
        if (!amountMatches(occurrence.expectedAmount, expense.amount)) return false
        if (!currencyMatches(occurrence.expectedCurrency, expense.currency)) return false
        return withinWindow(dayDelta(expense, occurrence), window)
    }

    /**
     * Matches ordered by the nearest-owner rank `(|d|, |E.amount - O.expectedAmount|, O.dueDate, O.id)`,
     * best first. Does not filter anything.
     */
    fun rankByNearest(expense: Expense, matches: List<RecurringOccurrence>): List<RecurringOccurrence> =
        matches.sortedWith(
            compareBy<RecurringOccurrence>(
                { abs(dayDelta(expense, it)) },
                { abs(expense.amount - it.expectedAmount) },
                { it.dueDate },
                { it.id }
            )
        )

    /**
     * N(E): the minimum of [matches] by the rank above, or null for an empty list. The caller removes
     * declined pairs from [matches] first (a decline leaves M(E)).
     */
    fun nearestOwner(expense: Expense, matches: List<RecurringOccurrence>): RecurringOccurrence? =
        rankByNearest(expense, matches).firstOrNull()

    /**
     * Whether [events] (the lifecycle events of one occurrence) hold a decline of [expenseId]: an
     * event of type [LINK_SUGGESTION_DECLINED] whose metadata is a JSON object with a numeric
     * `expenseId` equal to [expenseId]. Malformed or missing metadata is not a decline (the safe
     * direction: the suggestion can come back).
     */
    fun isDeclined(events: List<RecurringLifecycleEvent>, expenseId: Long): Boolean {
        for (event in events) {
            if (event.eventType != LINK_SUGGESTION_DECLINED) continue
            val metadata = event.metadata ?: continue
            val declinedId = try {
                (JSONObject(metadata).opt("expenseId") as? Number)?.toLong()
            } catch (e: JSONException) {
                null
            }
            if (declinedId == expenseId) return true
        }
        return false
    }
}
