package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * Fixture bridge shared by the entry view-model tests: the view models now collect
 * `observeReadHomeCurrency()`, so a mock that only stubs `homeCurrency()` also gets the typed read,
 * driven by the very same flow (every value is an Available, never a firstRunDefault).
 */
internal fun CurrencySettingsRepository.bridgeReadHome(): CurrencySettingsRepository {
    val source: Flow<String> = homeCurrency()
    every { observeReadHomeCurrency() } returns
        source.map { ReadHomeCurrencyState.Available(CurrencyCode(it), firstRunDefault = false) }
    return this
}

/**
 * N-01-3 / N-01-2 / J-02-2 on the manual entry view model: the typed read-time home currency
 * (failure, deadline, first run), the per-currency share-amount check, and the home-change discard
 * rules around a failed read. Real values only; home is driven through `observeReadHomeCurrency()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseViewModelHomeReadTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()
    private val installmentCoordinator = mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true)
    private val installmentDispatcher = mockk<InstallmentPostCommitDispatcher>(relaxed = true)
    private val privacyRepo = mockk<PrivacySettingsRepository>()
    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )
    private val homeFlow = MutableSharedFlow<ReadHomeCurrencyState>(replay = 1, extraBufferCapacity = 8)

    private lateinit var viewModel: AddExpenseViewModel
    private val athens = ZoneId.of("Europe/Athens")
    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    private val warnings = mutableListOf<String>()
    private val throwables = mutableListOf<Throwable>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= 5) warnings += message
            if (t != null) throwables += t
        }
    }

    /** Arguments of the last `addManualExpense` call that reached the mock. */
    private var lastSaveArgs: List<Any?> = emptyList()

    @Before
    override fun setup() {
        super.setup()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        Locale.setDefault(Locale.US)
        Timber.plant(tree)
        every { timeProvider.now() } returns
            ZonedDateTime.of(2025, 10, 16, 14, 30, 0, 0, athens).toInstant().toEpochMilli()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { privacyRepo.observeLoadState() } returns privacyFlow
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent
        coEvery { anySave() } coAnswers {
            lastSaveArgs = args.toList()
            Result.Success(1L)
        }
    }

    @After
    override fun tearDown() {
        try {
            Timber.uproot(tree)
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        } finally {
            super.tearDown()
        }
    }

    // ---- helpers ----

    private val st: AddExpenseState get() = viewModel.state.value
    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    private fun runNow() = testDispatcher.scheduler.runCurrent()
    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun avail(code: String, firstRun: Boolean = false) =
        ReadHomeCurrencyState.Available(CurrencyCode(code), firstRun)

    private suspend fun MockKMatcherScope.anySave() = manualExpenseRepository.addManualExpense(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )

    private fun newViewModel(read: Flow<ReadHomeCurrencyState>) {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.observeReadHomeCurrency() } returns read
        viewModel = AddExpenseViewModel(
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            currencySettingsRepository = repo,
            quickAddParser = QuickAddParser(),
            quickAddLearnedCategoryLookup = quickAddLearnedCategoryLookup,
            userPresentRatePreview = UserPresentRatePreview(
                RateEligibilityEvaluator(CurrencyConverter(rateStore, timeProvider, testDispatcher)),
                rateStore
            ),
            currencyRatesRepository = ratesRepo,
            privacySettingsRepository = privacyRepo,
            installmentPlanCoordinator = installmentCoordinator,
            installmentPostCommitDispatcher = installmentDispatcher
        )
    }

    /** A view model fed by the replaying [homeFlow], seeded with [initial]; settled (the home is Available). */
    private fun newViewModelWithHome(initial: String) {
        homeFlow.resetReplayCache()
        homeFlow.tryEmit(avail(initial))
        newViewModel(homeFlow)
        settle()
        viewModel.onSheetOpened()
        settle()
    }

    private fun emitHome(state: ReadHomeCurrencyState) {
        homeFlow.tryEmit(state)
        settle()
    }

    private fun readFails() = emitHome(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))

    private fun saveWithMerchant() {
        viewModel.updateMerchant("Shop")
        settle()
        viewModel.save()
        settle()
    }

    private fun assertSaveBlockedWithError() {
        coVerify(exactly = 0) { anySave() }
        assertTrue("a save error is shown", st.saveResult is SaveResult.Error)
    }

    // ---- A2 / N-01-2: a share amount is money in the selected currency ----

    private fun sharedDraft(home: String, amount: String, shareAmount: String) {
        newViewModelWithHome(home)
        viewModel.updateAmount(amount)
        viewModel.setIsSharedExpense(true)
        viewModel.updateSharedWithName("Sam")
        viewModel.updateMyShareAmount(shareAmount)
        settle()
        assertEquals(shareAmount, st.myShareAmount)
    }

    @Test
    fun `N-01-2 JPY share 750_5 on 1500 is rejected with a save error and never saved`() {
        sharedDraft("JPY", "1500", "750.5")

        saveWithMerchant()

        assertSaveBlockedWithError()
    }

    @Test
    fun `N-01-2 control JPY share 750 on 1500 saves with the whole share`() {
        sharedDraft("JPY", "1500", "750")

        saveWithMerchant()

        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains(750.0))
        assertTrue(lastSaveArgs.contains(1500.0))
        assertEquals(SaveResult.Success, st.saveResult)
    }

    @Test
    fun `N-01-2 USD share typed with three decimals is limited to cents at input and saves`() {
        newViewModelWithHome("USD")
        viewModel.updateAmount("20")
        viewModel.setIsSharedExpense(true)
        viewModel.updateSharedWithName("Sam")
        viewModel.updateMyShareAmount("10.005")
        settle()
        assertEquals("10.00", st.myShareAmount)

        saveWithMerchant()

        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains(10.0))
    }

    @Test
    fun `N-01-2 control USD share 10_5 on 20 saves`() {
        sharedDraft("USD", "20", "10.5")

        saveWithMerchant()

        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains(10.5))
    }

    @Test
    fun `N-01-2 a percentage share on JPY is not checked as an amount and saves`() {
        newViewModelWithHome("JPY")
        viewModel.updateAmount("1500")
        viewModel.setIsSharedExpense(true)
        viewModel.updateSharedWithName("Sam")
        viewModel.updateMySharePercentage("50")
        settle()

        saveWithMerchant()

        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains(50))
        assertEquals(SaveResult.Success, st.saveResult)
    }

    // ---- A3 / N-01-3: the typed read-time home currency ----

    @Test
    fun `N-01-3 a read that throws IOException does not crash, keeps home null and blocks save with a bounded log`() {
        newViewModel(flow { throw IOException("synthetic private path /data/918") })
        settle()
        viewModel.onSheetOpened()
        settle()

        assertNull(st.homeCurrency)
        viewModel.updateAmount("5")
        saveWithMerchant()
        assertSaveBlockedWithError()
        // One line per read: the first at creation, the second from the restart when the sheet opened.
        assertEquals(List(2) { "HOME_CURRENCY_READ_UNAVAILABLE reason=READ_ERROR" }, warnings)
        assertTrue("no throwable is logged", throwables.isEmpty())
    }

    @Test
    fun `N-01-3 a flow that never emits leaves home null, with no change before 5s and a TIMEOUT log at 5s`() {
        newViewModel(flow<ReadHomeCurrencyState> {
            emit(ReadHomeCurrencyState.Loading)
            awaitCancellation()
        })
        runNow()
        val baselineKey = st.previewAnnounceKey
        assertNull(st.homeCurrency)

        advance(4_999)
        assertEquals("no change before the deadline", baselineKey, st.previewAnnounceKey)
        assertNull(st.homeCurrency)
        assertTrue(warnings.isEmpty())

        advance(1)
        assertNull(st.homeCurrency)
        assertEquals(baselineKey + 1, st.previewAnnounceKey)
        assertEquals(listOf("HOME_CURRENCY_READ_UNAVAILABLE reason=TIMEOUT"), warnings)
        viewModel.updateAmount("5")
        saveWithMerchant()
        assertSaveBlockedWithError()
    }

    @Test
    fun `N-01-3 Available within the deadline cancels the timeout so no late null arrives`() {
        newViewModel(flow {
            delay(3_000)
            emit(avail("USD"))
            awaitCancellation()
        })
        runNow()
        assertNull(st.homeCurrency)

        advance(3_000)
        assertEquals("USD", st.homeCurrency)
        val keyAfterAvailable = st.previewAnnounceKey

        advance(10_000)
        assertEquals("USD", st.homeCurrency)
        assertEquals(keyAfterAvailable, st.previewAnnounceKey)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `N-01-3 an invalid stored code leaves home null and logs only INVALID_CODE`() {
        newViewModel(flowOf(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.INVALID_CODE)))
        runNow()

        assertNull(st.homeCurrency)
        assertEquals(listOf("HOME_CURRENCY_READ_UNAVAILABLE reason=INVALID_CODE"), warnings)
        viewModel.updateAmount("5")
        saveWithMerchant()
        assertSaveBlockedWithError()
    }

    @Test
    fun `N-01-3 an unset stored value is the EUR first-run home and save works`() {
        newViewModel(flowOf(avail("EUR", firstRun = true)))
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertTrue(warnings.isEmpty())
        viewModel.updateAmount("5")
        saveWithMerchant()
        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains("EUR"))
    }

    @Test
    fun `N-01-3 a CancellationException from the read propagates and is neither logged nor turned into an error`() {
        newViewModel(flow<ReadHomeCurrencyState> { throw kotlinx.coroutines.CancellationException("synthetic") })
        runNow()
        advance(10_000)

        assertNull(st.homeCurrency)
        assertTrue("cancellation is not a read error: $warnings", warnings.isEmpty())
    }

    // ---- A4 / J-02-2: home change versus a foreign pick ----

    @Test
    fun `J-02-2 home EUR with USD picked and 5 typed is cleared with the note when home becomes USD`() {
        newViewModelWithHome("EUR")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()
        assertEquals("5", st.amount)
        assertFalse(st.homeChangeClearedNote)

        emitHome(avail("USD"))

        assertEquals("", st.amount)
        assertTrue(st.homeChangeClearedNote)
        assertEquals("USD", st.homeCurrency)
    }

    @Test
    fun `J-02-2 control home EUR to GBP with USD picked keeps the draft and the pick`() {
        newViewModelWithHome("EUR")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()

        emitHome(avail("GBP"))

        assertEquals("5", st.amount)
        assertEquals("USD", st.selectedCurrency)
        assertEquals("GBP", st.homeCurrency)
        assertFalse(st.homeChangeClearedNote)
    }

    @Test
    fun `J-02-2 a home-denominated draft is still discarded on a real home change`() {
        newViewModelWithHome("EUR")
        viewModel.updateAmount("5")
        settle()

        emitHome(avail("GBP"))

        assertEquals("", st.amount)
        assertTrue(st.homeChangeClearedNote)
    }

    // ---- A4: a failed read is "home unknown", never "home changed" ----

    @Test
    fun `J-02-2 known home then a failed read keeps the draft and the pick, no note, and blocks save`() {
        newViewModelWithHome("EUR")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()

        readFails()

        assertNull(st.homeCurrency)
        assertEquals("5", st.amount)
        assertFalse(st.homeChangeClearedNote)
        saveWithMerchant()
        assertSaveBlockedWithError()
    }

    @Test
    fun `J-02-2 after a failed read the same home is unchanged, keeps the pick, shows no note and save works`() {
        newViewModelWithHome("EUR")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()
        readFails()

        emitHome(avail("EUR"))

        assertEquals("EUR", st.homeCurrency)
        assertEquals("5", st.amount)
        assertEquals("USD", st.selectedCurrency)
        assertFalse(st.homeChangeClearedNote)
        saveWithMerchant()
        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains("USD"))
        assertTrue(lastSaveArgs.contains(5.0))
    }

    @Test
    fun `J-02-2 after a failed read a different home discards a home-denominated draft with the note`() {
        newViewModelWithHome("EUR")
        viewModel.updateAmount("5")
        settle()
        readFails()
        assertEquals("5", st.amount)
        assertFalse(st.homeChangeClearedNote)

        emitHome(avail("GBP"))

        assertEquals("", st.amount)
        assertEquals("GBP", st.homeCurrency)
        assertTrue(st.homeChangeClearedNote)
        coVerify(exactly = 0) { anySave() }
    }

    @Test
    fun `J-02-2 after a failed read a foreign pick equal to the new home is cleared with the note`() {
        newViewModelWithHome("EUR")
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()
        readFails()
        assertEquals("5", st.amount)

        emitHome(avail("USD"))

        assertEquals("", st.amount)
        assertEquals("USD", st.homeCurrency)
        assertTrue(st.homeChangeClearedNote)
    }

    // ---- F-entry restart: onSheetOpened restarts a dead home collection once ----

    private val subscriptions = AtomicInteger(0)
    private val readEnds = CompletableDeferred<Unit>()

    /**
     * Subscription 1: home EUR, then (when [readEnds] completes) a throwing read, which ends the collection
     * with a READ_ERROR. Later subscriptions follow [homeFlow].
     */
    private fun endableRead(): Flow<ReadHomeCurrencyState> = flow {
        if (subscriptions.incrementAndGet() == 1) {
            emit(avail("EUR"))
            readEnds.await()
            throw IOException("synthetic private path /data/918")
        }
        emitAll(homeFlow)
    }

    /** Opens the sheet on a known EUR home with a USD pick and a typed 5; the read has not failed yet. */
    private fun openOnKnownHomeWithForeignDraft() {
        subscriptions.set(0)
        homeFlow.resetReplayCache()
        homeFlow.tryEmit(avail("EUR"))
        newViewModel(endableRead())
        settle()
        viewModel.onSheetOpened()
        settle()
        assertEquals("EUR", st.homeCurrency)
        viewModel.selectCurrency("USD")
        viewModel.updateAmount("5")
        settle()
    }

    private fun endTheRead() {
        readEnds.complete(Unit)
        settle()
        assertNull(st.homeCurrency)
        assertEquals("the ended collection holds no subscriber", 0, homeFlow.subscriptionCount.value)
    }

    @Test
    fun `N-01-3 after READ_ERROR the save is blocked and onSheetOpened restarts once, keeping the draft and pick, then save works`() {
        openOnKnownHomeWithForeignDraft()
        endTheRead()
        saveWithMerchant()
        assertSaveBlockedWithError()
        assertEquals("5", st.amount)

        viewModel.onSheetOpened()
        settle()

        assertEquals("EUR", st.homeCurrency)
        assertEquals(2, subscriptions.get())
        assertEquals(1, homeFlow.subscriptionCount.value)
        assertEquals("5", st.amount)
        assertEquals("USD", st.selectedCurrency)
        assertFalse(st.homeChangeClearedNote)
        viewModel.save()
        settle()
        coVerify(exactly = 1) { anySave() }
        assertTrue(lastSaveArgs.contains("USD"))
        assertTrue(lastSaveArgs.contains(5.0))
    }

    @Test
    fun `N-01-3 onSheetOpened does not restart the collection when home is known`() {
        openOnKnownHomeWithForeignDraft()

        viewModel.onSheetOpened()
        viewModel.onSheetOpened()
        settle()

        assertEquals(1, subscriptions.get())
        assertEquals("EUR", st.homeCurrency)
    }

    @Test
    fun `N-01-3 onSheetOpened does not restart the collection while the job is active, including after the timeout null`() {
        subscriptions.set(0)
        newViewModel(flow<ReadHomeCurrencyState> {
            subscriptions.incrementAndGet()
            emit(ReadHomeCurrencyState.Loading)
            awaitCancellation()
        })
        runNow()

        viewModel.onSheetOpened()
        viewModel.onSheetOpened()
        runNow()
        assertEquals(1, subscriptions.get())

        advance(5_000)
        assertNull(st.homeCurrency)
        viewModel.onSheetOpened()
        viewModel.onSheetOpened()
        runNow()

        assertEquals("a timeout null leaves the job active", 1, subscriptions.get())
    }

    @Test
    fun `N-01-3 repeated onSheetOpened after READ_ERROR creates exactly one collector`() {
        openOnKnownHomeWithForeignDraft()
        endTheRead()

        viewModel.onSheetOpened()
        viewModel.onSheetOpened()
        viewModel.onSheetOpened()
        settle()

        assertEquals(2, subscriptions.get())
        assertEquals(1, homeFlow.subscriptionCount.value)
        assertEquals("EUR", st.homeCurrency)

        viewModel.onSheetOpened()
        settle()
        assertEquals(2, subscriptions.get())
        assertEquals(1, homeFlow.subscriptionCount.value)
        assertEquals("USD", st.selectedCurrency)
        assertEquals("5", st.amount)
    }
}
