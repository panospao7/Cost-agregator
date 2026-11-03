package com.yourname.expensetracker.ui.screens.naturallanguage

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import com.yourname.expensetracker.domain.naturallanguage.SearchCursor
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real [NaturalLanguageSearchViewModel] driven through its private performSearch (debounced query), with a
 * real [NaturalLanguageSearchEngine] over a fake row repository. Only the repositories and the converter are
 * substituted. The queries carry no amount filter, so every row the fake returns reaches the total.
 *
 * Expected totals are written out by hand from the rows and the stub rates below, never recomputed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class NaturalLanguageSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val nowMs = 1_710_000_000_000L
    private val rowDate = 1_700_000_000_000L

    private val queryOne = "show my recent spending"
    private val queryTwo = "show my other spending"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- fixtures ----

    private class FakeRepo : NaturalLanguageExpenseQueryRepository {
        var rows: List<NaturalLanguageExpense> = emptyList()
        var failure: Throwable? = null

        override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> =
            throw AssertionError("getExpensesBetween must not be used")

        @Suppress("OVERRIDE_DEPRECATION")
        override suspend fun getExpensesBetweenFiltered(
            startMs: Long,
            endMs: Long,
            merchants: List<String>?,
            categories: List<String>?,
            minAmount: Double?,
            maxAmount: Double?
        ): List<NaturalLanguageExpense> = throw AssertionError("deprecated query must not be used")

        override suspend fun getExpensesBetweenFilteredKeyset(
            startMs: Long,
            endMs: Long,
            categoryIds: Set<Long>?,
            merchants: List<String>?,
            transactionType: String?,
            keywordSearch: String?,
            limit: Int,
            cursor: SearchCursor?
        ): List<NaturalLanguageExpense> {
            failure?.let { throw it }
            return if (cursor == null) rows else emptyList()
        }
    }

    private fun row(id: Long, amount: Double, effective: Double, currency: String = "EUR") =
        NaturalLanguageExpense(
            id = id,
            amount = amount,
            effectiveAmount = effective,
            currency = currency,
            merchant = "Shop",
            date = rowDate - id,
            categoryId = null
        )

    /** Converter stub: only currencies in [rates] have a rate; EUR is home. Others come back as failed. */
    private fun converter(rates: Map<String, Double> = emptyMap()): CurrencyConverter {
        val converter = mockk<CurrencyConverter>()
        coEvery { converter.convertMultiple(any(), "EUR") } answers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            var total = 0.0
            val failures = mutableListOf<FailedConversion>()
            for ((amount, currency) in amounts) {
                val rate = if (currency == "EUR") 1.0 else rates[currency]
                if (rate == null) {
                    failures += FailedConversion(amount, currency, "EUR", "no rate")
                } else {
                    total += amount * rate
                }
            }
            MultiConversionAggregate(total, "EUR", failures)
        }
        return converter
    }

    private fun viewModel(repo: FakeRepo, converter: CurrencyConverter): NaturalLanguageSearchViewModel {
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns nowMs
        val settings = mockk<CurrencySettingsRepository>()
        every { settings.homeCurrency() } returns flowOf("EUR")
        val merchants = mockk<MerchantNormalizationRepository>()
        coEvery { merchants.getAliasByRawName(any()) } returns null
        coEvery { merchants.getAliasByNormalizedKey(any()) } returns null
        coEvery { merchants.getTopMerchants(any()) } returns emptyList()
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = repo,
            speechInputGateway = mockk(relaxed = true),
            timeProvider = timeProvider,
            currencyConverter = converter,
            currencySettingsRepository = settings,
            categoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = merchants
        )
        return NaturalLanguageSearchViewModel(engine, settings, converter, mockk(relaxed = true))
    }

    /** Types the query and lets the 300 ms debounce and the search finish in virtual time. */
    private fun TestScope.search(vm: NaturalLanguageSearchViewModel, text: String) {
        vm.updateQuery(text)
        advanceTimeBy(301)
        runCurrent()
    }

    // ---- idle / cleared ----

    @Test
    fun `idle view model has an unavailable total and no results`() = runTest(dispatcher) {
        val vm = viewModel(FakeRepo(), converter())

        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertEquals(SearchState.Idle, vm.searchState.value)
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `clearing the query after an available total returns to unavailable`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
        val vm = viewModel(repo, converter())
        search(vm, queryOne)
        assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)

        vm.clearQuery()

        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertEquals(SearchState.Idle, vm.searchState.value)
        assertTrue(vm.results.value.isEmpty())
        assertNull(vm.interpretation.value)
    }

    @Test
    fun `emptying the query field returns to unavailable`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
        val vm = viewModel(repo, converter())
        search(vm, queryOne)

        vm.updateQuery("")

        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertEquals(SearchState.Idle, vm.searchState.value)
    }

    // ---- unavailable versus zero ----

    @Test
    fun `EUR home with only THB rows and no rate is Unavailable and never Available zero while the row count stays 3`() =
        runTest(dispatcher) {
            val repo = FakeRepo().apply {
                rows = listOf(
                    row(1, 1000.0, 1000.0, "THB"),
                    row(2, 500.0, 500.0, "THB"),
                    row(3, 250.0, 250.0, "THB")
                )
            }
            val vm = viewModel(repo, converter(rates = emptyMap()))

            search(vm, queryOne)

            assertEquals(SearchTotal.Unavailable, vm.total.value)
            assertEquals(3, vm.results.value.size)
            assertEquals(SearchState.Results, vm.searchState.value)
        }

    @Test
    fun `single neutralised EUR row is a real zero total and not Unavailable`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 1200.0, 0.0)) }
        val vm = viewModel(repo, converter())

        search(vm, queryOne)

        assertEquals(SearchTotal.Available(0.0, 0), vm.total.value)
        assertEquals(1, vm.results.value.size)
    }

    // ---- partial and complete ----

    @Test
    fun `mixed EUR and THB with no THB rate is Available with the EUR total and the left out count`() =
        runTest(dispatcher) {
            val repo = FakeRepo().apply {
                rows = listOf(
                    row(1, 10.0, 10.0),
                    row(2, 20.0, 20.0),
                    row(3, 1000.0, 1000.0, "THB"),
                    row(4, 500.0, 500.0, "THB")
                )
            }
            val vm = viewModel(repo, converter(rates = emptyMap()))

            search(vm, queryOne)

            // EUR 10 + 20 = 30; the two THB rows are left out.
            assertEquals(SearchTotal.Available(30.0, 2), vm.total.value)
            assertEquals(4, vm.results.value.size)
        }

    @Test
    fun `all convertible rows give a complete total with nothing left out`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(row(1, 10.0, 10.0), row(2, 100.0, 100.0, "THB"))
        }
        val vm = viewModel(repo, converter(rates = mapOf("THB" to 0.5)))

        search(vm, queryOne)

        // 10 EUR + 100 THB at 0.5 = 60 EUR.
        assertEquals(SearchTotal.Available(60.0, 0), vm.total.value)
    }

    // ---- effectiveAmount drives the total (stated behaviour) ----

    @Test
    fun `neutralised row appears as a row but adds zero to the total`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(row(1, 1200.0, 0.0), row(2, 100.0, 30.0))
        }
        val vm = viewModel(repo, converter())

        search(vm, queryOne)

        assertEquals(2, vm.results.value.size)
        // Effective shares 0 + 30. A gross-based total would be 1300.
        assertEquals(SearchTotal.Available(30.0, 0), vm.total.value)
    }

    @Test
    fun `foreign buckets are summed from effective amounts before conversion`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 100.0, 30.0),
                row(2, 1000.0, 400.0, "THB"),
                row(3, 500.0, 0.0, "THB")
            )
        }
        val vm = viewModel(repo, converter(rates = mapOf("THB" to 0.5)))

        search(vm, queryOne)

        // EUR 30 + THB (400 + 0) * 0.5 = 230. A gross-based total would be 100 + 1500 * 0.5 = 850.
        assertEquals(SearchTotal.Available(230.0, 0), vm.total.value)
        assertEquals(3, vm.results.value.size)
    }

    // ---- error and cancellation ----

    @Test
    fun `search failure after an available total leaves the total Unavailable and the state Error`() =
        runTest(dispatcher) {
            val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
            val vm = viewModel(repo, converter())
            search(vm, queryOne)
            assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)

            repo.failure = IllegalStateException("synthetic")
            search(vm, queryTwo)

            assertEquals(SearchTotal.Unavailable, vm.total.value)
            assertEquals(SearchState.Error("SEARCH_FAILED"), vm.searchState.value)
        }

    @Test
    fun `cancellation is not turned into an Error state or an Unavailable total`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
        val vm = viewModel(repo, converter())
        search(vm, queryOne)

        repo.failure = CancellationException("synthetic")
        search(vm, queryTwo)

        // A swallowed CancellationException would land in the generic handler: Error + Unavailable.
        assertEquals(SearchState.Interpreting, vm.searchState.value)
        assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)
    }
}
