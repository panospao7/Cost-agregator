package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.core.time.PeriodKind
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyBucketEngineMoneyTest {

    private val engine = DailyBucketEngine()
    private val zone = ZoneId.of("UTC")

    @Test
    fun `daily buckets expose typed normalized aggregate and preserve provenance`() {
        val day = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli()
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            period = PeriodRange(PeriodKind.CUSTOM, day, day + 86_400_000L),
            includedExpenses = listOf(
                expense(1L, 10.0, "USD", day + 1_000L, 100L),
                expense(2L, 5.0, "EUR", day + 2_000L, 101L)
            )
        )

        val bucket = engine.buildBuckets(input, input.period!!, zone).single()
        val result = bucket.moneyResult as MoneyAggregateResult.Available

        assertThat(bucket.total?.displayAmount).isEqualTo(15.0)
        assertThat(bucket.count).isEqualTo(2)
        assertThat(result.aggregate.displayCurrency).isEqualTo(CurrencyCode.EUR)
        assertThat(result.aggregate.requestedRateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(result.aggregate.metadata.sourceCurrencyCodes)
            .containsExactly(CurrencyCode.USD, CurrencyCode.EUR)
    }

    @Test
    fun `unknown exclusion provenance makes the affected daily aggregate unavailable`() {
        val day = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli()
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            period = PeriodRange(PeriodKind.CUSTOM, day, day + 86_400_000L),
            includedExpenses = listOf(expense(1L, 10.0, "EUR", day + 1_000L)),
            excludedExpenses = listOf(
                ExcludedExpense(
                    id = 2L,
                    originalAmount = 5.0,
                    originalCurrency = "USD",
                    reason = ExclusionReason.CONVERSION_FAILED
                )
            ),
            dataQuality = AnalyticsDataQuality(isPartial = true, excludedCount = 1)
        )

        val bucket = engine.buildBuckets(input, input.period!!, zone).single()

        assertThat(bucket.moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat(bucket.total).isNull()
    }

    @Test
    fun `empty days remain available typed zero aggregates`() {
        val day = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli()
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            period = PeriodRange(PeriodKind.CUSTOM, day, day + 2 * 86_400_000L)
        )

        val buckets = engine.buildBuckets(input, input.period!!, zone)

        assertThat(buckets).hasSize(2)
        assertTrue(buckets.all { it.moneyResult is MoneyAggregateResult.Available })
        assertThat(buckets.map { it.total?.displayAmount }).containsExactly(0.0, 0.0).inOrder()
    }

    private fun expense(
        id: Long,
        amount: Double,
        sourceCurrency: String,
        date: Long,
        rateValidDate: Long? = null
    ) = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = sourceCurrency,
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        date = date,
        merchant = "Merchant $id",
        merchantKey = null,
        categoryId = null,
        categoryNameSnapshot = null,
        transactionType = "PURCHASE",
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = "TEST",
        rateBasis = RateBasis.TRANSACTION_DATE.name,
        rateUsed = null,
        rateValidDate = rateValidDate,
        rateLastUpdated = null,
        rateSource = null,
        conversionPath = null
    )
}
