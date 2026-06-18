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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun expense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long = 1_700_000_000_000L
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
        myShareAmount = null
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
