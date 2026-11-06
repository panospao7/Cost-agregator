package com.yourname.expensetracker.ui.screens.recurringmanual

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkConfirmResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkDeclineResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkFailureCodes
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestion
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestions
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkUnavailableReason
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FC-PAID-UNLINKED S3 (ux-flows sections 3.1 and 9): the Recurring Expenses ViewModel maps the use case
 * result to [PaymentLinkSuggestionsUiState], drops items it cannot render truthfully, and runs Link /
 * Not this bill through the use case only with the existing in-flight and snackbar-message pattern.
 *
 * The use case is a strict mock: every call the ViewModel makes must be stubbed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRecurringExpenseViewModelPaymentLinkTest : ViewModelTestUtils() {

    private val recurringExpenseRepository = mockk<ManualRecurringExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val useCase = mockk<MarkRecurringBillPaidUseCase>()

    private val fixedNow = 1_700_000_000_000L

    /** What the next [MarkRecurringBillPaidUseCase.findSuggestions] call returns. */
    private var suggestionsResult: PaymentLinkSuggestions = PaymentLinkSuggestions.Available(emptyList())
    private var rules: List<ManualRecurringExpense> = emptyList()

    @Before
    override fun setup() {
        super.setup()
        every { timeProvider.now() } returns fixedNow
        rules = listOf(rule(RULE_ID, "Rent"))
        coEvery { recurringExpenseRepository.getAll() } answers { rules }
        coEvery { useCase.findSuggestions() } answers { suggestionsResult }
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun rule(id: Long, merchant: String, nextDate: Long = fixedNow + 2 * DAY_MS) = ManualRecurringExpense(
        id = id,
        merchant = merchant,
        amount = 400.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = nextDate,
        note = null,
        isSubscription = false,
        isActive = true,
        createdAt = fixedNow
    )

    private fun suggestion(
        occurrenceId: Long = 100L,
        ruleId: Long = RULE_ID,
        expenseId: Long = 900L,
        dueDate: Long = fixedNow + 2 * DAY_MS,
        paidAmount: Double = 400.0,
        paidCurrency: String = "EUR"
    ) = PaymentLinkSuggestion(
        occurrenceId = occurrenceId,
        ruleId = ruleId,
        occurrenceKey = "rule:$ruleId:$occurrenceId",
        dueDate = dueDate,
        expectedAmount = 400.0,
        expectedCurrency = "EUR",
        expenseId = expenseId,
        expenseDate = fixedNow - DAY_MS,
        paidAmount = paidAmount,
        paidCurrency = paidCurrency,
        dayDelta = -3,
        alternatives = emptyList()
    )

    private fun available(vararg items: PaymentLinkSuggestion) = PaymentLinkSuggestions.Available(items.toList())

    private fun newViewModel(): ManualRecurringExpenseViewModel {
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return ManualRecurringExpenseViewModel(
            recurringExpenseRepository,
            timeProvider,
            currencySettingsRepository = currencyRepo,
            markRecurringBillPaid = useCase
        )
    }

    /** Builds the view model on a state that already holds one Content suggestion for [RULE_ID]. */
    private fun kotlinx.coroutines.test.TestScope.viewModelWithOneSuggestion(): Pair<ManualRecurringExpenseViewModel, PaymentLinkSuggestionUi> {
        suggestionsResult = available(suggestion())
        val vm = newViewModel()
        advanceUntilIdle()
        val content = vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content
        return vm to content.byRuleId.getValue(RULE_ID).single()
    }

    // ---- state mapping (section 3.1) ---------------------------------------------------------

    @Test
    fun `state is Loading until the first result arrives and the list is already usable`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { gate.await() }

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(PaymentLinkSuggestionsUiState.Loading, vm.paymentLinkSuggestions.value)
        // A pending suggestion read never blanks the rule list.
        assertEquals(listOf(RULE_ID), vm.uiState.value.recurringExpenses.map { it.id })
        assertEquals(null, vm.uiState.value.errorText)

        gate.complete(available(suggestion()))
        advanceUntilIdle()
        assertTrue(vm.paymentLinkSuggestions.value is PaymentLinkSuggestionsUiState.Content)
    }

    @Test
    fun `INV-8 INV-9 available items map to Content grouped by rule id and ordered by due date`() =
        runTest(testDispatcher) {
            rules = listOf(rule(RULE_ID, "Rent"), rule(OTHER_RULE_ID, "Gym"))
            suggestionsResult = available(
                suggestion(occurrenceId = 102L, dueDate = fixedNow + 20 * DAY_MS, expenseId = 902L),
                suggestion(occurrenceId = 101L, dueDate = fixedNow + 5 * DAY_MS, expenseId = 901L),
                suggestion(occurrenceId = 103L, ruleId = OTHER_RULE_ID, expenseId = 903L)
            )

            val vm = newViewModel()
            advanceUntilIdle()

            val content = vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content
            assertEquals(setOf(RULE_ID, OTHER_RULE_ID), content.byRuleId.keys)
            assertEquals(listOf(101L, 102L), content.byRuleId.getValue(RULE_ID).map { it.occurrenceId })
            assertEquals(listOf(103L), content.byRuleId.getValue(OTHER_RULE_ID).map { it.occurrenceId })
            val first = content.byRuleId.getValue(RULE_ID).first()
            assertEquals(901L, first.expenseId)
            assertEquals(400.0, first.paidAmount, 0.0)
            assertEquals("EUR", first.paidCurrency)
            assertEquals(fixedNow + 5 * DAY_MS, first.dueDate)
            assertEquals(fixedNow - DAY_MS, first.expenseDate)
        }

    @Test
    fun `empty Available maps to None and not to Content or Unavailable`() = runTest(testDispatcher) {
        suggestionsResult = available()

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
    }

    @Test
    fun `INV-11 unavailable results map to Unavailable for both reason codes and never to None`() =
        runTest(testDispatcher) {
            for (reason in PaymentLinkUnavailableReason.values()) {
                suggestionsResult = PaymentLinkSuggestions.Unavailable(reason)

                val vm = newViewModel()
                advanceUntilIdle()

                assertEquals(
                    "reason $reason",
                    PaymentLinkSuggestionsUiState.Unavailable,
                    vm.paymentLinkSuggestions.value
                )
                // The suggestion failure never blanks the list or becomes a load error.
                assertEquals(listOf(RULE_ID), vm.uiState.value.recurringExpenses.map { it.id })
                assertEquals(null, vm.uiState.value.errorText)
            }
        }

    @Test
    fun `a use case exception maps to Unavailable and its text is not kept`() = runTest(testDispatcher) {
        coEvery { useCase.findSuggestions() } coAnswers { throw IllegalStateException("db-secret-detail") }

        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(PaymentLinkSuggestionsUiState.Unavailable, vm.paymentLinkSuggestions.value)
        assertFalse(vm.paymentLinkSuggestions.value.toString().contains("db-secret-detail"))
        assertFalse(vm.uiState.value.toString().contains("db-secret-detail"))
        assertEquals(null, vm.uiState.value.errorText)
    }

    @Test
    fun `a CancellationException from the use case is rethrown and not mapped to Unavailable`() =
        runTest(testDispatcher) {
            coEvery { useCase.findSuggestions() } coAnswers { throw CancellationException("cancelled-by-test") }

            val vm = newViewModel()
            advanceUntilIdle()

            // Not swallowed: nothing was committed, neither Unavailable nor None.
            assertEquals(PaymentLinkSuggestionsUiState.Loading, vm.paymentLinkSuggestions.value)
            assertEquals(null, vm.uiState.value.errorText)
            assertEquals(null, vm.uiState.value.actionMessage)
        }

    @Test
    fun `items with a non-finite non-positive or blank currency amount are dropped`() = runTest(testDispatcher) {
        suggestionsResult = available(
            suggestion(occurrenceId = 201L, paidAmount = Double.NaN),
            suggestion(occurrenceId = 202L, paidAmount = Double.POSITIVE_INFINITY),
            suggestion(occurrenceId = 203L, paidAmount = Double.NEGATIVE_INFINITY),
            suggestion(occurrenceId = 204L, paidAmount = 0.0),
            suggestion(occurrenceId = 205L, paidAmount = -12.5),
            suggestion(occurrenceId = 206L, paidCurrency = ""),
            suggestion(occurrenceId = 207L, paidCurrency = "   "),
            suggestion(occurrenceId = 208L, paidAmount = 400.0)
        )

        val vm = newViewModel()
        advanceUntilIdle()

        val content = vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content
        val shown = content.byRuleId.values.flatten()
        assertEquals(listOf(208L), shown.map { it.occurrenceId })
        assertTrue(shown.all { it.paidAmount.isFinite() && it.paidAmount > 0.0 && it.paidCurrency.isNotBlank() })
    }

    @Test
    fun `when every item is unrenderable nothing is shown and no blank or zero block exists`() =
        runTest(testDispatcher) {
            suggestionsResult = available(
                suggestion(occurrenceId = 201L, paidAmount = 0.0),
                suggestion(occurrenceId = 202L, paidCurrency = "")
            )

            val vm = newViewModel()
            advanceUntilIdle()

            assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
        }

    @Test
    fun `a recompute keeps the previous Content until the new result arrives`() = runTest(testDispatcher) {
        val (vm, _) = viewModelWithOneSuggestion()
        val before = vm.paymentLinkSuggestions.value
        val gate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { gate.await() }

        vm.refresh()
        advanceUntilIdle()

        // No flicker to Loading while recomputing.
        assertEquals(before, vm.paymentLinkSuggestions.value)

        gate.complete(available())
        advanceUntilIdle()
        assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
    }

    @Test
    fun `a stale older read finishing late does not overwrite the newer result`() = runTest(testDispatcher) {
        val oldGate = CompletableDeferred<PaymentLinkSuggestions>()
        coEvery { useCase.findSuggestions() } coAnswers { oldGate.await() }
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(PaymentLinkSuggestionsUiState.Loading, vm.paymentLinkSuggestions.value)

        coEvery { useCase.findSuggestions() } coAnswers { available(suggestion(occurrenceId = 300L)) }
        vm.refresh()
        advanceUntilIdle()
        val newer = vm.paymentLinkSuggestions.value
        assertTrue(newer is PaymentLinkSuggestionsUiState.Content)

        oldGate.complete(PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED))
        advanceUntilIdle()

        assertEquals(newer, vm.paymentLinkSuggestions.value)
    }

    // ---- Link (section 3.1 action table) -----------------------------------------------------

    @Test
    fun `link success shows the done snackbar message with the merchant and recomputes`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                PaymentLinkConfirmResult.Linked
            // After the link the occurrence is PAID: the next read has nothing to suggest, and the
            // rule's due date moved forward.
            suggestionsResult = available()
            rules = listOf(rule(RULE_ID, "Rent", nextDate = fixedNow + 32 * DAY_MS))

            vm.linkSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(
                UiText.StringResource(R.string.recurring_link_done_format, listOf<Any>("Rent")),
                vm.uiState.value.actionMessage
            )
            assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
            assertEquals(fixedNow + 32 * DAY_MS, vm.uiState.value.recurringExpenses.single().nextDate)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            coVerify(exactly = 1) { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) }
            coVerify(exactly = 0) { useCase.declinePayment(any(), any(), any()) }
            coVerify(atLeast = 2) { useCase.findSuggestions() }
            coVerify(atLeast = 2) { recurringExpenseRepository.getAll() }
        }

    @Test
    fun `AlreadyLinkedToThis is a success and shows the same done message`() = runTest(testDispatcher) {
        val (vm, item) = viewModelWithOneSuggestion()
        coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
            PaymentLinkConfirmResult.AlreadyLinkedToThis
        suggestionsResult = available()

        vm.linkSuggestedPayment(item)
        advanceUntilIdle()

        assertEquals(
            UiText.StringResource(R.string.recurring_link_done_format, listOf<Any>("Rent")),
            vm.uiState.value.actionMessage
        )
        assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
    }

    @Test
    fun `NoLongerValid and ExpenseLinkedElsewhere show the same stale message and recompute`() =
        runTest(testDispatcher) {
            for (result in listOf(PaymentLinkConfirmResult.NoLongerValid, PaymentLinkConfirmResult.ExpenseLinkedElsewhere)) {
                val (vm, item) = viewModelWithOneSuggestion()
                coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } returns result
                suggestionsResult = available()

                vm.linkSuggestedPayment(item)
                advanceUntilIdle()

                assertEquals(
                    "$result",
                    UiText.StringResource(R.string.recurring_link_no_longer_valid),
                    vm.uiState.value.actionMessage
                )
                assertEquals("$result", PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
                assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            }
        }

    @Test
    fun `link Failed WRITE_BLOCKED shows the maintenance message and keeps the block for a retry`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            val before = vm.paymentLinkSuggestions.value
            coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED)

            vm.linkSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(
                UiText.StringResource(R.string.ui_write_blocked_maintenance),
                vm.uiState.value.actionMessage
            )
            assertEquals(before, vm.paymentLinkSuggestions.value)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            assertEquals(null, vm.uiState.value.errorText)
        }

    @Test
    fun `link Failed INTERNAL and any other code show the generic error and keep the block`() =
        runTest(testDispatcher) {
            for (code in listOf(PaymentLinkFailureCodes.INTERNAL, "SOMETHING_ELSE")) {
                val (vm, item) = viewModelWithOneSuggestion()
                val before = vm.paymentLinkSuggestions.value
                coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                    PaymentLinkConfirmResult.Failed(code)

                vm.linkSuggestedPayment(item)
                advanceUntilIdle()

                assertEquals(
                    code,
                    UiText.StringResource(R.string.recurring_error_generic),
                    vm.uiState.value.actionMessage
                )
                assertEquals(code, before, vm.paymentLinkSuggestions.value)
                assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
                // The raw code is never put into the UI state.
                assertFalse(vm.uiState.value.toString().contains(code))
            }
        }

    @Test
    fun `link in flight id is set during the call ignores a second tap and clears afterwards`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            val gate = CompletableDeferred<PaymentLinkConfirmResult>()
            coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } coAnswers { gate.await() }

            vm.linkSuggestedPayment(item)
            assertTrue(RULE_ID in vm.uiState.value.inFlightRuleIds)
            vm.linkSuggestedPayment(item) // ignored: still in flight
            vm.declineSuggestedPayment(item) // also ignored: the rule's buttons are all disabled
            advanceUntilIdle()
            assertTrue(RULE_ID in vm.uiState.value.inFlightRuleIds)

            gate.complete(PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.INTERNAL))
            advanceUntilIdle()

            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            coVerify(exactly = 1) { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) }
            coVerify(exactly = 0) { useCase.declinePayment(any(), any(), any()) }
        }

    @Test
    fun `an in flight call for one rule does not disable another rule`() = runTest(testDispatcher) {
        rules = listOf(rule(RULE_ID, "Rent"), rule(OTHER_RULE_ID, "Gym"))
        suggestionsResult = available(
            suggestion(),
            suggestion(occurrenceId = 103L, ruleId = OTHER_RULE_ID, expenseId = 903L)
        )
        val vm = newViewModel()
        advanceUntilIdle()
        val content = vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content
        val rentItem = content.byRuleId.getValue(RULE_ID).single()
        val gate = CompletableDeferred<PaymentLinkConfirmResult>()
        coEvery { useCase.linkExistingPayment(RULE_ID, rentItem.occurrenceId, rentItem.expenseId) } coAnswers { gate.await() }

        vm.linkSuggestedPayment(rentItem)
        advanceUntilIdle()

        assertEquals(setOf(RULE_ID), vm.uiState.value.inFlightRuleIds)
        gate.complete(PaymentLinkConfirmResult.NoLongerValid)
        advanceUntilIdle()
    }

    @Test
    fun `an exception thrown by link maps to the generic message clears in flight and leaks no text`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } coAnswers {
                throw IllegalStateException("sql-secret-detail")
            }

            vm.linkSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(UiText.StringResource(R.string.recurring_error_generic), vm.uiState.value.actionMessage)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            assertFalse(vm.uiState.value.toString().contains("sql-secret-detail"))
            assertTrue(vm.paymentLinkSuggestions.value is PaymentLinkSuggestionsUiState.Content)
        }

    @Test
    fun `a CancellationException during link is rethrown shows no message and still clears in flight`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            coEvery { useCase.linkExistingPayment(RULE_ID, item.occurrenceId, item.expenseId) } coAnswers {
                throw CancellationException("cancelled-by-test")
            }

            vm.linkSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(null, vm.uiState.value.actionMessage)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            assertTrue(vm.paymentLinkSuggestions.value is PaymentLinkSuggestionsUiState.Content)
        }

    // ---- Decline ------------------------------------------------------------------------------

    @Test
    fun `decline success shows the declined message and the recompute removes the suggestion`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                PaymentLinkDeclineResult.Declined
            suggestionsResult = available() // the declined pair is no longer suggested

            vm.declineSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(UiText.StringResource(R.string.recurring_link_declined), vm.uiState.value.actionMessage)
            assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            coVerify(exactly = 1) { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) }
            coVerify(exactly = 0) { useCase.linkExistingPayment(any(), any(), any()) }
            coVerify(atLeast = 2) { useCase.findSuggestions() }
        }

    @Test
    fun `decline removes only the declined pair when the rule has another suggestion`() =
        runTest(testDispatcher) {
            suggestionsResult = available(
                suggestion(occurrenceId = 101L, expenseId = 901L, dueDate = fixedNow + 2 * DAY_MS),
                suggestion(occurrenceId = 102L, expenseId = 902L, dueDate = fixedNow + 30 * DAY_MS)
            )
            val vm = newViewModel()
            advanceUntilIdle()
            val items = (vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content)
                .byRuleId.getValue(RULE_ID)
            assertEquals(listOf(101L, 102L), items.map { it.occurrenceId })
            coEvery { useCase.declinePayment(RULE_ID, 101L, 901L) } returns PaymentLinkDeclineResult.Declined
            suggestionsResult = available(suggestion(occurrenceId = 102L, expenseId = 902L, dueDate = fixedNow + 30 * DAY_MS))

            vm.declineSuggestedPayment(items.first())
            advanceUntilIdle()

            val remaining = (vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content)
                .byRuleId.getValue(RULE_ID)
            assertEquals(listOf(102L), remaining.map { it.occurrenceId })
        }

    @Test
    fun `AlreadyDeclined is a success and shows the declined message`() = runTest(testDispatcher) {
        val (vm, item) = viewModelWithOneSuggestion()
        coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
            PaymentLinkDeclineResult.AlreadyDeclined
        suggestionsResult = available()

        vm.declineSuggestedPayment(item)
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.recurring_link_declined), vm.uiState.value.actionMessage)
        assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
    }

    @Test
    fun `decline NoLongerValid shows the stale message and recomputes`() = runTest(testDispatcher) {
        val (vm, item) = viewModelWithOneSuggestion()
        coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
            PaymentLinkDeclineResult.NoLongerValid
        suggestionsResult = available()

        vm.declineSuggestedPayment(item)
        advanceUntilIdle()

        assertEquals(UiText.StringResource(R.string.recurring_link_no_longer_valid), vm.uiState.value.actionMessage)
        assertEquals(PaymentLinkSuggestionsUiState.None, vm.paymentLinkSuggestions.value)
    }

    @Test
    fun `decline Failed WRITE_BLOCKED shows the maintenance message and keeps the block`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            val before = vm.paymentLinkSuggestions.value
            coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED)

            vm.declineSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(
                UiText.StringResource(R.string.ui_write_blocked_maintenance),
                vm.uiState.value.actionMessage
            )
            assertEquals(before, vm.paymentLinkSuggestions.value)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
        }

    @Test
    fun `decline Failed with another code shows the generic error and keeps the block`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            val before = vm.paymentLinkSuggestions.value
            coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } returns
                PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.INTERNAL)

            vm.declineSuggestedPayment(item)
            advanceUntilIdle()

            assertEquals(UiText.StringResource(R.string.recurring_error_generic), vm.uiState.value.actionMessage)
            assertEquals(before, vm.paymentLinkSuggestions.value)
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
        }

    @Test
    fun `decline in flight id is set during the call and cleared after a thrown exception`() =
        runTest(testDispatcher) {
            val (vm, item) = viewModelWithOneSuggestion()
            val gate = CompletableDeferred<Unit>()
            coEvery { useCase.declinePayment(RULE_ID, item.occurrenceId, item.expenseId) } coAnswers {
                gate.await()
                throw IllegalStateException("boom-secret-detail")
            }

            vm.declineSuggestedPayment(item)
            assertTrue(RULE_ID in vm.uiState.value.inFlightRuleIds)
            advanceUntilIdle()
            assertTrue(RULE_ID in vm.uiState.value.inFlightRuleIds)

            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            assertEquals(UiText.StringResource(R.string.recurring_error_generic), vm.uiState.value.actionMessage)
            assertFalse(vm.uiState.value.toString().contains("boom-secret-detail"))
        }

    // ---- stale UI / missing rule --------------------------------------------------------------

    @Test
    fun `a suggestion whose rule is no longer in the list is dropped without calling the use case`() =
        runTest(testDispatcher) {
            val (vm, _) = viewModelWithOneSuggestion()
            val orphan = PaymentLinkSuggestionUi(
                occurrenceId = 500L,
                ruleId = 77L,
                expenseId = 950L,
                paidAmount = 10.0,
                paidCurrency = "EUR",
                expenseDate = fixedNow,
                dueDate = fixedNow
            )

            vm.linkSuggestedPayment(orphan)
            vm.declineSuggestedPayment(orphan)
            advanceUntilIdle()

            coVerify(exactly = 0) { useCase.linkExistingPayment(any(), any(), any()) }
            coVerify(exactly = 0) { useCase.declinePayment(any(), any(), any()) }
            assertTrue(vm.uiState.value.inFlightRuleIds.isEmpty())
            assertEquals(null, vm.uiState.value.actionMessage)
            // The orphan was never in the state; the recompute still shows the real suggestion.
            val content = vm.paymentLinkSuggestions.value as PaymentLinkSuggestionsUiState.Content
            assertEquals(listOf(100L), content.byRuleId.getValue(RULE_ID).map { it.occurrenceId })
        }

    private companion object {
        const val RULE_ID = 5L
        const val OTHER_RULE_ID = 6L
        const val DAY_MS = 86_400_000L
    }
}
