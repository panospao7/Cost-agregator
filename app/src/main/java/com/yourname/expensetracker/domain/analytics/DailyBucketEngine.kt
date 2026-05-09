package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.time.PeriodRange
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A04-FIXED: Builds exact-range daily expense buckets from normalized input.
 *
 * Buckets exactly cover [period] without "last N days from now" offset logic.
 * Uses [period.startInclusiveMillis] and [period.endExclusiveMillis].
 */
@Singleton
class DailyBucketEngine @Inject constructor() {

    data class DailyBucket(
        val date: Long,       // epoch millis at start of day (local midnight)
        val total: Double,    // normalized amount (already home-currency)
        val count: Int
    )

    fun buildBuckets(
        input: NormalizedAnalyticsInput,
        period: PeriodRange,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DailyBucket> {
        val startDay = Instant.ofEpochMilli(period.startInclusiveMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS)
        val endDay = Instant.ofEpochMilli(period.endExclusiveMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS)

        // Aggregate normalized amounts per day
        val byDay = input.includedExpenses
            .filter { it.date >= period.startInclusiveMillis && it.date < period.endExclusiveMillis }
            .groupBy { dayStart(it.date, zoneId) }

        val buckets = mutableListOf<DailyBucket>()
        var current = startDay
        while (current < endDay || current == startDay) {  // include start day
            val dayEpoch = current.toInstant().toEpochMilli()
            val dayExpenses = byDay[dayEpoch].orEmpty()
            buckets.add(DailyBucket(dayEpoch, dayExpenses.sumOf { it.normalizedAmount }, dayExpenses.size))
            current = current.plusDays(1)
            if (current > endDay) break
        }
        return buckets
    }

    private fun dayStart(epochMillis: Long, zoneId: ZoneId): Long {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
    }
}
