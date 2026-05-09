package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary of spending aggregated to a named geographic area (~1 km grid cell).
 *
 * The area name is derived from the [Expense.resolvedAddress] field, which is
 * already populated by the backfill worker for most located expenses.
 */
data class AreaSpending(
    /** Human-readable area name (e.g. "Γλυφάδα" or "Glyfada"). */
    val areaName: String,
    /** Total amount spent in this area. */
    val totalSpend: Double,
    /** Number of individual transactions in this area. */
    val transactionCount: Int,
    /** Average transaction amount. */
    val avgTransaction: Double,
    /** Representative latitude of the area centroid. */
    val latitude: Double,
    /** Representative longitude of the area centroid. */
    val longitude: Double
)

/**
 * Domain-layer engine that builds [AreaSpending]s from located expenses.
 *
 * Area naming strategy:
 *  - Parse [Expense.resolvedAddress] to extract the second component (suburb/city)
 *    e.g. "Σκλαβενίτης, Γλυφάδα, Αττική" → "Γλυφάδα"
 *  - Cluster expenses on a ~1 km grid ([GRID_DEG] ≈ 0.009°) so nearby expenses
 *    that share an area name are grouped together.
 *  - Results are sorted by [AreaSpending.totalSpend] descending.
 */
@Singleton
class AreaSpendingEngine @Inject constructor() {

    private data class AreaNameStats(
        var count: Int = 0,
        var totalSpend: Double = 0.0
    )

    private data class GridCell(val latBucket: Long, val lonBucket: Long)

    private data class Accumulator(
        var totalSpend: Double = 0.0,
        var count: Int = 0,
        var latSum: Double = 0.0,
        var lonSum: Double = 0.0,
        val areaCandidates: MutableMap<String, AreaNameStats> = linkedMapOf()
    )

    @Deprecated(
        "Use computeNormalized() which returns MoneyAggregate-based results for multi-currency safety",
        ReplaceWith(
            "computeNormalized(expenses.map { it.toLocatedMoneyExpense(homeCurrency) }, homeCurrency, converter)",
            "com.yourname.expensetracker.domain.location.LocatedMoneyExpense"
        )
    )
    fun compute(expenses: List<Expense>): List<AreaSpending> {
        // Only consider expenses that have a lat/lon AND a resolved address
        val located = expenses.filter {
            it.latitude != null && it.longitude != null && !it.resolvedAddress.isNullOrBlank()
        }
        if (located.isEmpty()) return emptyList()

        val cells = HashMap<GridCell, Accumulator>()

        for (expense in located) {
            val lat = expense.latitude!!
            val lon = expense.longitude!!
            val areaName = parseAreaName(expense.resolvedAddress!!)

            val latBucket = (lat / GRID_DEG).toLong()
            val lonBucket = (lon / GRID_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)

            val acc = cells.getOrPut(cell) { Accumulator() }
            acc.totalSpend += expense.effectiveAmount
            acc.count += 1
            acc.latSum += lat
            acc.lonSum += lon
            val stats = acc.areaCandidates.getOrPut(areaName) { AreaNameStats() }
            stats.count += 1
            stats.totalSpend += expense.effectiveAmount
        }

        // Merge cells that share the same area name (handles address spelling variations)
        val byArea = HashMap<String, Accumulator>()
        for ((_, acc) in cells) {
            val resolvedAreaName = selectRepresentativeAreaName(acc.areaCandidates)
            val existing = byArea[resolvedAreaName]
            if (existing == null) {
                byArea[resolvedAreaName] = acc
            } else {
                existing.totalSpend += acc.totalSpend
                existing.count += acc.count
                existing.latSum += acc.latSum
                existing.lonSum += acc.lonSum
            }
        }

        return byArea.values
            .filter { it.count > 0 && selectRepresentativeAreaName(it.areaCandidates).isNotBlank() }
            .map { acc ->
                AreaSpending(
                    areaName = selectRepresentativeAreaName(acc.areaCandidates),
                    totalSpend = acc.totalSpend,
                    transactionCount = acc.count,
                    avgTransaction = acc.totalSpend / acc.count,
                    latitude = acc.latSum / acc.count,
                    longitude = acc.lonSum / acc.count
                )
            }
            .sortedByDescending { it.totalSpend }
    }

    /**
     * Currency-safe area spending using [LocatedMoneyExpense].
     *
     * Groups expenses by grid cell (~1 km) and builds a [MoneyAggregate] per cell
     * via [MoneyAggregateBuilder.fromBuckets]. Skips expenses with failed conversion.
     * Results use grid-cell coordinate keys since [LocatedMoneyExpense] does not
     * carry a resolved address for human-readable area names.
     *
     * @param expenses  Located expenses (caller must ensure they are pre-filtered
     *                  to spending-only transaction types).
     * @param homeCurrency  User's home currency code (e.g. "EUR").
     * @param converter  CurrencyConverter for multi-currency aggregation.
     * @return Map of grid-cell label → [MoneyAggregate] for that area.
     */
    suspend fun computeNormalized(
        expenses: List<LocatedMoneyExpense>,
        homeCurrency: String,
        converter: CurrencyConverter
    ): Map<String, MoneyAggregate> {
        val validExpenses = expenses.filter {
            it.conversionStatus == ConversionStatus.HOME_CURRENCY ||
            it.conversionStatus == ConversionStatus.CONVERTED
        }
        if (validExpenses.isEmpty()) return emptyMap()

        val byCell = HashMap<GridCell, MutableList<LocatedMoneyExpense>>()
        for (expense in validExpenses) {
            val latBucket = (expense.latitude / GRID_DEG).toLong()
            val lonBucket = (expense.longitude / GRID_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)
            byCell.getOrPut(cell) { mutableListOf() }.add(expense)
        }

        return byCell.mapValues { (cell, cellExpenses) ->
            val buckets = cellExpenses.map { exp ->
                Pair(exp.normalizedAmount ?: exp.originalAmount, exp.normalizedCurrency)
            }
            MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, converter)
        }.mapKeys { "${it.key.latBucket}_${it.key.lonBucket}" }
    }

    /**
     * Extract the most meaningful part of a resolved address.
     *
     * Typical formats from Nominatim:
     *  "Shop Name, Suburb, City, Region"  → returns "Suburb"
     *  "Street 5, City, Region"           → returns "City"
     *  "City"                             → returns "City"
     */
    private fun parseAreaName(resolvedAddress: String): String {
        val parts = resolvedAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> parts[1]  // Second component is typically suburb/municipality
            parts.size == 1 -> parts[0]
            else -> resolvedAddress.trim()
        }
    }

    private fun selectRepresentativeAreaName(candidates: Map<String, AreaNameStats>): String {
        return candidates.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AreaNameStats>> { it.value.count }
                    .thenByDescending { it.value.totalSpend }
                    .thenBy { it.key.lowercase() }
                    .thenBy { it.key }
            )
            .firstOrNull()
            ?.key
            .orEmpty()
    }

    private companion object {
        /** Grid cell size in degrees — approximately 1 km at mid-latitudes. */
        const val GRID_DEG = 0.009
    }
}
