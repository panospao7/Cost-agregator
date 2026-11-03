package com.yourname.expensetracker.ui.screens.naturallanguage

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.NaturalLanguageExpenseQueryRepositoryImpl
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputGateway
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S26-1 tests for [NaturalLanguageSearchViewModel] and [NaturalLanguageSearchEngine]:
 * - Neutralised rows (`spendingExclusionReason != null`) and non-PURCHASE rows (`DEPOSIT`/`TRANSFER`)
 *   must NOT be summed into `TOTAL_AMOUNT`.
 * - ViewModel total must use the engine's `convertAsOf(date)` normalized amounts (`RateBasis.TRANSACTION_DATE`),
 *   NOT re-convert raw `effectiveAmount` at `LATEST_AVAILABLE`.
 * - Failed historical conversions must surface in `interpretation.dataQuality` and reflect partial/failed
 *   quality rather than silently summing raw foreign amounts or reporting a clean total.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NaturalLanguageSearchViewModelTest : ViewModelTestUtils() {

    companion object {
        private const val NOW_MS = 1_710_000_000_000L
        private const val TX_DATE_T = 1_709_500_000_000L
    }

    private lateinit var expenseDao: ExpenseDao
    private lateinit var queryRepository: NaturalLanguageExpenseQueryRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var merchantNormalizationRepository: MerchantNormalizationRepository
    private lateinit var speechInputGateway: SpeechInputGateway
    private lateinit var timeProvider: TimeProvider
    private lateinit var searchEngine: NaturalLanguageSearchEngine
    private lateinit var viewModel: NaturalLanguageSearchViewModel

    @Before
    override fun setup() {
        super.setup()
        expenseDao = mockk(relaxed = true)
        queryRepository = NaturalLanguageExpenseQueryRepositoryImpl(expenseDao)
        currencyConverter = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        merchantNormalizationRepository = mockk(relaxed = true)
        speechInputGateway = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        every { timeProvider.now() } returns NOW_MS
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { merchantNormalizationRepository.getTopMerchants(any()) } returns emptyList()
        coEvery { merchantNormalizationRepository.getAliasByRawName(any()) } returns null
        coEvery { merchantNormalizationRepository.getAliasByNormalizedKey(any()) } returns null

        searchEngine = NaturalLanguageSearchEngine(
            expenseQueryRepository = queryRepository,
            speechInputGateway = speechInputGateway,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            categoryRepository = categoryRepository,
            merchantNormalizationRepository = merchantNormalizationRepository
        )
        viewModel = NaturalLanguageSearchViewModel(
            searchEngine = searchEngine,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            speechInputGateway = speechInputGateway
        )
    }

    private fun seedDaoWithSqlSemantics(seededRows: List<Expense>, txTypeSlot: io.mockk.CapturingSlot<String>) {
        coEvery {
            expenseDao.getExpensesFilteredKeyset(
                startDate = any(),
                endDate = any(),
                categoryIds = any(),
                categoryFilterEnabled = any(),
                merchantLike = any(),
                transactionType = capture(txTypeSlot),
                keywordLike = any(),
                limit = any(),
                cursorDate = any(),
                cursorId = any()
            )
        } answers {
            val startDate = firstArg<Long>()
            val endDate = secondArg<Long>()
            val requestedType = txTypeSlot.captured
            seededRows.filter { e ->
                e.date in startDate until endDate &&
                    e.countsAsSpending &&
                    e.transactionType.name == requestedType
            }
        }
    }

    // ── Property 2: S26-1 NL search exclusion ───────────────────────────────

    @Test
    fun `S26-1 total query excludes neutralised purchase and DEPOSIT leaving only normal 40_0 EUR purchase`() =
        runTest(testDispatcher) {
            val seededRows = listOf(
                Expense(
                    id = 1L,
                    amount = 40.0,
                    currency = "EUR",
                    merchant = "Bakery",
                    transactionType = TransactionType.PURCHASE,
                    date = TX_DATE_T
                ),
                Expense(
                    id = 2L,
                    amount = 600.0,
                    currency = "EUR",
                    merchant = "Electronics",
                    transactionType = TransactionType.PURCHASE,
                    date = TX_DATE_T,
                    spendingExclusionReason = "INSTALLMENT_PURCHASE"
                ),
                Expense(
                    id = 3L,
                    amount = 200.0,
                    currency = "EUR",
                    merchant = "Payroll",
                    transactionType = TransactionType.DEPOSIT,
                    date = TX_DATE_T
                )
            )
            val txTypeSlot = slot<String>()
            seedDaoWithSqlSemantics(seededRows, txTypeSlot)

            viewModel.updateQuery("how much did I spend this month")
            advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(
                NaturalLanguageSearchEngine.QueryType.TOTAL_AMOUNT,
                viewModel.interpretation.value?.queryType
            )
            assertEquals("engine must request PURCHASE only", TransactionType.PURCHASE.name, txTypeSlot.captured)
            assertEquals(SearchState.Results, viewModel.searchState.value)
            assertEquals("result count", 1, viewModel.results.value.size)
            assertEquals(1L, viewModel.results.value.single().expense.id)
            assertEquals(40.0, viewModel.results.value.single().normalizedAmount, 0.0)
            assertEquals("TOTAL_AMOUNT must be 40.0 EUR", 40.0, viewModel.totalInHomeCurrency.value, 0.0)
        }

    // ── Property 3: S26-1 rate-basis parity (engine vs VM) ──────────────────

    @Test
    fun `S26-1 ViewModel total uses historical TRANSACTION_DATE convertAsOf 80_0 EUR not LATEST_AVAILABLE 90_0 EUR`() =
        runTest(testDispatcher) {
            val foreignPurchase = Expense(
                id = 10L,
                amount = 100.0,
                currency = "USD",
                merchant = "US Store",
                transactionType = TransactionType.PURCHASE,
                date = TX_DATE_T
            )
            val txTypeSlot = slot<String>()
            seedDaoWithSqlSemantics(listOf(foreignPurchase), txTypeSlot)

            // Historical rate at TX_DATE_T is 0.80 -> 80.0 EUR
            coEvery {
                currencyConverter.convertAsOf(100.0, "USD", "EUR", TX_DATE_T)
            } returns ConversionResult(
                originalAmount = 100.0,
                originalCurrency = "USD",
                convertedAmount = 80.0,
                targetCurrency = "EUR",
                rateUsed = 0.80,
                timestamp = TX_DATE_T
            )
            // Latest rate is 0.90 -> 90.0 EUR (would be used if VM re-converted at LATEST_AVAILABLE)
            coEvery {
                currencyConverter.convert(100.0, "USD", "EUR")
            } returns ConversionResult(
                originalAmount = 100.0,
                originalCurrency = "USD",
                convertedAmount = 90.0,
                targetCurrency = "EUR",
                rateUsed = 0.90,
                timestamp = NOW_MS
            )
            coEvery {
                currencyConverter.convertMultiple(any(), "EUR")
            } returns com.yourname.expensetracker.domain.currency.MultiConversionAggregate(
                total = 90.0,
                targetCurrency = "EUR",
                failedConversions = emptyList()
            )

            viewModel.updateQuery("total spent this month")
            advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(SearchState.Results, viewModel.searchState.value)
            assertEquals(1, viewModel.results.value.size)
            assertEquals(80.0, viewModel.results.value.single().normalizedAmount, 0.0)
            assertEquals(
                "ViewModel total must use historical TRANSACTION_DATE (80.0), not LATEST_AVAILABLE (90.0)",
                80.0,
                viewModel.totalInHomeCurrency.value,
                0.0
            )
            coVerify(exactly = 0) { currencyConverter.convertMultiple(any(), any()) }
        }

    // ── Property 4: S26-1 failed conversion quality ─────────────────────────

    @Test
    fun `S26-1 failed historical conversion surfaces partial quality in state and never sums raw foreign amount`() =
        runTest(testDispatcher) {
            val euroPurchase = Expense(
                id = 20L,
                amount = 40.0,
                currency = "EUR",
                merchant = "Local Shop",
                transactionType = TransactionType.PURCHASE,
                date = TX_DATE_T
            )
            val unconvertibleUsdPurchase = Expense(
                id = 21L,
                amount = 100.0,
                currency = "USD",
                merchant = "Foreign Shop",
                transactionType = TransactionType.PURCHASE,
                date = TX_DATE_T
            )
            val txTypeSlot = slot<String>()
            seedDaoWithSqlSemantics(listOf(euroPurchase, unconvertibleUsdPurchase), txTypeSlot)

            // Historical conversion fails for USD on TX_DATE_T
            coEvery {
                currencyConverter.convertAsOf(100.0, "USD", "EUR", TX_DATE_T)
            } returns null

            viewModel.updateQuery("total spent this month")
            advanceTimeBy(350)
            advanceUntilIdle()

            // Partial failure: 40.0 EUR included, 100.0 USD excluded, quality warnings set
            assertEquals(SearchState.Results, viewModel.searchState.value)
            assertEquals(1, viewModel.results.value.size)
            assertEquals(20L, viewModel.results.value.single().expense.id)
            assertEquals("must not sum raw 100.0 USD into 40.0 EUR total", 40.0, viewModel.totalInHomeCurrency.value, 0.0)
            val partialQuality = viewModel.interpretation.value!!.dataQuality
            assertEquals("failedCurrencyConversions count", 1, partialQuality.failedCurrencyConversions)
            assertTrue("dataQuality.hasWarnings must be true on partial failure", partialQuality.hasWarnings)
        }

    @Test
    fun `S26-1 when all foreign historical conversions fail state is Empty with total 0_0 and failed quality`() =
        runTest(testDispatcher) {
            val unconvertibleUsdPurchase = Expense(
                id = 30L,
                amount = 100.0,
                currency = "USD",
                merchant = "Foreign Only",
                transactionType = TransactionType.PURCHASE,
                date = TX_DATE_T
            )
            val txTypeSlot = slot<String>()
            seedDaoWithSqlSemantics(listOf(unconvertibleUsdPurchase), txTypeSlot)

            coEvery {
                currencyConverter.convertAsOf(100.0, "USD", "EUR", TX_DATE_T)
            } returns null

            viewModel.updateQuery("how much did I spend this month")
            advanceTimeBy(350)
            advanceUntilIdle()

            assertEquals(SearchState.Empty, viewModel.searchState.value)
            assertTrue(viewModel.results.value.isEmpty())
            assertEquals(0.0, viewModel.totalInHomeCurrency.value, 0.0)
            val unavailableQuality = viewModel.interpretation.value!!.dataQuality
            assertEquals(1, unavailableQuality.failedCurrencyConversions)
            assertTrue(unavailableQuality.hasWarnings)
        }
}
