package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense

/**
 * Single Kotlin owner of the comparable-home-value (CV) rule: the amount a row
 * is sorted and filtered by when the list is ordered by amount in the home
 * currency H.
 *
 * Pure: no clock, no I/O, no logging. The SQL mirror is
 * [com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.COMPARABLE_HOME_VALUE_E_SQL];
 * neither mirror changes without the shared fixture and the parity test.
 *
 * Rule (first matching branch wins):
 *  1. `isNotMine`            → `Value(0.0)` (zero is the same in every currency).
 *  2. `currency == H`        → `Value(effectiveAmount)`; the snapshot is ignored.
 *  3. valid snapshot for H   (`baseAmount > 0`, `baseCurrency == H`, `exchangeRateUsed > 0`)
 *     → shared with `myShareAmount`: `myShareAmount * exchangeRateUsed`;
 *       shared with `mySharePercentage`: `baseAmount * mySharePercentage / 100.0`;
 *       otherwise `baseAmount`. Computed unrounded, same operation order as SQL.
 *  4. otherwise              → `None(reason)`.
 *
 * A row is never compared by its raw amount unless branch 2 holds. Currency
 * equality is exact and case-sensitive, like the SQL `=`.
 */
object ComparableHomeValue {

    /** Direction of the amount ordering. */
    enum class Direction { ASCENDING, DESCENDING }

    /** Comparable value of [e] against the home currency [home] (branches 1-4, in order). */
    fun comparableValue(e: Expense, home: CurrencyCode): ComparableValue {
        if (e.isNotMine) return ComparableValue.Value(0.0)
        if (e.currency == home.code) return ComparableValue.Value(normalizeZero(e.effectiveAmount))
        if (e.baseAmount > 0.0 && e.baseCurrency == home.code && e.exchangeRateUsed > 0.0) {
            return ComparableValue.Value(normalizeZero(snapshotAmount(e)))
        }
        val reason = when {
            !(e.baseAmount > 0.0) -> NoComparableValueReason.NO_SNAPSHOT
            e.baseCurrency != home.code -> NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY
            else -> NoComparableValueReason.SNAPSHOT_RATE_INVALID
        }
        return ComparableValue.None(reason)
    }

    /**
     * Ownership-adjusted snapshot value of [e] (branch 3 without the `baseCurrency == H`
     * condition), for the mixed-currency date header, which sums a group that already
     * shares one base currency.
     *
     * Returns 0.0 for a not-mine row, null when there is no usable snapshot
     * (`baseAmount <= 0` or `exchangeRateUsed <= 0`). Never falls back to the raw amount.
     */
    fun snapshotValue(e: Expense): Double? {
        if (e.isNotMine) return 0.0
        if (!(e.baseAmount > 0.0) || !(e.exchangeRateUsed > 0.0)) return null
        return normalizeZero(snapshotAmount(e))
    }

    /**
     * Total order for amount sorting (INV-5): rows without a comparable value last
     * (both directions), then the value in [direction], then `date` descending, then
     * `id` descending. Mirrors the SQL ORDER BY templates.
     */
    fun comparator(home: CurrencyCode, direction: Direction): Comparator<Expense> =
        Comparator { a, b ->
            val cvA = comparableValue(a, home)
            val cvB = comparableValue(b, home)
            val byValue = when {
                cvA is ComparableValue.Value && cvB is ComparableValue.Value -> {
                    val c = cvA.amountInHome.compareTo(cvB.amountInHome)
                    if (direction == Direction.ASCENDING) c else -c
                }
                cvA is ComparableValue.Value -> -1
                cvB is ComparableValue.Value -> 1
                else -> 0
            }
            if (byValue != 0) {
                byValue
            } else {
                val byDate = b.date.compareTo(a.date)
                if (byDate != 0) byDate else b.id.compareTo(a.id)
            }
        }

    /**
     * Inclusive min/max membership. With no bound set every row passes (the SQL emits no
     * predicate); with a bound set, rows without a comparable value are excluded.
     */
    fun withinBounds(value: ComparableValue, min: Double?, max: Double?): Boolean {
        if (min == null && max == null) return true
        if (value !is ComparableValue.Value) return false
        if (min != null && !(value.amountInHome >= min)) return false
        if (max != null && !(value.amountInHome <= max)) return false
        return true
    }

    /** Convenience over [withinBounds] for an [Expense] and home currency [home]. */
    fun withinBounds(e: Expense, home: CurrencyCode, min: Double?, max: Double?): Boolean =
        withinBounds(comparableValue(e, home), min, max)

    /** Branch-3 value; caller has already checked the snapshot is usable. */
    private fun snapshotAmount(e: Expense): Double = when {
        e.isSharedExpense && e.myShareAmount != null -> e.myShareAmount * e.exchangeRateUsed
        e.isSharedExpense && e.mySharePercentage != null -> e.baseAmount * e.mySharePercentage / 100.0
        else -> e.baseAmount
    }

    /** `-0.0` and `0.0` are equal in SQLite but not under Kotlin `compareTo`. */
    private fun normalizeZero(v: Double): Double = if (v == 0.0) 0.0 else v
}
