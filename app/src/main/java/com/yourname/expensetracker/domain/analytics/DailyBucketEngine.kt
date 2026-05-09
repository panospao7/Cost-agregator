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
        val startDate = Instant.ofEpochMilli(period.startInclusiveMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS)
        val endDate = Instant.ofEpochMilli(period.endExclusiveMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS)
        val days = ChronoUnit.DAYS.between(startDate, endDate).toInt().coerceAtLeast(0)

        // Aggregate normalized amounts per day
        val byDay = input.includedExpenses
            .filter { it.date >= period.startInclusiveMillis && it.date < period.endExclusiveMillis }
            .groupBy { dayStart(it.date, zoneId) }

        return (0..days).map { dayOffset ->
            val dayStart = startDate.plusDays(dayOffset.toLong()).toInstant().toEpochMilli()
            val dayExpenses = byDay[dayStart].orEmpty()
            DailyBucket(
                date = dayStart,
                total = dayExpenses.sumOf { it.normalizedAmount },
                count = dayExpenses.size
            )
        }
    }

    private fun dayStart(epochMillis: Long, zoneId: ZoneId): Long {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
    }
}
