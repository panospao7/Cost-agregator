package com.yourname.expensetracker.ui.screens.naturallanguage

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import com.yourname.expensetracker.domain.naturallanguage.SearchCursor
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
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
            if (cursor != null) return emptyList()
            // First-page calls are numbered from 0 so a test can hold one search open and let a later one finish.
            val callIndex = firstPageCalls++
            return queryHook?.invoke(callIndex) ?: rows
        }

        var firstPageCalls = 0
        var queryHook: (suspend (callIndex: Int) -> List<NaturalLanguageExpense>)? = null
    }

    /**
     * [countsInTotal] defaults to true (an ordinary counted purchase). Deposits and flagged rows must set
     * it to false, as the repository does; [neutralised] marks a flagged (spending-excluded) row.
     */
    private fun row(
        id: Long,
        amount: Double,
        effective: Double,
        currency: String = "EUR",
        date: Long = rowDate - id,
        countsInTotal: Boolean = true,
        neutralised: Boolean = false
    ) = NaturalLanguageExpense(
        id = id,
        amount = amount,
        effectiveAmount = effective,
        currency = currency,
        merchant = "Shop",
        date = date,
        categoryId = null,
        countsInTotal = countsInTotal,
        isSpendingNeutralised = neutralised
    )

    /** One recorded call to the per-date conversion. */
    private data class AsOfCall(val amount: Double, val from: String, val to: String, val atMillis: Long)

    /**
     * Converter stub for the per-row, row-date conversion ([CurrencyConverter.convertAsOf]).
     * A rate is looked up first by (currency, atMillis) in [ratesByDate], then by currency in [rates];
     * no entry means no rate (null result). Every call is appended to [calls]. The latest-rate
     * bucket path ([CurrencyConverter.convertMultiple]) answers with a sentinel total, so any use of it
     * shows up as a wrong total instead of passing silently.
     */
    private fun converter(
        rates: Map<String, Double> = emptyMap(),
        ratesByDate: Map<Pair<String, Long>, Double> = emptyMap(),
        calls: MutableList<AsOfCall> = mutableListOf()
    ): CurrencyConverter {
        val converter = mockk<CurrencyConverter>()
        coEvery {
            converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
        } answers {
            val amount = arg<Double>(0)
            val from = arg<String>(1)
            val to = arg<String>(2)
            val at = arg<Long>(3)
            calls += AsOfCall(amount, from, to, at)
            val rate = if (from == to) 1.0 else ratesByDate[from to at] ?: rates[from]
            rate?.let { ConversionResult(amount, from, amount * it, to, it, at) }
        }
        coEvery { converter.convertMultiple(any(), any()) } returns
            MultiConversionAggregate(LATEST_SENTINEL_TOTAL, "EUR", emptyList())
        return converter
    }

    private fun viewModel(
        repo: FakeRepo,
        converter: CurrencyConverter,
        wrapEngine: (NaturalLanguageSearchEngine) -> NaturalLanguageSearchEngine = { it }
    ): NaturalLanguageSearchViewModel {
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
        return NaturalLanguageSearchViewModel(wrapEngine(engine), settings, converter, mockk(relaxed = true))
    }

    /** View model over a spied real engine so the number of interpretQuery calls can be verified. */
    private fun spiedViewModel(repo: FakeRepo): Pair<NaturalLanguageSearchViewModel, NaturalLanguageSearchEngine> {
        lateinit var spy: NaturalLanguageSearchEngine
        val vm = viewModel(repo, converter()) { real -> spyk(real).also { spy = it } }
        return vm to spy
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
    fun `foreign rows are converted from their effective amounts and not their gross amounts`() = runTest(dispatcher) {
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

    // ---- rows that do not count as spending (deposits, flagged rows) ----

    @Test
    fun `purchase 40 plus deposit 200 totals 40 and both rows stay in the results`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 40.0, 40.0),
                row(2, 200.0, 200.0, countsInTotal = false)
            )
        }
        val vm = viewModel(repo, converter())

        search(vm, queryOne)

        assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)
        assertEquals(setOf(1L, 2L), vm.results.value.map { it.expense.id }.toSet())
        assertEquals(SearchState.Results, vm.searchState.value)
    }

    @Test
    fun `flagged USD row with no rate does not make the total partial or unavailable`() = runTest(dispatcher) {
        val calls = mutableListOf<AsOfCall>()
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 40.0, 40.0),
                row(2, 300.0, 0.0, "USD", countsInTotal = false, neutralised = true),
                row(3, 20.0, 20.0, "USD", countsInTotal = false)
            )
        }
        val vm = viewModel(repo, converter(rates = emptyMap(), calls = calls))

        search(vm, queryOne)

        // The flagged USD row and the USD deposit are shown but never converted and never counted as left out.
        assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)
        assertEquals(3, vm.results.value.size)
        assertTrue("non-counted rows must not be converted", calls.isEmpty())
    }

    @Test
    fun `counted USD row with no rate next to an EUR purchase is Available with the EUR sum and one left out`() =
        runTest(dispatcher) {
            val repo = FakeRepo().apply {
                rows = listOf(
                    row(1, 40.0, 40.0),
                    row(2, 25.0, 25.0),
                    row(3, 500.0, 500.0, "USD")
                )
            }
            val vm = viewModel(repo, converter(rates = emptyMap()))

            search(vm, queryOne)

            // EUR 40 + 25 = 65; the unrated USD 500 is left out and never added raw.
            assertEquals(SearchTotal.Available(65.0, 1), vm.total.value)
            assertEquals(3, vm.results.value.size)
        }

    @Test
    fun `only unconvertible counted rows is Unavailable even beside flagged and deposit rows`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 500.0, 500.0, "USD"),
                row(2, 300.0, 0.0, "USD", countsInTotal = false, neutralised = true),
                row(3, 200.0, 200.0, countsInTotal = false)
            )
        }
        val vm = viewModel(repo, converter(rates = emptyMap()))

        search(vm, queryOne)

        // The EUR deposit must not turn the unrated counted row into a fabricated Available(0.0).
        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertEquals(3, vm.results.value.size)
    }

    @Test
    fun `results with no counted row at all give a real zero total`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 200.0, 200.0, countsInTotal = false),
                row(2, 300.0, 0.0, "USD", countsInTotal = false, neutralised = true)
            )
        }
        val vm = viewModel(repo, converter())

        search(vm, queryOne)

        assertEquals(SearchTotal.Available(0.0, 0), vm.total.value)
        assertEquals(2, vm.results.value.size)
    }

    // ---- per-row conversion at the row date ----

    @Test
    fun `each counted foreign row converts at its own date and not at the latest rate`() = runTest(dispatcher) {
        val day1 = rowDate
        val day2 = rowDate - 86_400_000L
        val calls = mutableListOf<AsOfCall>()
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 100.0, 100.0, "USD", date = day1),
                row(2, 100.0, 100.0, "USD", date = day2)
            )
        }
        val vm = viewModel(
            repo,
            converter(ratesByDate = mapOf(("USD" to day1) to 0.5, ("USD" to day2) to 0.25), calls = calls)
        )

        search(vm, queryOne)

        // 100 * 0.5 + 100 * 0.25 = 75. A single latest rate (either one) would give 100 or 50;
        // the bucket path answers with a 9999 sentinel.
        assertEquals(SearchTotal.Available(75.0, 0), vm.total.value)
        assertEquals(
            setOf(AsOfCall(100.0, "USD", "EUR", day1), AsOfCall(100.0, "USD", "EUR", day2)),
            calls.toSet()
        )
        assertEquals("one conversion per counted foreign row", 2, calls.size)
    }

    @Test
    fun `a counted foreign row with a rate only on another date is left out and not converted at a different date`() =
        runTest(dispatcher) {
            val rated = rowDate
            val unrated = rowDate - 86_400_000L
            val repo = FakeRepo().apply {
                rows = listOf(
                    row(1, 10.0, 10.0),
                    row(2, 100.0, 100.0, "USD", date = rated),
                    row(3, 100.0, 100.0, "USD", date = unrated)
                )
            }
            val vm = viewModel(repo, converter(ratesByDate = mapOf(("USD" to rated) to 0.5)))

            search(vm, queryOne)

            // EUR 10 + USD 100 * 0.5 = 60; the USD row dated without a rate is left out.
            assertEquals(SearchTotal.Available(60.0, 1), vm.total.value)
        }

    @Test
    fun `a raw foreign amount is never added to the total`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(
                row(1, 10.0, 10.0),
                row(2, 200.0, 200.0, "USD"),
                row(3, 7000.0, 7000.0, "JPY")
            )
        }
        val vm = viewModel(repo, converter(rates = mapOf("USD" to 0.5)))

        search(vm, queryOne)

        // EUR 10 + USD 200 * 0.5 = 110; JPY has no rate and is left out. Raw sums would be 210 or 7210.
        assertEquals(SearchTotal.Available(110.0, 1), vm.total.value)
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

    // ---- retry and short-query behaviour ----

    @Test
    fun `retrySearch after an Error interprets the same query again and can succeed`() = runTest(dispatcher) {
        val repo = FakeRepo().apply {
            rows = listOf(row(1, 40.0, 40.0))
            failure = IllegalStateException("synthetic")
        }
        val (vm, engine) = spiedViewModel(repo)
        search(vm, "abc")
        assertEquals(SearchState.Error("SEARCH_FAILED"), vm.searchState.value)

        repo.failure = null
        vm.retrySearch()
        runCurrent()

        coVerify(exactly = 2) { engine.interpretQuery("abc") }
        assertEquals(SearchState.Results, vm.searchState.value)
        assertEquals(SearchTotal.Available(40.0, 0), vm.total.value)
    }

    @Test
    fun `updateQuery with fewer than 3 characters after an Error returns to Idle`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { failure = IllegalStateException("synthetic") }
        val vm = viewModel(repo, converter())
        search(vm, "abc")
        assertEquals(SearchState.Error("SEARCH_FAILED"), vm.searchState.value)

        vm.updateQuery("ab")

        assertEquals(SearchState.Idle, vm.searchState.value)
        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `typing abc then ab then abc searches again for the second abc`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
        val (vm, engine) = spiedViewModel(repo)

        search(vm, "abc")
        search(vm, "ab")
        assertEquals(SearchState.Idle, vm.searchState.value)
        search(vm, "abc")

        coVerify(exactly = 2) { engine.interpretQuery("abc") }
        assertEquals(SearchState.Results, vm.searchState.value)
    }

    @Test
    fun `retrySearch with fewer than 3 characters does nothing`() = runTest(dispatcher) {
        val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
        val (vm, engine) = spiedViewModel(repo)
        vm.updateQuery("ab")

        vm.retrySearch()
        runCurrent()
        advanceTimeBy(301)
        runCurrent()

        coVerify(exactly = 0) { engine.interpretQuery(any()) }
        assertEquals(SearchState.Idle, vm.searchState.value)
    }

    @Test
    fun `R3 abc then ab then abc inside the 300 ms debounce window searches again after a finished abc search`() =
        runTest(dispatcher) {
            val repo = FakeRepo().apply { rows = listOf(row(1, 40.0, 40.0)) }
            val (vm, engine) = spiedViewModel(repo)
            search(vm, "abc")
            assertEquals(SearchState.Results, vm.searchState.value)

            vm.updateQuery("ab")
            advanceTimeBy(100)
            vm.updateQuery("abc")
            advanceTimeBy(100)
            // Still inside the debounce window of the second abc: nothing ran yet.
            coVerify(exactly = 1) { engine.interpretQuery("abc") }
            advanceTimeBy(301)
            runCurrent()

            coVerify(exactly = 2) { engine.interpretQuery("abc") }
            assertEquals(SearchState.Results, vm.searchState.value)
        }

    @Test
    fun `R3 a slow older search cannot overwrite the results of a newer query`() = runTest(dispatcher) {
        val gateOld = kotlinx.coroutines.CompletableDeferred<List<NaturalLanguageExpense>>()
        val repo = FakeRepo().apply {
            queryHook = { index -> if (index == 0) gateOld.await() else listOf(row(2, 20.0, 20.0)) }
        }
        val vm = viewModel(repo, converter())
        search(vm, queryOne)
        assertEquals(SearchState.Interpreting, vm.searchState.value)

        search(vm, queryTwo)
        assertEquals(listOf(2L), vm.results.value.map { it.expense.id })

        gateOld.complete(listOf(row(1, 999.0, 999.0)))
        runCurrent()

        assertEquals(listOf(2L), vm.results.value.map { it.expense.id })
        assertEquals(SearchTotal.Available(20.0, 0), vm.total.value)
        assertEquals(SearchState.Results, vm.searchState.value)
    }

    @Test
    fun `R3 a slow older search cannot overwrite a clear`() = runTest(dispatcher) {
        val gateOld = kotlinx.coroutines.CompletableDeferred<List<NaturalLanguageExpense>>()
        val repo = FakeRepo().apply { queryHook = { gateOld.await() } }
        val vm = viewModel(repo, converter())
        search(vm, queryOne)
        assertEquals(SearchState.Interpreting, vm.searchState.value)

        vm.clearQuery()
        gateOld.complete(listOf(row(1, 999.0, 999.0)))
        runCurrent()
        advanceTimeBy(301)
        runCurrent()

        assertEquals(SearchState.Idle, vm.searchState.value)
        assertTrue(vm.results.value.isEmpty())
        assertEquals(SearchTotal.Unavailable, vm.total.value)
        assertNull(vm.interpretation.value)
    }

    @Test
    fun `R3 a slow older search cannot overwrite a newer retry and cancellation shows no error state`() =
        runTest(dispatcher) {
            val gateOld = kotlinx.coroutines.CompletableDeferred<List<NaturalLanguageExpense>>()
            val repo = FakeRepo().apply {
                queryHook = { index -> if (index == 0) gateOld.await() else listOf(row(2, 20.0, 20.0)) }
            }
            val vm = viewModel(repo, converter())
            search(vm, "abc")
            assertEquals(SearchState.Interpreting, vm.searchState.value)

            vm.retrySearch()
            runCurrent()
            assertEquals(listOf(2L), vm.results.value.map { it.expense.id })

            // The superseded search is cancelled; failing it afterwards must not surface as an error either.
            gateOld.completeExceptionally(IllegalStateException("synthetic late failure"))
            runCurrent()

            assertEquals(listOf(2L), vm.results.value.map { it.expense.id })
            assertEquals(SearchState.Results, vm.searchState.value)
            assertEquals(SearchTotal.Available(20.0, 0), vm.total.value)
        }

    @Test
    fun `R3 cancelling an in-flight search by clearing never produces the Error state`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<List<NaturalLanguageExpense>>()
        val repo = FakeRepo().apply { queryHook = { gate.await() } }
        val vm = viewModel(repo, converter())
        search(vm, "abc")

        vm.updateQuery("ab")
        runCurrent()

        assertEquals(SearchState.Idle, vm.searchState.value)
    }

    private companion object {
        /** Total returned by the latest-rate bucket path of the converter stub; no expected total equals it. */
        const val LATEST_SENTINEL_TOTAL = 9999.0
    }
}
