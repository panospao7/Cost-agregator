package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.SeedEntry
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

/**
 * Loan-installments S6a, test pass 2: reconcile rules R1 to R6 (domain spec 5.4, AE-25), the
 * orphan-flag clear (second halves of R1/R3), F1 continue-on-failure, containment of unmapped
 * exceptions, cancellation and idempotency, on a real in-memory Room database with the real TLC.
 * Stored states are seeded through the DAO; flags only ever change through TLC.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorReconcileTest {

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

    private suspend fun entry(planId: Long, seq: Int) = fx.entries(planId).first { it.seq == seq }

    private suspend fun reconcileDone(): InstallmentOpResult.Done<InstallmentReconcileReport> =
        coordinator.reconcile() as InstallmentOpResult.Done

    /** A purchase-attached plan with one SCHEDULED entry and the given purchase (flag as stored). */
    private suspend fun purchasePlan(uuid: String, purchase: Long): Long = fx.seedPlan(
        uuid = uuid,
        kind = "PURCHASE_ATTACHED",
        purchaseExpenseId = purchase,
        entries = listOf(SeedEntry(1))
    )

    // ------------------------------------------------------------------ R1 / R3 re-flag

    @Test
    fun R1_unflagged_plan_purchase_is_reflagged_and_a_second_run_changes_nothing() = runTest {
        val purchase = fx.seedExpense()
        purchasePlan("r1-plan", purchase)

        val first = reconcileDone()

        assertEquals(InstallmentReconcileReport(r1Reflagged = 1), first.value)
        assertEquals(1, first.postCommit.size)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchase))
        val second = reconcileDone()
        assertEquals(InstallmentReconcileReport(), second.value)
        assertTrue(second.postCommit.isEmpty())
    }

    @Test
    fun R3_reversed_entry_with_an_unflagged_expense_is_reflagged_INSTALLMENT_REVERSED() = runTest {
        val refunded = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = fx.seedPlan(
            uuid = "r3-plan",
            status = "REFUNDED",
            entries = listOf(SeedEntry(1, status = "REVERSED", linkedExpenseId = refunded))
        )

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r3Reflagged = 1), done.value)
        assertEquals(1, done.postCommit.size)
        assertEquals("INSTALLMENT_REVERSED", fx.flagOf(refunded))
        assertEquals("REVERSED", entry(planId, 1).status)
    }

    // ------------------------------------------------------------------ R2

    @Test
    fun R2_paid_entry_whose_expense_is_gone_reopens_with_generation_plus_one_in_an_active_plan() = runTest {
        val gone = fx.seedCharge(athensMillis(2027, 4, 10))
        val kept = fx.seedCharge(athensMillis(2027, 5, 10))
        val planId = fx.seedPlan(
            uuid = "r2-plan",
            entries = listOf(
                SeedEntry(1, status = "PAID", linkedExpenseId = gone),
                SeedEntry(2, status = "PAID", linkedExpenseId = kept),
                SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            )
        )
        fx.deleteExpenseRow(gone)

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r2Reopened = 1), done.value)
        val reopened = entry(planId, 1)
        assertEquals("SCHEDULED", reopened.status)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, reopened.recordGeneration)
        assertEquals("PAID", entry(planId, 2).status)
        assertEquals(kept, entry(planId, 2).linkedExpenseId)
    }

    @Test
    fun R2_paid_entry_whose_expense_is_gone_is_cancelled_EXPENSE_REMOVED_in_a_cancelled_plan() = runTest {
        val gone = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = fx.seedPlan(
            uuid = "r2-cancelled",
            status = "CANCELLED",
            entries = listOf(SeedEntry(1, status = "PAID", linkedExpenseId = gone), SeedEntry(2, status = "CANCELLED"))
        )
        fx.deleteExpenseRow(gone)

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r2Reopened = 1), done.value)
        val stored = entry(planId, 1)
        assertEquals("CANCELLED", stored.status)
        assertEquals("EXPENSE_REMOVED", stored.statusReason)
        assertNull(stored.linkedExpenseId)
    }

    @Test
    fun R2_reversed_entry_whose_refunded_expense_is_gone_is_cancelled_with_the_link_cleared() = runTest {
        val gone = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = fx.seedPlan(
            uuid = "r2-refunded",
            status = "REFUNDED",
            entries = listOf(SeedEntry(1, status = "REVERSED", linkedExpenseId = gone))
        )
        fx.deleteExpenseRow(gone)

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r2Reopened = 1), done.value)
        val stored = entry(planId, 1)
        assertEquals("CANCELLED", stored.status)
        assertEquals("EXPENSE_REMOVED", stored.statusReason)
        assertNull(stored.linkedExpenseId)
    }

    // ------------------------------------------------------------------ R4

    @Test
    fun R4_paid_entry_whose_expense_changed_currency_or_type_is_detached() = runTest {
        val currencyDrift = fx.seedCharge(athensMillis(2027, 4, 10))
        val typeDrift = fx.seedCharge(athensMillis(2027, 5, 10))
        val planId = fx.seedPlan(
            uuid = "r4-plan",
            entries = listOf(
                SeedEntry(1, status = "PAID", linkedExpenseId = currencyDrift),
                SeedEntry(2, status = "PAID", linkedExpenseId = typeDrift),
                SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            )
        )
        fx.exec("UPDATE expenses SET currency = 'USD' WHERE id = $currencyDrift")
        fx.tlc.updateType(typeDrift, TransactionType.DEPOSIT)

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r4Unlinked = 2), done.value)
        assertEquals("SCHEDULED", entry(planId, 1).status)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertEquals(1, entry(planId, 1).recordGeneration)
        assertEquals(1, entry(planId, 2).recordGeneration)
    }

    // ------------------------------------------------------------------ R5 (AE-25)

    @Test
    fun AE25_R5_purchase_deleted_detaches_the_plan_and_leaves_entries_and_monthly_totals_unchanged() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        val first = fx.seedCharge(athensMillis(2027, 4, 10))
        val second = fx.seedCharge(athensMillis(2027, 5, 10))
        coordinator.linkExpense(fx.entries(planId)[0].id, first)
        coordinator.linkExpense(fx.entries(planId)[1].id, second)
        val entriesBefore = fx.entries(planId)
        val totalsBefore = listOf(3, 4, 5).map { fx.spending(2027, it) }
        assertEquals(listOf(0.0, 50.0, 50.0), totalsBefore)

        fx.deleteExpenseRow(purchase)
        val done = coordinator.onExpenseDeleted(purchase) as InstallmentOpResult.Done

        assertEquals(InstallmentReconcileReport(r5Detached = 1), done.value)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("STANDALONE", plan.kind)
        assertNull(plan.purchaseExpenseId)
        assertEquals("ACTIVE", plan.status)
        assertEquals(entriesBefore, fx.entries(planId))
        assertEquals(totalsBefore, listOf(3, 4, 5).map { fx.spending(2027, it) })
        assertEquals(InstallmentReconcileReport(), (coordinator.onExpenseDeleted(purchase) as InstallmentOpResult.Done).value)
    }

    // ------------------------------------------------------------------ R6

    @Test
    fun R6_active_plan_without_scheduled_entries_completes_and_a_completed_plan_with_one_reopens() = runTest {
        val finished = fx.seedPlan(
            uuid = "r6-finished",
            entries = listOf(SeedEntry(1, status = "PAID_BEFORE_TRACKING"), SeedEntry(2, status = "SKIPPED"))
        )
        val reopened = fx.seedPlan(
            uuid = "r6-reopen",
            status = "COMPLETED",
            entries = listOf(SeedEntry(1, status = "PAID_BEFORE_TRACKING"), SeedEntry(2))
        )

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r6Completed = 1), done.value)
        val completedPlan = fx.planDao.getPlanById(finished)!!
        assertEquals("COMPLETED", completedPlan.status)
        assertEquals("AUTO_COMPLETED", completedPlan.statusReason)
        assertNotNull(completedPlan.terminalAt)
        val activePlan = fx.planDao.getPlanById(reopened)!!
        assertEquals("ACTIVE", activePlan.status)
        assertNull(activePlan.terminalAt)
    }

    @Test
    fun reconcile_with_nothing_to_repair_returns_zeros_no_batches_and_logs_nothing() = runTest {
        val purchase = fx.seedExpense()
        fx.setFlag(purchase, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        purchasePlan("healthy", purchase)
        val tree = CapturingTree()
        tree.plant()
        try {
            val done = reconcileDone()

            assertEquals(InstallmentReconcileReport(), done.value)
            assertTrue(done.postCommit.isEmpty())
        } finally {
            tree.uproot()
        }
        assertTrue(tree.lines.none { it.contains("InstallmentReconcile") })
    }

    // ------------------------------------------------------------------ orphan flags

    @Test
    fun orphan_flag_on_an_unreferenced_expense_is_cleared_through_TLC_and_a_batch_is_returned() = runTest {
        val purchaseOrphan = fx.seedExpense(merchant = "Orphan A")
        val reversedOrphan = fx.seedExpense(merchant = "Orphan B")
        fx.setFlag(purchaseOrphan, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        fx.setFlag(reversedOrphan, SpendingExclusionReason.INSTALLMENT_REVERSED)

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(orphanFlagsCleared = 2), done.value)
        assertEquals(2, done.postCommit.size)
        assertNull(fx.flagOf(purchaseOrphan))
        assertNull(fx.flagOf(reversedOrphan))
        // Both 600.00 purchases count again in March once their orphan flags are gone.
        assertEquals(1200.0, fx.spending(2027, 3), 0.0)
    }

    @Test
    fun orphan_scan_keeps_flags_that_a_plan_purchase_or_an_entry_link_still_references() = runTest {
        val planPurchase = fx.seedExpense(merchant = "Plan Purchase")
        val reversedLink = fx.seedExpense(merchant = "Reversed Link")
        fx.setFlag(planPurchase, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        fx.setFlag(reversedLink, SpendingExclusionReason.INSTALLMENT_REVERSED)
        purchasePlan("kept-purchase", planPurchase)
        fx.seedPlan(
            uuid = "kept-reversed",
            status = "REFUNDED",
            entries = listOf(SeedEntry(1, status = "REVERSED", linkedExpenseId = reversedLink))
        )

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(), done.value)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(planPurchase))
        assertEquals("INSTALLMENT_REVERSED", fx.flagOf(reversedLink))
    }

    @Test
    fun orphan_scan_is_reason_specific_a_reversed_flag_on_a_PAID_entry_link_is_cleared_and_counts_again() = runTest {
        val paid = fx.seedCharge(athensMillis(2027, 4, 10))
        fx.seedPlan(
            uuid = "stale-reversed",
            entries = listOf(SeedEntry(1, status = "PAID", linkedExpenseId = paid))
        )
        fx.setFlag(paid, SpendingExclusionReason.INSTALLMENT_REVERSED)
        assertEquals(0.0, fx.spending(2027, 4), 0.0)

        val done = reconcileDone()

        // The single-entry plan is fully PAID, so R6 also completes it in the same run.
        assertEquals(InstallmentReconcileReport(r6Completed = 1, orphanFlagsCleared = 1), done.value)
        assertNull(fx.flagOf(paid))
        assertEquals(50.0, fx.spending(2027, 4), 0.0)
    }

    @Test
    fun orphan_scan_is_reason_specific_a_purchase_flag_on_an_entry_linked_non_purchase_is_cleared() = runTest {
        val linked = fx.seedCharge(athensMillis(2027, 4, 10))
        fx.seedPlan(
            uuid = "stale-purchase",
            entries = listOf(SeedEntry(1, status = "PAID", linkedExpenseId = linked))
        )
        fx.setFlag(linked, SpendingExclusionReason.INSTALLMENT_PURCHASE)

        val done = reconcileDone()

        // The single-entry plan is fully PAID, so R6 also completes it in the same run.
        assertEquals(InstallmentReconcileReport(r6Completed = 1, orphanFlagsCleared = 1), done.value)
        assertNull(fx.flagOf(linked))
    }

    @Test
    fun orphan_clear_rechecks_inside_the_transaction_and_a_concurrent_declare_wins() = runTest {
        val candidate = fx.seedExpense()
        fx.setFlag(candidate, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val racing = HookedFactsReader(fx.facts)
        racing.afterFindFlagged = { purchasePlan("raced-declare", candidate) }

        val done = fx.coordinator(reader = racing).reconcile() as InstallmentOpResult.Done

        assertEquals(InstallmentReconcileReport(), done.value)
        assertTrue(done.postCommit.isEmpty())
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(candidate))
    }

    @Test
    fun orphan_clear_leaves_a_flag_that_changed_after_the_scan() = runTest {
        val candidate = fx.seedExpense()
        fx.setFlag(candidate, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val racing = HookedFactsReader(fx.facts)
        racing.afterFindFlagged = { fx.setFlag(candidate, SpendingExclusionReason.INSTALLMENT_REVERSED) }

        val done = fx.coordinator(reader = racing).reconcile() as InstallmentOpResult.Done

        assertEquals(InstallmentReconcileReport(), done.value)
        assertEquals("INSTALLMENT_REVERSED", fx.flagOf(candidate))
    }

    @Test
    fun orphan_candidate_deleted_after_the_scan_is_skipped_without_a_failure() = runTest {
        val candidate = fx.seedExpense()
        fx.setFlag(candidate, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val racing = HookedFactsReader(fx.facts)
        racing.afterFindFlagged = { fx.deleteExpenseRow(candidate) }

        val done = fx.coordinator(reader = racing).reconcile() as InstallmentOpResult.Done

        assertEquals(InstallmentReconcileReport(), done.value)
    }

    // ------------------------------------------------------------------ F1 continue-on-failure

    @Test
    fun F1_a_plan_that_throws_an_unmapped_exception_is_contained_and_committed_batches_are_returned() = runTest {
        val a = fx.seedExpense(merchant = "Shop A")
        val b = fx.seedExpense(merchant = "Shop B")
        val c = fx.seedExpense(merchant = "Shop C")
        purchasePlan("f1-a", a)
        val failingPlanId = purchasePlan("f1-b", b)
        purchasePlan("f1-c", c)
        val hooked = HookedPlanDao(fx.planDao)
        hooked.getPlanByIdHook = { id ->
            if (id == failingPlanId) throw IllegalStateException("sentinel-private-detail")
        }
        val tree = CapturingTree()
        tree.plant()
        val done = try {
            fx.coordinator(dao = hooked).reconcile() as InstallmentOpResult.Done
        } finally {
            tree.uproot()
        }

        assertEquals(InstallmentReconcileReport(r1Reflagged = 2, plansFailed = 1), done.value)
        assertEquals(2, done.postCommit.size)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(a))
        assertNull(fx.flagOf(b))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(c))
        // Only the exception class name is logged: no message, no stack, no ids.
        assertTrue(tree.lines.any { it.contains("cause=IllegalStateException") })
        assertTrue(tree.lines.none { it.contains("sentinel-private-detail") })
    }

    @Test
    fun F1_a_refused_or_unreadable_plan_counts_as_failed_and_the_run_continues() = runTest {
        val good = fx.seedExpense()
        purchasePlan("good", good)
        fx.seedPlan(uuid = "bogus", status = "NOT_A_STATUS", entries = listOf(SeedEntry(1)))

        val done = reconcileDone()

        assertEquals(InstallmentReconcileReport(r1Reflagged = 1, plansFailed = 1), done.value)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(good))
    }

    @Test
    fun F1_an_orphan_that_throws_counts_in_orphanFlagsFailed_and_the_other_orphan_is_still_cleared() = runTest {
        val failing = fx.seedExpense(merchant = "Orphan One")
        val other = fx.seedExpense(merchant = "Orphan Two")
        fx.setFlag(failing, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        fx.setFlag(other, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val hooked = HookedPlanDao(fx.planDao)
        hooked.getEntryByLinkedExpenseIdHook = { id ->
            if (id == failing) throw IllegalStateException("sentinel-private-detail")
        }
        val tree = CapturingTree()
        tree.plant()
        val done = try {
            fx.coordinator(dao = hooked).reconcile() as InstallmentOpResult.Done
        } finally {
            tree.uproot()
        }

        assertEquals(InstallmentReconcileReport(orphanFlagsCleared = 1, orphanFlagsFailed = 1), done.value)
        assertEquals(1, done.postCommit.size)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(failing))
        assertNull(fx.flagOf(other))
        assertTrue(tree.lines.none { it.contains("sentinel-private-detail") })
    }

    @Test
    fun F1_cancellation_propagates_returns_nothing_and_keeps_the_plans_committed_before_it() = runTest {
        val a = fx.seedExpense(merchant = "Shop A")
        val b = fx.seedExpense(merchant = "Shop B")
        purchasePlan("cancel-a", a)
        val cancelledPlanId = purchasePlan("cancel-b", b)
        val hooked = HookedPlanDao(fx.planDao)
        hooked.getPlanByIdHook = { id ->
            if (id == cancelledPlanId) throw CancellationException("synthetic cancellation")
        }

        try {
            fx.coordinator(dao = hooked).reconcile()
            fail("CancellationException must propagate out of reconcile")
        } catch (expected: CancellationException) {
            // propagated, never counted as a plan failure
        }

        // Plan A committed its own transaction before the cancellation; plan B rolled back.
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(a))
        assertNull(fx.flagOf(b))
    }

    @Test
    fun reconcile_in_a_blocked_barrier_mode_throws_before_any_read_or_write() = runTest {
        val purchase = fx.seedExpense()
        purchasePlan("blocked", purchase)
        fx.setMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)

        try {
            coordinator.reconcile()
            fail("a blocked write barrier must refuse the reconcile")
        } catch (expected: Exception) {
            assertEquals("DatabaseAccessBlockedException", expected.javaClass.simpleName)
        }

        fx.setMode(RestoreMaintenanceMode.Mode.NORMAL)
        assertNull(fx.flagOf(purchase))
    }
}
