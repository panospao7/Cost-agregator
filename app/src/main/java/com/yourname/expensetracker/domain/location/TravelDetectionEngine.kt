package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
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

/** Currency-safe version of [TravelTrip] using [MoneyAggregate]. */
data class NormalizedTravelTrip(
    /** Approximate start date of the trip (epoch ms). */
    val startDate: Long,
    /** Approximate end date of the trip (epoch ms). */
    val endDate: Long,
    /** Total amount spent during this trip as a [MoneyAggregate]. */
    val aggregate: MoneyAggregate,
    /** Number of transactions during this trip. */
    val transactionCount: Int,
    /** Representative destination hint for the trip (derived from merchant name). */
    val destinationHint: String?
)

/** Currency-safe version of [TravelInsight] using [MoneyAggregate] for all spend totals. */
data class NormalizedTravelInsight(
    /** Estimated home area centroid latitude (null if home could not be determined). */
    val homeLatitude: Double?,
    /** Estimated home area centroid longitude (null if home could not be determined). */
    val homeLongitude: Double?,
    /** Aggregate spend in expenses classified as HOME (≤ 5 km from home centroid). */
    val homeAggregate: MoneyAggregate,
    /** Aggregate spend in expenses classified as LOCAL (5–50 km from home centroid). */
    val localAggregate: MoneyAggregate,
    /** Aggregate spend in expenses classified as TRAVEL (> 50 km from home centroid). */
    val travelAggregate: MoneyAggregate,
    /** Number of distinct inferred trips with per-trip [MoneyAggregate]. */
    val travelTrips: List<NormalizedTravelTrip>
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

    @Deprecated(
        message = "Use computeNormalized() which returns MoneyAggregate-based results for multi-currency safety",
        replaceWith = ReplaceWith(
            "computeNormalized(expenses.map { it.toLocatedMoneyExpense(homeCurrency) }, homeCurrency, converter)",
            "com.yourname.expensetracker.domain.location.LocatedMoneyExpense"
        ),
        level = DeprecationLevel.WARNING
    )
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

    // ── Normalized (currency-safe) methods ───────────────────────────────

    /**
     * Currency-safe home/travel detection using [LocatedMoneyExpense].
     *
     * Same algorithm as [compute] but:
     * - Accepts [LocatedMoneyExpense] with pre-normalised amounts
     * - Skips expenses where [ConversionStatus] is [ConversionStatus.FAILED]
     * - Returns [NormalizedTravelInsight] with [MoneyAggregate] fields instead of
     *   raw [Double] sums
     *
     * @param expenses  Located expenses with normalised amounts.
     * @param homeCurrency  User's home currency code (e.g. "EUR").
     * @param converter  CurrencyConverter for multi-currency aggregation via [MoneyAggregateBuilder].
     * @return A [NormalizedTravelInsight] if home could be determined, or null if
     *         fewer than [MIN_EXPENSES_FOR_HOME] valid expenses are available.
     */
    suspend fun computeNormalized(
        expenses: List<LocatedMoneyExpense>,
        homeCurrency: String,
        converter: CurrencyConverter
    ): NormalizedTravelInsight? {
        val validExpenses = expenses.filter {
            it.conversionStatus == ConversionStatus.HOME_CURRENCY ||
            it.conversionStatus == ConversionStatus.CONVERTED
        }
        if (validExpenses.size < MIN_EXPENSES_FOR_HOME) return null

        // ── 1. Determine home area using a ~5 km grid ──────────────────────
        data class GridCell(val latBucket: Long, val lonBucket: Long)

        val cellCounts = HashMap<GridCell, Int>()
        val cellLatSum = HashMap<GridCell, Double>()
        val cellLonSum = HashMap<GridCell, Double>()

        for (exp in validExpenses) {
            val lat = exp.latitude
            val lon = exp.longitude
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

        // ── 2. Classify each valid expense ─────────────────────────────────
        val homeExpenses = mutableListOf<LocatedMoneyExpense>()
        val localExpenses = mutableListOf<LocatedMoneyExpense>()
        val travelExpenses = mutableListOf<LocatedMoneyExpense>()

        for (exp in validExpenses) {
            val distKm = GeoUtils.haversineKm(homeLat, homeLon, exp.latitude, exp.longitude)
            when {
                distKm <= HOME_RADIUS_KM  -> homeExpenses.add(exp)
                distKm <= LOCAL_RADIUS_KM -> localExpenses.add(exp)
                else -> travelExpenses.add(exp)
            }
        }

        // ── 3. Build MoneyAggregate for each category ──────────────────────
        val homeAggregate = buildAggregate(homeExpenses, homeCurrency, converter)
        val localAggregate = buildAggregate(localExpenses, homeCurrency, converter)
        val travelAggregate = buildAggregate(travelExpenses, homeCurrency, converter)

        // ── 4. Group travel expenses into trips ────────────────────────────
        val trips = groupIntoTripsNormalized(travelExpenses, homeCurrency, converter)

        return NormalizedTravelInsight(
            homeLatitude = homeLat,
            homeLongitude = homeLon,
            homeAggregate = homeAggregate,
            localAggregate = localAggregate,
            travelAggregate = travelAggregate,
            travelTrips = trips
        )
    }

    /**
     * Groups [LocatedMoneyExpense]s into trips using the same gap logic as
     * [groupIntoTrips], but builds a [MoneyAggregate] per trip.
     */
    private suspend fun groupIntoTripsNormalized(
        travelExpenses: List<LocatedMoneyExpense>,
        homeCurrency: String,
        converter: CurrencyConverter
    ): List<NormalizedTravelTrip> {
        if (travelExpenses.isEmpty()) return emptyList()

        val sorted = travelExpenses.sortedBy { it.date }
        val trips = mutableListOf<NormalizedTravelTrip>()

        var tripStart = sorted[0].date
        var tripEnd = sorted[0].date
        var tripExpenses = mutableListOf(sorted[0])
        var tripDest: String? = sorted[0].merchant.takeIf { it.isNotBlank() }

        for (i in 1 until sorted.size) {
            val exp = sorted[i]
            if (exp.date - tripEnd <= TRIP_GAP_MS) {
                // Continue same trip
                tripEnd = exp.date
                tripExpenses.add(exp)
                if (tripDest == null) {
                    tripDest = exp.merchant.takeIf { it.isNotBlank() }
                }
            } else {
                // Save previous trip and start a new one
                trips.add(
                    NormalizedTravelTrip(
                        startDate = tripStart,
                        endDate = tripEnd,
                        aggregate = buildAggregate(tripExpenses, homeCurrency, converter),
                        transactionCount = tripExpenses.size,
                        destinationHint = tripDest
                    )
                )
                tripStart = exp.date
                tripEnd = exp.date
                tripExpenses = mutableListOf(exp)
                tripDest = exp.merchant.takeIf { it.isNotBlank() }
            }
        }
        // Flush last trip
        trips.add(
            NormalizedTravelTrip(
                startDate = tripStart,
                endDate = tripEnd,
                aggregate = buildAggregate(tripExpenses, homeCurrency, converter),
                transactionCount = tripExpenses.size,
                destinationHint = tripDest
            )
        )

        return trips
    }

    /**
     * Build a [MoneyAggregate] from a list of [LocatedMoneyExpense]s.
     * Returns an empty aggregate when the list is empty.
     */
    private suspend fun buildAggregate(
        expenses: List<LocatedMoneyExpense>,
        homeCurrency: String,
        converter: CurrencyConverter
    ): MoneyAggregate {
        if (expenses.isEmpty()) return MoneyAggregate.empty(CurrencyCode(homeCurrency))
        val buckets = expenses.map { Pair(it.normalizedAmount ?: it.originalAmount, it.normalizedCurrency) }
        return MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, converter)
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
