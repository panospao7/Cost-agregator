package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.EntryMoney
import com.yourname.expensetracker.domain.installment.readmodel.EntrySnapshot
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentBudgetInputs
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentLinkSuggestion
import com.yourname.expensetracker.domain.installment.readmodel.PlanSnapshot
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * loan-installments S12a: Transactions surfaces. Row marks (OQ-22: source badge + "Installment n of N" as
 * a UI join), purchase chips, the refunded chip, the installments filter, the row menu variants, the
 * AE-17 surfaces (suggestion chip and possible duplicate), dormancy (no read, no hook while the flag is
 * off) and regression (an unmarked row and the unfiltered list are unchanged). The flag is a compile-time
 * false, so enabled paths pass `enabled = true` explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelInstallmentTest : ViewModelTestUtils() {

    private val day = 86_400_000L

    private fun expense(
        expenseId: Long,
        type: TransactionType = TransactionType.PURCHASE,
        notMine: Boolean = false,
        shared: Boolean = false,
        exclusion: com.yourname.expensetracker.domain.transaction.SpendingExclusionReason? = null,
        amountValue: Double = 50.0,
        currencyCode: String = "EUR",
        dateMillis: Long = 0L,
        splitTemplate: Long? = null
    ): Expense = mockk {
        every { id } returns expenseId
        every { transactionType } returns type
        every { isNotMine } returns notMine
        every { isSharedExpense } returns shared
        every { splitTemplateId } returns splitTemplate
        every { splitVisualization } returns null
        // The entity stores the enum NAME as a String?.
        every { spendingExclusionReason } returns exclusion?.name
        every { amount } returns amountValue
        every { currency } returns currencyCode
        every { date } returns dateMillis
    }

    private fun entry(
        seq: Int,
        status: InstallmentEntryStatus,
        linked: Long?,
        due: Long = 100L + seq,
        minor: Long = 5_000L,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR
    ) = MarkEntryInput(seq, kind, status, linked, due, minor)

    private val plan = MarkPlanInput(
        planId = 3L,
        currencyCode = "EUR",
        purchaseExpenseId = 10L,
        entries = listOf(
            entry(1, InstallmentEntryStatus.PAID, linked = 11L),
            entry(2, InstallmentEntryStatus.REVERSED, linked = 12L),
            entry(3, InstallmentEntryStatus.SCHEDULED, linked = null),
            entry(4, InstallmentEntryStatus.CANCELLED, linked = null)
        )
    )

    private val marks = InstallmentRowMarksLoader.build(listOf(plan), suggestionSeqByExpenseId = mapOf(20L to 3))

    @Test
    fun `purchase record gets the Paid in N chip data and payments get Installment n of N`() {
        assertEquals(InstallmentRowMark.PurchaseRecord(3L, installmentCount = 4), marks.byExpenseId[10L])
        assertEquals(InstallmentRowMark.Payment(3L, seq = 1, total = 4, reversed = false), marks.byExpenseId[11L])
        // OQ-22: the chip is a join on the entry link; nothing about the expense is stored or changed.
        assertEquals(setOf(10L, 11L, 12L), marks.byExpenseId.keys)
    }

    @Test
    fun `a reversed entry is marked refunded and a SCHEDULED or CANCELLED entry is not marked`() {
        val reversed = marks.byExpenseId[12L] as InstallmentRowMark.Payment
        assertTrue(reversed.reversed)
        assertFalse((marks.byExpenseId[11L] as InstallmentRowMark.Payment).reversed)
    }

    @Test
    fun `AE-17 suggestion seq is exposed for an unlinked candidate`() {
        assertEquals(3, marks.suggestionSeqByExpenseId[20L])
        val ui = InstallmentRowUi.of(expense(20L), marks, ZoneOffset.UTC, enabled = true)!!
        assertEquals(3, ui.suggestionSeq)
        assertNull(ui.mark)
    }

    @Test
    fun `AE-17 a captured charge right after a paid entry is a possible duplicate of that installment`() {
        val paidDue = marks.paidEntries.single().dueEpochDay
        val duplicate = expense(30L, amountValue = 50.0, dateMillis = (paidDue + 1) * day + 3_600_000L)
        assertEquals(1, InstallmentDuplicateRule.duplicateOfSeq(duplicate, marks, ZoneOffset.UTC))

        // Same day as the due date, outside the window, other currency, other amount, already marked.
        assertNull(InstallmentDuplicateRule.duplicateOfSeq(expense(31L, dateMillis = paidDue * day), marks, ZoneOffset.UTC))
        assertNull(InstallmentDuplicateRule.duplicateOfSeq(expense(32L, dateMillis = (paidDue + 30) * day), marks, ZoneOffset.UTC))
        assertNull(
            InstallmentDuplicateRule.duplicateOfSeq(
                expense(33L, currencyCode = "USD", dateMillis = (paidDue + 1) * day), marks, ZoneOffset.UTC
            )
        )
        assertNull(
            InstallmentDuplicateRule.duplicateOfSeq(
                expense(34L, amountValue = 51.0, dateMillis = (paidDue + 1) * day), marks, ZoneOffset.UTC
            )
        )
        assertNull(
            InstallmentDuplicateRule.duplicateOfSeq(
                expense(11L, dateMillis = (paidDue + 1) * day), marks, ZoneOffset.UTC
            )
        )
    }

    @Test
    fun `row menu variants follow ux-flows 3-2`() {
        assertEquals(
            InstallmentMenuAction.PayInInstallments,
            InstallmentRowUi.menuFor(expense(40L), null)
        )
        assertEquals(
            InstallmentMenuAction.ViewPlan(3L),
            InstallmentRowUi.menuFor(expense(10L), marks.byExpenseId[10L])
        )
        assertEquals(
            InstallmentMenuAction.ViewPlan(3L),
            InstallmentRowUi.menuFor(expense(11L), marks.byExpenseId[11L])
        )
        assertEquals(
            InstallmentMenuAction.Disabled(InstallmentMenuDisabledReason.NOT_MINE),
            InstallmentRowUi.menuFor(expense(41L, notMine = true), null)
        )
        assertEquals(
            InstallmentMenuAction.Disabled(InstallmentMenuDisabledReason.SHARED_OR_SPLIT),
            InstallmentRowUi.menuFor(expense(42L, shared = true), null)
        )
        // Non-spending types are hidden.
        assertEquals(InstallmentMenuAction.Hidden, InstallmentRowUi.menuFor(expense(43L, type = TransactionType.TRANSFER), null))
    }

    @Test
    fun `fail closed - a flagged row with no mark is hidden, never offered a second declaration`() {
        for (reason in com.yourname.expensetracker.domain.transaction.SpendingExclusionReason.values()) {
            assertEquals(
                reason.name,
                InstallmentMenuAction.Hidden,
                InstallmentRowUi.menuFor(expense(44L, exclusion = reason), null)
            )
        }
    }

    @Test
    fun `a split is detected through splitTemplateId alone`() {
        assertEquals(
            InstallmentMenuAction.Disabled(InstallmentMenuDisabledReason.SHARED_OR_SPLIT),
            InstallmentRowUi.menuFor(expense(45L, splitTemplate = 5L), null)
        )
    }

    @Test
    fun `AE-17 the duplicate rule ignores a non-spending type even when amount and date match`() {
        val paidDue = marks.paidEntries.single().dueEpochDay
        val matchingDate = (paidDue + 1) * day + 3_600_000L
        assertEquals(1, InstallmentDuplicateRule.duplicateOfSeq(expense(46L, dateMillis = matchingDate), marks, ZoneOffset.UTC))
        assertNull(
            InstallmentDuplicateRule.duplicateOfSeq(
                expense(47L, type = TransactionType.TRANSFER, dateMillis = matchingDate), marks, ZoneOffset.UTC
            )
        )
    }

    @Test
    fun `the installments filter keeps marked rows and drops empty date groups`() {
        fun row(id: Long): ExpenseWithCategory {
            val inner = expense(id)
            return mockk { every { expense } returns inner }
        }
        val grouped = linkedMapOf(
            "Today" to listOf(row(10L), row(99L)),
            "Yesterday" to listOf(row(98L)),
            "Earlier" to listOf(row(11L))
        )
        val filtered = TransactionsInstallmentViewModel.filterGrouped(grouped, marks)

        assertEquals(listOf("Today", "Earlier"), filtered.keys.toList())
        assertEquals(listOf(10L), filtered.getValue("Today").map { it.expense.id })
        assertEquals(listOf(11L), filtered.getValue("Earlier").map { it.expense.id })
    }

    @Test
    fun `regression - with no marks an expense row is unchanged and the unfiltered list is the original`() {
        val ui = InstallmentRowUi.of(expense(50L), InstallmentRowMarks.EMPTY, ZoneOffset.UTC, enabled = true)!!
        assertNull(ui.mark)
        assertNull(ui.suggestionSeq)
        assertNull(ui.duplicateSeq)
        assertEquals(InstallmentMenuAction.PayInInstallments, ui.menu)
    }

    @Test
    fun `dormant - no row hooks while the flag is off`() {
        // Default flag = false: no row UI at all, so no chip, badge or menu entry can render.
        assertNull(InstallmentRowUi.of(expense(10L), marks))
    }

    @Test
    fun `dormant - the loader reads nothing and the view model keeps the filter off`() = runTest(testDispatcher) {
        val repository = mockk<InstallmentPlanReadRepository>() // strict: any call fails the test
        val loader = InstallmentRowMarksLoader(repository)
        assertEquals(InstallmentRowMarks.EMPTY, loader.load())
        coVerify(exactly = 0) { repository.loadBudgetInputs() }
        coVerify(exactly = 0) { repository.loadOverview() }
        coVerify(exactly = 0) { repository.loadHistory() }
        coVerify(exactly = 0) { repository.loadDetail(any()) }
        coVerify(exactly = 0) { repository.loadSuggestions() }

        val vm = TransactionsInstallmentViewModel(loader)
        vm.refresh()
        vm.toggleFilter()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallmentRowMarks.EMPTY, vm.marks.value)
        assertFalse(vm.filterOn.value)
    }

    /** PE-D: a plan snapshot as the single bulk read returns it (one PAID regular entry, purchase attached). */
    private fun snapshotOf(
        planId: Long,
        purchaseId: Long?,
        paidLinked: Long,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE
    ) = PlanSnapshot(
        planId = planId,
        planUuid = "uuid-$planId",
        displayName = "Plan $planId",
        lenderLabel = null,
        kind = InstallmentPlanKind.PURCHASE_ATTACHED,
        fulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        status = status,
        currency = InstallmentCurrency.EUR,
        purchaseExpenseId = purchaseId,
        purchase = null,
        terminalEpochDay = null,
        entries = listOf(
            EntrySnapshot(
                entryId = planId * 100 + 1,
                seq = 1,
                originalScheduledMinor = null,
                linkedExpenseId = paidLinked,
                statusReason = null,
                money = EntryMoney(InstallmentEntryKind.REGULAR, InstallmentEntryStatus.PAID, 100L, 5_000L)
            )
        )
    )

    private fun inputsOf(vararg plans: PlanSnapshot) = InstallmentBudgetInputs(plans.toList(), unreadablePlanCount = 0)

    /** Strict mock: loadOverview, loadHistory and loadDetail are not stubbed, so any call fails the test. */
    private fun readRepository(vararg planIds: Long): InstallmentPlanReadRepository {
        val repository = mockk<InstallmentPlanReadRepository>()
        io.mockk.coEvery { repository.loadBudgetInputs() } returns
            inputsOf(*planIds.map { id -> snapshotOf(id, purchaseId = id * 10, paidLinked = id * 10 + 1) }.toTypedArray())
        io.mockk.coEvery { repository.loadSuggestions() } returns emptyList()
        return repository
    }

    @Test
    fun `AE-17 a PAYOFF entry shows the source badge only, with no n of N sequence`() {
        val payoffPlan = MarkPlanInput(
            planId = 5L,
            currencyCode = "EUR",
            purchaseExpenseId = 50L,
            entries = listOf(
                entry(1, InstallmentEntryStatus.PAID, linked = 51L),
                entry(0, InstallmentEntryStatus.PAID, linked = 52L, kind = InstallmentEntryKind.PAYOFF)
            )
        )
        val built = InstallmentRowMarksLoader.build(listOf(payoffPlan))
        val payoff = built.byExpenseId[52L] as InstallmentRowMark.Payment
        assertFalse(payoff.showSequence)
        // N counts the REGULAR entries only; the regular payment keeps its chip.
        assertEquals(1, payoff.total)
        assertTrue((built.byExpenseId[51L] as InstallmentRowMark.Payment).showSequence)
        // A PAYOFF entry is not a duplicate reference.
        assertEquals(listOf(1), built.paidEntries.map { it.seq })
    }

    @Test
    fun `enabled - the loader maps plan snapshots to marks`() = runTest(testDispatcher) {
        val loaded = InstallmentRowMarksLoader(readRepository(3L)).load(enabled = true)!!
        assertEquals(InstallmentRowMark.PurchaseRecord(3L, installmentCount = 1), loaded.byExpenseId[30L])
        assertEquals(InstallmentRowMark.Payment(3L, seq = 1, total = 1, reversed = false), loaded.byExpenseId[31L])
        assertEquals(setOf(30L, 31L), loaded.byExpenseId.keys)
        assertEquals(listOf(InstallmentPaidEntryRef(3L, 1, "EUR", 100L, 5_000L)), loaded.paidEntries)
    }

    @Test
    fun `PE-D the loader makes one snapshot read, one suggestions read and no per-plan detail read`() = runTest(testDispatcher) {
        val repository = readRepository(3L, 4L, 5L)
        val loaded = InstallmentRowMarksLoader(repository).load(enabled = true)!!

        coVerify(exactly = 1) { repository.loadBudgetInputs() }
        coVerify(exactly = 1) { repository.loadSuggestions() }
        coVerify(exactly = 0) { repository.loadDetail(any()) }
        coVerify(exactly = 0) { repository.loadOverview() }
        coVerify(exactly = 0) { repository.loadHistory() }
        assertEquals(setOf(30L, 31L, 40L, 41L, 50L, 51L), loaded.byExpenseId.keys)
    }

    @Test
    fun `PE-D plans of every status are marked, as the former overview plus history plus detail reads were`() =
        runTest(testDispatcher) {
            val repository = mockk<InstallmentPlanReadRepository>()
            io.mockk.coEvery { repository.loadBudgetInputs() } returns inputsOf(
                snapshotOf(3L, purchaseId = 30L, paidLinked = 31L, status = InstallmentPlanStatus.ACTIVE),
                snapshotOf(4L, purchaseId = 40L, paidLinked = 41L, status = InstallmentPlanStatus.COMPLETED),
                snapshotOf(5L, purchaseId = 50L, paidLinked = 51L, status = InstallmentPlanStatus.CANCELLED),
                snapshotOf(6L, purchaseId = 60L, paidLinked = 61L, status = InstallmentPlanStatus.REFUNDED),
                snapshotOf(7L, purchaseId = null, paidLinked = 71L, status = InstallmentPlanStatus.PAID_OFF)
            )
            io.mockk.coEvery { repository.loadSuggestions() } returns listOf(
                InstallmentLinkSuggestion(3L, 301L, 2, 99L, 5_000L, 100L, 0L)
            )

            val loaded = InstallmentRowMarksLoader(repository).load(enabled = true)!!

            assertEquals(setOf(30L, 31L, 40L, 41L, 50L, 51L, 60L, 61L, 71L), loaded.byExpenseId.keys)
            assertEquals(InstallmentRowMark.Payment(7L, seq = 1, total = 1, reversed = false), loaded.byExpenseId[71L])
            assertEquals(mapOf(99L to 2), loaded.suggestionSeqByExpenseId)
        }

    @Test
    fun `two quick refreshes cause one read after the debounce`() = runTest(testDispatcher) {
        val repository = readRepository(3L)
        val vm = TransactionsInstallmentViewModel(InstallmentRowMarksLoader(repository))
        vm.refresh(enabled = true)
        testDispatcher.scheduler.advanceTimeBy(100)
        vm.refresh(enabled = true)
        testDispatcher.scheduler.advanceTimeBy(TransactionsInstallmentViewModel.REFRESH_DEBOUNCE_MS - 1)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { repository.loadBudgetInputs() }

        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.loadBudgetInputs() }
        assertEquals(setOf(30L, 31L), vm.marks.value.byExpenseId.keys)
    }

    @Test
    fun `a refresh cancels an in-flight read so a slow older read never lands`() = runTest(testDispatcher) {
        val repository = mockk<InstallmentPlanReadRepository>()
        var calls = 0
        io.mockk.coEvery { repository.loadBudgetInputs() } coAnswers {
            calls++
            if (calls == 1) {
                kotlinx.coroutines.delay(10_000)
                inputsOf(snapshotOf(3L, 30L, 31L))
            } else {
                inputsOf(snapshotOf(4L, 40L, 41L))
            }
        }
        io.mockk.coEvery { repository.loadSuggestions() } returns emptyList()
        val vm = TransactionsInstallmentViewModel(InstallmentRowMarksLoader(repository))

        vm.refresh(enabled = true)
        // Past the debounce: the first read is now in flight (suspended in its delay).
        testDispatcher.scheduler.advanceTimeBy(TransactionsInstallmentViewModel.REFRESH_DEBOUNCE_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, calls)

        vm.refresh(enabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(setOf(40L, 41L), vm.marks.value.byExpenseId.keys)
    }

    @Test
    fun `enabled - the filter toggles and a failed read shows no marks`() = runTest(testDispatcher) {
        // Start from NON-empty marks so the EMPTY assertion below is meaningful.
        val repository = readRepository(3L)
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        val vm = TransactionsInstallmentViewModel(InstallmentRowMarksLoader(repository))
        vm.refresh(enabled = true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf(30L, 31L), vm.marks.value.byExpenseId.keys)

        io.mockk.coEvery { repository.loadBudgetInputs() } throws IllegalStateException("db text Demo 600")
        timber.log.Timber.plant(tree)
        try {
            vm.refresh(enabled = true)
            testDispatcher.scheduler.advanceUntilIdle()
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertEquals(InstallmentRowMarks.EMPTY, vm.marks.value)
        // The failure is logged by exception class only: no message text, no throwable, no merchant.
        assertEquals(
            listOf<Pair<Throwable?, String>>(null to "Transactions installment marks failed: class=IllegalStateException"),
            logs
        )
        assertTrue(logs.none { it.second.contains("Demo") || it.second.contains("600") })

        vm.toggleFilter(enabled = true)
        assertTrue(vm.filterOn.value)
        vm.toggleFilter(enabled = true)
        assertFalse(vm.filterOn.value)
    }

    @Test
    fun `a CancellationException from a read is rethrown by the loader`() = runTest(testDispatcher) {
        val repository = mockk<InstallmentPlanReadRepository>()
        io.mockk.coEvery { repository.loadBudgetInputs() } throws CancellationException("cancelled")
        try {
            InstallmentRowMarksLoader(repository).load(enabled = true)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
