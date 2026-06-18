package com.yourname.expensetracker.domain.location

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

/**
 * A single point on the spending heatmap.
 *
 * @param latitude   Geographic latitude.
 * @param longitude  Geographic longitude.
 * @param weight     Relative intensity — proportional to total spend at this cluster.
 *                   Normalised to [0.0, 1.0] by [SpendingHeatmapEngine].
 * @param totalSpend Absolute EUR amount represented by this point (for tooltip).
 * @param count      Number of transactions at this cluster.
 */
data class HeatmapPoint(
    val latitude: Double,
    val longitude: Double,
    val weight: Float,
    val totalSpend: Double,
    val count: Int
)

/**
 * TODO (A14): Use normalized snapshots/MoneyAggregate instead of raw DAO sums
 *             for multi-currency safety. The [LocatedExpense.amount] values are
 *             raw per-currency amounts and summing them across currencies is
 *             silently wrong. See LOC-9 below for details.
 *
 * Domain-layer engine that converts a list of [LocatedExpense]s into a list
 * of [HeatmapPoint]s suitable for rendering on the osmdroid map.
 *
 * **Pre-filter contract:** callers must supply only spending-type expenses
 * (i.e. those where `DomainTransactionType.isSpending` is `true`).
 * Deposits, withdrawals, transfers, and unknown types must be excluded
 * *before* calling [compute]. This engine does not perform transaction-type
 * filtering itself.
 *
 * Algorithm:
 *  1. Cluster markers that fall within [CLUSTER_RADIUS_DEG] of each other
 *     (simple grid-snap clustering — O(n) with a HashMap).
 *  2. Ignore non-positive `amount` values, then sum positive spend for each
 *     cluster cell.
 *  3. Apply log-normalisation so a single monster spend doesn't drown everything.
 *  4. Normalise all weights to [0.0, 1.0].
 */
@Singleton
class SpendingHeatmapEngine @Inject constructor() {

    /**
     * Compute heatmap points from the given pre-filtered spending-only expenses.
     *
     * @param expenses located expenses already filtered to spending types only
     *                 (see class-level contract). Returns an empty list if empty.
     *
     * ## LOC-9: MultiCurrencyRepository in location analytics (limitation)
     * The input [LocatedExpense.amount] values are raw amounts as stored in the
     * database (in the expense's original currency). When the user has multi-currency
     * expenses, summing these raw amounts across currencies produces misleading
     * heatmap intensities (e.g. 100 JPY ≈ 0.60 EUR, not 100 EUR).
     *
     * A currency-aware normalisation method (`normalizeAmounts`) was planned but
     * could not be implemented because:
     * 1. [LocatedExpense] does **not** carry a `currency` field — the engine has
     *    no way to know which currency each amount is denominated in.
     * 2. The `compute()` method is a synchronous (non-suspend) function, while
     *    [CurrencyConverter.convert] is a suspend function that requires a
     *    coroutine context.
     *
     * Callers SHOULD normalise amounts to the user's home currency **before**
     * constructing [LocatedExpense] instances and passing them to [compute].
     * The [MultiCurrencyRepository.getExpensesWithConversion] method can be used
     * to obtain per-expense home-currency amounts from the data layer.
     * Once caller-side normalisation is in place, this engine will treat all
     * amounts as home-currency-equivalent and the log-normalisation step will
     * produce correct relative intensities regardless of source currency mix.
     */
    fun compute(expenses: List<LocatedExpense>): List<HeatmapPoint> {
        // W29: Callers must pre-normalize expenses to home currency.
        // This engine does NOT perform currency conversion.
        if (expenses.isEmpty()) return emptyList()

        // ── Step 1: Grid-snap cluster ─────────────────────────────────────────
        // Round lat/lon to the nearest grid cell (~150 m at Greece latitudes)
        data class GridCell(val latBucket: Long, val lonBucket: Long)

        data class Accumulator(
            var totalSpend: Double = 0.0,
            var count: Int = 0,
            var latSum: Double = 0.0,
            var lonSum: Double = 0.0
        )

        val cells = HashMap<GridCell, Accumulator>()

        for (expense in expenses) {
            if (expense.amount <= 0.0) continue

            val latBucket = floor(expense.latitude / CLUSTER_RADIUS_DEG).toLong()
            val lonBucket = floor(expense.longitude / CLUSTER_RADIUS_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)
            val acc = cells.getOrPut(cell) { Accumulator() }
            // TODO (W29): Normalize amounts to home currency before summing.
            // Current raw-sum can mix currencies. Use MoneyAggregate or pre-normalized amounts.
            acc.totalSpend += expense.amount
            acc.count += 1
            acc.latSum += expense.latitude
            acc.lonSum += expense.longitude
        }

        if (cells.isEmpty()) return emptyList()

        // ── Step 2: Log-normalise then build result in a single pass ──────────
        // Use ln(1 + spend) to compress large outliers.
        // Collect entries list once so iteration order is consistent.
        val entries = cells.values.toList()
        val logWeights = entries.map { ln(1.0 + it.totalSpend) }
        val maxRaw = logWeights.maxOrNull() ?: return emptyList()
        if (maxRaw == 0.0) return emptyList()

        // ── Step 3: Build result list ─────────────────────────────────────────
        return entries.mapIndexed { idx, acc ->
            HeatmapPoint(
                latitude = acc.latSum / acc.count,
                longitude = acc.lonSum / acc.count,
                weight = (logWeights[idx] / maxRaw).toFloat().coerceIn(0f, 1f),
                totalSpend = acc.totalSpend,
                count = acc.count
            )
        }
    }

    /**
     * Currency-safe heatmap computation using [LocatedMoneyExpense.normalizedAmount].
     *
     * Sums only [ConversionStatus.CONVERTED] and [ConversionStatus.HOME_CURRENCY] rows,
     * skipping expenses where conversion failed ([ConversionStatus.FAILED]).
     * Uses the same grid-snap clustering and log-normalisation as [compute].
     */
    fun computeNormalized(expenses: List<LocatedMoneyExpense>): List<HeatmapPoint> {
        val validExpenses = expenses.filter {
            it.conversionStatus == ConversionStatus.HOME_CURRENCY ||
            it.conversionStatus == ConversionStatus.CONVERTED
        }

        if (validExpenses.isEmpty()) return emptyList()

        // ── Step 1: Grid-snap cluster ─────────────────────────────────────────
        data class GridCell(val latBucket: Long, val lonBucket: Long)

        data class Accumulator(
            var totalSpend: Double = 0.0,
            var count: Int = 0,
            var latSum: Double = 0.0,
            var lonSum: Double = 0.0
        )

        val cells = HashMap<GridCell, Accumulator>()

        for (expense in validExpenses) {
            val normAmount = expense.normalizedAmount ?: continue
            if (normAmount <= 0.0) continue

            val latBucket = floor(expense.latitude / CLUSTER_RADIUS_DEG).toLong()
            val lonBucket = floor(expense.longitude / CLUSTER_RADIUS_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)
            val acc = cells.getOrPut(cell) { Accumulator() }
            acc.totalSpend += normAmount
            acc.count += 1
            acc.latSum += expense.latitude
            acc.lonSum += expense.longitude
        }

        if (cells.isEmpty()) return emptyList()

        // ── Step 2: Log-normalise ─────────────────────────────────────────────
        val entries = cells.values.toList()
        val logWeights = entries.map { ln(1.0 + it.totalSpend) }
        val maxRaw = logWeights.maxOrNull() ?: return emptyList()
        if (maxRaw == 0.0) return emptyList()

        // ── Step 3: Build result list ─────────────────────────────────────────
        return entries.mapIndexed { idx, acc ->
            HeatmapPoint(
                latitude = acc.latSum / acc.count,
                longitude = acc.lonSum / acc.count,
                weight = (logWeights[idx] / maxRaw).toFloat().coerceIn(0f, 1f),
                totalSpend = acc.totalSpend,
                count = acc.count
            )
        }
    }

    private companion object {
        /** ~0.0015° ≈ 150 m at mid-latitude. */
        const val CLUSTER_RADIUS_DEG = 0.0015
    }
}
