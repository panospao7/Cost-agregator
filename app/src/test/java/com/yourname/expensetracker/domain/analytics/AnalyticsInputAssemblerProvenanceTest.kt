package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PR4: Verifies that [AnalyticsInputAssembler] correctly populates
 * rate-provenance fields on [NormalizedExpense] and [ExcludedExpense].
 */
class AnalyticsInputAssemblerProvenanceTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var normalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var assembler: AnalyticsInputAssembler
    private lateinit var exchangeRateStore: FakeExchangeRateStore

    private val homeCurrency = "EUR"

    /** Wide-open period covering all test expense dates. */
    private val widePeriod = PeriodRange(
        kind = PeriodKind.CUSTOM,
        startInclusiveMillis = 0L,
        endExclusiveMillis = 9_999_999_999_999L
    )

    @Before
    fun setup() {
        exchangeRateStore = FakeExchangeRateStore()
        normalizer = AnalyticsCurrencyNormalizer(CurrencyConverter(exchangeRateStore, timeProvider = mockk()))

        expenseRepository = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)

        coEvery { currencySettingsRepository.homeCurrency() } returns flowOf(homeCurrency)
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { timeProvider.now() } returns 1_700_000_000_000L

        assembler = AnalyticsInputAssembler(
            expenseRepository = expenseRepository,
            normalizer = normalizer,
            currencySettingsRepository = currencySettingsRepository,
            timeProvider = timeProvider,
            categoryRepository = categoryRepository
        )
    }

    // -----------------------------------------------------------------------
    // Rate provenance on NormalizedExpense — identity conversion
    // -----------------------------------------------------------------------

    @Test
    fun `identity conversion populates rateBasis IDENTITY and rateUsed 1 dot 0`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns
            listOf(expense(id = 1L, amount = 50.0, currency = "EUR"))

        val input = assembler.build(period = widePeriod)

        val norm = input.includedExpenses.single()
        assertThat(norm.rateBasis).isEqualTo("IDENTITY")
        assertThat(norm.rateUsed).isEqualTo(1.0)
        assertThat(norm.rateValidDate).isNotNull()
        assertThat(norm.rateLastUpdated).isNull()
        assertThat(norm.conversionPath).isEqualTo("IDENTITY")
    }

    @Test
    fun `decision aggregate preserves historical source currency and rate dates`() = runTest {
        val date = 1_700_000_000_000L
        exchangeRateStore.putRate("USD", "EUR", 0.5, date, date, "TEST")
        val normalized = normalizer.normalizeExpenses(listOf(
            expense(1L, 20.0, "USD", date), expense(2L, 5.0, "EUR", date)
        ), "EUR")
        val aggregate = normalized.completeMoneyAggregateForRows(normalized.includedExpenses, "EUR")
        assertThat(aggregate.displayAmount).isEqualTo(15.0)
        assertThat(aggregate.totalTransactionCount).isEqualTo(2)
        assertThat(aggregate.requestedRateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(aggregate.actualRateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(aggregate.metadata.sourceCurrencyCodes).containsExactly(
            com.yourname.expensetracker.domain.core.money.CurrencyCode.USD,
            com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR
        )
        assertThat(aggregate.metadata.rateValidDates).contains(date)
    }

    @Test
    fun `decision aggregate declines partial all failed and stale inputs but accepts genuine empty`() = runTest {
        val date = 1_700_000_000_000L
        val failed = listOf(expense(1L, 20.0, "USD", date), expense(2L, 30.0, "GBP", date))
        for (rows in listOf(failed, failed + expense(3L, 5.0, "EUR", date))) {
            val normalized = normalizer.normalizeExpenses(rows, "EUR")
            org.junit.Assert.assertThrows(com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException::class.java) {
                normalized.completeMoneyAggregateForRows(normalized.includedExpenses, "EUR")
            }
        }
        exchangeRateStore.putRate("USD", "EUR", 0.5, date, date - 10L * 86_400_000L, "TEST")
        val stale = normalizer.normalizeExpenses(listOf(expense(4L, 20.0, "USD", date)), "EUR")
        assertThat(stale.includedExpenses).hasSize(1)
        org.junit.Assert.assertThrows(com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException::class.java) {
            stale.completeMoneyAggregateForRows(stale.includedExpenses, "EUR")
        }
        val empty = normalizer.normalizeExpenses(emptyList(), "EUR").completeMoneyAggregateForRows(emptyList(), "EUR")
        assertThat(empty.displayAmount).isEqualTo(0.0)
        assertThat(empty.isPartial).isFalse()
        assertThat(empty.conversionQuality).isEqualTo(com.yourname.expensetracker.domain.core.money.ConversionQuality.COMPLETE)
    }

    // -----------------------------------------------------------------------
    // Rate provenance on NormalizedExpense — successful conversion
    // -----------------------------------------------------------------------

    @Test
    fun `successful conversion populates rate provenance fields`() = runTest {
        val expenseDate = 1_700_000_000_000L
        val validDate = 1_700_000_000_000L
        val lastUpdated = 1_700_500_000_000L
        exchangeRateStore.putRate(
            from = "USD", to = "EUR", rate = 0.92,
            updatedAt = lastUpdated, validDate = validDate, source = "ECB"
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns
            listOf(expense(id = 10L, amount = 100.0, currency = "USD", date = expenseDate))

        val input = assembler.build(period = widePeriod)

        val norm = input.includedExpenses.single()
        assertThat(norm.rateBasis).isEqualTo(RateBasis.TRANSACTION_DATE.name)
        assertThat(norm.rateUsed).isEqualTo(0.92)
        assertThat(norm.rateValidDate).isEqualTo(validDate)
        assertThat(norm.rateLastUpdated).isEqualTo(lastUpdated)
        assertThat(norm.rateSource).isEqualTo("ECB")
        assertThat(norm.conversionPath).isEqualTo(ConversionPath.DIRECT.name)
    }

    // -----------------------------------------------------------------------
    // ExcludedExpense — invalid transaction currency
    // -----------------------------------------------------------------------

    @Test
    fun `excluded expense with invalid currency has warningType and message`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns
            listOf(expense(id = 20L, amount = 10.0, currency = "???"))

        val input = assembler.build(period = widePeriod)

        assertThat(input.includedExpenses).isEmpty()
        assertThat(input.excludedExpenses).hasSize(1)
        val excluded = input.excludedExpenses.single()
        assertThat(excluded.reason).isEqualTo(ExclusionReason.INVALID_CURRENCY)
        assertThat(excluded.warningType).isEqualTo(AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY)
        assertThat(excluded.message).isEqualTo("Analytics excluded transaction(s) with invalid currency codes.")
    }

    // -----------------------------------------------------------------------
    // ExcludedExpense — missing exchange rate
    // -----------------------------------------------------------------------

    @Test
    fun `excluded expense with missing rate has warningType and message`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns
            listOf(expense(id = 30L, amount = 50.0, currency = "USD"))

        val input = assembler.build(period = widePeriod)

        assertThat(input.includedExpenses).isEmpty()
        assertThat(input.excludedExpenses).hasSize(1)
        val excluded = input.excludedExpenses.single()
        assertThat(excluded.reason).isEqualTo(ExclusionReason.CONVERSION_FAILED)
        assertThat(excluded.warningType).isEqualTo(AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE)
        assertThat(excluded.message).isEqualTo("Analytics excluded transaction(s) because exchange rates were unavailable.")
    }

    @Test
    fun `data quality counts affected transactions rather than warning objects`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(id = 30L, amount = 50.0, currency = "USD"),
            expense(id = 31L, amount = 60.0, currency = "USD")
        )

        val input = assembler.build(period = widePeriod)

        assertThat(input.dataQuality.excludedCount).isEqualTo(2)
        assertThat(input.dataQuality.missingRateCount).isEqualTo(2)
        assertThat(input.dataQuality.confidenceMultiplier).isWithin(0.000001).of(0.96)
        assertThat(input.dataQuality.warnings).hasSize(1)
        assertThat(input.dataQuality.warnings.single().affectedTransactionCount).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // ExcludedExpense — mixed: valid + invalid
    // -----------------------------------------------------------------------

    @Test
    fun `mix of valid and excluded expenses each have correct provenance`() = runTest {
        exchangeRateStore.putRate("USD", "EUR", rate = 0.92, updatedAt = 1_700_500_000_000L)

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(id = 40L, amount = 50.0, currency = "EUR"),       // identity
            expense(id = 41L, amount = 100.0, currency = "USD"),      // converted
            expense(id = 42L, amount = 20.0, currency = "???"),       // invalid currency
            expense(id = 43L, amount = 75.0, currency = "GBP")        // missing rate
        )

        val input = assembler.build(period = widePeriod)

        // Two included
        assertThat(input.includedExpenses).hasSize(2)
        val id40 = input.includedExpenses.single { it.id == 40L }
        assertThat(id40.rateBasis).isEqualTo("IDENTITY")
        assertThat(id40.rateUsed).isEqualTo(1.0)

        val id41 = input.includedExpenses.single { it.id == 41L }
        assertThat(id41.rateBasis).isEqualTo(RateBasis.TRANSACTION_DATE.name)
        assertThat(id41.rateUsed).isEqualTo(0.92)

        // Two excluded
        assertThat(input.excludedExpenses).hasSize(2)

        val invalidCurrency = input.excludedExpenses.single { it.id == 42L }
        assertThat(invalidCurrency.reason).isEqualTo(ExclusionReason.INVALID_CURRENCY)
        assertThat(invalidCurrency.warningType).isEqualTo(AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY)
        assertThat(invalidCurrency.message).isNotNull()

        val missingRate = input.excludedExpenses.single { it.id == 43L }
        assertThat(missingRate.reason).isEqualTo(ExclusionReason.CONVERSION_FAILED)
        assertThat(missingRate.warningType).isEqualTo(AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE)
        assertThat(missingRate.message).isNotNull()
    }

    @Test
    fun `assembler carries spendingExclusionReason to NormalizedExpense and round-trip ExpenseSnapshot`() = runTest {
        val neutralised = expense(
            id = 50L,
            amount = 900.0,
            currency = "EUR",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val ordinary = expense(
            id = 51L,
            amount = 45.0,
            currency = "EUR",
            spendingExclusionReason = null
        )
        assertThat(neutralised.effectiveAmount).isEqualTo(0.0)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(neutralised, ordinary)

        val input = assembler.build(period = widePeriod)

        assertThat(input.includedExpenses).hasSize(1)
        assertThat(input.excludedExpenses).isEmpty()
        assertThat(input.dataQuality.excludedCount).isEqualTo(0)

        val normOrdinary = input.includedExpenses.single()
        assertThat(normOrdinary.id).isEqualTo(51L)
        assertThat(normOrdinary.spendingExclusionReason).isNull()
        assertThat(normOrdinary.normalizedAmount).isEqualTo(45.0)
        assertThat(normOrdinary.toExpenseSnapshot().spendingExclusionReason).isNull()
        assertThat(normOrdinary.toExpenseSnapshot().effectiveAmount).isEqualTo(45.0)

        // Direct NormalizedExpense.toExpenseSnapshot() and SingleCurrencyAnalyticsInput round-trip preserve spendingExclusionReason
        val normNeutralised = NormalizedExpense(
            id = 50L,
            originalAmount = 900.0,
            originalEffectiveAmount = 0.0,
            originalCurrency = "EUR",
            normalizedAmount = 0.0,
            normalizedCurrency = "EUR",
            date = 1_700_000_000_000L,
            merchant = "Merchant 50",
            merchantKey = null,
            categoryId = null,
            categoryNameSnapshot = null,
            transactionType = "PURCHASE",
            isNotMine = false,
            isSharedExpense = false,
            ownershipMode = null,
            source = null,
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        assertThat(normNeutralised.toExpenseSnapshot().spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(normNeutralised.toExpenseSnapshot().effectiveAmount).isEqualTo(0.0)

        val singleCurrency = SingleCurrencyAnalyticsInput.from(
            listOf(normNeutralised.toExpenseSnapshot(), normOrdinary.toExpenseSnapshot()),
            displayCurrency = "EUR"
        )
        val singleById = singleCurrency.includedExpenses.associateBy { it.id }
        assertThat(singleById.getValue(50L).spendingExclusionReason).isEqualTo("INSTALLMENT_PURCHASE")
        assertThat(singleById.getValue(51L).spendingExclusionReason).isNull()
    }

    @Test
    fun `assembler drops foreign neutralised row with no rate without marking period partial`() = runTest {
        // FakeExchangeRateStore is empty — no USD->EUR rate exists.
        val neutralisedForeign = expense(
            id = 60L,
            amount = 500.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val homeOrdinary = expense(
            id = 61L,
            amount = 40.0,
            currency = "EUR",
            spendingExclusionReason = null
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(neutralisedForeign, homeOrdinary)

        val input = assembler.build(period = widePeriod)

        assertThat(input.includedExpenses).hasSize(1)
        assertThat(input.excludedExpenses).isEmpty()
        assertThat(input.dataQuality.isPartial).isFalse()
        assertThat(input.dataQuality.excludedCount).isEqualTo(0)
        assertThat(input.dataQuality.missingRateCount).isEqualTo(0)
        assertThat(input.dataQuality.warnings).isEmpty()

        val normOrdinary = input.includedExpenses.single()
        assertThat(normOrdinary.id).isEqualTo(61L)
        assertThat(normOrdinary.normalizedAmount).isEqualTo(40.0)
        assertThat(normOrdinary.normalizedCurrency).isEqualTo("EUR")
        assertThat(normOrdinary.spendingExclusionReason).isNull()
    }

    @Test
    fun `assembler negative control non-neutralised foreign row with no rate is excluded and marks period partial`() = runTest {
        // FakeExchangeRateStore is empty — no USD->EUR rate exists.
        val neutralisedForeign = expense(
            id = 62L,
            amount = 500.0,
            currency = "USD",
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        val nonNeutralisedForeign = expense(
            id = 63L,
            amount = 75.0,
            currency = "USD",
            spendingExclusionReason = null
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(neutralisedForeign, nonNeutralisedForeign)

        val input = assembler.build(period = widePeriod)

        assertThat(input.includedExpenses).isEmpty()
        assertThat(input.excludedExpenses.map { it.id }).containsExactly(63L)
        assertThat(input.dataQuality.isPartial).isTrue()
        assertThat(input.dataQuality.excludedCount).isEqualTo(1)
        assertThat(input.dataQuality.missingRateCount).isEqualTo(1)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun expense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long = 1_700_000_000_000L,
        spendingExclusionReason: String? = null
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = date,
        rawNotificationId = null,
        categoryId = null,
        createdAt = date,
        paymentMethod = PaymentMethod.UNKNOWN,
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

        fun putRate(
            from: String,
            to: String,
            rate: Double,
            updatedAt: Long,
            validDate: Long = 0L,
            source: String = "manual"
        ) {
            rates[from.uppercase() to to.uppercase()] = DomainExchangeRate(
                fromCurrency = from.uppercase(),
                toCurrency = to.uppercase(),
                rate = rate,
                lastUpdated = updatedAt,
                validDate = validDate,
                source = source
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
