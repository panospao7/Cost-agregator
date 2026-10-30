package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * Loan-installments S11a: the installment branch of the Add expense ViewModel (INV-16, INV-27, INV-31;
 * AE-1, AE-15d, AE-22; MEC AE-28). Oracle: the loan-installments domain spec and the plan amendment
 * "S11 re-anchor after MEC B2/C" sections 3, 5 and 8. The coordinator and the dispatcher are mocks; the
 * rate preview is the REAL use case over the in-memory rate store of [AddExpenseViewModelTest]. Synthetic
 * data only. Default context: home EUR, Europe/Athens, now Thu 2025-10-16 14:30, Locale US.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExpenseInstallmentsViewModelTest : ViewModelTestUtils() {

    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val quickAddLearnedCategoryLookup = mockk<QuickAddLearnedCategoryLookup>()
    private val installmentCoordinator = mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true)
    private val installmentDispatcher = mockk<InstallmentPostCommitDispatcher>(relaxed = true)
    private val rateStore = AddExpenseRateStoreFake()
    private val ratesRepo = AddExpenseRatesRepositoryFake()
    private val privacyFlow = MutableStateFlow<PrivacySettingsLoadState>(
        PrivacySettingsLoadState.Loaded(PrivacySettings())
    )
    private val privacyRepo = mockk<PrivacySettingsRepository>()

    private lateinit var viewModel: AddExpenseViewModel
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private data class LoggedLine(val priority: Int, val message: String, val throwable: Throwable?)

    private val loggedLines = mutableListOf<LoggedLine>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedLines += LoggedLine(priority, message, t)
        }
    }

    // Captures of every declareWithNewExpense call, in call order.
    private val declareRequests = mutableListOf<CreateExpenseRequest>()
    private val declareZones = mutableListOf<ZoneId>()
    private val declareTerms = mutableListOf<InstallmentPlanTerms>()

    private val athens = ZoneId.of("Europe/Athens")
    private val planRef = InstallmentPlanRef(planId = 5L, planStatus = InstallmentPlanStatus.ACTIVE)

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(logTree)
        Locale.setDefault(Locale.US)
        every { timeProvider.now() } returns at(athens, 2025, 10, 16, 14, 30)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { expenseRepository.searchMerchants(any()) } returns emptyList()
        coEvery { quickAddLearnedCategoryLookup.lookup(any(), any()) } returns LearnedCategoryLookupResult.Absent
        every { privacyRepo.observeLoadState() } returns privacyFlow
        // The repository builder is a fake: it echoes its arguments into a real request (no rate flag).
        coEvery {
            manualExpenseRepository.buildInstallmentPurchaseRequest(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers {
            val args = invocation.args
            CreateExpenseRequest(
                merchant = args[0] as String,
                amount = args[1] as Double,
                currency = args[2] as String,
                date = args[6] as Long,
                transactionType = args[4] as TransactionType,
                source = ExpenseSource.MANUAL_ENTRY,
                categoryId = args[3] as Long?,
                notes = args[7] as String?,
                paymentMethod = args[5] as PaymentMethod,
                isManualEntry = true
            )
        }
    }

    @After
    override fun tearDown() {
        try {
            TimeZone.setDefault(originalTimeZone)
            Locale.setDefault(originalLocale)
            Timber.uproot(logTree)
        } finally {
            super.tearDown()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private val st: AddExpenseState get() = viewModel.state.value
    private val region: CurrencyRegionUi get() = st.currencyRegion

    private fun createViewModel(currencyRepo: CurrencySettingsRepository) = AddExpenseViewModel(
        manualExpenseRepository = manualExpenseRepository,
        expenseRepository = expenseRepository,
        categoryRepository = categoryRepository,
        timeProvider = timeProvider,
        currencySettingsRepository = currencyRepo,
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

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    private fun runNow() = testDispatcher.scheduler.runCurrent()

    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun epochDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

    private fun putRate(from: String, to: String, rate: Double, date: LocalDate) {
        rateStore.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 0L,
            source = "ecb",
            validDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }

    /** A fresh VM for [home] with the sheet opened in [zone] at [now]. */
    private fun openSheet(
        home: String = "EUR",
        zone: ZoneId = athens,
        now: Long = at(zone, 2025, 10, 16, 14, 30)
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Locale.setDefault(Locale.US)
        every { timeProvider.now() } returns now
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf(home)
        viewModel = createViewModel(repo)
        settle()
        viewModel.onSheetOpened()
        settle()
        loggedLines.clear()
    }

    private fun enable() = viewModel.setInstallmentsEnabled(true, enabled = true)

    private fun disable() = viewModel.setInstallmentsEnabled(false, enabled = true)

    /** Merchant and amount typed, then installments switched on (default 12 payments, equal split). */
    private fun fillOn(amount: String = "600", merchant: String = "Laptop Shop") {
        viewModel.updateMerchant(merchant)
        viewModel.updateAmount(amount)
        enable()
        settle()
    }

    /** USD selected, installments on, 600 entered, dated Wed 15 Oct with a stored 15 Oct rate: Eligible. */
    private fun openEligibleUsdInstallmentForm() {
        putRate("USD", "EUR", 0.9, oct(15))
        openSheet()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        enable()
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Laptop Shop")
        viewModel.updateAmount("600")
        settle()
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    private fun stubDeclare(result: InstallmentOpResult<InstallmentPlanRef>) {
        coEvery {
            installmentCoordinator.declareWithNewExpense(
                capture(declareRequests), capture(declareZones), capture(declareTerms)
            )
        } returns result
    }

    private fun stubAddManualExpense() {
        coEvery {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.Success(1L)
    }

    private fun verifyCoordinatorCalls(times: Int) {
        coVerify(exactly = times) {
            installmentCoordinator.declareWithNewExpense(any(), any(), any())
        }
    }

    private fun verifyNoManualExpenseSaved() {
        coVerify(exactly = 0) {
            manualExpenseRepository.addManualExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    private fun verifyNoBuilderCall() {
        coVerify(exactly = 0) {
            manualExpenseRepository.buildInstallmentPurchaseRequest(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    private fun text(resId: Int): UiText = UiText.StringResource(resId)

    // ---------------------------------------------------------------------------------------------
    // The pure sub-state and the block reason
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `state count accepts 2 to 480 and rejects 0, 1, 481 and non-numbers`() {
        assertEquals(12, AddExpenseInstallmentsState().count)
        assertEquals(2, AddExpenseInstallmentsState(countText = "2").count)
        assertEquals(480, AddExpenseInstallmentsState(countText = "480").count)
        assertEquals(12, AddExpenseInstallmentsState(countText = " 12 ").count)
        assertNull(AddExpenseInstallmentsState(countText = "1").count)
        assertNull(AddExpenseInstallmentsState(countText = "0").count)
        assertNull(AddExpenseInstallmentsState(countText = "481").count)
        assertNull(AddExpenseInstallmentsState(countText = "").count)
        assertNull(AddExpenseInstallmentsState(countText = "1x").count)
    }

    @Test
    fun `state fixedMinor parses a positive amount and rejects zero, negatives and junk`() {
        assertEquals(5000L, AddExpenseInstallmentsState(fixedText = "50.00").fixedMinor)
        assertEquals(5050L, AddExpenseInstallmentsState(fixedText = "50,5").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "0").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "0.00").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "-5").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "abc").fixedMinor)
        assertNull(AddExpenseInstallmentsState(fixedText = "1.234").fixedMinor)
    }

    @Test
    fun `state plan name follows the trimmed merchant until a name is typed and is capped at 80`() {
        assertEquals("Shop", AddExpenseInstallmentsState().effectiveName("  Shop  "))
        assertEquals("Plan", AddExpenseInstallmentsState(nameText = "  Plan ").effectiveName("Shop"))
        assertEquals(80, AddExpenseInstallmentsState(nameText = "x".repeat(200)).effectiveName("Shop").length)
        assertEquals(80, AddExpenseInstallmentsState().effectiveName("m".repeat(200)).length)
    }

    @Test
    fun `block reason maps Not mine, Shared and a non-spending type and is null otherwise`() {
        assertNull(AddExpenseState().installmentsBlockReason)
        assertEquals(InstallmentRefusalCode.NOT_MINE, AddExpenseState(isNotMine = true).installmentsBlockReason)
        assertEquals(
            InstallmentRefusalCode.SHARED_NOT_SUPPORTED,
            AddExpenseState(isSharedExpense = true).installmentsBlockReason
        )
        assertEquals(
            InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE,
            AddExpenseState(transactionType = TransactionType.TRANSFER).installmentsBlockReason
        )
        assertEquals(
            InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE,
            AddExpenseState(transactionType = TransactionType.DEPOSIT).installmentsBlockReason
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Feature gate and the switch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `gate the switch is a no-op when the feature is not enabled`() {
        openSheet()

        viewModel.setInstallmentsEnabled(true, enabled = false)
        settle()

        assertFalse(st.installments.on)
        assertNull(st.installments.planUuid)
        assertEquals("EUR", st.selectedCurrency)
    }

    @Test
    fun `gate the switch is ignored while the home currency is still loading`() {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns emptyFlow()
        viewModel = createViewModel(repo)
        settle()

        enable()
        settle()

        assertNull(st.homeCurrency)
        assertFalse(st.installments.on)
    }

    @Test
    fun `switch on creates one plan uuid per session and keeps it across off and on`() {
        openSheet()

        enable()
        settle()
        val first = st.installments.planUuid
        assertTrue(st.installments.on)
        assertNotNull(first)
        assertTrue(first!!.isNotBlank())

        disable()
        enable()
        settle()

        assertEquals(first, st.installments.planUuid)
    }

    @Test
    fun `setters are no-ops while installments are off`() {
        openSheet()

        viewModel.setInstallmentCountText("6")
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("10")
        viewModel.setInstallmentName("Plan")
        viewModel.setInstallmentFirstDue(20_000L)
        viewModel.setInstallmentMode(InstallmentFulfilmentMode.CONFIRM)

        assertEquals(AddExpenseInstallmentsState(), st.installments)
    }

    @Test
    fun `setters filter and bound their text`() {
        openSheet()
        enable()

        viewModel.setInstallmentCountText("1a2b")
        assertEquals("12", st.installments.countText)
        viewModel.setInstallmentCountText("12345")
        assertEquals("123", st.installments.countText)

        viewModel.setInstallmentFixedText("1".repeat(40))
        assertEquals(12, st.installments.fixedText.length)

        viewModel.setInstallmentName("   ")
        assertNull(st.installments.nameText)
        viewModel.setInstallmentName("n".repeat(200))
        assertEquals(80, st.installments.nameText!!.length)
    }

    // ---- block reason: the switch is disabled for Not mine, Shared and a non-spending type ----

    @Test
    fun `block reason Not mine ignores the switch and a control form turns it on`() {
        openSheet()
        viewModel.setIsNotMine(true)
        enable()
        assertFalse(st.installments.on)

        viewModel.setIsNotMine(false)
        enable()
        assertTrue(st.installments.on)
    }

    @Test
    fun `block reason Shared ignores the switch`() {
        openSheet()
        viewModel.setIsSharedExpense(true)

        enable()

        assertFalse(st.installments.on)
    }

    @Test
    fun `block reason a Transfer type ignores the switch`() {
        openSheet()
        viewModel.selectTransactionType(TransactionType.TRANSFER)

        enable()

        assertFalse(st.installments.on)
    }

    @Test
    fun `setting Not mine while on turns installments off and keeps the plan values`() {
        openSheet()
        fillOn()
        viewModel.setInstallmentCountText("6")
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("120.50")
        val uuid = st.installments.planUuid

        viewModel.setIsNotMine(true)
        settle()

        assertFalse(st.installments.on)
        assertTrue(st.isNotMine)
        assertEquals("6", st.installments.countText)
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, st.installments.split)
        assertEquals("120.50", st.installments.fixedText)
        assertEquals(uuid, st.installments.planUuid)
        assertEquals("600", st.amount)
        assertEquals("Laptop Shop", st.merchant)
    }

    @Test
    fun `setting Shared while on turns installments off and keeps the plan values`() {
        openSheet()
        fillOn()
        viewModel.setInstallmentCountText("9")

        viewModel.setIsSharedExpense(true)
        settle()

        assertFalse(st.installments.on)
        assertTrue(st.isSharedExpense)
        assertEquals("9", st.installments.countText)
        assertEquals("600", st.amount)
    }

    @Test
    fun `choosing a non-spending type while on turns installments off and keeps the plan values`() {
        openSheet()
        fillOn()
        viewModel.setInstallmentCountText("9")

        viewModel.selectTransactionType(TransactionType.TRANSFER)
        settle()

        assertFalse(st.installments.on)
        assertEquals(TransactionType.TRANSFER, st.transactionType)
        assertEquals("9", st.installments.countText)
    }

    @Test
    fun `Not mine then back to mine does not switch installments on again`() {
        openSheet()
        fillOn()
        viewModel.setIsNotMine(true)
        viewModel.setIsNotMine(false)
        settle()

        assertFalse(st.installments.on)
    }

    // ---------------------------------------------------------------------------------------------
    // AE-1 / INV-27 / INV-16: one coordinator call, plan currency == purchase currency == selected
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `AE-1 EUR 600 over 12 reaches declareWithNewExpense once and never addManualExpense`() {
        val batches = listOf(PostCommitActionBatch.empty("b1"), PostCommitActionBatch.empty("b2"))
        stubDeclare(InstallmentOpResult.Done(planRef, batches))
        openSheet()
        fillOn()
        assertEquals("EUR", st.selectedCurrency)

        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        verifyCoordinatorCalls(1)
        verifyNoManualExpenseSaved()
        val request = declareRequests.single()
        val terms = declareTerms.single()
        assertEquals("EUR", request.currency)
        assertEquals("EUR", terms.currency)
        assertEquals(600.0, request.amount, 0.0)
        assertEquals(600.0, terms.declaredTotal)
        assertNull(terms.fixedPaymentAmount)
        assertEquals(12, terms.installmentCount)
        assertEquals(InstallmentSplitMethod.EQUAL_SPLIT, terms.splitMethod)
        assertEquals(InstallmentFulfilmentMode.AUTO_RECORD, terms.fulfilmentMode)
        assertEquals(0, terms.paidBeforeTrackingCount)
        assertEquals("Laptop Shop", request.merchant)
        assertEquals("Laptop Shop", terms.displayName)
        assertEquals(athens, declareZones.single())
        assertEquals(st.installments.planUuid, terms.planUuid)
        // The post-commit batches are dispatched exactly once, by the shared dispatcher (F3).
        coVerify(exactly = 1) { installmentDispatcher.dispatch(batches) }
    }

    @Test
    fun `AE-1 default first due is purchase plus one month and declared-on is the local today`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()

        viewModel.save()
        settle()

        val terms = declareTerms.single()
        assertEquals(epochDay(2025, 11, 16), terms.firstDueEpochDay)
        assertEquals(epochDay(2025, 10, 16), terms.declaredOnEpochDay)
    }

    @Test
    fun `AE-1 a month-end purchase clamps the default first due to the last day of the next month`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet(now = at(athens, 2025, 1, 31, 12, 0))
        fillOn()

        viewModel.save()
        settle()

        assertEquals(epochDay(2025, 2, 28), declareTerms.single().firstDueEpochDay)
    }

    @Test
    fun `AE-1 a typed first due, name and mode reach the terms`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.setInstallmentFirstDue(epochDay(2025, 12, 1))
        viewModel.setInstallmentName("Laptop plan")
        viewModel.setInstallmentMode(InstallmentFulfilmentMode.CONFIRM)
        viewModel.setInstallmentCountText("6")

        viewModel.save()
        settle()

        val terms = declareTerms.single()
        assertEquals(epochDay(2025, 12, 1), terms.firstDueEpochDay)
        assertEquals("Laptop plan", terms.displayName)
        assertEquals(InstallmentFulfilmentMode.CONFIRM, terms.fulfilmentMode)
        assertEquals(6, terms.installmentCount)
        // The purchase is still named after the merchant, not the plan.
        assertEquals("Laptop Shop", declareRequests.single().merchant)
    }

    @Test
    fun `AE-1 a fixed payment split sends the fixed amount and no declared total`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("50,5")

        viewModel.save()
        settle()

        val terms = declareTerms.single()
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, terms.splitMethod)
        assertEquals(50.5, terms.fixedPaymentAmount)
        assertNull(terms.declaredTotal)
        assertEquals(600.0, declareRequests.single().amount, 0.0)
    }

    @Test
    fun `INV-16 a USD plan keeps the typed USD amount with no conversion and USD on both sides`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openEligibleUsdInstallmentForm()
        viewModel.updateAmount("20.00")
        settle()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        val request = declareRequests.single()
        val terms = declareTerms.single()
        assertEquals("USD", request.currency)
        assertEquals("USD", terms.currency)
        assertEquals(20.0, request.amount, 0.0)
        assertEquals(20.0, terms.declaredTotal)
        verifyNoManualExpenseSaved()
    }

    @Test
    fun `INV-27 the zone captured when the sheet opened decides the plan dates, not UTC and not a later device zone`() {
        val ny = ZoneId.of("America/New_York")
        val purchase = at(ny, 2025, 10, 15, 21, 30) // already 16 Oct in UTC
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet(zone = ny, now = purchase)
        fillOn()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))

        viewModel.save()
        settle()

        assertEquals(ny, declareZones.single())
        val terms = declareTerms.single()
        assertEquals(epochDay(2025, 11, 15), terms.firstDueEpochDay)
        assertEquals(epochDay(2025, 10, 15), terms.declaredOnEpochDay)
    }

    @Test
    fun `INV-27 the repository builder receives the typed purchase fields`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.selectCategory(3L)
        viewModel.updateNotes("synthetic note")

        viewModel.save()
        settle()

        coVerify(exactly = 1) {
            manualExpenseRepository.buildInstallmentPurchaseRequest(
                merchant = "Laptop Shop",
                amount = 600.0,
                currency = "EUR",
                categoryId = 3L,
                transactionType = TransactionType.PURCHASE,
                paymentMethod = PaymentMethod.CASH,
                date = at(athens, 2025, 10, 16, 14, 30),
                notes = "synthetic note"
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Local pre-checks: no coordinator call
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a bad count is refused locally without a coordinator call`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()

        for (bad in listOf("", "0", "1", "481", "999")) {
            viewModel.setInstallmentCountText(bad)
            viewModel.save()
            settle()
            assertEquals(
                "count '$bad'",
                SaveResult.Error(text(R.string.declare_installments_refused_count)),
                st.saveResult
            )
            assertFalse(st.isSaving)
        }
        verifyCoordinatorCalls(0)
        verifyNoManualExpenseSaved()
        verifyNoBuilderCall()
        assertTrue(st.installments.on)
        assertEquals("600", st.amount)
    }

    @Test
    fun `the count boundaries 2 and 480 are accepted`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()

        viewModel.setInstallmentCountText("2")
        viewModel.save()
        settle()
        viewModel.setInstallmentCountText("480")
        viewModel.save()
        settle()

        verifyCoordinatorCalls(2)
        assertEquals(listOf(2, 480), declareTerms.map { it.installmentCount })
    }

    @Test
    fun `a bad fixed payment is refused locally without a coordinator call`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)

        for (bad in listOf("", "abc", "0", "0.00", "-5", "1.234")) {
            viewModel.setInstallmentFixedText(bad)
            viewModel.save()
            settle()
            assertEquals(
                "fixed '$bad'",
                SaveResult.Error(text(R.string.installments_action_refused_amount)),
                st.saveResult
            )
        }
        verifyCoordinatorCalls(0)
        verifyNoBuilderCall()
        assertTrue(st.installments.on)
    }

    @Test
    fun `an equal split ignores a leftover invalid fixed text`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.setInstallmentFixedText("abc")

        viewModel.save()
        settle()

        verifyCoordinatorCalls(1)
        assertNull(declareTerms.single().fixedPaymentAmount)
    }

    @Test
    fun `an amount above the currency cap is refused by the amount check before any coordinator call`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn(amount = "1000000.01")

        viewModel.save()
        settle()

        assertNotNull(st.amountError)
        verifyCoordinatorCalls(0)

        viewModel.updateAmount("1000000")
        viewModel.save()
        settle()
        verifyCoordinatorCalls(1)
    }

    // ---------------------------------------------------------------------------------------------
    // AE-22 / INV-31: each refusal gives a fixed string and keeps the form
    // ---------------------------------------------------------------------------------------------

    private fun assertRefusalKeepsForm(code: InstallmentRefusalCode, expected: Int) {
        stubDeclare(InstallmentOpResult.Refused(code))
        openSheet()
        fillOn()
        viewModel.setInstallmentCountText("6")
        viewModel.setInstallmentName("Laptop plan")
        val uuid = st.installments.planUuid

        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(text(expected)), st.saveResult)
        verifyCoordinatorCalls(1)
        verifyNoManualExpenseSaved()
        coVerify(exactly = 0) { installmentDispatcher.dispatch(any()) }
        // The form is kept whole and Save is usable again.
        assertFalse(st.isSaving)
        assertFalse(st.mutation.isRunning)
        assertTrue(st.installments.on)
        assertEquals("6", st.installments.countText)
        assertEquals("Laptop plan", st.installments.nameText)
        assertEquals(uuid, st.installments.planUuid)
        assertEquals("Laptop Shop", st.merchant)
        assertEquals("600", st.amount)
        assertEquals("EUR", st.selectedCurrency)
        // A refusal that is not a rate refusal never touches the currency region.
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `AE-22 NOT_MINE gives the not-mine string and keeps the form`() =
        assertRefusalKeepsForm(InstallmentRefusalCode.NOT_MINE, R.string.declare_installments_refused_not_mine)

    @Test
    fun `AE-22 SHARED_NOT_SUPPORTED gives the shared string and keeps the form`() =
        assertRefusalKeepsForm(InstallmentRefusalCode.SHARED_NOT_SUPPORTED, R.string.declare_installments_refused_shared)

    @Test
    fun `AE-22 SPLIT_NOT_SUPPORTED gives the shared string and keeps the form`() =
        assertRefusalKeepsForm(InstallmentRefusalCode.SPLIT_NOT_SUPPORTED, R.string.declare_installments_refused_shared)

    @Test
    fun `AE-22 INVALID_COUNT from the coordinator gives the count string and keeps the form`() =
        assertRefusalKeepsForm(InstallmentRefusalCode.INVALID_COUNT, R.string.declare_installments_refused_count)

    @Test
    fun `AE-22 FIRST_DUE_BEFORE_PURCHASE gives the first-date string and keeps the form`() =
        assertRefusalKeepsForm(
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE,
            R.string.declare_installments_refused_first_date
        )

    @Test
    fun `AE-22 AMOUNT_TOO_SMALL_FOR_COUNT gives the too-small string and keeps the form`() =
        assertRefusalKeepsForm(
            InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT,
            R.string.declare_installments_refused_too_small
        )

    @Test
    fun `AE-22 UNSUPPORTED_CURRENCY backstop gives the generic refusal string and keeps the form`() =
        assertRefusalKeepsForm(
            InstallmentRefusalCode.UNSUPPORTED_CURRENCY,
            R.string.installments_action_refused_generic
        )

    @Test
    fun `AE-22 a coordinator failure gives its fixed string and keeps the form`() {
        stubDeclare(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT))
        openSheet()
        fillOn()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(text(R.string.installments_action_failed_conflict)), st.saveResult)
        assertFalse(st.isSaving)
        assertTrue(st.installments.on)
        assertEquals("600", st.amount)

        stubDeclare(InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_CREATE_FAILED))
        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(text(R.string.installments_action_failed_unexpected)), st.saveResult)
    }

    @Test
    fun `INV-31 a thrown coordinator exception shows the generic string and logs only the bounded code`() {
        coEvery {
            installmentCoordinator.declareWithNewExpense(any(), any(), any())
        } throws IllegalStateException("db path /data/user/0/secret amount 600 EUR")
        openSheet()
        fillOn()
        loggedLines.clear()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(text(R.string.add_expense_error_save_failed)), st.saveResult)
        assertFalse(st.isSaving)
        assertEquals(listOf("ADD_EXPENSE_SAVE_FAILED"), loggedLines.map { it.message })
        assertTrue(loggedLines.all { it.throwable == null })
        assertFalse(st.toString().contains("secret"))
        assertTrue(st.installments.on)
    }

    @Test
    fun `INV-31 coordinator cancellation propagates without an Error result and isSaving is cleared`() {
        coEvery {
            installmentCoordinator.declareWithNewExpense(any(), any(), any())
        } throws CancellationException("scope cancelled")
        openSheet()
        fillOn()
        loggedLines.clear()

        viewModel.save()
        settle()

        assertNull(st.saveResult)
        assertNull(st.mutation.error)
        assertFalse(st.isSaving)
        assertFalse(st.mutation.isRunning)
        assertTrue(loggedLines.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // MEC AE-28: mode-aware selection, the stash, and rate refusals
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `MEC AE-28 JPY becomes Unset when installments switch on and is restored on switch off`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        settle()
        assertEquals("JPY", st.selectedCurrency)

        enable()
        settle()

        assertNull(st.selectedCurrency)
        assertEquals(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE, st.installments.currencyUnset)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), region)
        assertTrue(st.isCurrencySaveBlocked)
        assertEquals(listOf("EUR", "USD"), st.currencyOptions)

        disable()
        settle()

        assertFalse(st.installments.on)
        assertEquals("JPY", st.selectedCurrency)
        assertNull(st.installments.currencyUnset)
        assertTrue("JPY" in st.currencyOptions)
    }

    @Test
    fun `MEC AE-28 a save with the currency Unset shows the choose-currency string and calls nothing`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        viewModel.selectCurrency("JPY")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("600")
        enable()
        settle()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Error(text(R.string.mec_save_disabled_currency)), st.saveResult)
        verifyCoordinatorCalls(0)
        verifyNoManualExpenseSaved()
        verifyNoBuilderCall()
        assertNull(st.selectedCurrency)
    }

    @Test
    fun `MEC AE-28 an explicit USD pick while on discards the parked JPY`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        enable()
        settle()

        viewModel.selectCurrency("USD")
        settle()
        assertEquals("USD", st.selectedCurrency)
        assertNull(st.installments.currencyUnset)

        disable()
        settle()

        assertEquals("USD", st.selectedCurrency)
        assertNotEquals("JPY", st.selectedCurrency)
    }

    @Test
    fun `MEC AE-28 an explicit home pick while on discards the parked JPY`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        enable()
        settle()

        viewModel.selectCurrency("EUR")
        settle()
        assertEquals("EUR", st.selectedCurrency)

        disable()
        settle()

        assertEquals("EUR", st.selectedCurrency)
    }

    @Test
    fun `MEC AE-28 a currency outside EUR and USD cannot be picked while on`() {
        openSheet()
        enable()

        viewModel.selectCurrency("GBP")
        viewModel.selectCurrency("JPY")
        settle()

        assertEquals("EUR", st.selectedCurrency)
    }

    @Test
    fun `MEC AE-28 a home outside EUR and USD starts Unset until a pick and then saves in the pick`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet(home = "GBP")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("600")
        enable()
        settle()

        assertNull(st.selectedCurrency)
        assertEquals(CurrencyUnsetReason.NONE_PROPOSED, st.installments.currencyUnset)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED), region)
        assertEquals(listOf("EUR", "USD"), st.currencyOptions)

        viewModel.save()
        settle()
        assertEquals(SaveResult.Error(text(R.string.mec_save_disabled_currency)), st.saveResult)
        verifyCoordinatorCalls(0)

        viewModel.selectCurrency("EUR")
        settle()
        assertEquals("EUR", st.selectedCurrency)
        viewModel.save()
        settle()

        verifyCoordinatorCalls(1)
        assertEquals("EUR", declareRequests.single().currency)
        assertEquals("EUR", declareTerms.single().currency)
    }

    @Test
    fun `MEC AE-28 switching off with a GBP home returns to the home currency`() {
        openSheet(home = "GBP")
        enable()
        settle()
        assertNull(st.selectedCurrency)

        disable()
        settle()

        assertEquals("GBP", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `MEC AE-16 a currency change while on keeps the amount and the fixed text and re-previews`() {
        putRate("USD", "EUR", 0.9, oct(15))
        openSheet()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.updateAmount("600")
        enable()
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("50")
        settle()
        assertEquals(CurrencyRegionUi.Home, region)

        viewModel.selectCurrency("USD")
        assertEquals(CurrencyRegionUi.Pending, region)
        settle()

        assertEquals("600", st.amount)
        assertEquals("50", st.installments.fixedText)
        assertTrue(region is CurrencyRegionUi.Eligible)
    }

    @Test
    fun `MEC AE-16 switching off re-validates the unchanged amount for the restored currency`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        viewModel.updateAmount("10.50")
        enable()
        settle()
        assertNull(st.amountError)

        disable()
        settle()

        assertEquals("10.50", st.amount)
        assertEquals(
            UiText.StringResource(R.string.mec_amount_no_decimals, listOf("JPY")),
            st.amountError
        )
    }

    private fun assertRateRefusalShowsSaveCard(
        reason: RateRefusalReason,
        variant: CurrencyBlockVariant
    ) {
        stubDeclare(InstallmentOpResult.RateRefused(reason))
        openEligibleUsdInstallmentForm()
        viewModel.setInstallmentCountText("6")
        val uuid = st.installments.planUuid

        viewModel.save()
        settle()

        assertEquals(
            CurrencyRegionUi.Blocked(variant, latestRateDate = null, origin = CurrencyBlockOrigin.SAVE),
            region
        )
        verifyCoordinatorCalls(1)
        verifyNoManualExpenseSaved()
        assertFalse(st.isSaving)
        assertFalse(st.mutation.isRunning)
        // The form is kept whole.
        assertTrue(st.installments.on)
        assertEquals("6", st.installments.countText)
        assertEquals(uuid, st.installments.planUuid)
        assertEquals("Laptop Shop", st.merchant)
        assertEquals("600", st.amount)
        assertEquals("USD", st.selectedCurrency)
        coVerify(exactly = 0) { installmentDispatcher.dispatch(any()) }
    }

    @Test
    fun `MEC AE-28 a NO_RATE refusal keeps the form and shows the SAVE card with Save blocked`() {
        assertRateRefusalShowsSaveCard(RateRefusalReason.NO_RATE, CurrencyBlockVariant.NONE)
        assertTrue(st.isCurrencySaveBlocked)
        assertNull(st.saveResult)
    }

    @Test
    fun `MEC AE-28 a STALE_RATE refusal keeps the form and shows the SAVE card with Save blocked`() {
        assertRateRefusalShowsSaveCard(RateRefusalReason.STALE_RATE, CurrencyBlockVariant.STALE)
        assertTrue(st.isCurrencySaveBlocked)
    }

    @Test
    fun `MEC AE-28 an UNSUPPORTED_CURRENCY rate refusal keeps the form and shows the SAVE card with Save blocked`() {
        assertRateRefusalShowsSaveCard(RateRefusalReason.UNSUPPORTED_CURRENCY, CurrencyBlockVariant.UNSUPPORTED)
        assertTrue(st.isCurrencySaveBlocked)
    }

    @Test
    fun `MEC AE-28 a HOME_CURRENCY_UNRESOLVED refusal keeps the retry error and does not block Save`() {
        assertRateRefusalShowsSaveCard(RateRefusalReason.HOME_CURRENCY_UNRESOLVED, CurrencyBlockVariant.HOME_UNRESOLVED)
        assertEquals(SaveResult.Error(text(R.string.mec_block_home_unresolved)), st.saveResult)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `MEC AE-28 a late slow preview never overwrites the SAVE card of an installment refusal`() {
        openEligibleUsdInstallmentForm()
        stubDeclare(InstallmentOpResult.RateRefused(RateRefusalReason.NO_RATE))
        rateStore.latencyMs = { _, _, _ -> 1_000L }

        viewModel.updateAmount("700") // starts a slow evaluation of the new figure
        viewModel.save()
        runNow() // the evaluation is suspended in the store; the refusal arrives and cancels it
        val saveCard = CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE)
        assertEquals(saveCard, region)

        advance(5_000) // the late result would be Eligible
        settle()

        assertEquals(saveCard, region)
        assertTrue(st.isCurrencySaveBlocked)
        assertFalse(st.isSaving)
        assertEquals("700", st.amount)
    }

    @Test
    fun `MEC AE-28 control without a refusal the same slow preview does apply`() {
        openEligibleUsdInstallmentForm()
        rateStore.latencyMs = { _, _, _ -> 1_000L }

        viewModel.updateAmount("700")
        runNow()
        advance(5_000)

        assertTrue(region is CurrencyRegionUi.Eligible)
        assertFalse(st.isCurrencySaveBlocked)
    }

    // ---------------------------------------------------------------------------------------------
    // AE-15d: a double submit gives one plan; planUuid is stable across a retry
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `AE-15d a double tap while the first save is in flight gives one coordinator call`() {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            installmentCoordinator.declareWithNewExpense(any(), any(), any())
        } coAnswers {
            gate.await()
            InstallmentOpResult.Done(planRef)
        }
        openSheet()
        fillOn()

        viewModel.save()
        viewModel.save() // before the first coroutine even started
        runNow()
        viewModel.save() // while the coordinator is suspended
        runNow()
        assertTrue(st.isSaving)

        gate.complete(Unit)
        settle()

        verifyCoordinatorCalls(1)
        assertEquals(SaveResult.Success, st.saveResult)
        assertFalse(st.isSaving)
    }

    @Test
    fun `AE-15d AlreadyInState from the coordinator is treated as success without a dispatch`() {
        stubDeclare(InstallmentOpResult.AlreadyInState(planRef))
        openSheet()
        fillOn()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        assertFalse(st.isSaving)
        assertNull(st.mutation.error)
        coVerify(exactly = 0) { installmentDispatcher.dispatch(any()) }
    }

    @Test
    fun `AE-15d a second submit after a committed save reuses the same plan uuid and is success`() {
        val results = listOf<InstallmentOpResult<InstallmentPlanRef>>(
            InstallmentOpResult.Done(planRef),
            InstallmentOpResult.AlreadyInState(planRef)
        )
        coEvery {
            installmentCoordinator.declareWithNewExpense(
                capture(declareRequests), capture(declareZones), capture(declareTerms)
            )
        } returnsMany results
        openSheet()
        fillOn()

        viewModel.save()
        settle()
        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        assertEquals(2, declareTerms.size)
        assertEquals(declareTerms[0].planUuid, declareTerms[1].planUuid)
    }

    @Test
    fun `AE-15d the plan uuid is stable across a retry after a refusal`() {
        val results = listOf<InstallmentOpResult<InstallmentPlanRef>>(
            InstallmentOpResult.RateRefused(RateRefusalReason.NO_RATE),
            InstallmentOpResult.Refused(InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE),
            InstallmentOpResult.Done(planRef)
        )
        coEvery {
            installmentCoordinator.declareWithNewExpense(
                capture(declareRequests), capture(declareZones), capture(declareTerms)
            )
        } returnsMany results
        openEligibleUsdInstallmentForm()
        val uuid = st.installments.planUuid
        assertNotNull(uuid)

        viewModel.save()
        settle()
        viewModel.save()
        settle()
        viewModel.setInstallmentFirstDue(epochDay(2025, 12, 1))
        viewModel.save()
        settle()

        assertEquals(3, declareTerms.size)
        assertTrue(declareTerms.all { it.planUuid == uuid })
        assertEquals(SaveResult.Success, st.saveResult)
    }

    @Test
    fun `AE-15d a new session after a reset gets a different plan uuid`() {
        openSheet()
        enable()
        val first = st.installments.planUuid
        assertNotNull(first)

        viewModel.reset()
        assertNull(st.installments.planUuid)
        enable()
        settle()

        val second = st.installments.planUuid
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `AE-15d the setters are ignored while a save is in flight`() {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            installmentCoordinator.declareWithNewExpense(any(), any(), any())
        } coAnswers {
            gate.await()
            InstallmentOpResult.Done(planRef)
        }
        openSheet()
        fillOn()
        viewModel.save()
        runNow()
        assertTrue(st.isSaving)

        viewModel.setInstallmentCountText("6")
        viewModel.setInstallmentName("Changed")
        disable()

        assertEquals("12", st.installments.countText)
        assertNull(st.installments.nameText)
        assertTrue(st.installments.on)

        gate.complete(Unit)
        settle()
    }

    // ---------------------------------------------------------------------------------------------
    // Exclusion with Repeat
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `exclusion switching installments on clears Repeat and flags the helper`() {
        openSheet()
        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertTrue(st.isRecurring)

        enable()
        settle()

        assertFalse(st.isRecurring)
        assertEquals(RecurrenceFrequency.MONTHLY, st.recurrenceFrequency)
        assertTrue(st.installments.repeatClearedByInstallments)
    }

    @Test
    fun `exclusion toggleRecurring and setRecurrenceFrequency are no-ops while on and work again after off`() {
        openSheet()
        enable()

        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertFalse(st.isRecurring)
        assertEquals(RecurrenceFrequency.MONTHLY, st.recurrenceFrequency)

        disable()
        settle()
        assertFalse(st.installments.repeatClearedByInstallments)
        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertTrue(st.isRecurring)
        assertEquals(RecurrenceFrequency.WEEKLY, st.recurrenceFrequency)
    }

    @Test
    fun `exclusion a plain save after Repeat was cleared by installments sends no recurrence`() {
        stubAddManualExpense()
        openSheet()
        viewModel.toggleRecurring()
        enable()
        disable()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        settle()

        viewModel.save()
        settle()

        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = any(),
                amount = any(),
                currency = any(),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = any(),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any(),
                recurrenceFrequency = isNull(),
                recurringNote = any(),
                entryZone = any()
            )
        }
    }

    @Test
    fun `exclusion control a plain save with Repeat on still sends the frequency`() {
        stubAddManualExpense()
        openSheet()
        viewModel.toggleRecurring()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        settle()

        viewModel.save()
        settle()

        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = any(),
                amount = any(),
                currency = any(),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = any(),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any(),
                recurrenceFrequency = eq(RecurrenceFrequency.MONTHLY),
                recurringNote = any(),
                entryZone = any()
            )
        }
    }

    @Test
    fun `regression a foreign currency keeps Repeat hidden with installments off and on`() {
        openSheet()
        viewModel.selectCurrency("USD")
        settle()

        viewModel.toggleRecurring()
        viewModel.setRecurrenceFrequency(RecurrenceFrequency.WEEKLY)
        assertFalse(st.isRecurring)
        assertEquals(RecurrenceFrequency.MONTHLY, st.recurrenceFrequency)

        enable()
        settle()
        assertEquals("USD", st.selectedCurrency)
        viewModel.toggleRecurring()
        assertFalse(st.isRecurring)
    }

    @Test
    fun `regression picking a foreign currency clears a Repeat that was on`() {
        openSheet()
        viewModel.toggleRecurring()
        assertTrue(st.isRecurring)

        viewModel.selectCurrency("USD")
        settle()

        assertFalse(st.isRecurring)
        assertTrue(st.repeatClearedByCurrency)
    }

    // ---------------------------------------------------------------------------------------------
    // Session: reset and new sheet sessions
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `session reset clears the whole installment sub-state and the parked currency`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        fillOn()
        viewModel.setInstallmentCountText("6")
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("50")
        viewModel.setInstallmentName("Plan")
        viewModel.setInstallmentMode(InstallmentFulfilmentMode.CONFIRM)
        viewModel.setInstallmentFirstDue(epochDay(2025, 12, 1))

        viewModel.reset()
        settle()

        assertEquals(AddExpenseInstallmentsState(), st.installments)
        assertEquals("EUR", st.selectedCurrency)
        assertEquals(CurrencyRegionUi.Home, region)
        assertEquals("", st.amount)

        // The parked JPY did not survive: switching on again starts on home, not Unset.
        enable()
        settle()
        assertEquals("EUR", st.selectedCurrency)
        assertNull(st.installments.currencyUnset)
    }

    @Test
    fun `session a confirmed navigate to the rates setting discards the installment draft`() {
        openSheet()
        fillOn()
        viewModel.setInstallmentCountText("6")
        viewModel.requestOpenExchangeRateSetting()
        assertEquals(CurrencyOpenSettingRequest.CONFIRM_NEEDED, st.openSettingRequest)

        viewModel.confirmOpenExchangeRateSetting()
        settle()

        assertEquals(CurrencyOpenSettingRequest.NAVIGATE, st.openSettingRequest)
        assertEquals(AddExpenseInstallmentsState(), st.installments)
        assertEquals("", st.amount)
    }

    @Test
    fun `session a USD installment draft then onSheetLeft then onSheetOpened gives a blank draft`() {
        openEligibleUsdInstallmentForm()
        viewModel.setInstallmentCountText("6")
        assertEquals("USD", st.selectedCurrency)

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals(AddExpenseInstallmentsState(), st.installments)
        assertEquals("EUR", st.selectedCurrency)
        assertEquals("", st.amount)
        assertEquals("", st.merchant)
        assertEquals(CurrencyRegionUi.Home, region)
        assertFalse(st.isCurrencySaveBlocked)
    }

    @Test
    fun `session a parked JPY draft then onSheetLeft then onSheetOpened gives a blank draft on home`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        fillOn(amount = "600")
        assertNull(st.selectedCurrency)

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals(AddExpenseInstallmentsState(), st.installments)
        assertEquals("EUR", st.selectedCurrency)
        assertEquals("", st.amount)
        assertEquals(CurrencyRegionUi.Home, region)
    }

    @Test
    fun `session an Unset draft from a GBP home then onSheetLeft then onSheetOpened gives a blank draft`() {
        openSheet(home = "GBP")
        fillOn()
        assertNull(st.selectedCurrency)

        viewModel.onSheetLeft()
        viewModel.onSheetOpened()
        settle()

        assertEquals(AddExpenseInstallmentsState(), st.installments)
        assertEquals("GBP", st.selectedCurrency)
        assertEquals("", st.amount)
    }

    @Test
    fun `session a configuration change keeps an installment draft that is on the selected currency`() {
        openSheet()
        viewModel.selectCurrency("USD")
        fillOn()
        viewModel.setInstallmentCountText("6")

        viewModel.onSheetOpened() // recomposition: the zone is already captured
        settle()

        assertTrue(st.installments.on)
        assertEquals("6", st.installments.countText)
        assertEquals("USD", st.selectedCurrency)
        assertEquals("600", st.amount)
    }

    // ---------------------------------------------------------------------------------------------
    // Regression: a plain save is untouched
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `regression a plain save calls addManualExpense with identical arguments and no coordinator interaction`() {
        stubAddManualExpense()
        openSheet()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        viewModel.selectCategory(3L)
        viewModel.updateNotes("synthetic note")
        settle()

        viewModel.save()
        settle()

        assertEquals(SaveResult.Success, st.saveResult)
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = eq("Shop"),
                amount = eq(12.5),
                currency = eq("EUR"),
                categoryId = eq(3L),
                transactionType = eq(TransactionType.PURCHASE),
                paymentMethod = eq(PaymentMethod.CASH),
                date = eq(at(athens, 2025, 10, 16, 14, 30)),
                notes = eq("synthetic note"),
                transferDirection = isNull(),
                transferAccountName = isNull(),
                isNotMine = eq(false),
                ownerName = isNull(),
                isSharedExpense = eq(false),
                sharedWithName = isNull(),
                mySharePercentage = isNull(),
                myShareAmount = isNull(),
                latitude = isNull(),
                longitude = isNull(),
                locationSource = isNull(),
                recurrenceFrequency = isNull(),
                recurringNote = isNull(),
                entryZone = eq(athens)
            )
        }
        verifyCoordinatorCalls(0)
        verifyNoBuilderCall()
        coVerify(exactly = 0) { installmentDispatcher.dispatch(any()) }
        confirmVerified(installmentCoordinator, installmentDispatcher)
    }

    @Test
    fun `regression a plain foreign save is unchanged and the coordinator is never touched`() {
        stubAddManualExpense()
        putRate("USD", "EUR", 0.9, oct(15))
        openSheet()
        viewModel.updateDate(at(athens, 2025, 10, 15))
        viewModel.selectCurrency("USD")
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("20.00")
        settle()

        viewModel.save()
        settle()

        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = eq("Shop"),
                amount = eq(20.0),
                currency = eq("USD"),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = eq(at(athens, 2025, 10, 15)),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any(),
                recurrenceFrequency = any(),
                recurringNote = any(),
                entryZone = eq(athens)
            )
        }
        verifyCoordinatorCalls(0)
        verifyNoBuilderCall()
    }

    @Test
    fun `regression a save after switching installments on and off takes the plain path`() {
        stubAddManualExpense()
        openSheet()
        viewModel.updateMerchant("Shop")
        viewModel.updateAmount("12.50")
        enable()
        viewModel.setInstallmentCountText("6")
        disable()
        settle()

        viewModel.save()
        settle()

        verifyCoordinatorCalls(0)
        verifyNoBuilderCall()
        coVerify(exactly = 1) {
            manualExpenseRepository.addManualExpense(
                merchant = eq("Shop"),
                amount = eq(12.5),
                currency = eq("EUR"),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = any(),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any(),
                recurrenceFrequency = any(),
                recurringNote = any(),
                entryZone = any()
            )
        }
        confirmVerified(installmentCoordinator, installmentDispatcher)
    }

    // ---------------------------------------------------------------------------------------------
    // S11c: installmentsDerived (entry zone, default first due, before-purchase flag, preview)
    // ---------------------------------------------------------------------------------------------

    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** 15 Oct 21:30 in New York, which is already 16 Oct in Tokyo (10:30). */
    private val nyEvening: Long get() = at(newYork, 2025, 10, 15, 21, 30)

    private val derived: AddExpenseInstallmentsDerived get() = viewModel.installmentsDerived.value

    @Test
    fun `S11c a zone captured by onSheetOpened is used by installmentsDerived with no other state change`() {
        // A VM that has not opened the sheet falls back to the device zone (Tokyo).
        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))
        every { timeProvider.now() } returns nyEvening
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        viewModel = createViewModel(repo)
        settle()
        viewModel.updateDate(nyEvening)
        settle()
        assertEquals(epochDay(2025, 11, 16), derived.defaultFirstDueEpochDay)

        // The device zone changes, then the sheet opens: the session zone is captured and the derivation follows.
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        viewModel.onSheetOpened()
        settle()

        assertEquals(nyEvening, st.date)
        assertEquals(epochDay(2025, 11, 15), derived.defaultFirstDueEpochDay)
        assertEquals(epochDay(2025, 11, 15), derived.effectiveFirstDueEpochDay)
    }

    @Test
    fun `S11c the pinned session zone beats a later device zone change`() {
        openSheet(zone = newYork, now = nyEvening)
        assertEquals(epochDay(2025, 11, 15), derived.defaultFirstDueEpochDay)

        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))
        viewModel.updateMerchant("Laptop Shop") // forces a recomputation
        settle()

        assertEquals(nyEvening, st.date)
        assertEquals(epochDay(2025, 11, 15), derived.defaultFirstDueEpochDay)
    }

    @Test
    fun `S11c after onSheetLeft the derivation falls back to the device zone`() {
        openSheet(zone = newYork, now = nyEvening)
        assertEquals(epochDay(2025, 11, 15), derived.defaultFirstDueEpochDay)

        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))
        viewModel.onSheetLeft()
        settle()

        assertEquals(epochDay(2025, 11, 16), derived.defaultFirstDueEpochDay)
    }

    @Test
    fun `S11c after reset the derivation falls back to the device zone`() {
        openSheet(zone = newYork, now = nyEvening)
        assertEquals(epochDay(2025, 11, 15), derived.defaultFirstDueEpochDay)

        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))
        viewModel.reset()
        viewModel.updateDate(nyEvening)
        settle()

        assertEquals(epochDay(2025, 11, 16), derived.defaultFirstDueEpochDay)
    }

    @Test
    fun `S11c the saved first due equals the default the form shows, with no divergence`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet(zone = newYork, now = nyEvening)
        fillOn()
        val shown = derived.defaultFirstDueEpochDay
        assertEquals(epochDay(2025, 11, 15), shown)
        // A later device zone change must not move the saved date away from the displayed one.
        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))

        viewModel.save()
        settle()

        val terms = declareTerms.single()
        assertEquals(shown, terms.firstDueEpochDay)
        assertEquals(epochDay(2025, 11, 15), terms.firstDueEpochDay)
    }

    @Test
    fun `S11c a month-end default shown by the form is the date that is saved`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet(now = at(athens, 2025, 1, 31, 12, 0))
        fillOn()
        val shown = derived.defaultFirstDueEpochDay

        viewModel.save()
        settle()

        assertEquals(epochDay(2025, 2, 28), shown)
        assertEquals(shown, declareTerms.single().firstDueEpochDay)
    }

    @Test
    fun `S11c an explicit first due is the effective date shown and the date saved`() {
        stubDeclare(InstallmentOpResult.Done(planRef))
        openSheet()
        fillOn()
        viewModel.setInstallmentFirstDue(epochDay(2025, 12, 1))
        settle()

        assertEquals(epochDay(2025, 12, 1), derived.effectiveFirstDueEpochDay)
        assertEquals(epochDay(2025, 11, 16), derived.defaultFirstDueEpochDay)

        viewModel.save()
        settle()

        assertEquals(derived.effectiveFirstDueEpochDay, declareTerms.single().firstDueEpochDay)
    }

    @Test
    fun `S11c the before-purchase flag follows an explicit first due against the purchase civil day`() {
        openSheet() // purchase Thu 16 Oct 2025 14:30 Athens
        fillOn()
        assertFalse(derived.firstDueBeforePurchase)

        viewModel.setInstallmentFirstDue(epochDay(2025, 10, 15))
        settle()
        assertTrue(derived.firstDueBeforePurchase)

        viewModel.setInstallmentFirstDue(epochDay(2025, 10, 16))
        settle()
        assertFalse(derived.firstDueBeforePurchase)
    }

    @Test
    fun `S11c the preview is Hidden while installments are off and for an Unset currency or an invalid count`() {
        openSheet()
        viewModel.updateAmount("600")
        settle()
        assertEquals(AddExpenseInstallmentPreview.Hidden, derived.preview)

        enable()
        settle()
        assertTrue(derived.preview is AddExpenseInstallmentPreview.Ready)

        viewModel.setInstallmentCountText("1")
        settle()
        assertEquals(AddExpenseInstallmentPreview.Hidden, derived.preview)
    }

    @Test
    fun `S11c the preview is Hidden when the currency is Unset`() {
        openSheet()
        viewModel.selectCurrency("JPY")
        viewModel.updateAmount("600")
        enable()
        settle()

        assertNull(st.selectedCurrency)
        assertEquals(AddExpenseInstallmentPreview.Hidden, derived.preview)
    }

    @Test
    fun `S11c the preview needs the amount while it is blank and becomes Ready once it is typed`() {
        openSheet()
        enable()
        settle()
        assertEquals(AddExpenseInstallmentPreview.NeedsAmount, derived.preview)

        viewModel.updateAmount("600")
        settle()

        val ready = derived.preview as AddExpenseInstallmentPreview.Ready
        assertEquals(5_000L, ready.regularPaymentMinor)
        assertEquals(60_000L, ready.totalMinor)
        assertEquals(0, ready.pastDueCount)
        assertNull(ready.financingCostMinor)
    }

    @Test
    fun `S11c a fixed payment below the price previews a negative financing cost`() {
        openSheet()
        fillOn()
        viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
        viewModel.setInstallmentFixedText("45")
        settle()

        val ready = derived.preview as AddExpenseInstallmentPreview.Ready
        assertEquals(-6_000L, ready.financingCostMinor)
    }

    @Test
    fun `S11c a purchase dated in the past previews the past-due count against the injected clock`() {
        // Purchase Fri 1 Aug 2025; today (injected) is Thu 16 Oct 2025. Default first due 1 Sep:
        // 1 Sep and 1 Oct are before today, 1 Nov is not.
        openSheet()
        viewModel.updateDate(at(athens, 2025, 8, 1))
        fillOn()

        assertEquals(epochDay(2025, 9, 1), derived.defaultFirstDueEpochDay)
        val ready = derived.preview as AddExpenseInstallmentPreview.Ready
        assertEquals(2, ready.pastDueCount)
    }
}
