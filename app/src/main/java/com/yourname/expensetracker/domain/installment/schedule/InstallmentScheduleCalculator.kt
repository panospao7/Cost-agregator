package com.yourname.expensetracker.domain.installment.schedule

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Input of [InstallmentScheduleCalculator.generate].
 *
 * Dates are civil dates as epochDay. "Today" is passed in as [declaredOnEpochDay]; the calculator
 * never reads a clock or a zone by itself.
 *
 * @property declaredTotal EQUAL_SPLIT only: the amount being split (plan currency).
 * @property fixedPaymentAmount FIXED_PAYMENT only: the constant payment (plan currency).
 * @property purchaseEpochDay civil date of the purchase expense, or null for a STANDALONE plan.
 * @property paidBeforeTrackingCount "payments already made: k" (INV-32).
 */
data class InstallmentScheduleRequest(
    val splitMethod: InstallmentSplitMethod,
    val currency: String,
    val installmentCount: Int,
    val declaredTotal: Double?,
    val fixedPaymentAmount: Double?,
    val firstDueEpochDay: Long,
    val residualPlacement: InstallmentResidualPlacement,
    val declaredOnEpochDay: Long,
    val purchaseEpochDay: Long?,
    val paidBeforeTrackingCount: Int = 0
)

/** One generated REGULAR entry. [amount] is [amountMinor] as an exact 2-decimal Double. */
data class GeneratedInstallment(
    val seq: Int,
    val dueEpochDay: Long,
    val amountMinor: Long,
    val amount: Double,
    /** PAID_BEFORE_TRACKING for the first k entries, SCHEDULED for the rest. Never anything else. */
    val status: InstallmentEntryStatus
)

/**
 * A generated schedule.
 *
 * @property dueBeforeDeclaredCount P: the number of entries whose due date is strictly before the
 *   declaration date (the upper bound for k).
 * @property completesImmediately true when no entry is left SCHEDULED, so the plan must commit as
 *   COMPLETED in the declaration transaction (INV-21).
 */
data class GeneratedSchedule(
    val currency: InstallmentCurrency,
    val anchorDayOfMonth: Int,
    val totalMinor: Long,
    val entries: List<GeneratedInstallment>,
    val lastDueEpochDay: Long,
    val dueBeforeDeclaredCount: Int,
    val scheduledCount: Int,
    val completesImmediately: Boolean
)

/** Result of schedule generation: a schedule or a bounded refusal (nothing is written either way). */
sealed interface ScheduleGenerationResult {
    data class Success(val schedule: GeneratedSchedule) : ScheduleGenerationResult
    data class Refused(val code: InstallmentRefusalCode) : ScheduleGenerationResult
}

/**
 * Pure schedule generation and civil-date arithmetic for installment plans (domain spec sections
 * 9 and 10; INV-9, INV-10, INV-11, INV-18). The only caller is the plan lifecycle coordinator
 * (and, for read-only previews, the plan read model). No I/O, no clock, no Android.
 *
 * ## Money
 * All arithmetic is in Long minor units ([InstallmentMinorUnits]). Equal split is an exact
 * integer split: `base = totalMinor / n`, `r = totalMinor - base * n` (`0 <= r < n`), and the
 * residual entry (FIRST or LAST) is `base + r`. No rounding mode is involved in the split; the
 * only rounding is the HALF_UP normalisation of inputs with more than two decimals.
 *
 * ## Dates
 * Due dates are civil dates (epochDay). `due(k) = clampToMonthEnd(month of the first due date +
 * (k - 1) months, anchorDayOfMonth)`, always computed from the anchor, never chained.
 * Installment expenses are stamped at 12:00 local time of the due date in the zone supplied by
 * the caller ([recordTimestampMillis]).
 */
object InstallmentScheduleCalculator {

    const val MIN_COUNT: Int = 2
    const val MAX_COUNT: Int = 480

    /** Supported civil-date range: 1970-01-01 .. 2200-12-31. Outside it the input is INVALID_DATE. */
    val MIN_EPOCH_DAY: Long = LocalDate.of(1970, 1, 1).toEpochDay()
    val MAX_EPOCH_DAY: Long = LocalDate.of(2200, 12, 31).toEpochDay()

    /**
     * Validates the request (domain spec 7.0 order: count, amounts, currency, input dates, the
     * supported range of the last generated due date, first due date not before the purchase,
     * minimum amount per entry, paid-before-tracking range) and generates the REGULAR entries.
     * The first failing rule wins; a refusal carries only a bounded code.
     */
    fun generate(request: InstallmentScheduleRequest): ScheduleGenerationResult {
        if (request.installmentCount < MIN_COUNT || request.installmentCount > MAX_COUNT) {
            return refused(InstallmentRefusalCode.INVALID_COUNT)
        }
        val count = request.installmentCount

        // Amounts: the amount the chosen split method needs must be present, finite and > 0 minor units.
        val inputMinor = when (request.splitMethod) {
            InstallmentSplitMethod.EQUAL_SPLIT -> request.declaredTotal?.let(InstallmentMinorUnits::toMinorOrNull)
            InstallmentSplitMethod.FIXED_PAYMENT -> request.fixedPaymentAmount?.let(InstallmentMinorUnits::toMinorOrNull)
        }
        if (inputMinor == null || inputMinor <= 0L) return refused(InstallmentRefusalCode.INVALID_AMOUNT)

        val currency = InstallmentCurrency.fromCodeOrNull(request.currency)
        if (currency == null || !InstallmentMinorUnits.isSupportedScale(currency)) {
            return refused(InstallmentRefusalCode.UNSUPPORTED_CURRENCY)
        }

        if (!isSupportedDay(request.firstDueEpochDay) ||
            !isSupportedDay(request.declaredOnEpochDay) ||
            (request.purchaseEpochDay != null && !isSupportedDay(request.purchaseEpochDay))
        ) {
            return refused(InstallmentRefusalCode.INVALID_DATE)
        }

        // The date rule covers the whole schedule: the last generated due date must also lie in the
        // supported range (the dates are non-decreasing, so checking the last one is enough). It
        // belongs to the date rule, so it is checked before FIRST_DUE_BEFORE_PURCHASE.
        val anchorDay = LocalDate.ofEpochDay(request.firstDueEpochDay).dayOfMonth
        val dueDates = dueDates(request.firstDueEpochDay, anchorDay, count)
        if (!isSupportedDay(dueDates.last())) return refused(InstallmentRefusalCode.INVALID_DATE)

        if (request.purchaseEpochDay != null && request.firstDueEpochDay < request.purchaseEpochDay) {
            return refused(InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE)
        }

        val amountsMinor: List<Long>
        val totalMinor: Long
        when (request.splitMethod) {
            InstallmentSplitMethod.EQUAL_SPLIT -> {
                // Each entry must be at least 0.01.
                amountsMinor = splitEqually(inputMinor, count, request.residualPlacement)
                    ?: return refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT)
                totalMinor = inputMinor
            }

            InstallmentSplitMethod.FIXED_PAYMENT -> {
                // INV-10: every entry equals the fixed payment; there is no residual.
                amountsMinor = List(count) { inputMinor }
                totalMinor = inputMinor * count
            }
        }

        val dueBeforeDeclared = dueDates.count { it < request.declaredOnEpochDay }

        val k = request.paidBeforeTrackingCount
        if (k < 0 || k > dueBeforeDeclared) return refused(InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING)

        val entries = List(count) { index ->
            GeneratedInstallment(
                seq = index + 1,
                dueEpochDay = dueDates[index],
                amountMinor = amountsMinor[index],
                amount = InstallmentMinorUnits.fromMinor(amountsMinor[index]),
                status = if (index < k) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.SCHEDULED
            )
        }
        val scheduledCount = count - k
        return ScheduleGenerationResult.Success(
            GeneratedSchedule(
                currency = currency,
                anchorDayOfMonth = anchorDay,
                totalMinor = totalMinor,
                entries = entries,
                lastDueEpochDay = dueDates.last(),
                dueBeforeDeclaredCount = dueBeforeDeclared,
                scheduledCount = scheduledCount,
                completesImmediately = scheduledCount == 0
            )
        )
    }

    /**
     * Exact integer split of [totalMinor] over [count] entries (INV-9). Also used for re-spread
     * (spec 7.4 E2): pass the sum of the remaining SCHEDULED amounts and the new remaining count.
     *
     * All entries are `base = totalMinor / count`, except the residual entry (the first or the
     * last, per [placement]) which is `base + r` with `r = totalMinor - base * count`. The result
     * always sums to exactly [totalMinor]. Returns null when `count < 1` or when `totalMinor <
     * count` (an entry would be below 0.01).
     */
    fun splitEqually(totalMinor: Long, count: Int, placement: InstallmentResidualPlacement): List<Long>? {
        if (count < 1 || totalMinor < count.toLong()) return null
        val base = totalMinor / count
        val remainder = totalMinor - base * count
        val residualIndex = when (placement) {
            InstallmentResidualPlacement.FIRST -> 0
            InstallmentResidualPlacement.LAST -> count - 1
        }
        return List(count) { index -> if (index == residualIndex) base + remainder else base }
    }

    /**
     * Due date (epochDay) of the entry [monthOffset] months after the month of [baseEpochDay],
     * with the day-of-month set to [anchorDayOfMonth] and clamped to the month end. Computed from
     * the anchor, never from the previous entry, so an anchor of 31 Jan gives 28/29 Feb, 31 Mar,
     * 30 Apr (INV-18). A leap-day anchor gives 28 Feb in non-leap years and 29 Mar after.
     *
     * Edit E4 (new due day) calls this with the month of the next unrecorded entry as the base
     * and the new anchor day.
     *
     * @throws IllegalArgumentException when the arguments are outside the supported range;
     *   callers validate with [generate] or [isSupportedDay] first.
     */
    fun dueDateEpochDay(baseEpochDay: Long, anchorDayOfMonth: Int, monthOffset: Int): Long {
        require(anchorDayOfMonth in 1..31) { "anchorDayOfMonth out of range" }
        require(monthOffset >= 0) { "monthOffset must be >= 0" }
        require(isSupportedDay(baseEpochDay)) { "base date out of supported range" }
        val month = YearMonth.from(LocalDate.ofEpochDay(baseEpochDay)).plusMonths(monthOffset.toLong())
        val day = minOf(anchorDayOfMonth, month.lengthOfMonth())
        return month.atDay(day).toEpochDay()
    }

    /** Due dates of [count] consecutive entries starting at the month of [firstDueEpochDay] (entry 1 = offset 0). */
    fun dueDates(firstDueEpochDay: Long, anchorDayOfMonth: Int, count: Int): List<Long> =
        List(count) { index -> dueDateEpochDay(firstDueEpochDay, anchorDayOfMonth, index) }

    /** true when [epochDay] lies within the supported civil-date range. */
    fun isSupportedDay(epochDay: Long): Boolean = epochDay in MIN_EPOCH_DAY..MAX_EPOCH_DAY

    /**
     * Civil date (epochDay) of an instant in [zone]. The caller supplies both; this function does
     * not read a clock or a default zone.
     */
    fun civilEpochDay(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Timestamp of an installment expense: the due civil date at 12:00 local time in [zone]
     * (spec section 10, INV-18). Noon keeps the expense in the right day and month bucket under
     * DST shifts and zone changes of up to 11 hours. A late catch-up still dates the expense on
     * its due date, so [dueEpochDay] (not "now") must be passed. If 12:00 does not exist in the
     * zone on that date (a rare gap), the java.time rule moves it forward by the gap length.
     *
     * @throws IllegalArgumentException when [dueEpochDay] is outside the supported range.
     */
    fun recordTimestampMillis(dueEpochDay: Long, zone: ZoneId): Long {
        require(isSupportedDay(dueEpochDay)) { "due date out of supported range" }
        return LocalDate.ofEpochDay(dueEpochDay)
            .atTime(LocalTime.NOON)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun refused(code: InstallmentRefusalCode): ScheduleGenerationResult = ScheduleGenerationResult.Refused(code)
}
