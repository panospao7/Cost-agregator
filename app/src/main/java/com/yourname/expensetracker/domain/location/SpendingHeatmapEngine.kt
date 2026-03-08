package com.yourname.expensetracker.domain.location

import javax.inject.Inject
import javax.inject.Singleton
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
 * Domain-layer engine that converts a list of [MapExpenseMarker]s into a list
 * of [HeatmapPoint]s suitable for rendering on the osmdroid map.
 *
 * Algorithm:
 *  1. Cluster markers that fall within [CLUSTER_RADIUS_DEG] of each other
 *     (simple grid-snap clustering — O(n) with a HashMap).
 *  2. Sum the `amount` for each cluster cell.
 *  3. Apply log-normalisation so a single monster spend doesn't drown everything.
 *  4. Normalise all weights to [0.0, 1.0].
 */
@Singleton
class SpendingHeatmapEngine @Inject constructor() {

    /**
     * Compute heatmap points from the given located expenses.
     * Returns an empty list if [expenses] is empty.
     */
    fun compute(expenses: List<LocatedExpense>): List<HeatmapPoint> {
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
            val latBucket = (expense.latitude / CLUSTER_RADIUS_DEG).toLong()
            val lonBucket = (expense.longitude / CLUSTER_RADIUS_DEG).toLong()
            val cell = GridCell(latBucket, lonBucket)
            val acc = cells.getOrPut(cell) { Accumulator() }
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

    private companion object {
        /** ~0.0015° ≈ 150 m at mid-latitude. */
        const val CLUSTER_RADIUS_DEG = 0.0015
    }
}
