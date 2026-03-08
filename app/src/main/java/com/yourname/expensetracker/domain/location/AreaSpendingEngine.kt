package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
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

    fun compute(expenses: List<Expense>): List<AreaSpending> {
        // Only consider expenses that have a lat/lon AND a resolved address
        val located = expenses.filter {
            it.latitude != null && it.longitude != null && !it.resolvedAddress.isNullOrBlank()
        }
        if (located.isEmpty()) return emptyList()

        data class GridCell(val latBucket: Long, val lonBucket: Long)

        data class Accumulator(
            var areaName: String,
            var totalSpend: Double = 0.0,
            var count: Int = 0,
            var latSum: Double = 0.0,
            var lonSum: Double = 0.0
        )

        val cells = HashMap<GridCell, Accumulator>()

        for (expense in located) {
            val lat = expense.latitude!!
            val lon = expense.longitude!!
            val areaName = parseAreaName(expense.resolvedAddress!!)

            val latBucket = (lat / GRID_DEG).toLong()
            val lonBucket = (lon / GRID_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)

            val acc = cells.getOrPut(cell) { Accumulator(areaName) }
            // Keep the most common area name — for now use the first one per cell
            acc.totalSpend += expense.effectiveAmount
            acc.count += 1
            acc.latSum += lat
            acc.lonSum += lon
        }

        // Merge cells that share the same area name (handles address spelling variations)
        val byArea = HashMap<String, Accumulator>()
        for ((_, acc) in cells) {
            val existing = byArea[acc.areaName]
            if (existing == null) {
                byArea[acc.areaName] = acc
            } else {
                existing.totalSpend += acc.totalSpend
                existing.count += acc.count
                existing.latSum += acc.latSum
                existing.lonSum += acc.lonSum
            }
        }

        return byArea.values
            .filter { it.count > 0 && it.areaName.isNotBlank() }
            .map { acc ->
                AreaSpending(
                    areaName = acc.areaName,
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

    private companion object {
        /** Grid cell size in degrees — approximately 1 km at mid-latitudes. */
        const val GRID_DEG = 0.009
    }
}
