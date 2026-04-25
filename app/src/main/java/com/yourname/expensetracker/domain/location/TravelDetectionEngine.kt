package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.util.GeoUtils
import javax.inject.Inject
import javax.inject.Singleton

/** A single inferred trip away from home. */
data class TravelTrip(
    /** Approximate start date of the trip (epoch ms). */
    val startDate: Long,
    /** Approximate end date of the trip (epoch ms). */
    val endDate: Long,
    /** Total amount spent during this trip. */
    val totalSpend: Double,
    /** Number of transactions during this trip. */
    val transactionCount: Int,
    /** Representative area name for the trip destination (from resolvedAddress). */
    val destinationHint: String?
)

/** Summary of home vs. local vs. travel spending. */
data class TravelInsight(
    /** Estimated home area centroid latitude (null if home could not be determined). */
    val homeLatitude: Double?,
    /** Estimated home area centroid longitude (null if home could not be determined). */
    val homeLongitude: Double?,
    /** Total spend in expenses classified as HOME (≤ 5 km from home centroid). */
    val homeSpend: Double,
    /** Total spend in expenses classified as LOCAL (5–50 km from home centroid). */
    val localSpend: Double,
    /** Total spend in expenses classified as TRAVEL (> 50 km from home centroid). */
    val travelSpend: Double,
    /** Number of distinct inferred trips. */
    val travelTrips: List<TravelTrip>
)

/**
 * Domain-layer engine that detects home area and travel patterns from located expenses.
 *
 * Algorithm:
 *  1. Cluster expenses on a ~5 km grid. The cell with the most transactions = home area.
 *  2. Classify each expense relative to home: HOME / LOCAL / DAY_TRIP / TRAVEL.
 *  3. Consecutive TRAVEL expenses within 3 days of each other → same trip.
 *
 * Distance thresholds (confirmed by user):
 *  - ≤ 5 km  → HOME
 *  - 5–50 km → LOCAL
 *  - > 50 km → TRAVEL (grouped into trips)
 */
@Singleton
class TravelDetectionEngine @Inject constructor() {

    fun compute(expenses: List<Expense>): TravelInsight? {
        val located = expenses.filter { it.latitude != null && it.longitude != null }
        if (located.size < MIN_EXPENSES_FOR_HOME) return null

        // ── 1. Determine home area using a ~5 km grid ──────────────────────────
        data class GridCell(val latBucket: Long, val lonBucket: Long)

        val cellCounts = HashMap<GridCell, Int>()
        val cellLatSum = HashMap<GridCell, Double>()
        val cellLonSum = HashMap<GridCell, Double>()

        for (exp in located) {
            val lat = exp.latitude!!
            val lon = exp.longitude!!
            val cell = GridCell((lat / HOME_GRID_DEG).toLong(), (lon / HOME_GRID_DEG).toLong())
            cellCounts[cell] = (cellCounts[cell] ?: 0) + 1
            cellLatSum[cell] = (cellLatSum[cell] ?: 0.0) + lat
            cellLonSum[cell] = (cellLonSum[cell] ?: 0.0) + lon
        }

        val homeCell = cellCounts.maxByOrNull { it.value }?.key
            ?: return null

        val homeCellCount = cellCounts[homeCell]!!
        val homeLat = cellLatSum[homeCell]!! / homeCellCount
        val homeLon = cellLonSum[homeCell]!! / homeCellCount

        // ── 2. Classify each located expense ──────────────────────────────────
        var homeSpend = 0.0
        var localSpend = 0.0
        var travelSpend = 0.0

        val travelExpenses = mutableListOf<Expense>()

        for (exp in located) {
            val distKm = GeoUtils.haversineKm(homeLat, homeLon, exp.latitude!!, exp.longitude!!)
            when {
                distKm <= HOME_RADIUS_KM  -> homeSpend  += exp.effectiveAmount
                distKm <= LOCAL_RADIUS_KM -> localSpend += exp.effectiveAmount
                else -> {
                    travelSpend += exp.effectiveAmount
                    travelExpenses.add(exp)
                }
            }
        }

        // ── 3. Group travel expenses into trips ────────────────────────────────
        val trips = groupIntoTrips(travelExpenses)

        return TravelInsight(
            homeLatitude = homeLat,
            homeLongitude = homeLon,
            homeSpend = homeSpend,
            localSpend = localSpend,
            travelSpend = travelSpend,
            travelTrips = trips
        )
    }

    /**
     * Groups travel expenses into trips.
     * Consecutive expenses within [TRIP_GAP_MS] of each other = same trip.
     */
    private fun groupIntoTrips(travelExpenses: List<Expense>): List<TravelTrip> {
        if (travelExpenses.isEmpty()) return emptyList()

        val sorted = travelExpenses.sortedBy { it.date }
        val trips = mutableListOf<TravelTrip>()

        var tripStart = sorted[0].date
        var tripEnd = sorted[0].date
        var tripSpend = sorted[0].effectiveAmount
        var tripCount = 1
        var tripDest = parseDestinationHint(sorted[0].resolvedAddress)

        for (i in 1 until sorted.size) {
            val exp = sorted[i]
            if (exp.date - tripEnd <= TRIP_GAP_MS) {
                // Continue same trip
                tripEnd = exp.date
                tripSpend += exp.effectiveAmount
                tripCount++
                if (tripDest == null) {
                    tripDest = parseDestinationHint(exp.resolvedAddress)
                }
            } else {
                // Save previous trip and start a new one
                trips.add(TravelTrip(tripStart, tripEnd, tripSpend, tripCount, tripDest))
                tripStart = exp.date
                tripEnd = exp.date
                tripSpend = exp.effectiveAmount
                tripCount = 1
                tripDest = parseDestinationHint(exp.resolvedAddress)
            }
        }
        // Flush last trip
        trips.add(TravelTrip(tripStart, tripEnd, tripSpend, tripCount, tripDest))

        return trips
    }

    private fun parseDestinationHint(resolvedAddress: String?): String? {
        val parts = resolvedAddress
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return when {
            parts.size >= 2 -> parts[1]
            parts.size == 1 -> parts[0]
            else -> null
        }
    }

    private companion object {
        /** Minimum expenses needed to make a reliable home determination. */
        const val MIN_EXPENSES_FOR_HOME = 5

        /** Grid cell size in degrees ≈ 5 km — matches MerchantLocationRepository home grid. */
        const val HOME_GRID_DEG = 0.045

        /** Expenses within this radius of the home centroid = HOME zone. */
        const val HOME_RADIUS_KM = 5.0

        /** Expenses beyond HOME_RADIUS but within this = LOCAL zone. */
        const val LOCAL_RADIUS_KM = 50.0

        /** Consecutive travel expenses within this window = same trip (3 days). */
        const val TRIP_GAP_MS = 3L * 24 * 60 * 60 * 1000
    }
}
