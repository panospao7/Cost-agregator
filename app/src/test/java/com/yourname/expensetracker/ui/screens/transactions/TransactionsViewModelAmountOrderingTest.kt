package com.yourname.expensetracker.ui.screens.transactions

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.AmountSemantics
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.SortOrder
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.TransactionTab
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * MC 1a-3b: Transactions amount ordering on the rule owner.
 *
 * Covers AE-1 (MONTH mirror), AE-2/AE-15 (tab parity smoke), AE-6, AE-8 (VM paging),
 * AE-11 (Loading / 5 s TIMEOUT / Unavailable / retry), AE-12 (H change mid-paging), AE-16,
 * INV-9, INV-10, the count lifecycle, `CountUnavailable`, CancellationException propagation
 * and that the VM never asks the repository for the assistant-only raw amount mode.
 *
 * Time-range tabs are tested through the real `groupedTransactions` pipeline; the ALL tab
 * through a stubbed repository (the SQL itself is pinned by the repository and parity tests).
 * The repository page call runs on `Dispatchers.IO`, so waits use a call signal plus the
 * `isLoading` / `isLoadingMoreState` flags. Phases that assert Loading or the 5 s timeout never
 * suspend the test body, because `runTest` skips virtual time while the body is suspended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelAmountOrderingTest : ViewModelTestUtils() {

    private data class PagedCall(
        val limit: Int,
        val offset: Int,
        val sortOrder: SortOrder,
        val minAmount: Double?,
        val maxAmount: Double?,
        val semantics: AmountSemantics
    )

    private val eur = CurrencyCode.EUR
    private val usd = CurrencyCode.parse("USD")!!

    /**
     * A REAL repository (spied so the page and period reads stay stubbed) over a mocked DAO.
     * `countExpensesWithoutHomeValue` takes the value class [CurrencyCode], which mockk cannot
     * record a stub for, so the count is stubbed one level down on the DAO (no value class).
     */
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var currencySettings: CurrencySettingsRepository

    /** Source behind `observeReadHomeCurrency()`; emits nothing until a test emits. */
    private val homeSource = MutableSharedFlow<ReadHomeCurrencyState>(replay = 1, extraBufferCapacity = 16)

    private val pagedCalls = CopyOnWriteArrayList<PagedCall>()
    private val callStarted = Channel<PagedCall>(Channel.UNLIMITED)

    @Volatile
    private var pageProvider: suspend (PagedCall) -> List<ExpenseWithCategory> = { emptyList() }

    private val logs = CopyOnWriteArrayList<String>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message
        }
    }

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(logTree)
        expenseDao = mockk(relaxed = true)
        expenseRepository = spyk(
            ExpenseRepository(
                mockk(relaxed = true),
                mockk<AppDatabase>(relaxed = true),
                expenseDao,
                mockk<UserCorrectionDao>(relaxed = true),
                mockk<PendingReviewDao>(relaxed = true),
                mockk<MerchantCategoryRepository>(relaxed = true),
                mockk<MerchantNormalizer>(relaxed = true),
                mockk<TransferDirectionAnalytics>(relaxed = true),
                mockk<TransactionLifecycleCoordinator>(relaxed = true),
                mockk(relaxed = true)
            )
        )
        categoryRepository = mockk(relaxed = true)
        currencySettings = mockk(relaxed = true)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettings.observeReadHomeCurrency() } returns homeSource
        coEvery {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val call = PagedCall(
                limit = arg(0),
                offset = arg(1),
                minAmount = arg(9),
                maxAmount = arg(10),
                sortOrder = arg(11),
                semantics = arg(12)
            )
            pagedCalls += call
            callStarted.trySend(call)
            pageProvider(call)
        }
    }

    @After
    override fun tearDown() {
        Timber.uproot(logTree)
        super.tearDown()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newViewModel(): TransactionsViewModel {
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns 1_700_000_000_000L
        val vm = TransactionsViewModel(
            mockk(relaxed = true),
            expenseRepository,
            categoryRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            timeProvider,
            mockk(relaxed = true),
            currencySettings,
            sourceLinkQueryService = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk(relaxed = true)
        )
        // Run only what is due now: never skip virtual time (the 5 s timeout must not fire here).
        testDispatcher.scheduler.runCurrent()
        return vm
    }

    private fun available(code: CurrencyCode, firstRunDefault: Boolean = false) =
        ReadHomeCurrencyState.Available(code, firstRunDefault)

    private fun row(
        id: Long,
        currency: String,
        amount: Double,
        base: Double = 0.0,
        baseCurrency: String = "EUR",
        rate: Double = 0.0,
        date: Long = id * 1_000L
    ): ExpenseWithCategory = ExpenseWithCategory(
        expense = Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "M$id",
            transactionType = TransactionType.PURCHASE,
            date = date,
            baseAmount = base,
            baseCurrency = baseCurrency,
            exchangeRateUsed = rate
        ),
        category = null
    )

    /** Rows for the time-range tab pipeline, with or without an active filter. */
    private fun stubPeriod(rows: List<ExpenseWithCategory>) {
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(rows)
        every {
            expenseRepository.getExpensesWithCategoryFiltered(any(), any(), any(), any(), any())
        } returns flowOf(rows)
    }

    private suspend fun TransactionsViewModel.awaitIds(expected: List<Long>) {
        val grouped = groupedTransactions.first { g -> g.values.flatten().map { it.expense.id } == expected }
        assertEquals(expected, grouped.values.flatten().map { it.expense.id })
    }

    /** First page call has started; then the load has finished. */
    private suspend fun TransactionsViewModel.awaitInitialLoad(): PagedCall {
        val call = callStarted.receive()
        isLoading.first { !it }
        return call
    }

    private suspend fun TransactionsViewModel.awaitLoadMore(): PagedCall {
        val call = callStarted.receive()
        isLoadingMoreState.first { !it }
        return call
    }

    private fun pageOf(range: LongRange): List<ExpenseWithCategory> =
        range.map { row(it, "EUR", it.toDouble(), it.toDouble(), "EUR", 1.0) }

    private class RecordingProgram : SupportSQLiteProgram {
        val bound = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() { bound.clear() }
        override fun close() = Unit
    }

    /**
     * True when the count query binds [homeCode] as its home currency. The repository binds the
     * home code twice as a string; the tests here use no search text, so no other string is bound.
     */
    private fun SupportSQLiteQuery.bindsHome(homeCode: String): Boolean {
        val program = RecordingProgram()
        bindTo(program)
        return program.bound.values.any { it == homeCode }
    }

    // ------------------------------------------------------------------
    // AE-1 (MONTH mirror), AE-2, AE-15 smoke, AE-16
    // ------------------------------------------------------------------

    @Test
    fun `AE-1 MONTH sorts by the rule owner, ranks unranked rows last and counts them`() = runTest(testDispatcher) {
        val e1 = row(1, "EUR", 15.0, 15.0, "EUR", 1.0)
        val e2 = row(2, "EUR", 25.0, 25.0, "EUR", 1.0)
        val u1 = row(3, "USD", 20.0) // no rate: base = (0.0 / EUR / 0.0)
        stubPeriod(listOf(e1, e2, u1))
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.awaitIds(listOf(2, 1, 3))
        assertTrue(vm.groupedTransactions.value.containsKey("Sorted By Amount"))

        vm.setSortOrder(SortOrder.AMOUNT_ASC)
        vm.awaitIds(listOf(1, 2, 3))

        // min=15 drops the unranked row (INV-6) and the notice counts it (INV-8)
        vm.applyFilter(TransactionFilter(minAmount = 15.0))
        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.awaitIds(listOf(2, 1))
        assertEquals(
            TransactionsAmountUi.Ready(count = 1, home = eur, hidesRows = true),
            vm.amountUi.first { it == TransactionsAmountUi.Ready(1, eur, true) }
        )
        // ... and U1 still is a USD 20.00 row: the VM never alters row amounts or currency (INV-19)
        assertEquals("USD", u1.expense.currency)
        assertEquals(20.0, u1.expense.amount, 0.0)
    }

    @Test
    fun `AE-2 AE-15 MONTH membership and order for four sorts and bounds`() = runTest(testDispatcher) {
        val e1 = row(1, "EUR", 15.0, 15.0, "EUR", 1.0)
        val e2 = row(2, "EUR", 25.0, 25.0, "EUR", 1.0)
        val u1 = row(3, "USD", 20.0)
        val c1 = row(4, "USD", 20.0, 18.4, "EUR", 0.92)
        stubPeriod(listOf(e1, e2, u1, c1))
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.DATE_DESC); vm.awaitIds(listOf(4, 3, 2, 1))
        vm.setSortOrder(SortOrder.DATE_ASC); vm.awaitIds(listOf(1, 2, 3, 4))
        vm.setSortOrder(SortOrder.AMOUNT_DESC); vm.awaitIds(listOf(2, 4, 1, 3))
        vm.setSortOrder(SortOrder.AMOUNT_ASC); vm.awaitIds(listOf(1, 4, 2, 3))

        // AE-2: the converted foreign row is compared by its home value (18.40), not its raw 20.00
        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.applyFilter(TransactionFilter(minAmount = 19.0)); vm.awaitIds(listOf(2))
        vm.applyFilter(TransactionFilter(maxAmount = 19.0)); vm.awaitIds(listOf(4, 1))
        vm.applyFilter(TransactionFilter(minAmount = 0.0)); vm.awaitIds(listOf(2, 4, 1))
    }

    @Test
    fun `AE-15 ALL passes the same H, sort and bounds to the repository`() = runTest(testDispatcher) {
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.applyFilter(TransactionFilter(minAmount = 19.0))
        val call = vm.awaitInitialLoad()

        assertEquals(AmountSemantics.HomeComparable(eur), call.semantics)
        assertEquals(SortOrder.AMOUNT_DESC, call.sortOrder)
        assertEquals(19.0, call.minAmount)
        assertEquals(TransactionsViewModel.PAGE_SIZE, call.limit)
        assertEquals(0, call.offset)
    }

    @Test
    fun `AE-16 date sorts break exact ties by id and stay stable`() = runTest(testDispatcher) {
        stubPeriod(listOf(row(7, "EUR", 1.0, date = 5_000L), row(8, "EUR", 1.0, date = 5_000L), row(9, "EUR", 1.0, date = 5_000L)))
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.DATE_DESC); vm.awaitIds(listOf(9, 8, 7))
        vm.setSortOrder(SortOrder.DATE_ASC); vm.awaitIds(listOf(7, 8, 9))
    }

    // ------------------------------------------------------------------
    // AE-6 home switch
    // ------------------------------------------------------------------

    @Test
    fun `AE-6 H change re-ranks the same rows and recounts under the new H`() = runTest(testDispatcher) {
        val a = row(1, "EUR", 100.0, 100.0, "EUR", 1.0)
        val b = row(2, "USD", 30.0, 27.78, "EUR", 0.926)
        val c = row(3, "USD", 95.0, 95.0, "USD", 1.0)
        stubPeriod(listOf(a, b, c))
        homeSource.tryEmit(available(usd))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.awaitIds(listOf(3, 2, 1)) // C 95, B 30 (USD row), A has no USD value
        vm.amountUi.first { it == TransactionsAmountUi.Ready(1, usd, false) }

        vm.applyFilter(TransactionFilter(minAmount = 95.0))
        vm.awaitIds(listOf(3))

        // the user switches back to EUR: the stored sort and bound are kept
        homeSource.tryEmit(available(eur))
        testDispatcher.scheduler.runCurrent()
        vm.applyFilter(TransactionFilter())
        vm.awaitIds(listOf(1, 2, 3)) // A 100, B 27.78 (snapshot in EUR), C has no EUR value
        vm.amountUi.first { it == TransactionsAmountUi.Ready(1, eur, false) }
    }

    // ------------------------------------------------------------------
    // AE-8 VM paging
    // ------------------------------------------------------------------

    @Test
    fun `AE-8 ALL paging concatenates pages at 0, 50 and 100 under one H key`() = runTest(testDispatcher) {
        val all = pageOf(1L..105L)
        pageProvider = { call -> all.drop(call.offset).take(call.limit) }
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)

        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.loadMore(); vm.awaitLoadMore()
        vm.loadMore(); vm.awaitLoadMore()

        assertTrue(vm.hasReachedEnd.value)
        val ids = vm.transactions.first { it.size == 105 }.map { it.expense.id }
        assertEquals(all.map { it.expense.id }, ids)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf(0, 50, 100), pagedCalls.map { it.offset })
        assertTrue(pagedCalls.all { it.semantics == AmountSemantics.HomeComparable(eur) })
        assertTrue(pagedCalls.all { it.sortOrder == SortOrder.AMOUNT_DESC })
    }

    // ------------------------------------------------------------------
    // AE-11 Loading, 5 s TIMEOUT, Unavailable, retry (INV-9)
    // ------------------------------------------------------------------

    @Test
    fun `AE-11 Loading then exactly 5 s TIMEOUT disables amount ops and no placeholder reaches a query`() = runTest(testDispatcher) {
        // homeSource never emits. This phase must not suspend the test body (virtual-time skip).
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.selectTab(TransactionTab.ALL)
        vm.applyFilter(TransactionFilter(minAmount = 15.0))
        testDispatcher.scheduler.runCurrent()

        assertEquals(ReadHomeCurrencyState.Loading, vm.homeCurrencyState.value)
        assertEquals(AmountOrderingState.HOME_CURRENCY_LOADING, vm.amountOrderingState.value)
        assertEquals(TransactionsAmountUi.HomeLoading, vm.amountUi.value)

        testDispatcher.scheduler.advanceTimeBy(HOME_TIMEOUT_MS - 1)
        testDispatcher.scheduler.runCurrent()
        assertEquals(ReadHomeCurrencyState.Loading, vm.homeCurrencyState.value)

        testDispatcher.scheduler.advanceTimeBy(1)
        testDispatcher.scheduler.runCurrent()
        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.TIMEOUT), vm.homeCurrencyState.value)
        assertEquals(AmountOrderingState.HOME_CURRENCY_UNAVAILABLE, vm.amountOrderingState.value)
        assertEquals(TransactionsAmountUi.HomeUnavailable(ReadHomeFailure.TIMEOUT), vm.amountUi.value)

        // Every page requested so far used the disabled mode, with no amount arguments at all.
        // (The selectTab request is cancelled by applyFilter before it starts, so one call is made.)
        vm.awaitInitialLoad()
        assertEquals(1, pagedCalls.size)
        assertTrue(pagedCalls.all { it.semantics == AmountSemantics.AmountOpsDisabled })
        assertTrue(pagedCalls.all { it.minAmount == null && it.maxAmount == null })
        coVerify(exactly = 0) { expenseDao.countExpensesWithoutHomeValueDynamic(any()) }

        // Loading/Unavailable -> Unavailable emits no reload; a typed Available(USD) does.
        assertTrue(callStarted.tryReceive().isFailure)
        homeSource.tryEmit(available(usd))
        val reload = vm.awaitInitialLoad()
        assertEquals(AmountSemantics.HomeComparable(usd), reload.semantics)
        assertEquals(15.0, reload.minAmount)
        assertEquals(SortOrder.AMOUNT_DESC, reload.sortOrder)
        assertEquals(AmountOrderingState.AVAILABLE, vm.amountOrderingState.value)
        // the EUR placeholder never reached a query
        assertTrue(pagedCalls.none { it.semantics == AmountSemantics.HomeComparable(eur) })
    }

    @Test
    fun `INV-9 Unavailable keeps stored sort and bound, lists newest first, and retry restores AE-1`() = runTest(testDispatcher) {
        val e1 = row(1, "EUR", 15.0, 15.0, "EUR", 1.0)
        val e2 = row(2, "EUR", 25.0, 25.0, "EUR", 1.0)
        val u1 = row(3, "USD", 20.0)
        stubPeriod(listOf(e1, e2, u1))
        every { currencySettings.observeReadHomeCurrency() } returnsMany listOf(
            flowOf(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR)),
            flowOf(available(eur))
        )
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.applyFilter(TransactionFilter(minAmount = 15.0))

        // date DESC (id tiebreak), min not applied, no "Sorted By Amount" group
        vm.awaitIds(listOf(3, 2, 1))
        assertFalse(vm.groupedTransactions.value.containsKey("Sorted By Amount"))
        assertEquals(AmountOrderingState.HOME_CURRENCY_UNAVAILABLE, vm.amountOrderingState.value)
        assertEquals(TransactionsAmountUi.HomeUnavailable(ReadHomeFailure.READ_ERROR), vm.amountUi.value)
        // stored sort and bound are kept
        assertEquals(SortOrder.AMOUNT_DESC, vm.sortOrder.value)
        assertEquals(15.0, vm.filter.value?.minAmount)
        // even an explicit oldest-first choice shows newest first while paused
        vm.setSortOrder(SortOrder.DATE_ASC)
        vm.awaitIds(listOf(3, 2, 1))
        vm.setSortOrder(SortOrder.AMOUNT_DESC)

        vm.retryHomeCurrency()
        testDispatcher.scheduler.runCurrent()
        vm.awaitIds(listOf(2, 1))
        assertEquals(AmountOrderingState.AVAILABLE, vm.amountOrderingState.value)
        verify(exactly = 2) { currencySettings.observeReadHomeCurrency() }
    }

    @Test
    fun `retry is a no-op while the home currency is not Unavailable`() = runTest(testDispatcher) {
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.retryHomeCurrency()
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 1) { currencySettings.observeReadHomeCurrency() }
        assertEquals(available(eur), vm.homeCurrencyState.value)
    }

    @Test
    fun `a repeated Available with the same code does not reset ALL paging (N3)`() = runTest(testDispatcher) {
        pageProvider = { pageOf(1L..3L) }
        homeSource.tryEmit(available(eur, firstRunDefault = true))
        val vm = newViewModel()
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        assertEquals(1, pagedCalls.size)

        homeSource.tryEmit(available(eur, firstRunDefault = false)) // user picks EUR explicitly
        testDispatcher.scheduler.runCurrent()
        withContext(Dispatchers.Default) { delay(100) } // real time: a (wrong) reload would call the repository
        testDispatcher.scheduler.runCurrent()

        assertTrue(callStarted.tryReceive().isFailure)
        assertEquals(1, pagedCalls.size)
        assertEquals(available(eur, firstRunDefault = true), vm.homeCurrencyState.value)
    }

    // ------------------------------------------------------------------
    // AE-12 H change mid-paging (INV-10)
    // ------------------------------------------------------------------

    @Test
    fun `AE-12 H change resets paging, discards the late page and recounts under the new H`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val lateReturned = CompletableDeferred<Unit>()
        pageProvider = { call ->
            when {
                call.semantics == AmountSemantics.HomeComparable(eur) && call.offset == 0 -> pageOf(1L..50L)
                call.semantics == AmountSemantics.HomeComparable(eur) && call.offset == 50 -> pageOf(51L..100L)
                call.semantics == AmountSemantics.HomeComparable(eur) && call.offset == 100 -> {
                    // Not cancellable: the stale request still returns its page after the VM cancelled it.
                    withContext(NonCancellable) { gate.await() }
                    lateReturned.complete(Unit)
                    pageOf(9_001L..9_005L)
                }
                call.semantics == AmountSemantics.HomeComparable(usd) && call.offset == 0 -> pageOf(2_001L..2_003L)
                else -> emptyList()
            }
        }
        // first count (EUR) = 7, second count (USD) = 2; the Ready state is tagged with the count's H
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) } returns 7
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } returns 2
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)

        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.Ready(7, eur, false) }
        vm.loadMore(); vm.awaitLoadMore()
        vm.loadMore()
        val third = callStarted.receive() // page-3 request in flight under EUR
        assertEquals(100, third.offset)

        homeSource.tryEmit(available(usd))
        val reload = callStarted.receive()
        assertEquals(AmountSemantics.HomeComparable(usd), reload.semantics)
        assertEquals(0, reload.offset)
        vm.isLoading.first { !it }

        gate.complete(Unit)
        lateReturned.await()
        withContext(Dispatchers.Default) { delay(100) } // real time: let the late EUR result arrive
        testDispatcher.scheduler.runCurrent()

        val ids = vm.transactions.first { list -> list.map { it.expense.id } == listOf(2_001L, 2_002L, 2_003L) }
        assertEquals(listOf(2_001L, 2_002L, 2_003L), ids.map { it.expense.id })
        assertEquals(TransactionsAmountUi.Ready(2, usd, false), vm.amountUi.first { it == TransactionsAmountUi.Ready(2, usd, false) })
        assertTrue(vm.hasReachedEnd.value)
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) }
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) }
    }

    @Test
    fun `INV-10 a mutation refresh built under the old H is dropped after an H change`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val lateReturned = CompletableDeferred<Unit>()
        pageProvider = { call ->
            when {
                call.semantics == AmountSemantics.HomeComparable(eur) && call.limit == TransactionsViewModel.PAGE_SIZE ->
                    pageOf(1L..3L)
                call.semantics == AmountSemantics.HomeComparable(eur) -> {
                    // The mutation refresh (limit = loaded + 1): not cancellable, so the stale EUR
                    // result still comes back after the H change.
                    withContext(NonCancellable) { gate.await() }
                    lateReturned.complete(Unit)
                    pageOf(9_001L..9_003L)
                }
                call.semantics == AmountSemantics.HomeComparable(usd) && call.offset == 0 -> pageOf(2_001L..2_003L)
                else -> emptyList()
            }
        }
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) } returns 7
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) } returns 2
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.setSortOrder(SortOrder.AMOUNT_DESC)

        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.Ready(7, eur, false) }

        vm.updateCategory(pageOf(1L..1L).single().expense, 3L)
        val refresh = callStarted.receive() // refresh after mutation in flight under EUR
        assertEquals(AmountSemantics.HomeComparable(eur), refresh.semantics)
        assertEquals(4, refresh.limit)
        assertEquals(0, refresh.offset)

        homeSource.tryEmit(available(usd))
        val reload = callStarted.receive()
        assertEquals(AmountSemantics.HomeComparable(usd), reload.semantics)
        assertEquals(0, reload.offset)
        vm.isLoading.first { !it }

        gate.complete(Unit)
        lateReturned.await()
        withContext(Dispatchers.Default) { delay(100) } // real time: let the stale EUR refresh finish
        testDispatcher.scheduler.runCurrent()

        val ids = vm.transactions.first { it.isNotEmpty() }.map { it.expense.id }
        assertEquals(listOf(2_001L, 2_002L, 2_003L), ids) // the stale EUR refresh did not overwrite the list
        assertEquals(TransactionsAmountUi.Ready(2, usd, false), vm.amountUi.first { it == TransactionsAmountUi.Ready(2, usd, false) })
        assertTrue(vm.hasReachedEnd.value)
        // the stale refresh launched no second EUR count (it would have cancelled the USD count)
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) }
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("USD") }) }
    }

    @Test
    fun `INV-10 a late loadMore page from the old search key is dropped after search`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val lateReturned = CompletableDeferred<Unit>()
        pageProvider = { call ->
            when {
                call.offset == 50 -> {
                    // Not cancellable: the stale request still returns its page after the VM reset paging.
                    withContext(NonCancellable) { gate.await() }
                    lateReturned.complete(Unit)
                    pageOf(51L..100L)
                }
                pagedCalls.count { it.offset == 0 } == 1 -> pageOf(1L..50L) // initial load, old search key
                else -> pageOf(3_001L..3_003L) // reload under the new search key
            }
        }
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()

        vm.loadMore()
        val late = callStarted.receive() // page-2 request in flight under the old search key
        assertEquals(50, late.offset)

        vm.search("M") // matches every test merchant, so the in-memory search filter hides nothing
        testDispatcher.scheduler.advanceTimeBy(300) // past the 250 ms debounce
        testDispatcher.scheduler.runCurrent()
        val reload = callStarted.receive()
        assertEquals(0, reload.offset)
        vm.isLoading.first { !it }

        gate.complete(Unit)
        lateReturned.await()
        withContext(Dispatchers.Default) { delay(100) } // real time: let the late page arrive
        testDispatcher.scheduler.runCurrent()

        val ids = vm.transactions.first { it.isNotEmpty() }.map { it.expense.id }
        assertEquals(listOf(3_001L, 3_002L, 3_003L), ids)
        assertTrue(ids.none { it in 1L..100L }) // neither the old first page nor the late page
        assertTrue(vm.hasReachedEnd.value)
        assertFalse(vm.isLoadingMoreState.value)
        assertEquals(listOf(0, 50, 0), pagedCalls.map { it.offset })
    }

    // ------------------------------------------------------------------
    // Count lifecycle and CountUnavailable (INV-8, UX-Q-4)
    // ------------------------------------------------------------------

    @Test
    fun `count lifecycle is Inactive, CountPending, Ready and back to Inactive for a date sort`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) } coAnswers {
            gate.await()
            4
        }
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()
        assertEquals(TransactionsAmountUi.Inactive, vm.amountUi.value)

        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.CountPending }

        gate.complete(Unit)
        assertEquals(TransactionsAmountUi.Ready(4, eur, false), vm.amountUi.first { it is TransactionsAmountUi.Ready })

        vm.setSortOrder(SortOrder.DATE_DESC)
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.Inactive }
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) }
    }

    @Test
    fun `a failed count becomes CountUnavailable with a bounded log`() = runTest(testDispatcher) {
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) } throws
            IllegalStateException("secret merchant path /data/918")
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.CountUnavailable(eur, hidesRows = false) }

        vm.applyFilter(TransactionFilter(minAmount = 15.0))
        vm.awaitInitialLoad()
        vm.amountUi.first { it == TransactionsAmountUi.CountUnavailable(eur, hidesRows = true) }

        assertTrue(logs.any { it.contains("TRANSACTION_COUNT_FAILED") })
        assertTrue(logs.none { it.contains("secret") || it.contains("/data") })
    }

    // ------------------------------------------------------------------
    // CancellationException is never swallowed
    // ------------------------------------------------------------------

    @Test
    fun `cancellation in the count is rethrown and never becomes CountUnavailable`() = runTest(testDispatcher) {
        val countCalled = CompletableDeferred<Unit>()
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(match { it.bindsHome("EUR") }) } coAnswers {
            countCalled.complete(Unit)
            throw CancellationException("private SQL /data/918")
        }
        homeSource.tryEmit(available(eur))
        val vm = newViewModel()

        vm.setSortOrder(SortOrder.AMOUNT_DESC)
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()
        countCalled.await()
        withContext(Dispatchers.Default) { delay(100) }
        testDispatcher.scheduler.runCurrent()

        assertEquals(TransactionsAmountUi.CountPending, vm.amountUi.value)
        assertTrue(logs.none { it.contains("TRANSACTION_COUNT_FAILED") })
    }

    @Test
    fun `cancellation in the home currency read is rethrown and never becomes READ_ERROR`() = runTest(testDispatcher) {
        every { currencySettings.observeReadHomeCurrency() } returns flow<ReadHomeCurrencyState> {
            throw CancellationException("secret path")
        }
        val vm = newViewModel()
        testDispatcher.scheduler.advanceTimeBy(HOME_TIMEOUT_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertEquals(ReadHomeCurrencyState.Loading, vm.homeCurrencyState.value)
    }

    @Test
    fun `a failing home currency read becomes Unavailable READ_ERROR without exception text`() = runTest(testDispatcher) {
        every { currencySettings.observeReadHomeCurrency() } returns flow<ReadHomeCurrencyState> {
            throw IllegalStateException("secret path /data/918")
        }
        val vm = newViewModel()

        assertEquals(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR), vm.homeCurrencyState.value)
        assertEquals(TransactionsAmountUi.HomeUnavailable(ReadHomeFailure.READ_ERROR), vm.amountUi.value)
    }

    // ------------------------------------------------------------------
    // The VM never asks for the assistant-only raw mode
    // ------------------------------------------------------------------

    @Test
    fun `the VM never passes RawEffective across Loading, Available, paging, refresh and mutation refresh`() = runTest(testDispatcher) {
        pageProvider = { call -> if (call.limit >= 50) pageOf(1L..50L) else pageOf(1L..call.limit.toLong()) }
        val vm = newViewModel() // Loading: homeSource has not emitted
        vm.setSortOrder(SortOrder.AMOUNT_ASC)
        vm.selectTab(TransactionTab.ALL)
        vm.awaitInitialLoad()

        homeSource.tryEmit(available(usd))
        vm.awaitInitialLoad()
        vm.loadMore(); vm.awaitLoadMore()
        vm.updateCategory(pageOf(1L..1L).single().expense, 3L)
        callStarted.receive() // refresh after mutation: limit = loaded + 1
        vm.isLoading.first { !it }
        vm.refresh()
        callStarted.receive()
        vm.isLoading.first { !it }

        assertNotNull(pagedCalls.firstOrNull())
        assertTrue(pagedCalls.size >= 5)
        assertTrue(pagedCalls.none { it.semantics === AmountSemantics.RawEffective })
    }

    private companion object {
        const val HOME_TIMEOUT_MS = TransactionsViewModel.HOME_READ_TIMEOUT_MS
    }
}
