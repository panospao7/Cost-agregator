package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine
import com.yourname.expensetracker.domain.core.money.NormalizationResult
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class AnalyticsCurrencyNormalizerTest {

    private lateinit var exchangeRateStore: FakeExchangeRateStore
    private lateinit var normalizer: AnalyticsCurrencyNormalizer

    @Before
    fun setup() {
        exchangeRateStore = FakeExchangeRateStore()
        normalizer = AnalyticsCurrencyNormalizer(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))
    }

    @Test
    fun `normalizeExpenses keeps same-currency transactions without warnings`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 1L, amount = 25.0, currency = "EUR")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.homeCurrency).isEqualTo("EUR")
        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.includedExpenses.single().effectiveAmount).isEqualTo(25.0)
        assertThat(result.includedExpenses.single().currency).isEqualTo("EUR")
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `normalizeExpenses converts foreign currency into home currency`() = runTest {
        exchangeRateStore.putRate("USD", "EUR", rate = 2.0, updatedAt = 1234L)

        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 2L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.includedExpenses.single().effectiveAmount).isEqualTo(20.0)
        assertThat(result.includedExpenses.single().currency).isEqualTo("EUR")
        assertThat(result.latestRateTimestamp).isEqualTo(1234L)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `normalizeExpenses excludes invalid transaction currency with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 3L, amount = 10.0, currency = "???")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY)
        assertThat(result.warnings.single().affectedTransactionCount).isEqualTo(1)
    }

    @Test
    fun `normalizeExpenses rejects invalid home currency with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 4L, amount = 10.0, currency = "EUR")),
            homeCurrencyCode = "bad-home"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.INVALID_HOME_CURRENCY)
        assertThat(result.warnings.single().affectedTransactionCount).isEqualTo(1)
    }

    @Test
    fun `normalizeExpenses excludes missing-rate transactions with warning`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 5L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE)
        assertThat(result.warnings.single().sourceCurrencies).containsExactly("USD")
    }

    @Test
    fun `normalizeExpenses flags stale rate using validDate not lastUpdated`() = runTest {
        // Backfilled rate: lastUpdated is recent (near expense date) but validDate is far in the past.
        // P5-NEW-07: staleness must key off validDate, so this MUST be flagged stale.
        val expenseDate = 1_700_000_000_000L
        val farPastValidDate = expenseDate - 30L * 24 * 60 * 60 * 1000 // 30 days before
        exchangeRateStore.putRate("USD", "EUR", rate = 2.0, updatedAt = expenseDate, validDate = farPastValidDate)

        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 6L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).hasSize(1) // still included
        assertThat(result.warnings.map { it.type })
            .contains(AnalyticsConversionWarningType.STALE_EXCHANGE_RATE)
    }

    @Test
    fun `normalizeExpenses does not flag stale when validDate is near expense date`() = runTest {
        // Recent lastUpdated, validDate within 7 days of the expense → NOT stale.
        val expenseDate = 1_700_000_000_000L
        val nearValidDate = expenseDate - 2L * 24 * 60 * 60 * 1000 // 2 days before
        exchangeRateStore.putRate("USD", "EUR", rate = 2.0, updatedAt = expenseDate, validDate = nearValidDate)

        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 7L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.warnings.map { it.type })
            .doesNotContain(AnalyticsConversionWarningType.STALE_EXCHANGE_RATE)
    }

    @Test
    fun `normalizeExpenses carries spendingExclusionReason to ExpenseSnapshot and keeps null for ordinary expenses`() = runTest {
        val neutralised = expense(
            id = 8L,
            amount = 600.0,
            currency = "EUR",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val ordinary = expense(
            id = 9L,
            amount = 40.0,
            currency = "EUR",
            spendingExclusionReason = null
        )
        assertThat(neutralised.effectiveAmount).isEqualTo(0.0)

        // Single-row snapshot & MoneyNormalizationEngine carry spendingExclusionReason
        assertThat(neutralised.toExpenseSnapshot().spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(neutralised.toExpenseSnapshot().effectiveAmount).isEqualTo(0.0)
        assertThat(ordinary.toExpenseSnapshot().spendingExclusionReason).isNull()
        assertThat(ordinary.toExpenseSnapshot().effectiveAmount).isEqualTo(40.0)

        val moneyEngine = MoneyNormalizationEngine(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))
        val singleNorm = (moneyEngine.normalizeExpense(neutralised, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE) as NormalizationResult.Included).value
        assertThat(singleNorm.spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(singleNorm.normalizedAmount).isEqualTo(0.0)

        // Batch normalizeExpenses excludes the neutralised row from includedExpenses without marking excludedCount
        val result = normalizer.normalizeExpenses(
            expenses = listOf(neutralised, ordinary),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).hasSize(1)
        assertThat(result.excludedCount).isEqualTo(0)
        val includedOrdinary = result.includedExpenses.single()
        assertThat(includedOrdinary.id).isEqualTo(9L)
        assertThat(includedOrdinary.spendingExclusionReason).isNull()
        assertThat(includedOrdinary.effectiveAmount).isEqualTo(40.0)

        val aggregate = result.completeMoneyAggregateForRows(result.includedExpenses, "EUR")
        assertThat(aggregate.displayAmount).isEqualTo(40.0)
        assertThat(aggregate.totalTransactionCount).isEqualTo(1)
    }

    @Test
    fun `normalizeSnapshots preserves spendingExclusionReason across foreign conversion and keeps null`() = runTest {
        exchangeRateStore.putRate("USD", "EUR", rate = 0.5, updatedAt = 1_700_000_000_000L)
        val neutralised = expense(
            id = 10L,
            amount = 200.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val ordinary = expense(
            id = 11L,
            amount = 50.0,
            currency = "USD",
            spendingExclusionReason = null
        )
        val initialSnapshots = listOf(neutralised.toExpenseSnapshot(), ordinary.toExpenseSnapshot())
        assertThat(initialSnapshots[0].spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(initialSnapshots[0].effectiveAmount).isEqualTo(0.0)
        assertThat(initialSnapshots[1].spendingExclusionReason).isNull()
        assertThat(initialSnapshots[1].effectiveAmount).isEqualTo(50.0)

        val converted = normalizer.normalizeSnapshots(
            expenses = initialSnapshots,
            homeCurrencyCode = "EUR"
        )

        assertThat(converted.includedExpenses).hasSize(1)
        assertThat(converted.excludedCount).isEqualTo(0)
        val convertedOrdinary = converted.includedExpenses.single()
        assertThat(convertedOrdinary.id).isEqualTo(11L)
        assertThat(convertedOrdinary.spendingExclusionReason).isNull()
        assertThat(convertedOrdinary.effectiveAmount).isEqualTo(25.0)
    }

    @Test
    fun `foreign neutralised expense with no exchange rate is excluded from includedExpenses without failure and passes requireCompleteMoneyInput`() = runTest {
        // FakeExchangeRateStore is empty — no USD->EUR rate exists.
        val neutralisedForeign = expense(
            id = 12L,
            amount = 250.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        assertThat(neutralisedForeign.effectiveAmount).isEqualTo(0.0)

        val result = normalizer.normalizeExpenses(
            expenses = listOf(neutralisedForeign),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.normalizedExpenses).isEmpty()
        assertThat(result.excludedCount).isEqualTo(0)
        assertThat(result.hasWarnings).isFalse()
        assertThat(result.warnings).isEmpty()
        assertThat(result.excludedReasons).isEmpty()

        // Complete money input check (used by SmartSavingsEngine) must succeed and not be partial
        result.requireCompleteMoneyInput("EUR")
        val aggregate = result.completeMoneyAggregateForRows(result.includedExpenses, "EUR")
        assertThat(aggregate.displayAmount).isEqualTo(0.0)
        assertThat(aggregate.isPartial).isFalse()
        assertThat(aggregate.conversionQuality).isEqualTo(ConversionQuality.COMPLETE)
        assertThat(aggregate.metadata.includedTransactionCount).isEqualTo(0)
        assertThat(aggregate.metadata.excludedTransactionCount).isEqualTo(0)

        // MoneyNormalizationEngine path (NormalizationResult + aggregateExpenses)
        val moneyEngine = MoneyNormalizationEngine(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))
        val normResult = moneyEngine.normalizeExpense(neutralisedForeign, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertThat(normResult).isInstanceOf(NormalizationResult.Included::class.java)
        val included = (normResult as NormalizationResult.Included).value
        assertThat(included.normalizedAmount).isEqualTo(0.0)
        assertThat(included.normalizedCurrency).isEqualTo("EUR")
        assertThat(included.spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(included.rateBasis).isEqualTo("IDENTITY")
        assertThat(included.rateUsed).isEqualTo(1.0)

        val engineAggregate = moneyEngine.aggregateExpenses(
            expenses = listOf(neutralisedForeign),
            homeCurrency = CurrencyCode.EUR,
            rateBasis = RateBasis.TRANSACTION_DATE
        )
        assertThat(engineAggregate.displayAmount).isEqualTo(0.0)
        assertThat(engineAggregate.isPartial).isFalse()
        assertThat(engineAggregate.conversionQuality).isEqualTo(ConversionQuality.COMPLETE)
        assertThat(engineAggregate.metadata.includedTransactionCount).isEqualTo(0)
        assertThat(engineAggregate.metadata.excludedTransactionCount).isEqualTo(0)
        assertThat(engineAggregate.conversionFailures).isEmpty()
    }

    @Test
    fun `foreign neutralised snapshot with no exchange rate is excluded from includedExpenses without warnings`() = runTest {
        // FakeExchangeRateStore is empty — no USD->EUR rate exists.
        val neutralisedForeign = expense(
            id = 13L,
            amount = 300.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val initialSnapshot = listOf(neutralisedForeign.toExpenseSnapshot())
        assertThat(initialSnapshot.single().spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")

        val converted = normalizer.normalizeSnapshots(
            expenses = initialSnapshot,
            homeCurrencyCode = "EUR"
        )

        assertThat(converted.includedExpenses).isEmpty()
        assertThat(converted.normalizedExpenses).isEmpty()
        assertThat(converted.excludedCount).isEqualTo(0)
        assertThat(converted.hasWarnings).isFalse()
        converted.requireCompleteMoneyInput("EUR")
    }

    @Test
    fun `negative control non-neutralised foreign row with missing rate is excluded and marks period partial`() = runTest {
        // FakeExchangeRateStore is empty — no USD->EUR rate exists.
        val neutralisedForeign = expense(
            id = 14L,
            amount = 200.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val nonNeutralisedForeign = expense(
            id = 15L,
            amount = 80.0,
            currency = "USD",
            spendingExclusionReason = null
        )

        val result = normalizer.normalizeExpenses(
            expenses = listOf(neutralisedForeign, nonNeutralisedForeign),
            homeCurrencyCode = "EUR"
        )

        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.excludedCount).isEqualTo(1)
        assertThat(result.hasWarnings).isTrue()
        assertThat(result.warnings.single().type).isEqualTo(AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE)
        assertThat(result.warnings.single().affectedTransactionCount).isEqualTo(1)
        assertThat(result.excludedReasons.keys).containsExactly(15L)

        val thrown = org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireCompleteMoneyInput("EUR")
        }
        assertThat(thrown.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)

        // MoneyNormalizationEngine negative control
        val moneyEngine = MoneyNormalizationEngine(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))
        val normResult = moneyEngine.normalizeExpense(nonNeutralisedForeign, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertThat(normResult).isInstanceOf(NormalizationResult.Excluded::class.java)
        val excluded = normResult as NormalizationResult.Excluded
        assertThat(excluded.sourceEntityId).isEqualTo(15L)
        assertThat(excluded.failure.reason).isEqualTo(FailureReason.MISSING_RATE)

        val partialAgg = moneyEngine.aggregateExpenses(
            expenses = listOf(neutralisedForeign, nonNeutralisedForeign),
            homeCurrency = CurrencyCode.EUR,
            rateBasis = RateBasis.TRANSACTION_DATE
        )
        assertThat(partialAgg.isPartial).isTrue()
        assertThat(partialAgg.conversionQuality).isEqualTo(ConversionQuality.UNAVAILABLE)
        assertThat(partialAgg.metadata.includedTransactionCount).isEqualTo(0)
        assertThat(partialAgg.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(partialAgg.metadata.missingRateCount).isEqualTo(1)
    }

    @Test
    fun `isPartial contract preserved when all conversions fail with no neutralised rows`() = runTest {
        // FakeExchangeRateStore is empty — all non-neutralised foreign rows fail conversion.
        val allFailed = listOf(
            expense(id = 16L, amount = 50.0, currency = "USD", spendingExclusionReason = null),
            expense(id = 17L, amount = 70.0, currency = "GBP", spendingExclusionReason = null)
        )

        val result = normalizer.normalizeExpenses(
            expenses = allFailed,
            homeCurrencyCode = "EUR"
        )
        assertThat(result.includedExpenses).isEmpty()
        assertThat(result.excludedCount).isEqualTo(2)
        assertThat(result.hasWarnings).isTrue()
        org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireCompleteMoneyInput("EUR")
        }

        val moneyEngine = MoneyNormalizationEngine(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))
        val aggregate = moneyEngine.aggregateExpenses(
            expenses = allFailed,
            homeCurrency = CurrencyCode.EUR,
            rateBasis = RateBasis.TRANSACTION_DATE
        )
        assertThat(aggregate.isPartial).isTrue()
        assertThat(aggregate.conversionQuality).isEqualTo(ConversionQuality.UNAVAILABLE)
        assertThat(aggregate.displayAmount).isEqualTo(0.0)
        assertThat(aggregate.metadata.includedTransactionCount).isEqualTo(0)
        assertThat(aggregate.metadata.excludedTransactionCount).isEqualTo(2)
        assertThat(aggregate.metadata.missingRateCount).isEqualTo(2)
    }

    @Test
    fun `requireNoLostMoneyInput passes for complete same-currency input`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 20L, amount = 10.0, currency = "EUR")),
            homeCurrencyCode = "EUR"
        )
        result.requireNoLostMoneyInput("EUR")
    }

    @Test
    fun `requireNoLostMoneyInput passes with only a stale rate warning while requireCompleteMoneyInput still fails`() = runTest {
        val expenseDate = 1_700_000_000_000L
        exchangeRateStore.putRate(
            "USD", "EUR", rate = 2.0, updatedAt = expenseDate,
            validDate = expenseDate - 30L * 24 * 60 * 60 * 1000
        )
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 21L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )
        assertThat(result.warnings.map { it.type })
            .containsExactly(AnalyticsConversionWarningType.STALE_EXCHANGE_RATE)
        assertThat(result.excludedCount).isEqualTo(0)

        result.requireNoLostMoneyInput("EUR")
        org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireCompleteMoneyInput("EUR")
        }
    }

    @Test
    fun `requireNoLostMoneyInput fails when a row is excluded`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 22L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "EUR"
        )
        assertThat(result.excludedCount).isEqualTo(1)

        val thrown = org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireNoLostMoneyInput("EUR")
        }
        assertThat(thrown.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `requireNoLostMoneyInput fails on excludedCount above zero even without any warning`() {
        val result = AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = emptyList(),
            latestRateTimestamp = null,
            totalInputCount = 1
        )
        assertThat(result.excludedCount).isEqualTo(1)
        org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireNoLostMoneyInput("EUR")
        }
    }

    @Test
    fun `requireNoLostMoneyInput fails on a severe warning even with no excluded rows`() {
        for (type in listOf(
            AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
            AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
        )) {
            val result = AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = listOf(
                    AnalyticsConversionWarning(type = type, message = "synthetic", affectedTransactionCount = 1)
                ),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
            assertThat(result.excludedCount).isEqualTo(0)
            assertThat(result.severeWarnings).isNotEmpty()
            org.junit.Assert.assertThrows(type.name, MoneyDisplayUnavailableException::class.java) {
                result.requireNoLostMoneyInput("EUR")
            }
        }
    }

    @Test
    fun `requireNoLostMoneyInput fails when the result is not in the target currency`() = runTest {
        val usdResult = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 23L, amount = 10.0, currency = "USD")),
            homeCurrencyCode = "USD"
        )
        usdResult.requireNoLostMoneyInput("USD")

        val thrown = org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            usdResult.requireNoLostMoneyInput("EUR")
        }
        assertThat(thrown.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `requireNoLostMoneyInput rejects an invalid target currency`() = runTest {
        val result = normalizer.normalizeExpenses(
            expenses = listOf(expense(id = 24L, amount = 10.0, currency = "EUR")),
            homeCurrencyCode = "EUR"
        )
        val thrown = org.junit.Assert.assertThrows(MoneyDisplayUnavailableException::class.java) {
            result.requireNoLostMoneyInput("bad-target")
        }
        assertThat(thrown.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY)
    }

    private fun expense(
        id: Long,
        amount: Double,
        currency: String,
        spendingExclusionReason: String? = null
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        rawNotificationId = null,
        categoryId = null,
        createdAt = 1_700_000_000_000L,
        paymentMethod = com.yourname.expensetracker.data.database.entity.PaymentMethod.UNKNOWN,
        isManualEntry = false,
        notes = null,
        dedupeKey = null,
        transferDirection = null,
        transferAccountName = null,
        isNotMine = false,
        ownerName = null,
        isSharedExpense = false,
        sharedWithName = null,
        mySharePercentage = null,
        myShareAmount = null,
        spendingExclusionReason = spendingExclusionReason
    )

    private class FakeExchangeRateStore : ExchangeRateStore {
        private val rates = mutableMapOf<Pair<String, String>, DomainExchangeRate>()

        fun putRate(from: String, to: String, rate: Double, updatedAt: Long, validDate: Long = 0L) {
            rates[from.uppercase() to to.uppercase()] = DomainExchangeRate(
                fromCurrency = from.uppercase(),
                toCurrency = to.uppercase(),
                rate = rate,
                lastUpdated = updatedAt,
                validDate = validDate
            )
        }

        override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            return rates[fromCurrency.uppercase() to toCurrency.uppercase()]
        }

        override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            return rates[fromCurrency.uppercase() to toCurrency.uppercase()]
        }

        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rates[rate.fromCurrency.uppercase() to rate.toCurrency.uppercase()] = rate
        }

        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
            rates.forEach { insertOrUpdate(it) }
        }

        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? =
            getRate(fromCurrency, toCurrency)

        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()

        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }

        override suspend fun deleteOldRates(olderThan: Long) {
            rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
        }
    }
}