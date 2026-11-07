package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.transaction.RefundCandidate
import com.yourname.expensetracker.domain.transaction.RefundCandidates
import com.yourname.expensetracker.domain.transaction.RefundLikelihoodDetector
import com.yourname.expensetracker.domain.transaction.RefundLinkInfo
import com.yourname.expensetracker.domain.transaction.RefundLinkRefusal
import com.yourname.expensetracker.domain.transaction.RefundLinkResult
import com.yourname.expensetracker.domain.transaction.RefundLinkUseCase
import com.yourname.expensetracker.domain.transaction.RefundLinks
import com.yourname.expensetracker.domain.transaction.RefundReadFailure
import com.yourname.expensetracker.domain.transaction.RefundUnlinkResult
import com.yourname.expensetracker.domain.transaction.RefundWriteFailure
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Refund netting S4 (ux-flows 3 and 9): RefundLinkViewModel state machine over a mocked RefundLinkUseCase.
 * All data is synthetic. Every result of ux-flows 3.3 maps to exactly one resource-backed UiText.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefundLinkViewModelTest : ViewModelTestUtils() {

    private val useCase = mockk<RefundLinkUseCase>()
    private val expenses = mockk<ExpenseRepository>()
    private val categories = mockk<CategoryRepository>()
    private lateinit var model: RefundLinkViewModel

    private val eur = CurrencyCode.EUR
    private fun eur(amount: Double) = MoneyAmount(amount, eur)

    private val deposit = Expense(
        id = 1L, amount = 40.0, currency = "EUR", merchant = "Synthetic Refund", date = 2_000L,
        transactionType = TransactionType.DEPOSIT
    )
    private val purchase = Expense(
        id = 2L, amount = 100.0, currency = "EUR", merchant = "Synthetic Shop", date = 1_000L,
        transactionType = TransactionType.PURCHASE, categoryId = 5L
    )
    private val linkedPurchase = purchase.copy(refundNettedAmount = 40.0)
    private val linkedDeposit = deposit.copy(refundNettedAmount = 40.0)

    private fun candidate(id: Long = 2L, amount: Double = 100.0, remaining: Double = 100.0) = RefundCandidate(
        purchaseId = id, purchaseDate = 1_000L, amount = eur(amount), remaining = eur(remaining),
        exactAmountMatch = false, merchantMatch = false, dayDelta = 1
    )

    @Before override fun setup() {
        super.setup()
        model = RefundLinkViewModel(useCase, expenses, categories)
        coEvery { categories.getAll() } returns listOf(Category(id = 5L, name = "Groceries", icon = "x", color = "#000000"))
        coEvery { expenses.getExpenseById(2L) } returns purchase
        coEvery { expenses.getExpenseById(1L) } returns deposit
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(candidate()))
    }

    private fun kotlinx.coroutines.test.TestScope.collectErrors(into: MutableList<UiText>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.error.collect { into += it } }

    private fun kotlinx.coroutines.test.TestScope.collectSuccess(into: MutableList<UiText>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.successMessage.collect { into += it } }

    private fun kotlinx.coroutines.test.TestScope.collectRefresh(into: MutableList<Unit>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.refreshRequests.collect { into += it } }

    private suspend fun kotlinx.coroutines.test.TestScope.openAndSelect() {
        model.openPicker(deposit)
        advanceUntilIdle()
        model.selectCandidate((model.picker.value as RefundPickerUiState.Content).candidates.single())
    }

    // ---- picker states --------------------------------------------------------------------------

    @Test
    fun `openPicker shows Loading first then Content with live display text`() = runTest(testDispatcher) {
        model.openPicker(deposit)
        val loading = model.picker.value as RefundPickerUiState.Loading
        assertEquals(1L, loading.deposit.id)
        assertEquals(eur(40.0), loading.deposit.amount)
        advanceUntilIdle()
        val content = model.picker.value as RefundPickerUiState.Content
        val row = content.candidates.single()
        assertEquals(2L, row.purchaseId)
        assertEquals("Synthetic Shop", row.merchant)
        assertEquals("Groceries", row.categoryName)
        assertFalse(row.partlyRefunded)
        assertNull(model.confirm.value)
    }

    @Test
    fun `candidate with remaining below amount is partlyRefunded`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(candidate(remaining = 70.0)))
        model.openPicker(deposit)
        advanceUntilIdle()
        val row = (model.picker.value as RefundPickerUiState.Content).candidates.single()
        assertTrue(row.partlyRefunded)
        assertEquals(eur(70.0), row.remaining)
    }

    @Test
    fun `openPicker with no candidates gives None naming currency and window`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(emptyList())
        model.openPicker(deposit)
        advanceUntilIdle()
        val none = model.picker.value as RefundPickerUiState.None
        assertEquals("EUR", none.currencyCode)
        assertEquals(RefundLikelihoodDetector.WINDOW_DAYS, none.windowDays)
    }

    @Test
    fun `candidate read blocked gives Unavailable and never None`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Unavailable(RefundReadFailure.READ_BLOCKED)
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Unavailable)
    }

    @Test
    fun `candidate read failure gives Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Unavailable(RefundReadFailure.READ_FAILED)
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Unavailable)
    }

    @Test
    fun `an unreadable candidate row is dropped and the rest are kept in order`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(candidate(id = 9L), candidate(id = 2L)))
        coEvery { expenses.getExpenseById(9L) } throws IllegalStateException("synthetic read failure")
        model.openPicker(deposit)
        advanceUntilIdle()
        val ids = (model.picker.value as RefundPickerUiState.Content).candidates.map { it.purchaseId }
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `all candidates unreadable gives Unavailable not None`() = runTest(testDispatcher) {
        coEvery { expenses.getExpenseById(2L) } returns null
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Unavailable)
    }

    @Test
    fun `category read failure gives Unavailable`() = runTest(testDispatcher) {
        coEvery { categories.getAll() } throws IllegalStateException("synthetic category failure")
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Unavailable)
    }

    @Test
    fun `deposit with an unparsable currency does not open the picker`() = runTest(testDispatcher) {
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        model.openPicker(deposit.copy(currency = "??"))
        advanceUntilIdle()
        assertEquals(RefundPickerUiState.Idle, model.picker.value)
        assertEquals(listOf(UiText.from(R.string.refund_picker_unavailable)), errors)
        coVerify(exactly = 0) { useCase.findCandidates(any()) }
    }

    @Test
    fun `retryPicker re-reads only from Unavailable or None`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Unavailable(RefundReadFailure.READ_FAILED)
        model.openPicker(deposit)
        advanceUntilIdle()
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(candidate()))
        model.retryPicker()
        assertTrue(model.picker.value is RefundPickerUiState.Loading)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Content)
        model.retryPicker() // Content: ignored
        assertTrue(model.picker.value is RefundPickerUiState.Content)
        coVerify(exactly = 2) { useCase.findCandidates(1L) }
    }

    @Test
    fun `closePicker returns to Idle and drops the confirm`() = runTest(testDispatcher) {
        openAndSelect()
        model.closePicker()
        assertEquals(RefundPickerUiState.Idle, model.picker.value)
        assertNull(model.confirm.value)
    }

    @Test
    fun `cancellation while reading candidates is not turned into Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } throws CancellationException("cancelled")
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Loading)
    }

    @Test
    fun `unexpected candidate read exception gives Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } throws IllegalStateException("secret merchant=private /data/918")
        model.openPicker(deposit)
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.Unavailable)
    }

    // ---- select and confirm ---------------------------------------------------------------------

    @Test
    fun `selectCandidate builds the confirm preview in the deposit currency`() = runTest(testDispatcher) {
        openAndSelect()
        val confirm = model.confirm.value!!
        assertEquals(eur(40.0), confirm.applied)     // min(remaining 100, deposit 40)
        assertEquals(eur(60.0), confirm.netAfter)    // 100 - 40
        assertFalse(confirm.depositPartlyUsed)
        assertFalse(confirm.submitting)
        assertEquals(2L, confirm.candidate.purchaseId)
    }

    @Test
    fun `selectCandidate caps applied at the remaining and flags a partly used deposit`() = runTest(testDispatcher) {
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(candidate(remaining = 25.0)))
        openAndSelect()
        val confirm = model.confirm.value!!
        assertEquals(eur(25.0), confirm.applied)
        assertEquals(eur(0.0), confirm.netAfter)
        assertTrue(confirm.depositPartlyUsed)
    }

    @Test
    fun `selectCandidate is ignored unless the picker shows Content`() = runTest(testDispatcher) {
        model.selectCandidate(
            RefundCandidateRowUi(2L, "Synthetic Shop", 1_000L, null, eur(100.0), eur(100.0))
        )
        assertNull(model.confirm.value)
    }

    @Test
    fun `dismissConfirm closes the dialog when not submitting`() = runTest(testDispatcher) {
        openAndSelect()
        model.dismissConfirm()
        assertNull(model.confirm.value)
        assertTrue(model.picker.value is RefundPickerUiState.Content)
    }

    // ---- confirmLink success --------------------------------------------------------------------

    @Test
    fun `confirmLink Linked emits the success message refreshes the list and closes the picker`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Linked(linkId = 11L, applied = eur(40.0))
        val success = mutableListOf<UiText>()
        val refresh = mutableListOf<Unit>()
        val errors = mutableListOf<UiText>()
        collectSuccess(success); collectRefresh(refresh); collectErrors(errors)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()

        val message = success.single() as UiText.StringResource
        assertEquals(R.string.refund_done_format, message.resId)
        assertEquals("Synthetic Shop", message.args[0])
        assertTrue("net 60 is formatted into the sentence", (message.args[1] as String).contains("60"))
        assertEquals(1, refresh.size)
        assertEquals(RefundPickerUiState.Idle, model.picker.value)
        assertNull(model.confirm.value)
        assertTrue(errors.isEmpty())
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `confirmLink AlreadyLinked is a success with the already applied message`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.AlreadyLinked(linkId = 11L)
        val success = mutableListOf<UiText>()
        val refresh = mutableListOf<Unit>()
        collectSuccess(success); collectRefresh(refresh)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.refund_already_applied)), success)
        assertEquals(1, refresh.size)
        assertEquals(RefundPickerUiState.Idle, model.picker.value)
    }

    @Test
    fun `confirmLink Linked with an uncomputable net shows the plain success message without a net figure`() = runTest(testDispatcher) {
        val xxx = CurrencyCode.parse("XXX")!!
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Linked(linkId = 11L, applied = MoneyAmount(40.0, xxx))
        val success = mutableListOf<UiText>()
        collectSuccess(success)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.refund_done_plain_format, "Synthetic Shop")), success)
    }

    @Test
    fun `a refresh request raised while nobody collects is delivered once to the next collector`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.AlreadyLinked(linkId = 11L)
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        val refresh = mutableListOf<Unit>()
        collectRefresh(refresh)
        runCurrent()
        assertEquals(1, refresh.size)
    }

    @Test
    fun `selectCandidate in a currency without digits opens no confirm dialog and never previews zero`() = runTest(testDispatcher) {
        val xxx = CurrencyCode.parse("XXX")!!
        val xxxDeposit = deposit.copy(currency = "XXX")
        val xxxCandidate = RefundCandidate(
            purchaseId = 2L, purchaseDate = 1_000L, amount = MoneyAmount(100.0, xxx), remaining = MoneyAmount(100.0, xxx),
            exactAmountMatch = false, merchantMatch = false, dayDelta = 1
        )
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(xxxCandidate))
        val success = mutableListOf<UiText>()
        collectSuccess(success)
        runCurrent()
        model.openPicker(xxxDeposit)
        advanceUntilIdle()
        model.selectCandidate((model.picker.value as RefundPickerUiState.Content).candidates.single())
        assertNull(model.confirm.value)
        coVerify(exactly = 0) { useCase.link(any(), any()) }
        assertTrue(success.isEmpty())
    }

    @Test
    fun `selectCandidate with an unavailable currency scale shows the stale message and reloads the picker`() = runTest(testDispatcher) {
        val xxx = CurrencyCode.parse("XXX")!!
        val xxxDeposit = deposit.copy(currency = "XXX")
        val xxxCandidate = RefundCandidate(
            purchaseId = 2L, purchaseDate = 1_000L, amount = MoneyAmount(100.0, xxx), remaining = MoneyAmount(100.0, xxx),
            exactAmountMatch = false, merchantMatch = false, dayDelta = 1
        )
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(listOf(xxxCandidate))
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        model.openPicker(xxxDeposit)
        advanceUntilIdle()
        model.selectCandidate((model.picker.value as RefundPickerUiState.Content).candidates.single())
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.refund_refused_stale)), errors)
        assertNull(model.confirm.value)
        coVerify(exactly = 2) { useCase.findCandidates(1L) }
    }

    // ---- confirmLink refusals and failures ---------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.linkRefused(code: RefundLinkRefusal): Triple<List<UiText>, List<Unit>, List<UiText>> {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Refused(code)
        val errors = mutableListOf<UiText>()
        val refresh = mutableListOf<Unit>()
        val success = mutableListOf<UiText>()
        collectErrors(errors); collectRefresh(refresh); collectSuccess(success)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        return Triple(errors, refresh, success)
    }

    @Test
    fun `each refusal shows exactly its documented message and no success`() = runTest(testDispatcher) {
        val expected = mapOf(
            RefundLinkRefusal.NOTHING_TO_APPLY to R.string.refund_refused_nothing_to_apply,
            RefundLinkRefusal.REFUND_LINKED_ELSEWHERE to R.string.refund_refused_linked_elsewhere,
            RefundLinkRefusal.REFUND_BEFORE_PURCHASE to R.string.refund_refused_before_purchase,
            RefundLinkRefusal.CURRENCY_MISMATCH to R.string.refund_refused_currency,
            RefundLinkRefusal.INSTALLMENT_MANAGED to R.string.refund_refused_installment,
            RefundLinkRefusal.GROUP_LINKED to R.string.refund_refused_group,
            RefundLinkRefusal.REFUND_NOT_FOUND to R.string.refund_refused_stale,
            RefundLinkRefusal.PURCHASE_NOT_FOUND to R.string.refund_refused_stale,
            RefundLinkRefusal.SAME_ROW to R.string.refund_refused_stale,
            RefundLinkRefusal.NOT_A_DEPOSIT to R.string.refund_refused_stale,
            RefundLinkRefusal.NOT_A_PURCHASE to R.string.refund_refused_stale,
            RefundLinkRefusal.INVALID_AMOUNT to R.string.refund_refused_stale,
            RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE to R.string.refund_refused_stale
        )
        for ((code, res) in expected) {
            val vm = RefundLinkViewModel(useCase, expenses, categories)
            coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Refused(code)
            val errors = mutableListOf<UiText>()
            val success = mutableListOf<UiText>()
            val job1 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect { errors += it } }
            val job2 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.successMessage.collect { success += it } }
            runCurrent()
            vm.openPicker(deposit)
            advanceUntilIdle()
            vm.selectCandidate((vm.picker.value as RefundPickerUiState.Content).candidates.single())
            vm.confirmLink()
            advanceUntilIdle()
            assertEquals("$code", listOf(UiText.from(res)), errors)
            assertTrue("$code shows no success", success.isEmpty())
            assertTrue("$code never leaves a dynamic string", errors.none { it is UiText.DynamicString })
            assertNull("$code closes the dialog", vm.confirm.value)
            assertTrue("$code releases the row ids", vm.mutatingExpenseIds.value.isEmpty())
            job1.cancel(); job2.cancel()
        }
    }

    @Test
    fun `NOTHING_TO_APPLY closes the dialog and reloads the candidates`() = runTest(testDispatcher) {
        val (_, refresh, _) = linkRefused(RefundLinkRefusal.NOTHING_TO_APPLY)
        assertNull(model.confirm.value)
        assertTrue(model.picker.value is RefundPickerUiState.Content)
        coVerify(exactly = 2) { useCase.findCandidates(1L) }
        assertTrue("no list refresh for a plain reload", refresh.isEmpty())
    }

    @Test
    fun `a stale refusal reloads the picker and refreshes the list`() = runTest(testDispatcher) {
        val (_, refresh, _) = linkRefused(RefundLinkRefusal.PURCHASE_NOT_FOUND)
        assertNull(model.confirm.value)
        assertTrue(model.picker.value is RefundPickerUiState.Content)
        coVerify(exactly = 2) { useCase.findCandidates(1L) }
        assertEquals(1, refresh.size)
    }

    @Test
    fun `a stale refusal whose reload finds nothing shows None not a stale list`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Refused(RefundLinkRefusal.REFUND_NOT_FOUND)
        openAndSelect()
        coEvery { useCase.findCandidates(1L) } returns RefundCandidates.Available(emptyList())
        model.confirmLink()
        advanceUntilIdle()
        assertTrue(model.picker.value is RefundPickerUiState.None)
    }

    @Test
    fun `REFUND_LINKED_ELSEWHERE closes dialog and sheet and refreshes the list`() = runTest(testDispatcher) {
        val (_, refresh, _) = linkRefused(RefundLinkRefusal.REFUND_LINKED_ELSEWHERE)
        assertNull(model.confirm.value)
        assertEquals(RefundPickerUiState.Idle, model.picker.value)
        assertEquals(1, refresh.size)
        coVerify(exactly = 1) { useCase.findCandidates(1L) }
    }

    @Test
    fun `Failed WRITE_BLOCKED keeps the dialog open re-enables it and shows the maintenance message`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED)
        val errors = mutableListOf<UiText>()
        val refresh = mutableListOf<Unit>()
        collectErrors(errors); collectRefresh(refresh)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.ui_write_blocked_maintenance)), errors)
        val confirm = model.confirm.value!!
        assertFalse(confirm.submitting)
        assertTrue(model.picker.value is RefundPickerUiState.Content)
        assertTrue(refresh.isEmpty())
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `Failed INTERNAL keeps the dialog open and shows the generic message`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } returns RefundLinkResult.Failed(RefundWriteFailure.INTERNAL)
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.recurring_error_generic)), errors)
        assertFalse(model.confirm.value!!.submitting)
    }

    @Test
    fun `an unexpected link exception shows the generic message and never its text`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } throws IllegalStateException("secret merchant=private sql=/data/918")
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.recurring_error_generic)), errors)
        assertFalse(model.confirm.value!!.submitting)
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `cancellation of a link is not turned into an error and releases the row ids`() = runTest(testDispatcher) {
        coEvery { useCase.link(1L, 2L) } throws CancellationException("cancelled")
        val errors = mutableListOf<UiText>()
        val success = mutableListOf<UiText>()
        collectErrors(errors); collectSuccess(success)
        runCurrent()
        openAndSelect()
        model.confirmLink()
        advanceUntilIdle()
        assertTrue(errors.isEmpty())
        assertTrue(success.isEmpty())
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    // ---- in-flight guard ------------------------------------------------------------------------

    @Test
    fun `double tap on Apply writes once and marks both rows as mutating until done`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<RefundLinkResult>()
        coEvery { useCase.link(1L, 2L) } coAnswers { gate.await() }
        openAndSelect()
        model.confirmLink()
        model.confirmLink() // second tap while submitting
        runCurrent()
        assertTrue(model.confirm.value!!.submitting)
        assertEquals(setOf(1L, 2L), model.mutatingExpenseIds.value)
        model.dismissConfirm() // ignored while submitting
        assertTrue(model.confirm.value!!.submitting)

        gate.complete(RefundLinkResult.Linked(11L, eur(40.0)))
        advanceUntilIdle()
        coVerify(exactly = 1) { useCase.link(1L, 2L) }
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `confirmLink is ignored when no confirm dialog is open`() = runTest(testDispatcher) {
        model.confirmLink()
        advanceUntilIdle()
        coVerify(exactly = 0) { useCase.link(any(), any()) }
    }

    // ---- details --------------------------------------------------------------------------------

    private fun linksFor(subjectId: Long, counterpartId: Long, vararg applied: Double) = RefundLinks.Available(
        subjectId,
        applied.mapIndexed { index, amount ->
            RefundLinkInfo(linkId = 70L + index, counterpartId = counterpartId + index, appliedAmount = eur(amount), createdAt = 5L)
        }
    )

    @Test
    fun `openDetails shows Loading with row-derived numbers then Content`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.openDetails(linkedPurchase)
        val loading = model.details.value as RefundDetailsUiState.Loading
        assertEquals(eur(100.0), loading.subject.amount)
        assertEquals(eur(40.0), loading.subject.refunded)
        assertFalse(loading.subject.isDeposit)
        advanceUntilIdle()
        val content = model.details.value as RefundDetailsUiState.Content
        val row = content.links.single()
        assertEquals(1L, row.counterpartId)
        assertEquals("Synthetic Refund", row.merchant)
        assertEquals(eur(40.0), row.applied)
        assertEquals(eur(40.0), row.counterpartAmount)
    }

    @Test
    fun `details link read blocked gives Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns RefundLinks.Unavailable(RefundReadFailure.READ_BLOCKED)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        assertTrue(model.details.value is RefundDetailsUiState.Unavailable)
    }

    @Test
    fun `an unreadable counterpart makes the whole details list Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        coEvery { expenses.getExpenseById(1L) } returns null
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        assertTrue(model.details.value is RefundDetailsUiState.Unavailable)
    }

    @Test
    fun `details with no links closes silently and requests a refresh`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns RefundLinks.Available(2L, emptyList())
        val refresh = mutableListOf<Unit>()
        val errors = mutableListOf<UiText>()
        collectRefresh(refresh); collectErrors(errors)
        runCurrent()
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        assertEquals(RefundDetailsUiState.Idle, model.details.value)
        assertEquals(1, refresh.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `retryDetails re-reads from Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns RefundLinks.Unavailable(RefundReadFailure.READ_FAILED)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.retryDetails()
        assertTrue(model.details.value is RefundDetailsUiState.Loading)
        advanceUntilIdle()
        assertTrue(model.details.value is RefundDetailsUiState.Content)
    }

    @Test
    fun `cancellation while reading links is not turned into Unavailable`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } throws CancellationException("cancelled")
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        assertTrue(model.details.value is RefundDetailsUiState.Loading)
    }

    @Test
    fun `openDetails with an unparsable currency shows the unavailable message and no sheet`() = runTest(testDispatcher) {
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        model.openDetails(linkedPurchase.copy(currency = "??"))
        advanceUntilIdle()
        assertEquals(RefundDetailsUiState.Idle, model.details.value)
        assertEquals(listOf(UiText.from(R.string.refund_details_unavailable)), errors)
    }

    @Test
    fun `closeDetails returns to Idle and drops the unlink confirm`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        model.requestUnlink((model.details.value as RefundDetailsUiState.Content).links.single())
        model.closeDetails()
        assertEquals(RefundDetailsUiState.Idle, model.details.value)
        assertNull(model.unlinkConfirm.value)
    }

    // ---- requestUnlink wording (Q7) ---------------------------------------------------------------

    @Test
    fun `requestUnlink on the last link uses the gross and the go-back wording`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        model.requestUnlink((model.details.value as RefundDetailsUiState.Content).links.single())
        val unlink = model.unlinkConfirm.value!!
        assertTrue(unlink.lastLink)
        assertEquals(eur(100.0), unlink.shownAmount)
        assertEquals("Synthetic Shop", unlink.purchaseMerchant)
        assertEquals(1L, unlink.refundDepositId)
        assertFalse(unlink.submitting)
    }

    @Test
    fun `requestUnlink with other links remaining shows the remaining net`() = runTest(testDispatcher) {
        val twiceRefunded = purchase.copy(refundNettedAmount = 70.0)
        val secondDeposit = deposit.copy(id = 3L, amount = 30.0, refundNettedAmount = 30.0)
        coEvery { expenses.getExpenseById(3L) } returns secondDeposit
        coEvery { useCase.linksOf(2L) } returns RefundLinks.Available(
            2L,
            listOf(
                RefundLinkInfo(70L, 1L, eur(40.0), 5L),
                RefundLinkInfo(71L, 3L, eur(30.0), 6L)
            )
        )
        model.openDetails(twiceRefunded)
        advanceUntilIdle()
        val rows = (model.details.value as RefundDetailsUiState.Content).links
        model.requestUnlink(rows.first { it.counterpartId == 1L })
        val unlink = model.unlinkConfirm.value!!
        assertFalse(unlink.lastLink)
        assertEquals(eur(70.0), unlink.shownAmount) // 100 - (70 - 40) = 70, not the gross
        assertEquals(1L, unlink.refundDepositId)
    }

    @Test
    fun `requestUnlink on a purchase sheet uses the live links even when the list row refunded is stale`() = runTest(testDispatcher) {
        val secondDeposit = deposit.copy(id = 3L, amount = 30.0, refundNettedAmount = 30.0)
        coEvery { expenses.getExpenseById(3L) } returns secondDeposit
        coEvery { useCase.linksOf(2L) } returns RefundLinks.Available(
            2L,
            listOf(
                RefundLinkInfo(70L, 1L, eur(40.0), 5L),
                RefundLinkInfo(71L, 3L, eur(30.0), 6L)
            )
        )
        // The row says refunded 0 (stale) although two links exist.
        model.openDetails(purchase.copy(refundNettedAmount = 0.0))
        advanceUntilIdle()
        val rows = (model.details.value as RefundDetailsUiState.Content).links
        model.requestUnlink(rows.first { it.counterpartId == 1L })
        val unlink = model.unlinkConfirm.value!!
        assertFalse("the other link remains, so this is not the last link", unlink.lastLink)
        assertEquals(eur(70.0), unlink.shownAmount) // 100 - 30 remaining refunded
    }

    @Test
    fun `requestUnlink from a deposit sheet uses the counterpart purchase`() = runTest(testDispatcher) {
        coEvery { useCase.linksOf(1L) } returns linksFor(1L, 2L, 40.0)
        coEvery { expenses.getExpenseById(2L) } returns linkedPurchase
        model.openDetails(linkedDeposit)
        advanceUntilIdle()
        model.requestUnlink((model.details.value as RefundDetailsUiState.Content).links.single())
        val unlink = model.unlinkConfirm.value!!
        assertEquals("Synthetic Shop", unlink.purchaseMerchant)
        assertEquals(1L, unlink.refundDepositId)
        assertTrue(unlink.lastLink)
        assertEquals(eur(100.0), unlink.shownAmount)
    }

    @Test
    fun `requestUnlink is ignored unless details show Content`() = runTest(testDispatcher) {
        val row = RefundLinkRowUi(70L, 1L, "m", 1L, null, eur(40.0), eur(40.0), eur(0.0))
        model.requestUnlink(row)
        assertNull(model.unlinkConfirm.value)
    }

    @Test
    fun `requestUnlink with an unusable currency scale emits refund_details_unavailable and opens no confirm`() = runTest(testDispatcher) {
        val xxx = CurrencyCode.parse("XXX")!!
        val xxxRow = RefundLinkRowUi(70L, 2L, "m", 1L, null, MoneyAmount(40.0, xxx), MoneyAmount(100.0, xxx), MoneyAmount(40.0, xxx))
        coEvery { useCase.linksOf(1L) } returns linksFor(1L, 2L, 40.0)
        coEvery { expenses.getExpenseById(2L) } returns linkedPurchase
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        model.openDetails(linkedDeposit)
        advanceUntilIdle()
        assertTrue(model.details.value is RefundDetailsUiState.Content)
        // A row whose amounts have no usable scale cannot produce a preview; it must say so, not stay silent.
        model.requestUnlink(xxxRow)
        assertNull(model.unlinkConfirm.value)
        assertEquals(listOf(UiText.from(R.string.refund_details_unavailable)), errors)
    }

    // ---- confirmUnlink ----------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.openUnlinkConfirm() {
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        model.requestUnlink((model.details.value as RefundDetailsUiState.Content).links.single())
    }

    @Test
    fun `confirmUnlink Unlinked shows refund_unlinked refreshes and closes details when nothing is left`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } returns RefundUnlinkResult.Unlinked(70L)
        coEvery { expenses.getExpenseById(2L) } returns purchase // fresh row: refundNettedAmount back to 0
        val success = mutableListOf<UiText>()
        val refresh = mutableListOf<Unit>()
        collectSuccess(success); collectRefresh(refresh)
        runCurrent()
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.refund_unlinked)), success)
        assertEquals(1, refresh.size)
        assertNull(model.unlinkConfirm.value)
        assertEquals(RefundDetailsUiState.Idle, model.details.value)
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `confirmUnlink NotLinked is also a success`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } returns RefundUnlinkResult.NotLinked
        coEvery { expenses.getExpenseById(2L) } returns purchase
        val success = mutableListOf<UiText>()
        collectSuccess(success)
        runCurrent()
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.refund_unlinked)), success)
    }

    @Test
    fun `confirmUnlink reloads the sheet when other links remain`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } returns RefundUnlinkResult.Unlinked(70L)
        coEvery { expenses.getExpenseById(2L) } returns purchase.copy(refundNettedAmount = 30.0)
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        // Reloaded from the fresh row: Loading was entered again and the read repeated.
        coVerify(exactly = 2) { useCase.linksOf(2L) }
        assertNull(model.unlinkConfirm.value)
    }

    @Test
    fun `confirmUnlink Failed WRITE_BLOCKED keeps the dialog and shows the maintenance message`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } returns RefundUnlinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED)
        val errors = mutableListOf<UiText>()
        val success = mutableListOf<UiText>()
        collectErrors(errors); collectSuccess(success)
        runCurrent()
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.ui_write_blocked_maintenance)), errors)
        assertTrue(success.isEmpty())
        assertFalse(model.unlinkConfirm.value!!.submitting)
        assertTrue(model.details.value is RefundDetailsUiState.Content)
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `confirmUnlink Failed INTERNAL keeps the dialog and shows the generic message`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } returns RefundUnlinkResult.Failed(RefundWriteFailure.INTERNAL)
        val errors = mutableListOf<UiText>()
        collectErrors(errors)
        runCurrent()
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        assertEquals(listOf(UiText.from(R.string.recurring_error_generic)), errors)
        assertFalse(model.unlinkConfirm.value!!.submitting)
    }

    @Test
    fun `confirmUnlink cancellation is not turned into an error`() = runTest(testDispatcher) {
        coEvery { useCase.unlink(1L) } throws CancellationException("cancelled")
        val errors = mutableListOf<UiText>()
        val success = mutableListOf<UiText>()
        collectErrors(errors); collectSuccess(success)
        runCurrent()
        openUnlinkConfirm()
        model.confirmUnlink()
        advanceUntilIdle()
        assertTrue(errors.isEmpty())
        assertTrue(success.isEmpty())
        assertTrue(model.mutatingExpenseIds.value.isEmpty())
    }

    @Test
    fun `double tap on Remove link writes once`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<RefundUnlinkResult>()
        coEvery { useCase.unlink(1L) } coAnswers { gate.await() }
        coEvery { expenses.getExpenseById(2L) } returns purchase
        openUnlinkConfirm()
        model.confirmUnlink()
        model.confirmUnlink()
        runCurrent()
        assertTrue(model.unlinkConfirm.value!!.submitting)
        assertEquals(setOf(1L, 2L), model.mutatingExpenseIds.value)
        model.dismissUnlink() // ignored while submitting
        assertTrue(model.unlinkConfirm.value!!.submitting)
        gate.complete(RefundUnlinkResult.Unlinked(70L))
        advanceUntilIdle()
        coVerify(exactly = 1) { useCase.unlink(1L) }
    }

    @Test
    fun `link in flight blocks an unlink and the reverse`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<RefundLinkResult>()
        coEvery { useCase.link(1L, 2L) } coAnswers { gate.await() }
        coEvery { useCase.unlink(any()) } returns RefundUnlinkResult.Unlinked(70L)
        openAndSelect()
        model.confirmLink()
        runCurrent()
        // An unlink dialog opened meanwhile cannot write while the link is in flight.
        coEvery { useCase.linksOf(2L) } returns linksFor(2L, 1L, 40.0)
        model.openDetails(linkedPurchase)
        advanceUntilIdle()
        model.requestUnlink((model.details.value as RefundDetailsUiState.Content).links.single())
        model.confirmUnlink()
        runCurrent()
        coVerify(exactly = 0) { useCase.unlink(any()) }
        gate.complete(RefundLinkResult.Linked(11L, eur(40.0)))
        advanceUntilIdle()
    }
}
