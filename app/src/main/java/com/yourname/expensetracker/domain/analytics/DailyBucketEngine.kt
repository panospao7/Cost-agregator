package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import java.time.ZoneId
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
        val count: Int,
        val moneyResult: MoneyAggregateResult
    ) {
        /** Typed projection for chart consumers that need the daily total. */
        val total: MoneyAggregate?
            get() = (moneyResult as? MoneyAggregateResult.Available)?.aggregate
    }

    fun buildBuckets(
        input: NormalizedAnalyticsInput,
        period: PeriodRange,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DailyBucket> {
        val startDay = Instant.ofEpochMilli(period.startInclusiveMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS)

        // Aggregate normalized amounts per day
        val byDay = input.includedExpenses
            .filter { it.date >= period.startInclusiveMillis && it.date < period.endExclusiveMillis }
            .groupBy { dayStart(it.date, zoneId) }

        val buckets = mutableListOf<DailyBucket>()
        var current = startDay
        while (current.toInstant().toEpochMilli() < period.endExclusiveMillis) {
            val dayEpoch = current.toInstant().toEpochMilli()
            val dayExpenses = byDay[dayEpoch].orEmpty()
            val nextDayEpoch = current.plusDays(1).toInstant().toEpochMilli()
            val moneyResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
                input = input,
                includedExpenses = dayExpenses,
                startInclusiveMillis = dayEpoch,
                endExclusiveMillis = nextDayEpoch
            )
            buckets.add(DailyBucket(dayEpoch, dayExpenses.size, moneyResult))
            current = current.plusDays(1)
        }
        return buckets
    }

    private fun dayStart(epochMillis: Long, zoneId: ZoneId): Long {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId)
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
    }
}
