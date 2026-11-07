package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.PURCHASE_MILLIS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.SeedEntry
import com.yourname.expensetracker.domain.transaction.RefundLinkResult
import com.yourname.expensetracker.domain.transaction.RefundUnlinkResult
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import io.mockk.clearMocks
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Refund netting S3, decision D-19 (installments and refund links are mutually exclusive) seen from
 * the installment coordinator, on a real in-memory Room database with the real TLC. Oracle: the
 * refund-netting domain spec (INV-19, D-19); expected values are written out, never derived from
 * production code.
 *
 * A refund-linked row is created through the real TLC link path (a DEPOSIT linked to a PURCHASE).
 * Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorRefundLinkedTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator

    private val oneDay = 24L * 60L * 60L * 1000L

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

    /** Seeds a 600 purchase and a 100 DEPOSIT, links them through the real TLC; returns (purchase, refund). */
    private suspend fun refundLinkedPurchase(): Pair<Long, Long> {
        val purchase = fx.seedExpense(amount = 600.0)
        val deposit = fx.seedExpense(amount = 100.0, type = TransactionType.DEPOSIT, dateMillis = PURCHASE_MILLIS + oneDay)
        val linked = fx.tlc.linkRefund(deposit, purchase)
        assertTrue("fixture: expected Linked, got $linked", linked is RefundLinkResult.Linked)
        assertEquals(100.0, fx.expenseDao.getById(purchase)!!.refundNettedAmount, 0.0)
        clearMocks(fx.planner, answers = false)
        return purchase to deposit
    }

    private suspend fun nettedOf(expenseId: Long): Double = fx.expenseDao.getById(expenseId)!!.refundNettedAmount

    private suspend fun ae1WithPaid(paid: Int, today: Long): Pair<Long, Long> {
        val (planId, expenseId) = fx.declareAe1(coordinator)
        val entries = fx.entries(planId)
        for (seq in 1..paid) {
            val result = coordinator.confirmEntry(entries.first { it.seq == seq }.id, today, ATHENS)
            assertTrue("fixture: confirm failed $result", result is InstallmentOpResult.Done)
        }
        return planId to expenseId
    }

    // ------------------------------------------------------------------ declare

    @Test
    fun `INV-19 D-19 declareOnExpense on a refund-linked purchase fails REFUND_LINKED and rolls back every row`() = runBlocking {
        val (purchase, deposit) = refundLinkedPurchase()
        val counts = fx.tableCounts()
        val eventsBefore = fx.count("transaction_events")
        val linksBefore = fx.count("expense_refund_links")

        val result = coordinator.declareOnExpense(purchase, fx.terms(), ATHENS)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.REFUND_LINKED), result)
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        fx.assertTablesUnchanged(counts)
        assertNull("the exclusion flag must not be written", fx.flagOf(purchase))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals(linksBefore, fx.count("expense_refund_links"))
        assertEquals(100.0, nettedOf(purchase), 0.0)
        assertEquals(100.0, nettedOf(deposit), 0.0)
        verify(exactly = 0) { fx.planner.planUpdated(any(), any(), any(), any()) }
        assertEquals(0, fx.barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-19 D-19 control declareOnExpense on an unlinked purchase succeeds and flags it`() = runBlocking {
        val purchase = fx.seedExpense(amount = 600.0)

        val result = coordinator.declareOnExpense(purchase, fx.terms(), ATHENS)

        assertTrue("Expected Done, got $result", result is InstallmentOpResult.Done)
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchase))
        assertEquals(1, fx.count("installment_plans"))
        assertEquals(12, fx.count("installment_entries"))
    }

    @Test
    fun `INV-19 D-19 after unlinkRefund the same purchase can be declared`() = runBlocking {
        val (purchase, deposit) = refundLinkedPurchase()
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.REFUND_LINKED),
            coordinator.declareOnExpense(purchase, fx.terms(), ATHENS)
        )

        val unlink = fx.tlc.unlinkRefund(deposit)
        assertTrue("fixture: expected Unlinked, got $unlink", unlink is RefundUnlinkResult.Unlinked)
        val result = coordinator.declareOnExpense(purchase, fx.terms(), ATHENS)

        assertTrue("Expected Done, got $result", result is InstallmentOpResult.Done)
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchase))
        assertEquals(0.0, nettedOf(purchase), 0.0)
    }

    // ------------------------------------------------------------------ entry linking (other direction)

    @Test
    fun `INV-19 D-19 linkExpense of a refund-netted expense fails REFUND_LINKED and leaves the entry unchanged`() = runBlocking {
        val (purchase, _) = refundLinkedPurchase()
        val planId = fx.seedPlan(uuid = "rl-link-plan", entries = listOf(SeedEntry(1)))
        val entryId = fx.entries(planId).single().id
        val counts = fx.tableCounts()
        val eventsBefore = fx.count("transaction_events")

        val result = coordinator.linkExpense(entryId, purchase)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.REFUND_LINKED), result)
        val entry = fx.entries(planId).single()
        assertEquals("SCHEDULED", entry.status)
        assertNull(entry.linkedExpenseId)
        assertNull("the exclusion flag must not be written", fx.flagOf(purchase))
        fx.assertTablesUnchanged(counts)
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals(100.0, nettedOf(purchase), 0.0)
        verify(exactly = 0) { fx.planner.planUpdated(any(), any(), any(), any()) }
        assertEquals(0, fx.barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-19 D-19 linkExpense of a refund-netted expense already linked to the same entry stays a no-op`() = runBlocking {
        val (purchase, _) = refundLinkedPurchase()
        val planId = fx.seedPlan(
            uuid = "rl-legacy-link",
            entries = listOf(SeedEntry(1, status = "PAID", linkedExpenseId = purchase))
        )
        val entryId = fx.entries(planId).single().id
        val counts = fx.tableCounts()

        val result = coordinator.linkExpense(entryId, purchase)

        assertTrue("Expected AlreadyInState, got $result", result is InstallmentOpResult.AlreadyInState)
        assertEquals(purchase, fx.entries(planId).single().linkedExpenseId)
        fx.assertTablesUnchanged(counts)
        assertEquals(100.0, nettedOf(purchase), 0.0)
    }

    @Test
    fun `INV-19 D-19 control linkExpense of an unlinked purchase succeeds`() = runBlocking {
        val purchase = fx.seedExpense(amount = 50.0)
        val planId = fx.seedPlan(uuid = "rl-link-control", entries = listOf(SeedEntry(1)))
        val entryId = fx.entries(planId).single().id

        val result = coordinator.linkExpense(entryId, purchase)

        assertTrue("Expected Done, got $result", result is InstallmentOpResult.Done)
        assertEquals(purchase, fx.entries(planId).single().linkedExpenseId)
    }

    // ------------------------------------------------------------------ REFUNDED-plan path

    @Test
    fun `INV-19 D-19 refund of a plan whose paid expense is refund-linked fails REFUND_LINKED and rolls the whole refund back`() = runBlocking {
        val today = day(2027, 7, 5)
        val (planId, _) = ae1WithPaid(3, today)
        val paidExpenseIds = fx.entries(planId).filter { it.status == "PAID" }.map { it.linkedExpenseId!! }
        assertEquals(3, paidExpenseIds.size)
        // Legacy or restored state (linkExpense now refuses a refund-netted expense, but older data
        // may predate that): the last paid expense carries a refund netting while its plan is refunded.
        fx.exec("UPDATE expenses SET refundNettedAmount = 10.0 WHERE id = ${paidExpenseIds.last()}")
        val statusesBefore = fx.entries(planId).map { it.status }
        val eventsBefore = fx.count("transaction_events")
        val counts = fx.tableCounts()
        clearMocks(fx.planner, answers = false)

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.REFUND_LINKED), result)
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
        assertNull(fx.planDao.getPlanById(planId)!!.terminalAt)
        assertEquals(statusesBefore, fx.entries(planId).map { it.status })
        for (id in paidExpenseIds) assertNull("no INSTALLMENT_REVERSED flag survives the rollback", fx.flagOf(id))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        fx.assertTablesUnchanged(counts)
        assertEquals(10.0, nettedOf(paidExpenseIds.last()), 0.0)
        // No post-commit dispatch: Failed carries no post-commit batch (only Done does), so the
        // batches built for earlier entries inside the aborted transaction were dropped.
        assertTrue("Failed must not be a Done carrying post-commit batches", result !is InstallmentOpResult.Done)
        assertEquals(0, fx.barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-19 D-19 control refund of a plan with no refund-linked expense reverses and flags as before`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, day(2027, 7, 5))
        val paidExpenseIds = fx.entries(planId).filter { it.status == "PAID" }.map { it.linkedExpenseId!! }

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        assertTrue("Expected Done, got $result", result is InstallmentOpResult.Done)
        for (id in paidExpenseIds) assertEquals(SpendingExclusionReason.INSTALLMENT_REVERSED.name, fx.flagOf(id))
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    fun `INV-19 D-19 reconcile skips the plan purchase flag on a refund-linked purchase without failing`() = runBlocking {
        val (purchase, deposit) = refundLinkedPurchase()
        val planId = fx.seedPlan(
            uuid = "rl-reconcile-plan",
            kind = "PURCHASE_ATTACHED",
            purchaseExpenseId = purchase,
            entries = listOf(SeedEntry(1))
        )
        val eventsBefore = fx.count("transaction_events")

        val result = coordinator.reconcile()

        assertTrue("reconcile must not fail, got $result", result is InstallmentOpResult.Done)
        assertTrue("no flag batch for a skipped flag", (result as InstallmentOpResult.Done).postCommit.isEmpty())
        assertEquals("a skipped flag is not a repair", 0, result.value.r1Reflagged)
        assertNull("the flag is skipped, not forced", fx.flagOf(purchase))
        assertEquals(100.0, nettedOf(purchase), 0.0)
        assertEquals(100.0, nettedOf(deposit), 0.0)
        assertEquals(1, fx.count("expense_refund_links"))
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals(0, fx.barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-19 D-19 reconcile skips the reversed flag on a refund-linked entry expense without failing`() = runBlocking {
        val (charge, deposit) = run {
            val c = fx.seedCharge(InstallmentCoordinatorFixture.athensMillis(2027, 4, 10), amount = 50.0)
            val d = fx.seedExpense(amount = 20.0, type = TransactionType.DEPOSIT, dateMillis = PURCHASE_MILLIS + 40 * oneDay)
            val linked = fx.tlc.linkRefund(d, c)
            assertTrue("fixture: expected Linked, got $linked", linked is RefundLinkResult.Linked)
            c to d
        }
        val planId = fx.seedPlan(
            uuid = "rl-reconcile-reversed",
            status = "REFUNDED",
            entries = listOf(SeedEntry(1, status = "REVERSED", linkedExpenseId = charge))
        )
        clearMocks(fx.planner, answers = false)

        val result = coordinator.reconcile()

        assertTrue("reconcile must not fail, got $result", result is InstallmentOpResult.Done)
        assertTrue((result as InstallmentOpResult.Done).postCommit.isEmpty())
        assertEquals("a skipped flag is not a repair", 0, result.value.r3Reflagged)
        assertNull(fx.flagOf(charge))
        assertEquals(20.0, nettedOf(charge), 0.0)
        assertEquals("REVERSED", fx.entries(planId).single().status)
        assertEquals(1, fx.count("expense_refund_links"))
        assertEquals(20.0, nettedOf(deposit), 0.0)
    }

    @Test
    fun `INV-19 D-19 control reconcile still re-flags an unlinked plan purchase`() = runBlocking {
        val purchase = fx.seedExpense(amount = 600.0)
        fx.seedPlan(
            uuid = "rl-reconcile-control",
            kind = "PURCHASE_ATTACHED",
            purchaseExpenseId = purchase,
            entries = listOf(SeedEntry(1))
        )

        val result = coordinator.reconcile()

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals(1, (result as InstallmentOpResult.Done).value.r1Reflagged)
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchase))
    }
}
