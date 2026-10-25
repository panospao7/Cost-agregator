package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Loan-installments S6a, test pass 2: link, confirm, unlink, skip/unskip, adjust and bulk
 * paid-before-tracking on a real in-memory Room database (AE-5 CURRENCY_MISMATCH, AE-10b, AE-13,
 * AE-16, AE-19 noon stamp, INV-4/5/12/14/16/19/21/31/32, STRICT key replay adoption, lost
 * compare-and-set, constraint mapping, cancellation).
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorLinkConfirmTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator

    /** 2027-05-12: entries 1 (04-10) and 2 (05-10) of the standalone plan are due. */
    private val today = day(2027, 5, 12)

    @Before
    fun setUp() = runBlocking {
        fx = InstallmentCoordinatorFixture.create(ApplicationProvider.getApplicationContext())
        coordinator = fx.coordinator()
    }

    @After
    fun tearDown() {
        if (::fx.isInitialized) fx.close()
    }

    private suspend fun standalone(
        count: Int = 6,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        uuid: String = "standalone-uuid-1",
        currency: String = "EUR"
    ): Long {
        val done = coordinator.createStandalone(
            fx.standaloneTerms(planUuid = uuid, count = count, mode = mode, currency = currency),
            fx.categoryId,
            PaymentMethod.CARD
        ) as InstallmentOpResult.Done
        return done.value.planId
    }

    private suspend fun entryId(planId: Long, seq: Int): Long = fx.entries(planId).first { it.seq == seq }.id

    private suspend fun entry(planId: Long, seq: Int) = fx.entries(planId).first { it.seq == seq }

    /** Sum of the amounts of the expenses linked to PAID entries dated in the given Athens month. */
    private suspend fun planSpending(planId: Long, year: Int, month: Int): Double =
        fx.entries(planId).filter { it.status == "PAID" }.sumOf {
            val expense = fx.expenseDao.getById(it.linkedExpenseId!!)!!
            val local = Instant.ofEpochMilli(expense.date).atZone(ATHENS)
            if (local.year == year && local.monthValue == month) expense.amount else 0.0
        }

    // ------------------------------------------------------------------ confirm

    @Test
    fun confirm_creates_the_installment_expense_at_noon_on_the_due_date_and_claims_the_entry() = runTest {
        val planId = standalone()
        val entry1 = entry(planId, 1)

        val result = coordinator.confirmEntry(entry1.id, today, ATHENS)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentEntryStatus.PAID, done.value.entryStatus)
        assertEquals(1, done.postCommit.size)
        val stored = entry(planId, 1)
        assertEquals("PAID", stored.status)
        assertEquals("USER_CONFIRMED", stored.linkOrigin)
        assertEquals(done.value.linkedExpenseId, stored.linkedExpenseId)
        assertEquals(0, stored.recordGeneration)
        assertEquals(false, stored.autoRecordConsumed)
        val expense = fx.expenseDao.getById(stored.linkedExpenseId!!)!!
        assertEquals(50.0, expense.amount, 0.0)
        assertEquals("EUR", expense.currency)
        assertEquals("Synthetic Loan", expense.merchant)
        assertEquals(athensMillis(2027, 4, 10, 12, 0), expense.date)
        assertEquals(TransactionType.PURCHASE, expense.transactionType)
        assertEquals("INSTALLMENT_PLAN", expense.source)
        assertEquals(fx.categoryId, expense.categoryId)
        assertEquals(PaymentMethod.CARD, expense.paymentMethod)
        assertNull(expense.notes)
        assertNull(expense.spendingExclusionReason)
        assertTrue(
            "STRICT key must carry the installment identity but was ${expense.dedupeKey}",
            expense.dedupeKey!!.endsWith("installment:standalone-uuid-1:REGULAR:1:0")
        )
        assertEquals(50.0, fx.spending(2027, 4), 0.0)
    }

    @Test
    fun INV14_confirm_of_a_future_entry_is_refused_ILLEGAL_TRANSITION_and_creates_nothing() = runTest {
        val planId = standalone()
        val before = fx.tableCounts()

        val result = coordinator.confirmEntry(entryId(planId, 3), today, ATHENS)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION), result)
        fx.assertTablesUnchanged(before)
        assertEquals("SCHEDULED", entry(planId, 3).status)
    }

    @Test
    fun confirm_replay_of_an_already_paid_entry_is_AlreadyInState_and_creates_no_second_expense() = runTest {
        val planId = standalone()
        val id = entryId(planId, 1)
        val first = coordinator.confirmEntry(id, today, ATHENS) as InstallmentOpResult.Done

        val second = coordinator.confirmEntry(id, today, ATHENS)

        assertTrue(second is InstallmentOpResult.AlreadyInState)
        assertEquals(first.value.linkedExpenseId, (second as InstallmentOpResult.AlreadyInState).value.linkedExpenseId)
        assertEquals(1, fx.count("expenses"))
    }

    @Test
    fun AE19_confirm_on_the_dst_start_day_stamps_noon_local_and_stays_in_the_march_bucket() = runTest {
        val done = coordinator.createStandalone(
            fx.standaloneTerms(planUuid = "dst-plan").copy(firstDueEpochDay = day(2027, 3, 28)),
            fx.categoryId,
            PaymentMethod.CARD
        ) as InstallmentOpResult.Done
        val id = entryId(done.value.planId, 1)

        val confirmed = coordinator.confirmEntry(id, day(2027, 3, 28), ATHENS) as InstallmentOpResult.Done

        val expense = fx.expenseDao.getById(confirmed.value.linkedExpenseId!!)!!
        assertEquals(athensMillis(2027, 3, 28, 12, 0), expense.date)
        assertEquals(50.0, fx.spending(2027, 3), 0.0)
    }

    @Test
    fun INV21_confirming_the_last_scheduled_entry_completes_the_plan_in_the_same_transaction() = runTest {
        val planId = standalone(count = 2)
        coordinator.confirmEntry(entryId(planId, 1), today, ATHENS)
        val afterFirst = fx.planDao.getPlanById(planId)!!
        assertEquals("ACTIVE", afterFirst.status)

        val last = coordinator.confirmEntry(entryId(planId, 2), today, ATHENS) as InstallmentOpResult.Done

        assertEquals(InstallmentPlanStatus.COMPLETED, last.value.planStatus)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("COMPLETED", plan.status)
        assertEquals("AUTO_COMPLETED", plan.statusReason)
        assertNotNull(plan.terminalAt)
    }

    @Test
    fun confirm_of_a_missing_entry_fails_ENTRY_NOT_FOUND() = runTest {
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND),
            coordinator.confirmEntry(777_777L, today, ATHENS)
        )
    }

    // ------------------------------------------------------------------ AE-10b CONFIRM mode

    @Test
    fun AE10b_confirm_mode_entry_is_not_counted_until_linked_then_the_link_counts_in_may() = runTest {
        val planId = standalone(mode = InstallmentFulfilmentMode.CONFIRM)
        val entry2 = entry(planId, 2)
        // Nothing is linked by the end of May: the coordinator has recorded nothing on its own.
        assertEquals(0, fx.count("expenses"))
        assertEquals("SCHEDULED", entry2.status)
        assertNull(entry2.linkedExpenseId)
        assertEquals(0.0, planSpending(planId, 2027, 5), 0.0)

        // On 2027-06-02 the user links a captured 50.00 EUR expense dated 2027-05-11.
        val captured = fx.seedCharge(athensMillis(2027, 5, 11, 9, 0))
        val linked = coordinator.linkExpense(entry2.id, captured)

        assertTrue(linked is InstallmentOpResult.Done)
        assertEquals(50.0, planSpending(planId, 2027, 5), 0.0)
        assertEquals("PAID", entry(planId, 2).status)
        assertEquals("USER_LINKED", entry(planId, 2).linkOrigin)
        assertEquals(captured, entry(planId, 2).linkedExpenseId)
    }

    // ------------------------------------------------------------------ STRICT key replay adoption

    /** Confirms entry 1, then detaches it WITHOUT a generation bump so the same STRICT key replays. */
    private suspend fun confirmedThenDetached(): Triple<Long, Long, Long> {
        val planId = standalone()
        val id = entryId(planId, 1)
        val done = coordinator.confirmEntry(id, today, ATHENS) as InstallmentOpResult.Done
        val expenseId = done.value.linkedExpenseId!!
        fx.exec("UPDATE installment_entries SET status = 'SCHEDULED', linkedExpenseId = NULL, linkOrigin = NULL WHERE id = $id")
        return Triple(planId, id, expenseId)
    }

    @Test
    fun strict_replay_of_a_plausible_payment_is_adopted_without_a_second_expense() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()

        val result = coordinator.confirmEntry(id, today, ATHENS)

        val done = result as InstallmentOpResult.Done
        assertEquals(expenseId, done.value.linkedExpenseId)
        assertEquals(1, fx.count("expenses"))
        assertEquals(expenseId, entry(planId, 1).linkedExpenseId)
        assertEquals("PAID", entry(planId, 1).status)
    }

    private suspend fun assertReplayNotAdopted(planId: Long, id: Long) {
        val before = fx.tableCounts()
        val result = coordinator.confirmEntry(id, today, ATHENS)
        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.RECORD_DUPLICATE_NOT_ADOPTABLE), result)
        fx.assertTablesUnchanged(before)
        assertEquals("SCHEDULED", entry(planId, 1).status)
        assertNull(entry(planId, 1).linkedExpenseId)
    }

    @Test
    fun strict_replay_of_a_flagged_expense_is_not_adopted() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()
        fx.setFlag(expenseId, SpendingExclusionReason.INSTALLMENT_REVERSED)

        assertReplayNotAdopted(planId, id)
    }

    @Test
    fun strict_replay_of_an_expense_whose_currency_changed_is_not_adopted() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()
        fx.exec("UPDATE expenses SET currency = 'USD' WHERE id = $expenseId")

        assertReplayNotAdopted(planId, id)
    }

    @Test
    fun strict_replay_of_an_expense_whose_type_changed_is_not_adopted() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()
        fx.tlc.updateType(expenseId, TransactionType.DEPOSIT)

        assertReplayNotAdopted(planId, id)
    }

    @Test
    fun strict_replay_of_an_expense_linked_to_a_recurring_occurrence_is_not_adopted() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()
        fx.linkToOccurrence(expenseId)

        assertReplayNotAdopted(planId, id)
    }

    @Test
    fun strict_replay_of_an_expense_already_linked_to_another_entry_is_not_adopted() = runTest {
        val (planId, id, expenseId) = confirmedThenDetached()
        val other = entryId(planId, 2)
        fx.exec(
            "UPDATE installment_entries SET status = 'PAID', linkedExpenseId = $expenseId, linkOrigin = 'USER_LINKED' WHERE id = $other"
        )

        assertReplayNotAdopted(planId, id)
    }

    // ------------------------------------------------------------------ failures: CAS, constraint, cancellation

    @Test
    fun confirm_with_a_lost_claim_is_Failed_CONFLICT_and_rolls_the_created_expense_back() = runTest {
        val planId = standalone()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.claimResult = 0
        val racing = fx.coordinator(dao = hooked)
        val before = fx.tableCounts()
        val eventsBefore = fx.eventTypes()

        val result = racing.confirmEntry(entryId(planId, 1), today, ATHENS)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        fx.assertTablesUnchanged(before)
        assertEquals(eventsBefore, fx.eventTypes())
        assertEquals("SCHEDULED", entry(planId, 1).status)
    }

    @Test
    fun confirm_constraint_violation_is_Failed_CONSTRAINT_VIOLATION_without_message_and_rolls_back() = runTest {
        val planId = standalone()
        fx.exec(
            "CREATE TRIGGER synthetic_fail_entry BEFORE UPDATE ON installment_entries " +
                "BEGIN SELECT RAISE(ABORT, 'sentinel-sql-detail'); END"
        )
        val before = fx.tableCounts()
        val tree = CapturingTree()
        tree.plant()
        try {
            val result = coordinator.confirmEntry(entryId(planId, 1), today, ATHENS)

            assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONSTRAINT_VIOLATION), result)
        } finally {
            tree.uproot()
        }

        assertTrue(tree.lines.none { it.contains("sentinel-sql-detail") })
        fx.assertTablesUnchanged(before)
        assertEquals("SCHEDULED", entry(planId, 1).status)
    }

    @Test
    fun confirm_cancellation_propagates_and_rolls_the_expense_back() = runTest {
        val planId = standalone()
        val id = entryId(planId, 1)
        val hooked = HookedPlanDao(fx.planDao)
        var planReads = 0
        hooked.getPlanByIdHook = { planPk ->
            // 1st read: loadPlan. 2nd read: settlePlanStatus, after the expense create and the claim: cancel there.
            if (planPk == planId && ++planReads == 2) throw CancellationException("synthetic cancellation")
        }
        val cancelled = fx.coordinator(dao = hooked)
        val before = fx.tableCounts()

        try {
            cancelled.confirmEntry(id, today, ATHENS)
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated, never converted to a result
        }

        fx.assertTablesUnchanged(before)
        assertEquals("SCHEDULED", entry(planId, 1).status)
    }

    // ------------------------------------------------------------------ link: AE-5 and AE-13

    @Test
    fun AE5_linking_a_usd_expense_to_an_eur_plan_entry_is_refused_CURRENCY_MISMATCH() = runTest {
        val planId = standalone(currency = "EUR")
        val usd = fx.seedCharge(athensMillis(2027, 5, 11), amount = 56.0, currency = "USD")
        val id = entryId(planId, 2)

        val result = coordinator.linkExpense(id, usd)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.CURRENCY_MISMATCH), result)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertNull(entry(planId, 2).linkedExpenseId)
    }

    @Test
    fun AE5_a_usd_plan_accepts_a_usd_expense_and_refuses_an_eur_one() = runTest {
        val planId = standalone(currency = "USD", uuid = "usd-plan")
        val eur = fx.seedCharge(athensMillis(2027, 5, 11))
        val usd = fx.seedCharge(athensMillis(2027, 5, 11), currency = "USD")
        val id = entryId(planId, 2)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.CURRENCY_MISMATCH),
            coordinator.linkExpense(id, eur)
        )
        assertTrue(coordinator.linkExpense(id, usd) is InstallmentOpResult.Done)
    }

    @Test
    fun link_marks_the_entry_paid_with_origin_USER_LINKED_and_leaves_the_expense_untouched() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val expenseBefore = fx.expenseDao.getById(captured)!!
        val eventsBefore = fx.eventTypes()

        val done = coordinator.linkExpense(entryId(planId, 1), captured) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.PAID, done.value.entryStatus)
        assertEquals(captured, done.value.linkedExpenseId)
        assertTrue(done.postCommit.isEmpty())
        assertEquals("USER_LINKED", entry(planId, 1).linkOrigin)
        assertEquals(expenseBefore, fx.expenseDao.getById(captured))
        assertEquals(eventsBefore, fx.eventTypes())
    }

    @Test
    fun link_replay_to_the_same_entry_is_AlreadyInState() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val id = entryId(planId, 1)
        coordinator.linkExpense(id, captured)

        assertTrue(coordinator.linkExpense(id, captured) is InstallmentOpResult.AlreadyInState)
    }

    @Test
    fun AE13_expense_linked_to_a_recurring_occurrence_is_refused_LINKED_ELSEWHERE() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        fx.linkToOccurrence(captured)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.LINKED_ELSEWHERE),
            coordinator.linkExpense(entryId(planId, 1), captured)
        )
    }

    @Test
    fun AE13_expense_already_linked_to_entry_3_cannot_be_linked_to_entry_4() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 6, 11))
        coordinator.linkExpense(entryId(planId, 3), captured)

        val result = coordinator.linkExpense(entryId(planId, 4), captured)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.LINKED_ELSEWHERE), result)
        assertEquals("SCHEDULED", entry(planId, 4).status)
        assertEquals(captured, entry(planId, 3).linkedExpenseId)
    }

    @Test
    fun AE13_a_plan_purchase_and_a_flag_only_purchase_cannot_be_linked_as_a_payment() = runTest {
        val (_, purchase) = fx.declareAe1(coordinator)
        val standalonePlan = standalone()
        val orphanFlagged = fx.seedExpense(merchant = "Flag Only")
        fx.setFlag(orphanFlagged, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val id = entryId(standalonePlan, 1)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.LINKED_ELSEWHERE),
            coordinator.linkExpense(id, purchase)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.LINKED_ELSEWHERE),
            coordinator.linkExpense(id, orphanFlagged)
        )
        assertEquals("SCHEDULED", entry(standalonePlan, 1).status)
    }

    @Test
    fun link_of_a_non_spending_or_missing_expense_is_refused_or_fails_closed() = runTest {
        val planId = standalone()
        val deposit = fx.seedExpense(type = TransactionType.DEPOSIT, amount = 50.0)
        val id = entryId(planId, 1)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE),
            coordinator.linkExpense(id, deposit)
        )
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND),
            coordinator.linkExpense(id, 888_888L)
        )
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND),
            coordinator.linkExpense(777_777L, deposit)
        )
    }

    @Test
    fun link_to_a_skipped_entry_or_a_cancelled_plan_is_illegal() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        coordinator.skip(entryId(planId, 1), InstallmentStatusReason.USER_SKIP_WAIVED)
        val cancelledPlan = fx.seedPlan(
            uuid = "cancelled",
            status = "CANCELLED",
            entries = listOf(InstallmentCoordinatorFixture.SeedEntry(1, status = "SCHEDULED"))
        )

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.linkExpense(entryId(planId, 1), captured)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.linkExpense(entryId(cancelledPlan, 1), captured)
        )
    }

    @Test
    fun link_with_a_lost_claim_is_Failed_CONFLICT_and_changes_nothing() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val hooked = HookedPlanDao(fx.planDao)
        hooked.claimResult = 0

        val result = fx.coordinator(dao = hooked).linkExpense(entryId(planId, 1), captured)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals("SCHEDULED", entry(planId, 1).status)
    }

    // ------------------------------------------------------------------ unlink

    @Test
    fun unlink_returns_the_entry_to_scheduled_bumps_the_generation_and_keeps_the_expense() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val id = entryId(planId, 1)
        coordinator.linkExpense(id, captured)
        val expenseBefore = fx.expenseDao.getById(captured)

        val done = coordinator.unlinkExpense(id) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.SCHEDULED, done.value.entryStatus)
        assertNull(done.value.linkedExpenseId)
        val stored = entry(planId, 1)
        assertEquals("SCHEDULED", stored.status)
        assertNull(stored.linkedExpenseId)
        assertNull(stored.linkOrigin)
        assertEquals(1, stored.recordGeneration)
        assertEquals(expenseBefore, fx.expenseDao.getById(captured))
        assertTrue(coordinator.unlinkExpense(id) is InstallmentOpResult.AlreadyInState)
    }

    @Test
    fun unlink_of_the_last_payment_reopens_a_completed_plan_and_clears_terminal_fields() = runTest {
        val planId = standalone(count = 2)
        coordinator.confirmEntry(entryId(planId, 1), today, ATHENS)
        coordinator.confirmEntry(entryId(planId, 2), today, ATHENS)
        assertEquals("COMPLETED", fx.planDao.getPlanById(planId)!!.status)

        val done = coordinator.unlinkExpense(entryId(planId, 2)) as InstallmentOpResult.Done

        assertEquals(InstallmentPlanStatus.ACTIVE, done.value.planStatus)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("ACTIVE", plan.status)
        assertNull(plan.statusReason)
        assertNull(plan.terminalAt)
    }

    @Test
    fun unlink_with_a_lost_release_is_Failed_CONFLICT_and_keeps_the_link() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val id = entryId(planId, 1)
        coordinator.linkExpense(id, captured)
        val hooked = HookedPlanDao(fx.planDao)
        hooked.releaseResult = 0

        val result = fx.coordinator(dao = hooked).unlinkExpense(id)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals(captured, entry(planId, 1).linkedExpenseId)
    }

    // ------------------------------------------------------------------ AE-16 skip / unskip

    @Test
    fun AE16_skip_with_a_user_reason_moves_the_entry_to_skipped_and_unskip_returns_it() = runTest {
        val planId = standalone()
        val id = entryId(planId, 2)

        val skipped = coordinator.skip(id, InstallmentStatusReason.USER_SKIP_LENDER_HOLIDAY) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.SKIPPED, skipped.value.entryStatus)
        assertEquals("SKIPPED", entry(planId, 2).status)
        assertEquals("USER_SKIP_LENDER_HOLIDAY", entry(planId, 2).statusReason)
        assertTrue(coordinator.skip(id, InstallmentStatusReason.USER_SKIP_LENDER_HOLIDAY) is InstallmentOpResult.AlreadyInState)

        val back = coordinator.unskip(id) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.SCHEDULED, back.value.entryStatus)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertNull(entry(planId, 2).statusReason)
        assertTrue(coordinator.unskip(id) is InstallmentOpResult.AlreadyInState)
    }

    @Test
    fun AE16_skip_refuses_a_system_reason_a_paid_entry_and_a_missing_entry() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        coordinator.linkExpense(entryId(planId, 1), captured)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.skip(entryId(planId, 2), InstallmentStatusReason.PLAN_STOPPED)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.skip(entryId(planId, 1), InstallmentStatusReason.USER_SKIP_OTHER)
        )
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND),
            coordinator.skip(777_777L, InstallmentStatusReason.USER_SKIP_OTHER)
        )
        assertEquals("SCHEDULED", entry(planId, 2).status)
    }

    @Test
    fun AE16_skipping_the_last_scheduled_entry_completes_the_plan_and_unskip_reopens_it() = runTest {
        val planId = standalone(count = 2)
        coordinator.skip(entryId(planId, 1), InstallmentStatusReason.USER_SKIP_WAIVED)

        val last = coordinator.skip(entryId(planId, 2), InstallmentStatusReason.USER_SKIP_OTHER) as InstallmentOpResult.Done

        assertEquals(InstallmentPlanStatus.COMPLETED, last.value.planStatus)
        assertNotNull(fx.planDao.getPlanById(planId)!!.terminalAt)

        val reopened = coordinator.unskip(entryId(planId, 1)) as InstallmentOpResult.Done

        assertEquals(InstallmentPlanStatus.ACTIVE, reopened.value.planStatus)
        assertNull(fx.planDao.getPlanById(planId)!!.terminalAt)
        assertNull(fx.planDao.getPlanById(planId)!!.statusReason)
    }

    @Test
    fun skip_with_a_lost_status_compare_and_set_is_Failed_CONFLICT() = runTest {
        val planId = standalone()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.statusResult = 0

        val result = fx.coordinator(dao = hooked).skip(entryId(planId, 2), InstallmentStatusReason.USER_SKIP_OTHER)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals("SCHEDULED", entry(planId, 2).status)
    }

    @Test
    fun AE16_recorded_expense_deleted_returns_the_entry_to_scheduled_with_generation_1_and_consumed_flag_kept() = runTest {
        val expense = fx.seedCharge(athensMillis(2027, 5, 10))
        val planId = fx.seedPlan(
            uuid = "auto-plan",
            mode = "AUTO_RECORD",
            entries = listOf(
                InstallmentCoordinatorFixture.SeedEntry(
                    2, status = "PAID", linkedExpenseId = expense, autoRecordConsumed = true
                ),
                InstallmentCoordinatorFixture.SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            )
        )
        fx.deleteExpenseRow(expense)

        val done = coordinator.onExpenseDeleted(expense) as InstallmentOpResult.Done

        assertEquals(1, done.value.r2Reopened)
        val reopened = entry(planId, 2)
        assertEquals("SCHEDULED", reopened.status)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, reopened.recordGeneration)
        assertTrue("autoRecordConsumed must never reset (INV-12)", reopened.autoRecordConsumed)
        assertEquals(0.0, fx.spending(2027, 5), 0.0)
    }

    // ------------------------------------------------------------------ currency / type change of a linked expense

    @Test
    fun linked_expense_currency_change_detaches_the_entry_EXPENSE_REMOVED_r4() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        coordinator.linkExpense(entryId(planId, 1), captured)
        fx.exec("UPDATE expenses SET currency = 'USD' WHERE id = $captured")

        val done = coordinator.onExpenseChanged(captured) as InstallmentOpResult.Done

        assertEquals(1, done.value.r4Unlinked)
        val stored = entry(planId, 1)
        assertEquals("SCHEDULED", stored.status)
        assertNull(stored.linkedExpenseId)
        assertEquals(1, stored.recordGeneration)
    }

    @Test
    fun linked_expense_type_change_detaches_the_entry_and_an_unrelated_expense_changes_nothing() = runTest {
        val planId = standalone()
        val captured = fx.seedCharge(athensMillis(2027, 4, 11))
        val unrelated = fx.seedCharge(athensMillis(2027, 4, 12))
        coordinator.linkExpense(entryId(planId, 1), captured)
        fx.tlc.updateType(captured, TransactionType.DEPOSIT)

        val none = coordinator.onExpenseChanged(unrelated) as InstallmentOpResult.Done
        assertEquals(InstallmentReconcileReport(), none.value)
        assertEquals("PAID", entry(planId, 1).status)

        val done = coordinator.onExpenseChanged(captured) as InstallmentOpResult.Done
        assertEquals(1, done.value.r4Unlinked)
        assertEquals("SCHEDULED", entry(planId, 1).status)
    }

    // ------------------------------------------------------------------ adjust

    @Test
    fun adjust_amount_keeps_the_first_original_and_derives_the_reason() = runTest {
        val planId = standalone()
        val id = entryId(planId, 2)

        val done = coordinator.adjustEntry(id, 55.55, null) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.SCHEDULED, done.value.entryStatus)
        var stored = entry(planId, 2)
        assertEquals(55.55, stored.scheduledAmount, 0.0)
        assertEquals(50.0, stored.originalScheduledAmount!!, 0.0)
        assertEquals("USER_AMOUNT", stored.adjustReason)
        assertEquals(day(2027, 5, 10), stored.dueDate)

        coordinator.adjustEntry(id, 60.0, null)
        stored = entry(planId, 2)
        assertEquals(60.0, stored.scheduledAmount, 0.0)
        assertEquals("the first pre-edit amount is kept", 50.0, stored.originalScheduledAmount!!, 0.0)
    }

    @Test
    fun adjust_date_only_and_both_derive_USER_DATE_and_USER_AMOUNT_AND_DATE() = runTest {
        val planId = standalone()

        coordinator.adjustEntry(entryId(planId, 2), null, day(2027, 5, 15))
        assertEquals("USER_DATE", entry(planId, 2).adjustReason)
        assertEquals(day(2027, 5, 15), entry(planId, 2).dueDate)
        assertEquals(50.0, entry(planId, 2).scheduledAmount, 0.0)

        coordinator.adjustEntry(entryId(planId, 3), 51.0, day(2027, 6, 20))
        assertEquals("USER_AMOUNT_AND_DATE", entry(planId, 3).adjustReason)
        assertEquals(51.0, entry(planId, 3).scheduledAmount, 0.0)
        assertEquals(day(2027, 6, 20), entry(planId, 3).dueDate)
    }

    @Test
    fun INV11_adjust_normalises_a_three_decimal_amount_to_exact_two_decimals_half_up() = runTest {
        val planId = standalone()

        coordinator.adjustEntry(entryId(planId, 2), 12.346, null)
        coordinator.adjustEntry(entryId(planId, 3), 12.344, null)

        assertEquals(12.35, entry(planId, 2).scheduledAmount, 0.0)
        assertEquals(12.34, entry(planId, 3).scheduledAmount, 0.0)
    }

    @Test
    fun adjust_refuses_invalid_amounts_and_dates_and_writes_nothing() = runTest {
        val planId = standalone()
        val id = entryId(planId, 2)
        val stored = entry(planId, 2)

        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "amount $bad",
                InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT),
                coordinator.adjustEntry(id, bad, null)
            )
        }
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT),
            coordinator.adjustEntry(id, null, null)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE),
            coordinator.adjustEntry(id, null, day(2201, 1, 1))
        )
        assertEquals(stored, entry(planId, 2))
    }

    @Test
    fun adjust_of_a_paid_entry_or_a_cancelled_plan_entry_is_illegal_and_a_missing_entry_fails() = runTest {
        val planId = standalone()
        coordinator.confirmEntry(entryId(planId, 1), today, ATHENS)
        val cancelledPlan = fx.seedPlan(
            uuid = "cancelled",
            status = "CANCELLED",
            entries = listOf(InstallmentCoordinatorFixture.SeedEntry(1))
        )

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.adjustEntry(entryId(planId, 1), 70.0, null)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.adjustEntry(entryId(cancelledPlan, 1), 70.0, null)
        )
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND),
            coordinator.adjustEntry(777_777L, 70.0, null)
        )
        assertEquals(50.0, entry(planId, 1).scheduledAmount, 0.0)
    }

    @Test
    fun adjust_with_a_lost_compare_and_set_is_Failed_CONFLICT_and_keeps_the_schedule() = runTest {
        val planId = standalone()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.scheduleResult = 0

        val result = fx.coordinator(dao = hooked).adjustEntry(entryId(planId, 2), 70.0, null)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals(50.0, entry(planId, 2).scheduledAmount, 0.0)
        assertNull(entry(planId, 2).adjustReason)
    }

    // ------------------------------------------------------------------ bulk paid-before-tracking (AE-27)

    private suspend fun declareK4(): Long {
        val purchase = fx.seedExpense(
            amount = 1200.0,
            dateMillis = athensMillis(2027, 1, 5, 11, 0),
            merchant = "Demo Furniture"
        )
        val done = coordinator.declareOnExpense(
            purchase,
            fx.terms(total = 1200.0, firstDue = day(2027, 2, 5), paidBeforeTracking = 4, declaredOn = day(2027, 6, 20)),
            ATHENS
        ) as InstallmentOpResult.Done
        return done.value.planId
    }

    @Test
    fun AE27_bulk_mark_of_a_past_entry_is_all_or_nothing_and_a_repeat_is_AlreadyInState() = runTest {
        val planId = declareK4()
        val e5 = entryId(planId, 5)
        val e6 = entryId(planId, 6)

        val refused = coordinator.markPaidBeforeTracking(planId, listOf(e5, e6))
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING), refused)
        assertEquals("SCHEDULED", entry(planId, 5).status)

        assertTrue(coordinator.markPaidBeforeTracking(planId, listOf(e5)) is InstallmentOpResult.Done)
        assertEquals("PAID_BEFORE_TRACKING", entry(planId, 5).status)
        assertNull(entry(planId, 5).linkedExpenseId)
        assertTrue(coordinator.markPaidBeforeTracking(planId, listOf(e5)) is InstallmentOpResult.AlreadyInState)
    }

    @Test
    fun AE27_revert_returns_the_entry_to_scheduled_with_generation_and_consumed_flag_unchanged() = runTest {
        val planId = declareK4()
        val e3 = entryId(planId, 3)

        val done = coordinator.revertPaidBeforeTracking(e3) as InstallmentOpResult.Done

        assertEquals(InstallmentEntryStatus.SCHEDULED, done.value.entryStatus)
        val stored = entry(planId, 3)
        assertEquals("SCHEDULED", stored.status)
        assertEquals(0, stored.recordGeneration)
        assertEquals(false, stored.autoRecordConsumed)
        assertTrue(coordinator.revertPaidBeforeTracking(e3) is InstallmentOpResult.AlreadyInState)
    }

    // ------------------------------------------------------------------ strict-review fixes

    @Test
    fun adjust_with_no_effective_change_is_AlreadyInState_and_leaves_the_audit_fields_untouched() = runTest {
        val planId = standalone()
        val id = entryId(planId, 2)
        assertTrue(coordinator.adjustEntry(id, 12.34, null) is InstallmentOpResult.Done)
        val stored = entry(planId, 2)

        val sameAmount = coordinator.adjustEntry(id, 12.340001, null)
        val sameAmountAndDate = coordinator.adjustEntry(id, 12.34, stored.dueDate)

        assertTrue(sameAmount is InstallmentOpResult.AlreadyInState)
        assertTrue(sameAmountAndDate is InstallmentOpResult.AlreadyInState)
        assertEquals(stored, entry(planId, 2))
    }

    @Test
    fun delete_and_entry_operations_on_a_purchase_gone_PURCHASE_ATTACHED_plan_are_ILLEGAL_TRANSITION() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        fx.deleteExpenseRow(purchase)
        val entry1 = fx.entries(planId)[0].id
        val before = fx.entries(planId)
        val illegal = InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)

        assertEquals(illegal, coordinator.delete(planId))
        assertEquals(illegal, coordinator.skip(entry1, InstallmentStatusReason.USER_SKIP_OTHER))
        assertEquals(illegal, coordinator.confirmEntry(entry1, today, ATHENS))
        assertEquals(illegal, coordinator.adjustEntry(entry1, 11.0, null))

        assertNotNull(fx.planDao.getPlanById(planId))
        assertEquals(before, fx.entries(planId))
    }

    @Test
    fun link_unlink_and_mark_paid_before_tracking_on_a_purchase_gone_PURCHASE_ATTACHED_plan_are_ILLEGAL_TRANSITION() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        val paidEntry = fx.entries(planId)[0].id
        val scheduledEntry = fx.entries(planId)[1].id
        val paidCharge = fx.seedCharge(athensMillis(2027, 4, 11))
        assertTrue(coordinator.linkExpense(paidEntry, paidCharge) is InstallmentOpResult.Done)
        val freeCharge = fx.seedCharge(athensMillis(2027, 5, 11))
        fx.deleteExpenseRow(purchase)
        val before = fx.entries(planId)
        val illegal = InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)

        assertEquals(illegal, coordinator.linkExpense(scheduledEntry, freeCharge))
        assertEquals(illegal, coordinator.unlinkExpense(paidEntry))
        assertEquals(illegal, coordinator.markPaidBeforeTracking(planId, listOf(scheduledEntry)))

        assertNotNull(fx.planDao.getPlanById(planId))
        assertEquals(before, fx.entries(planId))
        assertEquals("PAID", fx.entries(planId)[0].status)
        assertEquals(paidCharge, fx.entries(planId)[0].linkedExpenseId)
        assertNull(fx.entries(planId)[1].linkedExpenseId)
    }

    @Test
    fun delete_on_a_purchase_gone_plan_with_a_PAID_entry_is_PLAN_HAS_PAYMENTS_because_payments_are_checked_first() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        val paidEntry = fx.entries(planId)[0].id
        val charge = fx.seedCharge(athensMillis(2027, 4, 11))
        assertTrue(coordinator.linkExpense(paidEntry, charge) is InstallmentOpResult.Done)
        fx.deleteExpenseRow(purchase)
        val before = fx.entries(planId)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_HAS_PAYMENTS),
            coordinator.delete(planId)
        )

        assertNotNull(fx.planDao.getPlanById(planId))
        assertEquals(before, fx.entries(planId))
    }
}
