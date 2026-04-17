package com.yourname.expensetracker.domain.location

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

/**
 * A spending place insight — summary of spending at a single location cluster.
 */
data class PlaceInsight(
    /** Human-readable place name (derived from the most common merchant in the cluster). */
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    /** Total amount spent at this place. */
    val totalSpend: Double,
    /** Number of individual transactions. */
    val transactionCount: Int,
    /** Average transaction amount. */
    val avgTransaction: Double,
    /** Merchant names that contribute to this cluster (up to 3 for display). */
    val merchantNames: List<String>,
    /** Date of the most-recent transaction at this place (epoch ms). */
    val lastVisit: Long
)

/**
 * Domain-layer engine that builds [PlaceInsight]s from located expenses.
 *
 * Uses the same grid-snap clustering as [SpendingHeatmapEngine] so the
 * insights align with the heatmap cells.
 */
@Singleton
class LocationInsightsEngine @Inject constructor() {

    /**
     * Compute place insights from [expenses].
     * Results are sorted by [PlaceInsight.totalSpend] descending.
     */
    fun compute(expenses: List<LocatedExpense>): List<PlaceInsight> {
        if (expenses.isEmpty()) return emptyList()

        data class GridCell(val latBucket: Long, val lonBucket: Long)

        data class Accumulator(
            var totalSpend: Double = 0.0,
            var count: Int = 0,
            var latSum: Double = 0.0,
            var lonSum: Double = 0.0,
            var lastVisit: Long = 0L,
            val merchants: MutableMap<String, Int> = mutableMapOf()
        )

        val cells = HashMap<GridCell, Accumulator>()

        for (expense in expenses) {
            val latBucket = floor(expense.latitude / CLUSTER_RADIUS_DEG).toLong()
            val lonBucket = floor(expense.longitude / CLUSTER_RADIUS_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)
            val acc = cells.getOrPut(cell) { Accumulator() }
            acc.totalSpend += expense.amount
            acc.count += 1
            acc.latSum += expense.latitude
            acc.lonSum += expense.longitude
            if (expense.date > acc.lastVisit) acc.lastVisit = expense.date
            acc.merchants[expense.merchant] = (acc.merchants[expense.merchant] ?: 0) + 1
        }

        return cells.values
            .filter { it.count > 0 }
            .map { acc ->
                val centLat = acc.latSum / acc.count
                val centLon = acc.lonSum / acc.count
                // Top merchant(s) by visit count
                val topMerchants = acc.merchants.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key }
                PlaceInsight(
                    placeName = topMerchants.firstOrNull() ?: "Unknown",
                    latitude = centLat,
                    longitude = centLon,
                    totalSpend = acc.totalSpend,
                    transactionCount = acc.count,
                    avgTransaction = if (acc.count > 0) acc.totalSpend / acc.count else 0.0,
                    merchantNames = topMerchants,
                    lastVisit = acc.lastVisit
                )
            }
            .sortedByDescending { it.totalSpend }
    }

    private companion object {
        /**
         * Fine-grained clustering grid size ≈ 167 m at the equator.
         * Must match [SpendingHeatmapEngine.CLUSTER_RADIUS_DEG].
         *
         * NOTE: This is intentionally much finer than the 0.045 deg (≈ 5 km)
         * grid used in [ExpenseDao.getMerchantLocationClusters].
         * - Here we cluster for map display, where each pin should represent a
         *   distinct physical location (e.g., separate branches of a chain).
         * - [ExpenseDao] clusters at city-district scale to determine which
         *   *area* a merchant is most commonly visited in, for biasing
         *   geocoding searches.
         */
        const val CLUSTER_RADIUS_DEG = 0.0015
    }
}
