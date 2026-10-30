package com.yourname.expensetracker.integration

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.installment.lifecycle.CapturingTree
import com.yourname.expensetracker.domain.installment.lifecycle.HookedPlanDao
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentDueRunReport
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loan-installments S13-b end-to-end scenarios (spec acceptance examples AE-1 to AE-11, AE-6 replay,
 * AE-23 privacy, plan S13 amendment items 9 and 10 = PE-A and PE-B).
 *
 * Real in-memory Room database, the real [InstallmentPlanLifecycleCoordinator], the real transaction
 * lifecycle coordinator (`ExpenseDao.insertAtomic` path) and the real due-recording call the worker
 * makes (`recordDueEntries`). Only peripheral collaborators are mocked inside the shared
 * [InstallmentCoordinatorFixture] (side-effect planner, converter, validator, diagnostics). Time is the
 * fixed 2027-03-12 fake clock, the zone is Europe/Athens, "today" is always passed explicitly.
 * Expected amounts and dates are written out from the spec, never computed by production code.
 * Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentEndToEndScenarioTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator

    @Before
    fun setUp() = runBlocking {
        fx = InstallmentCoordinatorFixture.create(ApplicationProvider.getApplicationContext())
        coordinator = fx.coordinator()
    }

    @After
    fun tearDown() {
        if (::fx.isInitialized) fx.close()
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun runDue(today: Long): InstallmentOpResult.Done<InstallmentDueRunReport> =
        coordinator.recordDueEntries(today, ATHENS) as InstallmentOpResult.Done

    private suspend fun entry(planId: Long, seq: Int) = fx.entries(planId).first { it.seq == seq }

    private suspend fun planStatus(planId: Long): String = fx.planDao.getPlanById(planId)!!.status

    private fun installmentExpenseIds(): List<Long> =
        fx.db.openHelper.readableDatabase.query(
            "SELECT id FROM expenses WHERE source = 'INSTALLMENT_PLAN' ORDER BY date, id"
        ).use {
            val ids = ArrayList<Long>()
            while (it.moveToNext()) ids += it.getLong(0)
            ids
        }

    /** Plan spending of one calendar month through the canonical aggregate (single EUR currency). */
    private suspend fun spending(year: Int, month: Int): Double = fx.spending(year, month)

    // ------------------------------------------------------------------ AE-1

    @Test
    fun AE1_declare_generates_twelve_entries_flags_the_purchase_and_catch_up_records_one_expense_per_due_date() = runTest {
        val (planId, purchaseId) = fx.declareAe1(coordinator)

        val entries = fx.entries(planId)
        assertEquals(12, entries.size)
        assertTrue(entries.all { it.scheduledAmount == 50.0 && it.status == "SCHEDULED" })
        assertEquals(day(2027, 4, 10), entries.first().dueDate)
        assertEquals(day(2028, 3, 10), entries.last().dueDate)
        assertEquals("ACTIVE", planStatus(planId))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchaseId))
        assertEquals(0.0, spending(2027, 3), 0.0)

        val first = runDue(day(2027, 4, 10))
        assertEquals(1, first.value.recorded)
        val paid = entry(planId, 1)
        assertEquals("PAID", paid.status)
        val expense = fx.expenseDao.getById(paid.linkedExpenseId!!)!!
        assertEquals(50.0, expense.amount, 0.0)
        assertEquals("EUR", expense.currency)
        assertEquals("Demo Electronics", expense.merchant)
        assertEquals(athensMillis(2027, 4, 10, 12, 0), expense.date)
        assertEquals(fx.categoryId, expense.categoryId)
        assertNull(expense.notes)
        assertEquals(50.0, spending(2027, 4), 0.0)
        assertNotNull("the flagged purchase still exists", fx.expenseDao.getById(purchaseId))
    }

    // ------------------------------------------------------------------ AE-1 totals, AE-11

    @Test
    fun AE11_last_recorded_entry_completes_the_plan_every_month_counts_50_and_the_total_is_600() = runTest {
        val (planId, purchaseId) = fx.declareAe1(coordinator)

        val firstEleven = listOf(
            2027 to 4, 2027 to 5, 2027 to 6, 2027 to 7, 2027 to 8, 2027 to 9,
            2027 to 10, 2027 to 11, 2027 to 12, 2028 to 1, 2028 to 2
        )
        for ((y, m) in firstEleven) {
            assertEquals(1, runDue(day(y, m, 10)).value.recorded)
            assertEquals("ACTIVE", planStatus(planId))
        }
        val last = runDue(day(2028, 3, 10))

        assertEquals(1, last.value.recorded)
        assertEquals("COMPLETED", planStatus(planId))
        assertTrue(fx.entries(planId).all { it.status == "PAID" })
        assertEquals(12, installmentExpenseIds().size)
        var total = spending(2027, 3)
        assertEquals("the flagged purchase counts nothing", 0.0, total, 0.0)
        for ((y, m) in firstEleven + listOf(2028 to 3)) {
            assertEquals("month $y-$m", 50.0, spending(y, m), 0.0)
            total += spending(y, m)
        }
        assertEquals(600.0, total, 0.0)
        assertNotNull(fx.expenseDao.getById(purchaseId))
        assertEquals(13, fx.count("expenses"))
        // A repeat after completion creates nothing.
        assertEquals(0, runDue(day(2028, 3, 10)).value.recorded)
        assertEquals(12, installmentExpenseIds().size)
    }

    @Test
    fun AE11_deleting_the_last_installment_expense_reopens_entry_12_and_the_plan_without_re_recording() = runTest {
        val (planId, _) = fx.declareAe1(coordinator)
        runDue(day(2028, 3, 10))
        assertEquals("COMPLETED", planStatus(planId))
        val lastExpenseId = entry(planId, 12).linkedExpenseId!!

        fx.deleteExpenseRow(lastExpenseId)
        assertTrue(coordinator.onExpenseDeleted(lastExpenseId) is InstallmentOpResult.Done)

        assertEquals("SCHEDULED", entry(planId, 12).status)
        assertNull(entry(planId, 12).linkedExpenseId)
        assertEquals("ACTIVE", planStatus(planId))
        assertEquals(0.0, spending(2028, 3), 0.0)

        // The latch keeps the automatic catch-up from recording it again.
        assertEquals(0, runDue(day(2028, 3, 12)).value.recorded)
        assertEquals("SCHEDULED", entry(planId, 12).status)
        assertEquals(11, installmentExpenseIds().size)
    }

    // ------------------------------------------------------------------ AE-6 replay and rollback

    @Test
    fun AE6_replaying_the_same_recording_creates_exactly_one_expense_and_a_replayed_declare_creates_no_second_plan() = runTest {
        val purchaseId = fx.seedExpense()
        val terms = fx.terms()
        val planId = (coordinator.declareOnExpense(purchaseId, terms, ATHENS) as InstallmentOpResult.Done).value.planId

        val first = runDue(day(2027, 4, 10))
        val second = runDue(day(2027, 4, 10))
        val third = runDue(day(2027, 4, 10))

        assertEquals(1, first.value.recorded)
        assertEquals(0, second.value.recorded)
        assertEquals(0, third.value.recorded)
        assertTrue(second.postCommit.isEmpty() && third.postCommit.isEmpty())
        assertEquals("exactly one installment expense", 1, installmentExpenseIds().size)
        assertEquals("purchase plus one installment", 2, fx.count("expenses"))
        assertEquals(50.0, spending(2027, 4), 0.0)
        assertEquals(0.0, spending(2027, 3), 0.0)

        val replayDeclare = coordinator.declareOnExpense(purchaseId, terms, ATHENS)
        assertTrue(replayDeclare is InstallmentOpResult.AlreadyInState)
        assertEquals(1, fx.count("installment_plans"))
        assertEquals(12, fx.count("installment_entries"))
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun AE6_failure_between_the_entries_insert_and_the_flag_write_persists_nothing_and_the_retry_succeeds_once() = runTest {
        val purchaseId = fx.seedExpense()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.afterInsertEntriesHook = { throw IllegalStateException("synthetic failure") }
        val failing = fx.coordinator(dao = hooked)

        try {
            failing.declareOnExpense(purchaseId, fx.terms(), ATHENS)
            fail("the injected failure must propagate")
        } catch (expected: IllegalStateException) {
            // rolled back below
        }
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(purchaseId))
        assertEquals(600.0, spending(2027, 3), 0.0)

        hooked.afterInsertEntriesHook = null
        assertTrue(failing.declareOnExpense(purchaseId, fx.terms(), ATHENS) is InstallmentOpResult.Done)
        assertEquals(1, fx.count("installment_plans"))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchaseId))
        assertEquals(0.0, spending(2027, 3), 0.0)
    }

    // ------------------------------------------------------------------ AE-7 stop, AE-8 refund

    /** AE-1 with entries 1 to 3 recorded (Apr to Jun); today is 2027-07-05. */
    private suspend fun threePaid(): Pair<Long, Long> {
        val ids = fx.declareAe1(coordinator)
        assertEquals(3, runDue(day(2027, 7, 5)).value.recorded)
        return ids
    }

    @Test
    fun AE7_stop_cancels_the_open_entries_keeps_the_paid_ones_and_every_expense() = runTest {
        val (planId, purchaseId) = threePaid()

        val result = coordinator.stop(planId)

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals("CANCELLED", planStatus(planId))
        val entries = fx.entries(planId)
        assertTrue(entries.take(3).all { it.status == "PAID" })
        assertTrue(entries.drop(3).all { it.status == "CANCELLED" && it.statusReason == InstallmentStatusReason.PLAN_STOPPED.name })
        assertEquals(4, fx.count("expenses"))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchaseId))
        for (month in 7..12) assertEquals(0.0, spending(2027, month), 0.0)
        for (month in 1..3) assertEquals(0.0, spending(2028, month), 0.0)
        assertEquals(150.0, spending(2027, 4) + spending(2027, 5) + spending(2027, 6), 0.0)
        // A later catch-up never records a cancelled entry, and a retry of Stop is a no-op.
        assertEquals(0, runDue(day(2027, 8, 12)).value.recorded)
        assertTrue(coordinator.stop(planId) is InstallmentOpResult.AlreadyInState)
        assertEquals(4, fx.count("expenses"))
    }

    @Test
    fun AE8_refund_reverses_the_paid_entries_flags_their_expenses_and_deletes_nothing() = runTest {
        val (planId, purchaseId) = threePaid()
        val paidExpenseIds = fx.entries(planId).take(3).map { it.linkedExpenseId!! }

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals("REFUNDED", planStatus(planId))
        val entries = fx.entries(planId)
        assertTrue(entries.take(3).all { it.status == "REVERSED" })
        assertTrue(entries.drop(3).all { it.status == "CANCELLED" && it.statusReason == InstallmentStatusReason.PLAN_REFUNDED.name })
        for (id in paidExpenseIds) assertEquals("INSTALLMENT_REVERSED", fx.flagOf(id))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchaseId))
        assertEquals(4, fx.count("expenses"))
        for (month in 4..6) assertEquals("month $month", 0.0, spending(2027, month), 0.0)
    }

    @Test
    fun AE8_refund_already_recorded_runs_the_stop_outcome_and_leaves_the_paid_expenses_counting() = runTest {
        val (planId, _) = threePaid()

        val result = coordinator.refund(planId, refundAlreadyRecorded = true)

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals("CANCELLED", planStatus(planId))
        assertTrue(fx.entries(planId).take(3).all { it.status == "PAID" })
        assertEquals(150.0, spending(2027, 4) + spending(2027, 5) + spending(2027, 6), 0.0)
        assertEquals(4, fx.count("expenses"))
    }

    // ------------------------------------------------------------------ AE-9 early payoff

    private suspend fun lifetime(): Double =
        (4..12).sumOf { spending(2027, it) } + (1..3).sumOf { spending(2028, it) }

    @Test
    fun AE9_payoff_with_the_default_amount_records_450_on_the_payoff_day_cancels_the_rest_and_totals_600() = runTest {
        val (planId, _) = threePaid()
        val today = day(2027, 7, 5)

        val result = coordinator.payOff(planId, null, today, today, ATHENS)

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals("PAID_OFF", planStatus(planId))
        val entries = fx.entries(planId)
        val payoff = entries.first { it.kind == "PAYOFF" }
        assertEquals("PAID", payoff.status)
        val payoffExpense = fx.expenseDao.getById(payoff.linkedExpenseId!!)!!
        assertEquals(450.0, payoffExpense.amount, 0.0)
        assertEquals(athensMillis(2027, 7, 5, 12, 0), payoffExpense.date)
        val regular = entries.filter { it.kind == "REGULAR" }
        assertTrue(regular.drop(3).all { it.status == "CANCELLED" && it.statusReason == InstallmentStatusReason.PAID_OFF_EARLY.name })
        assertEquals(450.0, spending(2027, 7), 0.0)
        for (month in 8..12) assertEquals(0.0, spending(2027, month), 0.0)
        assertEquals(600.0, lifetime(), 0.0)
        assertEquals(0, runDue(day(2027, 8, 12)).value.recorded)
    }

    @Test
    fun AE9_payoff_with_an_explicit_440_gives_a_lifetime_total_of_590() = runTest {
        val (planId, _) = threePaid()
        val today = day(2027, 7, 5)

        assertTrue(coordinator.payOff(planId, 440.0, today, today, ATHENS) is InstallmentOpResult.Done)

        assertEquals(440.0, spending(2027, 7), 0.0)
        assertEquals(590.0, lifetime(), 0.0)
    }

    // ------------------------------------------------------------------ AE-10 unconfirmed past installments

    @Test
    fun AE10a_auto_record_catches_up_a_missed_due_date_with_the_due_date_stamp_and_nothing_before_it() = runTest {
        val (planId, _) = fx.declareAe1(coordinator)
        assertEquals(1, runDue(day(2027, 4, 10)).value.recorded)

        assertEquals(0, runDue(day(2027, 5, 9)).value.recorded)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertEquals(0.0, spending(2027, 5), 0.0)

        assertEquals(1, runDue(day(2027, 5, 13)).value.recorded)
        val second = entry(planId, 2)
        assertEquals("PAID", second.status)
        assertEquals(athensMillis(2027, 5, 10, 12, 0), fx.expenseDao.getById(second.linkedExpenseId!!)!!.date)
        assertEquals(50.0, spending(2027, 5), 0.0)
    }

    @Test
    fun AE10b_confirm_mode_never_auto_records_and_linking_a_captured_expense_later_counts_it() = runTest {
        val planId = (coordinator.createStandalone(
            fx.standaloneTerms(planUuid = "confirm-uuid", count = 6, mode = InstallmentFulfilmentMode.CONFIRM),
            fx.categoryId,
            PaymentMethod.CARD
        ) as InstallmentOpResult.Done).value.planId

        assertEquals(0, runDue(day(2027, 5, 31)).value.recorded)
        assertEquals(0, installmentExpenseIds().size)
        assertEquals(0.0, spending(2027, 5), 0.0)
        assertEquals("SCHEDULED", entry(planId, 2).status)

        val captured = fx.seedCharge(athensMillis(2027, 5, 11), amount = 50.0)
        val linked = coordinator.linkExpense(entry(planId, 2).id, captured)

        assertTrue(linked is InstallmentOpResult.Done)
        assertEquals("PAID", entry(planId, 2).status)
        assertEquals(50.0, spending(2027, 5), 0.0)
    }

    @Test
    fun AE10c_late_declaration_leaves_the_pre_declaration_entries_unrecorded_and_records_from_the_declaration_on() = runTest {
        val purchaseId = fx.seedExpense(dateMillis = athensMillis(2027, 1, 15))
        val planId = (coordinator.declareOnExpense(
            purchaseId,
            fx.terms(firstDue = day(2027, 2, 15), declaredOn = day(2027, 4, 2)),
            ATHENS
        ) as InstallmentOpResult.Done).value.planId

        val run = runDue(day(2027, 4, 15))

        assertEquals(1, run.value.recorded)
        assertEquals("SCHEDULED", entry(planId, 1).status)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertEquals("PAID", entry(planId, 3).status)
        for (month in 1..3) assertEquals("month $month", 0.0, spending(2027, month), 0.0)
        assertEquals(50.0, spending(2027, 4), 0.0)
    }

    // ------------------------------------------------------------------ item 9 (PE-A) and item 10 (PE-B)

    @Test
    fun PEA_item9_a_past_due_scheduled_entry_is_never_auto_recorded_by_any_repeated_catch_up() = runTest {
        val purchaseId = fx.seedExpense()
        val planId = (coordinator.declareOnExpense(
            purchaseId,
            fx.terms(declaredOn = day(2027, 6, 12)),
            ATHENS
        ) as InstallmentOpResult.Done).value.planId
        assertTrue("non-vacuity: three entries are past due", fx.entries(planId).take(3).all { it.dueDate < day(2027, 6, 12) })

        repeat(3) { assertEquals(0, runDue(day(2027, 6, 12)).value.recorded) }
        val later = runDue(day(2027, 7, 10))

        assertEquals("only the 07-10 entry is applicable", 1, later.value.recorded)
        assertEquals(listOf("SCHEDULED", "SCHEDULED", "SCHEDULED", "PAID"), fx.entries(planId).take(4).map { it.status })
        assertTrue(fx.entries(planId).take(3).all { it.linkedExpenseId == null && !it.autoRecordConsumed })
        assertEquals(1, installmentExpenseIds().size)
        for (month in 4..6) assertEquals(0.0, spending(2027, month), 0.0)
    }

    @Test
    fun PEB_item10_after_unlink_the_entry_can_be_re_recorded_manually_under_the_next_generation() = runTest {
        val (planId, _) = fx.declareAe1(coordinator)
        runDue(day(2027, 4, 10))
        val original = entry(planId, 1)
        val originalExpenseId = original.linkedExpenseId!!

        assertTrue(coordinator.unlinkExpense(original.id) is InstallmentOpResult.Done)
        val reopened = entry(planId, 1)
        assertEquals("SCHEDULED", reopened.status)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, reopened.recordGeneration)
        assertEquals("the automatic catch-up never re-records it", 0, runDue(day(2027, 4, 10)).value.recorded)

        val again = coordinator.confirmEntry(original.id, day(2027, 4, 10), ATHENS)

        assertTrue(again is InstallmentOpResult.Done)
        val reRecorded = entry(planId, 1)
        assertEquals("PAID", reRecorded.status)
        val newExpenseId = reRecorded.linkedExpenseId!!
        assertTrue(newExpenseId != originalExpenseId)
        assertNotNull("the unlinked expense was never deleted", fx.expenseDao.getById(originalExpenseId))
        assertTrue(fx.expenseDao.getById(newExpenseId)!!.dedupeKey!!.endsWith("installment:plan-uuid-1:REGULAR:1:1"))
        assertEquals(2, installmentExpenseIds().size)
    }

    // ------------------------------------------------------------------ skip and edit

    @Test
    fun skip_and_adjust_change_what_the_catch_up_records_and_unskip_restores_the_entry() = runTest {
        val (planId, _) = fx.declareAe1(coordinator)
        val second = entry(planId, 2)
        val third = entry(planId, 3)

        assertTrue(coordinator.skip(second.id, InstallmentStatusReason.USER_SKIP_OTHER) is InstallmentOpResult.Done)
        assertTrue(coordinator.adjustEntry(third.id, 60.0, null) is InstallmentOpResult.Done)
        assertEquals(2, runDue(day(2027, 6, 12)).value.recorded)

        assertEquals("SKIPPED", entry(planId, 2).status)
        assertEquals("PAID", entry(planId, 3).status)
        assertEquals(0.0, spending(2027, 5), 0.0)
        assertEquals(60.0, spending(2027, 6), 0.0)
        assertEquals("ACTIVE", planStatus(planId))

        assertTrue(coordinator.unskip(second.id) is InstallmentOpResult.Done)
        assertEquals("SCHEDULED", entry(planId, 2).status)
    }

    // ------------------------------------------------------------------ AE-23 privacy

    @Test
    fun AE23_no_log_line_across_declare_record_failure_and_refund_contains_plan_text_merchant_amounts_or_dates() = runTest {
        val name = "SENTINEL-NAME-1101"
        val lender = "SENTINEL-LENDER-2202"
        val merchant = "SENTINEL-MERCHANT-3303"
        val forbidden = listOf(
            name, lender, merchant, "6543.21", "654321", "2181.07", "218107",
            "2027-04-10", "2027-05-10", day(2027, 4, 10).toString(), day(2027, 5, 10).toString(),
            "SENTINEL-SQL-4404"
        )
        val tree = CapturingTree()
        tree.plant()
        try {
            val purchaseId = fx.seedExpense(amount = 6543.21, merchant = merchant)
            val planId = (coordinator.declareOnExpense(
                purchaseId,
                fx.terms(displayName = name, lender = lender, total = 6543.21, count = 3, firstDue = day(2027, 4, 10)),
                ATHENS
            ) as InstallmentOpResult.Done).value.planId
            assertEquals(1, runDue(day(2027, 4, 10)).value.recorded)

            fx.exec(
                "CREATE TRIGGER synthetic_ae23 BEFORE UPDATE ON installment_entries " +
                    "BEGIN SELECT RAISE(ABORT, 'SENTINEL-SQL-4404'); END"
            )
            assertFalse(
                coordinator.skip(entry(planId, 2).id, InstallmentStatusReason.USER_SKIP_WAIVED) is InstallmentOpResult.Done
            )
            fx.exec("DROP TRIGGER synthetic_ae23")

            assertTrue(coordinator.refund(planId, refundAlreadyRecorded = false) is InstallmentOpResult.Done)
        } finally {
            tree.uproot()
        }

        assertTrue("the failing path must have logged something", tree.lines.isNotEmpty())
        assertTrue(tree.lines.any { it.contains("CONSTRAINT_VIOLATION") })
        for (line in tree.lines) {
            for (sentinel in forbidden) {
                assertTrue("a log line leaked '$sentinel': $line", !line.contains(sentinel))
            }
        }
    }

    @Test
    fun AE23_the_cloud_bound_briefing_input_type_has_no_installment_plan_field() {
        val fieldNames = DashboardBriefingInput::class.java.declaredFields.map { it.name }

        assertTrue("non-vacuity: the input type has fields", fieldNames.isNotEmpty())
        assertTrue(
            "installment data must not be part of the cloud payload input: $fieldNames",
            fieldNames.none { it.contains("installment", ignoreCase = true) }
        )
    }
}
