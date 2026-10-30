package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import dagger.Lazy
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loan pre-enable PE-A, item 3 (past-due proof): a plan declared on a purchase that is about three
 * months old, AUTO_RECORD with paidBeforeTrackingCount = 0, leaves the already-due entries
 * SCHEDULED and the catch-up (`recordDueEntries`, the worker's single call) and the pre-backup
 * reconcile never record an entry whose due date lies before `declaredOn` (INV-12, INV-32: the
 * applicability rule is `declaredOn <= due <= today`). Only a later entry with `declaredOn <= due
 * <= today` is recorded. Real in-memory Room, real TLC. Synthetic data only.
 *
 * Fixture: the purchase is dated 2027-03-10, the plan is 12 x 50.00 EUR, first due 2027-04-10, so
 * entries 1 to 3 fall on 2027-04-10, 05-10, 06-10 (before declaring on 2027-06-12) and entry 4 on
 * 2027-07-10.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorPastDueProofTest {

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

    private fun preBackupReconciler() = InstallmentPreBackupReconciler(
        Lazy { coordinator },
        Lazy { InstallmentPostCommitDispatcher(mockk<PostCommitActionRunner>(relaxed = true)) }
    )

    private fun installmentExpenseDates(): List<Long> =
        fx.db.openHelper.readableDatabase.query(
            "SELECT date FROM expenses WHERE source = 'INSTALLMENT_PLAN' ORDER BY date"
        ).use {
            val out = ArrayList<Long>()
            while (it.moveToNext()) out += it.getLong(0)
            out
        }

    /** Declares the 12 x 50.00 AUTO_RECORD plan on the 2027-03-10 purchase, declared on [declaredOn], k = 0. */
    private suspend fun declareAutoRecord(declaredOn: Long): Long {
        val purchase = fx.seedExpense()
        val done = coordinator.declareOnExpense(
            purchase,
            fx.terms(paidBeforeTracking = 0, declaredOn = declaredOn),
            ATHENS
        ) as InstallmentOpResult.Done
        return done.value.planId
    }

    @Test
    fun `PE-A past-due entries stay SCHEDULED - catch-up and pre-backup reconcile record nothing before declaredOn`() =
        runTest {
            val planId = declareAutoRecord(declaredOn = day(2027, 6, 12))
            // Non-vacuity: three entries really are past due on the declare day, all SCHEDULED, k = 0.
            val entries = fx.entries(planId)
            assertTrue(entries.take(3).all { it.dueDate < day(2027, 6, 12) && it.status == "SCHEDULED" })
            assertTrue(entries.all { it.status == "SCHEDULED" && it.linkedExpenseId == null })
            assertEquals("AUTO_RECORD", fx.planDao.getPlanById(planId)!!.fulfilmentMode)
            val tablesBefore = fx.tableCounts()

            // The catch-up on the declare day itself.
            val sameDay = (coordinator.recordDueEntries(day(2027, 6, 12), ATHENS) as InstallmentOpResult.Done)
            assertEquals(0, sameDay.value.recorded)
            assertTrue(sameDay.postCommit.isEmpty())
            fx.assertTablesUnchanged(tablesBefore)

            // The pre-backup reconcile after it.
            assertEquals(PreBackupReconcileResult.Reconciled, preBackupReconciler().reconcileBeforeBackup())
            fx.assertTablesUnchanged(tablesBefore)
            assertTrue(fx.entries(planId).all { it.status == "SCHEDULED" && !it.autoRecordConsumed })
            assertTrue(installmentExpenseDates().isEmpty())
        }

    @Test
    fun `PE-A only entries with declaredOn at or before the due date and due at or before today are recorded`() =
        runTest {
            val planId = declareAutoRecord(declaredOn = day(2027, 6, 12))

            // Two later entries (07-10 and 08-10) become due; the three past-due entries do not record.
            val later = coordinator.recordDueEntries(day(2027, 8, 12), ATHENS) as InstallmentOpResult.Done
            assertEquals(2, later.value.recorded)
            assertEquals(
                listOf(athensMillis(2027, 7, 10, 12, 0), athensMillis(2027, 8, 10, 12, 0)),
                installmentExpenseDates()
            )
            val entries = fx.entries(planId)
            assertEquals(listOf("SCHEDULED", "SCHEDULED", "SCHEDULED", "PAID", "PAID"), entries.take(5).map { it.status })
            assertTrue(entries.drop(5).all { it.status == "SCHEDULED" })
            assertTrue(entries.take(3).all { it.linkedExpenseId == null && !it.autoRecordConsumed })

            // The pre-backup reconcile after the catch-up creates no expense either.
            assertEquals(PreBackupReconcileResult.Reconciled, preBackupReconciler().reconcileBeforeBackup())
            assertEquals(2, installmentExpenseDates().size)
            assertEquals(listOf("SCHEDULED", "SCHEDULED", "SCHEDULED"), fx.entries(planId).take(3).map { it.status })

            // A repeat on the same day records nothing more.
            val repeat = coordinator.recordDueEntries(day(2027, 8, 12), ATHENS) as InstallmentOpResult.Done
            assertEquals(0, repeat.value.recorded)
            assertEquals(2, installmentExpenseDates().size)
        }

    @Test
    fun `PE-A boundary - an entry due exactly on declaredOn is recorded and the one before it is not`() = runTest {
        val planId = declareAutoRecord(declaredOn = day(2027, 6, 10))

        val done = coordinator.recordDueEntries(day(2027, 6, 10), ATHENS) as InstallmentOpResult.Done

        assertEquals(1, done.value.recorded)
        assertEquals(listOf(athensMillis(2027, 6, 10, 12, 0)), installmentExpenseDates())
        assertEquals(listOf("SCHEDULED", "SCHEDULED", "PAID"), fx.entries(planId).take(3).map { it.status })
    }
}
