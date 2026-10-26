package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.InstallmentPlanReadRepositoryImpl
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loan-installments S6b: stop, refund, payoff, edit details, extend, shorten and change due day on
 * a real in-memory Room database with the real TLC and the real write barrier. Oracle: the domain
 * spec (AE-7, AE-8 both answers, AE-9 450 and 440, AE-11 completion, AE-21; INV-2, 6, 9, 19, 20,
 * 21, 23). Expected values are written out from the spec examples. Every operation is also pinned
 * to delete no expense row.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorEndEditTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator

    private val today0705 = day(2027, 7, 5)

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

    /** AE-1 with the first [paid] entries confirmed ("mark paid") as of [today]; returns (planId, purchaseId). */
    private suspend fun ae1WithPaid(paid: Int, today: Long): Pair<Long, Long> {
        val (planId, expenseId) = fx.declareAe1(coordinator)
        val entries = fx.entries(planId)
        for (seq in 1..paid) {
            val result = coordinator.confirmEntry(entries.first { it.seq == seq }.id, today, ATHENS)
            assertTrue(result is InstallmentOpResult.Done)
        }
        return planId to expenseId
    }

    private suspend fun lifetime(): Double {
        var sum = fx.spending(2027, 3)
        for (month in 4..12) sum += fx.spending(2027, month)
        for (month in 1..4) sum += fx.spending(2028, month)
        return sum
    }

    private fun bySeq(entries: List<InstallmentEntry>, seq: Int): InstallmentEntry = entries.first { it.seq == seq && it.kind == "REGULAR" }

    private suspend fun statuses(planId: Long): List<String> = fx.entries(planId).map { it.status }

    private suspend fun financingCost(planId: Long): InstallmentAmount =
        InstallmentPlanReadRepositoryImpl(fx.planDao, fx.facts, fx.time).loadDetail(planId)!!.financingCost

    /** AE-1 with all 12 entries confirmed as of 2028-03-10: the plan is COMPLETED. */
    private suspend fun completedAe1(): Long {
        val (planId, _) = ae1WithPaid(12, day(2028, 3, 10))
        assertEquals("COMPLETED", fx.planDao.getPlanById(planId)!!.status)
        return planId
    }

    private suspend fun standalonePlan(
        count: Int = 6,
        terms: InstallmentPlanTerms = fx.standaloneTerms(count = count)
    ): Long {
        val created = coordinator.createStandalone(terms, fx.categoryId, PaymentMethod.CARD)
        return (created as InstallmentOpResult.Done).value.planId
    }

    // ------------------------------------------------------------------ AE-7 stop

    @Test
    fun `AE-7 stop cancels entries 4 to 12 and keeps paid entries and every expense`() = runBlocking {
        val (planId, purchaseId) = ae1WithPaid(3, today0705)
        val expensesBefore = fx.count("expenses")
        assertEquals(4, expensesBefore)

        val result = coordinator.stop(planId)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.CANCELLED, done.value.planStatus)
        val entries = fx.entries(planId)
        for (seq in 1..3) assertEquals("PAID", bySeq(entries, seq).status)
        for (seq in 4..12) {
            assertEquals("CANCELLED", bySeq(entries, seq).status)
            assertEquals("PLAN_STOPPED", bySeq(entries, seq).statusReason)
        }
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("CANCELLED", plan.status)
        assertEquals("USER_STOP", plan.statusReason)
        assertNotNull(plan.terminalAt)
        assertEquals(expensesBefore, fx.count("expenses"))
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchaseId))
        for (month in 7..12) assertEquals(0.0, fx.spending(2027, month), 0.001)
        assertEquals(150.0, lifetime(), 0.001)
        assertTrue(done.postCommit.isEmpty())
    }

    @Test
    fun `stop retry is AlreadyInState and writes nothing`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        assertTrue(coordinator.stop(planId) is InstallmentOpResult.Done)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)

        val retry = coordinator.stop(planId)

        assertTrue(retry is InstallmentOpResult.AlreadyInState)
        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
    }

    // ------------------------------------------------------------------ AE-8 refund

    @Test
    fun `AE-8 refund not recorded separately reverses paid entries and flags their expenses`() = runBlocking {
        val (planId, purchaseId) = ae1WithPaid(3, today0705)
        val paidExpenseIds = fx.entries(planId).filter { it.status == "PAID" }.map { it.linkedExpenseId!! }
        assertEquals(3, paidExpenseIds.size)
        val expensesBefore = fx.count("expenses")

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.REFUNDED, done.value.planStatus)
        val entries = fx.entries(planId)
        for (seq in 1..3) {
            assertEquals("REVERSED", bySeq(entries, seq).status)
            assertEquals(paidExpenseIds[seq - 1], bySeq(entries, seq).linkedExpenseId)
        }
        for (seq in 4..12) {
            assertEquals("CANCELLED", bySeq(entries, seq).status)
            assertEquals("PLAN_REFUNDED", bySeq(entries, seq).statusReason)
        }
        for (id in paidExpenseIds) assertEquals(SpendingExclusionReason.INSTALLMENT_REVERSED.name, fx.flagOf(id))
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchaseId))
        for (month in 4..6) assertEquals(0.0, fx.spending(2027, month), 0.001)
        assertEquals(0.0, lifetime(), 0.001)
        assertEquals(expensesBefore, fx.count("expenses"))
        assertEquals("REFUNDED", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `AE-8 refund already recorded runs Stop and equals the AE-7 outcome`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)

        val result = coordinator.refund(planId, refundAlreadyRecorded = true)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.CANCELLED, done.value.planStatus)
        val entries = fx.entries(planId)
        for (seq in 1..3) assertEquals("PAID", bySeq(entries, seq).status)
        for (seq in 4..12) assertEquals("PLAN_STOPPED", bySeq(entries, seq).statusReason)
        assertEquals(4, fx.count("expenses"))
        assertEquals(150.0, lifetime(), 0.001)
    }

    @Test
    fun `refund retry on a refunded plan is AlreadyInState and flags are not touched twice`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        assertTrue(coordinator.refund(planId, false) is InstallmentOpResult.Done)
        val eventsBefore = fx.count("transaction_events")

        val retry = coordinator.refund(planId, false)

        assertTrue(retry is InstallmentOpResult.AlreadyInState)
        assertEquals(eventsBefore, fx.count("transaction_events"))
    }

    // ------------------------------------------------------------------ AE-9 payoff

    @Test
    fun `AE-9 payoff with the default amount 450 creates the payoff expense and cancels entries 4 to 12`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val expensesBefore = fx.count("expenses")

        val result = coordinator.payOff(planId, null, today0705, today0705, ATHENS)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.PAID_OFF, done.value.planStatus)
        val entries = fx.entries(planId)
        for (seq in 4..12) {
            assertEquals("CANCELLED", bySeq(entries, seq).status)
            assertEquals("PAID_OFF_EARLY", bySeq(entries, seq).statusReason)
        }
        val payoff = entries.single { it.kind == "PAYOFF" }
        assertEquals(0, payoff.seq)
        assertEquals("PAID", payoff.status)
        assertEquals(450.0, payoff.scheduledAmount, 0.0)
        val expense = fx.expenseDao.getById(payoff.linkedExpenseId!!)!!
        assertEquals(450.0, expense.amount, 0.0)
        assertEquals("EUR", expense.currency)
        assertEquals(athensMillis(2027, 7, 5), expense.date)
        assertEquals(expensesBefore + 1, fx.count("expenses"))
        assertEquals(450.0, fx.spending(2027, 7), 0.001)
        for (month in 8..12) assertEquals(0.0, fx.spending(2027, month), 0.001)
        assertEquals(600.0, lifetime(), 0.001)
        assertEquals(1, done.postCommit.size)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("PAID_OFF", plan.status)
        assertEquals("USER_PAYOFF", plan.statusReason)
        // Financing cost: 3 x 50.00 paid + 450.00 payoff - 600.00 purchase = 0.00 (available, not unavailable).
        assertEquals(InstallmentAmount.Available(0L), financingCost(planId))
    }

    @Test
    fun `AE-9 payoff of 440 gives a lifetime total of 590`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)

        val result = coordinator.payOff(planId, 440.0, today0705, today0705, ATHENS)

        assertTrue(result is InstallmentOpResult.Done)
        val payoff = fx.entries(planId).single { it.kind == "PAYOFF" }
        assertEquals(440.0, fx.expenseDao.getById(payoff.linkedExpenseId!!)!!.amount, 0.0)
        assertEquals(440.0, fx.spending(2027, 7), 0.001)
        assertEquals(590.0, lifetime(), 0.001)
        // Financing cost: 150.00 + 440.00 - 600.00 = -10.00 (negative is returned as is).
        assertEquals(InstallmentAmount.Available(-1000L), financingCost(planId))
    }

    @Test
    fun `payoff retry is AlreadyInState and creates no second expense`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        assertTrue(coordinator.payOff(planId, null, today0705, today0705, ATHENS) is InstallmentOpResult.Done)
        val before = fx.tableCounts()

        val retry = coordinator.payOff(planId, null, today0705, today0705, ATHENS)

        assertTrue(retry is InstallmentOpResult.AlreadyInState)
        fx.assertTablesUnchanged(before)
    }

    @Test
    fun `payoff input refusals write nothing`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)

        // Date after today, date before the latest paid due date (2027-06-10), amount zero.
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE),
            coordinator.payOff(planId, null, today0705 + 1, today0705, ATHENS)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE),
            coordinator.payOff(planId, null, day(2027, 6, 1), today0705, ATHENS)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT),
            coordinator.payOff(planId, 0.0, today0705, today0705, ATHENS)
        )

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
    }

    // ------------------------------------------------------------------ AE-21 shorten with re-spread, AE-11 completion

    @Test
    fun `AE-21 shorten to 5 remaining with re-spread then completion in the same transaction`() = runBlocking {
        val (planId, _) = ae1WithPaid(2, day(2027, 5, 20))
        val expensesBefore = fx.count("expenses")

        val result = coordinator.shorten(planId, remainingCount = 5, respread = true)

        assertTrue(result is InstallmentOpResult.Done)
        assertEquals(expensesBefore, fx.count("expenses"))
        val entries = fx.entries(planId)
        for (seq in 1..2) {
            assertEquals("PAID", bySeq(entries, seq).status)
            assertEquals(50.0, bySeq(entries, seq).scheduledAmount, 0.0)
        }
        val expectedDue = listOf(day(2027, 6, 10), day(2027, 7, 10), day(2027, 8, 10), day(2027, 9, 10), day(2027, 10, 10))
        for ((index, seq) in (3..7).withIndex()) {
            val entry = bySeq(entries, seq)
            assertEquals("SCHEDULED", entry.status)
            assertEquals(100.0, entry.scheduledAmount, 0.0)
            assertEquals(expectedDue[index], entry.dueDate)
            assertEquals(50.0, entry.originalScheduledAmount!!, 0.0)
            assertEquals("RESPREAD", entry.adjustReason)
        }
        for (seq in 8..12) {
            assertEquals("CANCELLED", bySeq(entries, seq).status)
            assertEquals("SHORTENED", bySeq(entries, seq).statusReason)
        }
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)

        // Pay the five remaining entries: the plan completes in the same transaction as the last one (INV-21).
        val lastDay = day(2027, 10, 10)
        for (seq in 3..6) {
            val r = coordinator.confirmEntry(bySeq(entries, seq).id, lastDay, ATHENS) as InstallmentOpResult.Done
            assertEquals(InstallmentPlanStatus.ACTIVE, r.value.planStatus)
        }
        val last = coordinator.confirmEntry(bySeq(entries, 7).id, lastDay, ATHENS) as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.COMPLETED, last.value.planStatus)
        assertEquals("COMPLETED", fx.planDao.getPlanById(planId)!!.status)
        assertEquals(600.0, lifetime(), 0.001)
    }

    @Test
    fun `shorten without re-spread keeps amounts and a no-op shorten is AlreadyInState`() = runBlocking {
        val (planId, _) = ae1WithPaid(2, day(2027, 5, 20))

        val noop = coordinator.shorten(planId, remainingCount = 10, respread = false)
        assertTrue(noop is InstallmentOpResult.AlreadyInState)

        val result = coordinator.shorten(planId, remainingCount = 4, respread = false)
        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        for (seq in 3..6) assertEquals(50.0, bySeq(entries, seq).scheduledAmount, 0.0)
        for (seq in 7..12) assertEquals("SHORTENED", bySeq(entries, seq).statusReason)

        // 0 remaining is Stop's job; more than the SCHEDULED count is refused.
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT), coordinator.shorten(planId, 0, false))
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT), coordinator.shorten(planId, 9, false))
    }

    // ------------------------------------------------------------------ extend / change due day / edit details

    @Test
    fun `extend a fixed payment plan appends entries on the anchor day`() = runBlocking {
        val created = coordinator.createStandalone(fx.standaloneTerms(count = 6), fx.categoryId, PaymentMethod.CARD)
        val planId = (created as InstallmentOpResult.Done).value.planId
        val expensesBefore = fx.count("expenses")

        val result = coordinator.extend(planId, additionalCount = 2, respread = false)

        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        assertEquals(8, entries.size)
        assertEquals(day(2027, 10, 10), bySeq(entries, 7).dueDate)
        assertEquals(day(2027, 11, 10), bySeq(entries, 8).dueDate)
        assertEquals(50.0, bySeq(entries, 7).scheduledAmount, 0.0)
        assertEquals("SCHEDULED", bySeq(entries, 8).status)
        assertEquals(8, fx.planDao.getPlanById(planId)!!.installmentCount)
        assertEquals(expensesBefore, fx.count("expenses"))

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION), coordinator.extend(planId, 2, true))
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT), coordinator.extend(planId, 0, false))
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT), coordinator.extend(planId, 475, false))
    }

    @Test
    fun `extend an equal split plan re-spreads the remaining total over the new count`() = runBlocking {
        val (planId, _) = ae1WithPaid(2, day(2027, 5, 20))

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION), coordinator.extend(planId, 2, false))
        val result = coordinator.extend(planId, additionalCount = 2, respread = true)

        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        val scheduled = entries.filter { it.status == "SCHEDULED" }.sortedBy { it.seq }
        assertEquals(12, scheduled.size)
        // 500.00 over 12: base 41.66, residual 0.08 on the FIRST scheduled entry (INV-9).
        assertEquals(41.74, scheduled.first().scheduledAmount, 0.0)
        for (entry in scheduled.drop(1)) assertEquals(41.66, entry.scheduledAmount, 0.0)
        assertEquals(50000L, scheduled.sumOf { Math.round(it.scheduledAmount * 100) })
        assertEquals(14, bySeq(entries, 14).seq)
        assertEquals(14, fx.planDao.getPlanById(planId)!!.installmentCount)
        for (seq in 1..2) assertEquals(50.0, bySeq(entries, seq).scheduledAmount, 0.0)
    }

    @Test
    fun `change due day recomputes only the scheduled entries from the next unrecorded one`() = runBlocking {
        val (planId, _) = ae1WithPaid(2, day(2027, 5, 20))

        val result = coordinator.changeDueDay(planId, 15)

        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        assertEquals(day(2027, 4, 10), bySeq(entries, 1).dueDate)
        assertEquals(day(2027, 5, 10), bySeq(entries, 2).dueDate)
        assertEquals(day(2027, 6, 15), bySeq(entries, 3).dueDate)
        assertEquals(day(2027, 7, 15), bySeq(entries, 4).dueDate)
        assertEquals(day(2028, 3, 15), bySeq(entries, 12).dueDate)
        for (seq in 3..12) {
            assertEquals(50.0, bySeq(entries, seq).scheduledAmount, 0.0)
            assertEquals("DUE_DAY_CHANGED", bySeq(entries, seq).adjustReason)
        }
        assertEquals(15, fx.planDao.getPlanById(planId)!!.anchorDayOfMonth)
        assertTrue(coordinator.changeDueDay(planId, 15) is InstallmentOpResult.AlreadyInState)
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE), coordinator.changeDueDay(planId, 32))
    }

    @Test
    fun `edit details applies to expenses created afterwards and leaves recorded expenses unchanged`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, day(2027, 7, 10))
        val recordedId = bySeq(fx.entries(planId), 1).linkedExpenseId!!

        val result = coordinator.editDetails(
            planId, "Renamed Plan", "New Lender", fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", day(2027, 7, 10)
        )

        assertTrue(result is InstallmentOpResult.Done)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("Renamed Plan", plan.displayName)
        assertEquals("New Lender", plan.lenderLabel)
        assertEquals("CONFIRM", plan.fulfilmentMode)
        assertEquals("Demo Electronics", fx.expenseDao.getById(recordedId)!!.merchant)
        val next = coordinator.confirmEntry(bySeq(fx.entries(planId), 4).id, day(2027, 7, 10), ATHENS) as InstallmentOpResult.Done
        assertEquals("Renamed Plan", fx.expenseDao.getById(next.value.linkedExpenseId!!)!!.merchant)

        val unchanged = coordinator.editDetails(
            planId, "Renamed Plan", "New Lender", fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", day(2027, 7, 10)
        )
        assertTrue(unchanged is InstallmentOpResult.AlreadyInState)
    }

    @Test
    fun `currency is locked once an entry is paid and editable before`() = runBlocking {
        val (paidPlanId, _) = ae1WithPaid(1, day(2027, 4, 10))
        val locked = coordinator.editDetails(
            paidPlanId, "Demo Electronics", "Synthetic Lender", fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "USD", day(2027, 4, 10)
        )
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.CURRENCY_LOCKED), locked)
        assertEquals("EUR", fx.planDao.getPlanById(paidPlanId)!!.currency)

        val created = coordinator.createStandalone(fx.standaloneTerms(count = 4), fx.categoryId, PaymentMethod.CARD)
        val freshPlanId = (created as InstallmentOpResult.Done).value.planId
        val switched = coordinator.editDetails(
            freshPlanId, "Synthetic Loan", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "USD", today0705
        )
        assertTrue(switched is InstallmentOpResult.Done)
        assertEquals("USD", fx.planDao.getPlanById(freshPlanId)!!.currency)

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.UNSUPPORTED_CURRENCY),
            coordinator.editDetails(freshPlanId, "Synthetic Loan", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "CHF", today0705)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_REQUIRED),
            coordinator.editDetails(freshPlanId, "Synthetic Loan", null, null, InstallmentFulfilmentMode.CONFIRM, "USD", today0705)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND),
            coordinator.editDetails(freshPlanId, "Synthetic Loan", null, 987_654L, InstallmentFulfilmentMode.CONFIRM, "USD", today0705)
        )
    }

    // ------------------------------------------------------------------ terminal plans, purchase gone

    @Test
    fun `terminal plans refuse every end and edit operation`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        assertTrue(coordinator.stop(planId) is InstallmentOpResult.Done)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val illegal = InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)

        assertEquals(illegal, coordinator.refund(planId, false))
        assertEquals(illegal, coordinator.payOff(planId, null, today0705, today0705, ATHENS))
        assertEquals(illegal, coordinator.extend(planId, 1, true))
        assertEquals(illegal, coordinator.shorten(planId, 1, true))
        assertEquals(illegal, coordinator.changeDueDay(planId, 15))
        assertEquals(
            illegal,
            coordinator.editDetails(planId, "X", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", today0705)
        )

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
    }

    @Test
    fun `purchase gone refuses every end and edit operation with nothing written`() = runBlocking {
        val (planId, purchaseId) = ae1WithPaid(3, today0705)
        fx.deleteExpenseRow(purchaseId)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val illegal = InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)

        assertEquals(illegal, coordinator.stop(planId))
        assertEquals(illegal, coordinator.refund(planId, false))
        assertEquals(illegal, coordinator.payOff(planId, null, today0705, today0705, ATHENS))
        assertEquals(illegal, coordinator.extend(planId, 1, true))
        assertEquals(illegal, coordinator.shorten(planId, 5, true))
        assertEquals(illegal, coordinator.changeDueDay(planId, 15))
        assertEquals(
            illegal,
            coordinator.editDetails(planId, "X", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", today0705)
        )

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertNull(fx.expenseDao.getById(purchaseId))
    }

    // ------------------------------------------------------------------ compare-and-set lost, cancellation

    @Test
    fun `lost entry compare-and-set rolls stop back as CONFLICT`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val dao = HookedPlanDao(fx.planDao)
        dao.statusResult = 0

        val result = fx.coordinator(dao = dao).stop(planId)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `lost plan compare-and-set rolls refund back including the expense flags`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val paidExpenseIds = fx.entries(planId).filter { it.status == "PAID" }.map { it.linkedExpenseId!! }
        val statusesBefore = statuses(planId)
        val eventsBefore = fx.count("transaction_events")
        val dao = HookedPlanDao(fx.planDao)
        dao.planStatusResult = 0

        val result = fx.coordinator(dao = dao).refund(planId, false)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals(statusesBefore, statuses(planId))
        for (id in paidExpenseIds) assertNull(fx.flagOf(id))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `lost re-spread compare-and-set rolls shorten back`() = runBlocking {
        val (planId, _) = ae1WithPaid(2, day(2027, 5, 20))
        val statusesBefore = statuses(planId)
        val dao = HookedPlanDao(fx.planDao)
        dao.scheduleResult = 0

        val result = fx.coordinator(dao = dao).shorten(planId, 5, true)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals(50.0, bySeq(fx.entries(planId), 3).scheduledAmount, 0.0)
    }

    @Test
    fun `cancellation propagates from every end and edit operation and nothing is written`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val dao = HookedPlanDao(fx.planDao)
        dao.getPlanByIdHook = { throw CancellationException("test cancellation") }
        val hooked = fx.coordinator(dao = dao)
        val operations: List<suspend () -> Any> = listOf(
            { hooked.stop(planId) },
            { hooked.refund(planId, false) },
            { hooked.payOff(planId, null, today0705, today0705, ATHENS) },
            { hooked.extend(planId, 1, true) },
            { hooked.shorten(planId, 5, true) },
            { hooked.changeDueDay(planId, 15) },
            { hooked.editDetails(planId, "X", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", today0705) }
        )

        for (operation in operations) {
            var cancelled = false
            try {
                operation()
            } catch (e: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
    }

    @Test
    fun `cancellation thrown after the writes rolls stop refund and payoff back`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val paidExpenseIds = fx.entries(planId).filter { it.status == "PAID" }.map { it.linkedExpenseId!! }
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val eventsBefore = fx.count("transaction_events")
        val dao = HookedPlanDao(fx.planDao)
        // The plan move is the last write of each operation: every earlier write is already done.
        dao.planStatusHook = { throw CancellationException("test cancellation") }
        val hooked = fx.coordinator(dao = dao)
        val operations: List<suspend () -> Any> = listOf(
            { hooked.stop(planId) },
            { hooked.refund(planId, true) },
            { hooked.refund(planId, false) },
            { hooked.payOff(planId, null, today0705, today0705, ATHENS) }
        )

        for (operation in operations) {
            var cancelled = false
            try {
                operation()
            } catch (e: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        for (id in paidExpenseIds) assertNull(fx.flagOf(id))
        assertTrue(fx.entries(planId).none { it.kind == "PAYOFF" })
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `lost plan compare-and-set rolls payoff back including the payoff expense and entry`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val eventsBefore = fx.count("transaction_events")
        val dao = HookedPlanDao(fx.planDao)
        dao.planStatusResult = 0

        val result = fx.coordinator(dao = dao).payOff(planId, null, today0705, today0705, ATHENS)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), result)
        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertTrue(fx.entries(planId).none { it.kind == "PAYOFF" })
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `refund with a paid entry whose expense row is gone fails EXPENSE_NOT_FOUND and rolls back`() = runBlocking {
        val (planId, _) = ae1WithPaid(3, today0705)
        val entries = fx.entries(planId)
        val firstExpenseId = bySeq(entries, 1).linkedExpenseId!!
        val secondExpenseId = bySeq(entries, 2).linkedExpenseId!!
        fx.deleteExpenseRow(secondExpenseId)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val eventsBefore = fx.count("transaction_events")

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND), result)
        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        // Entry 1 was reversed and flagged before entry 2 aborted: both are rolled back.
        assertNull(fx.flagOf(firstExpenseId))
        assertEquals("PAID", bySeq(fx.entries(planId), 1).status)
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
    }

    // ------------------------------------------------------------------ COMPLETED plans (D-a, D-b)

    @Test
    fun `a completed plan can be refunded and every installment expense is reversed`() = runBlocking {
        val planId = completedAe1()
        val expensesBefore = fx.count("expenses")

        val result = coordinator.refund(planId, refundAlreadyRecorded = false)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.REFUNDED, done.value.planStatus)
        val entries = fx.entries(planId)
        for (seq in 1..12) {
            assertEquals("REVERSED", bySeq(entries, seq).status)
            assertEquals(SpendingExclusionReason.INSTALLMENT_REVERSED.name, fx.flagOf(bySeq(entries, seq).linkedExpenseId!!))
        }
        assertEquals("REFUNDED", fx.planDao.getPlanById(planId)!!.status)
        assertEquals(expensesBefore, fx.count("expenses"))
        assertEquals(0.0, lifetime(), 0.001)
    }

    @Test
    fun `refund already recorded on a completed plan is AlreadyInState and writes nothing`() = runBlocking {
        val planId = completedAe1()
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val eventsBefore = fx.count("transaction_events")

        val result = coordinator.refund(planId, refundAlreadyRecorded = true)

        assertEquals(
            InstallmentOpResult.AlreadyInState(InstallmentPlanRef(planId, InstallmentPlanStatus.COMPLETED)),
            result
        )
        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals(eventsBefore, fx.count("transaction_events"))
        assertEquals("COMPLETED", fx.planDao.getPlanById(planId)!!.status)
    }

    @Test
    fun `a completed plan refuses stop payoff extend shorten due day and currency or mode edits`() = runBlocking {
        val planId = completedAe1()
        val finalDay = day(2028, 3, 10)
        val before = fx.tableCounts()
        val statusesBefore = statuses(planId)
        val illegal = InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)

        assertEquals(illegal, coordinator.stop(planId))
        assertEquals(illegal, coordinator.payOff(planId, null, finalDay, finalDay, ATHENS))
        assertEquals(illegal, coordinator.extend(planId, 1, true))
        assertEquals(illegal, coordinator.shorten(planId, 1, true))
        assertEquals(illegal, coordinator.changeDueDay(planId, 15))
        // The plan is AUTO_RECORD in EUR: a mode change and a currency change are both ACTIVE-only.
        assertEquals(
            illegal,
            coordinator.editDetails(
                planId, "Demo Electronics", "Synthetic Lender", fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", finalDay
            )
        )
        assertEquals(
            illegal,
            coordinator.editDetails(
                planId, "Demo Electronics", "Synthetic Lender", fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "USD", finalDay
            )
        )

        fx.assertTablesUnchanged(before)
        assertEquals(statusesBefore, statuses(planId))
        assertEquals("AUTO_RECORD", fx.planDao.getPlanById(planId)!!.fulfilmentMode)
        assertEquals("EUR", fx.planDao.getPlanById(planId)!!.currency)
    }

    @Test
    fun `a completed plan accepts a name lender and category edit and stays completed`() = runBlocking {
        val planId = completedAe1()

        val result = coordinator.editDetails(
            planId, "Renamed Plan", "New Lender", fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "EUR", day(2028, 3, 10)
        )

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.COMPLETED, done.value.planStatus)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("Renamed Plan", plan.displayName)
        assertEquals("New Lender", plan.lenderLabel)
        assertEquals("COMPLETED", plan.status)
        assertEquals("AUTO_RECORD", plan.fulfilmentMode)
        assertEquals("EUR", plan.currency)
    }

    // ------------------------------------------------------------------ CONFIRM -> AUTO_RECORD with due entries (D-c)

    @Test
    fun `switching to auto record is refused while a scheduled entry is due until it is skipped`() = runBlocking {
        val planId = standalonePlan()
        val dueDay = day(2027, 4, 10)

        // A name-only edit is not a mode switch: allowed with a due entry.
        val renamed = coordinator.editDetails(
            planId, "Renamed Loan", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", dueDay
        )
        assertTrue(renamed is InstallmentOpResult.Done)

        val refused = coordinator.editDetails(
            planId, "Renamed Loan", null, fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "EUR", dueDay
        )
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION), refused)
        assertEquals("CONFIRM", fx.planDao.getPlanById(planId)!!.fulfilmentMode)

        // The day before the first due date nothing is due yet: allowed (and back again).
        val early = coordinator.editDetails(
            planId, "Renamed Loan", null, fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "EUR", dueDay - 1
        )
        assertTrue(early is InstallmentOpResult.Done)
        assertEquals("AUTO_RECORD", fx.planDao.getPlanById(planId)!!.fulfilmentMode)
        val back = coordinator.editDetails(
            planId, "Renamed Loan", null, fx.categoryId, InstallmentFulfilmentMode.CONFIRM, "EUR", dueDay
        )
        assertTrue(back is InstallmentOpResult.Done)

        // The user skips the due entry; the next one (2027-05-10) is not due on 2027-04-10.
        val skipped = coordinator.skip(bySeq(fx.entries(planId), 1).id, InstallmentStatusReason.USER_SKIP_WAIVED)
        assertTrue(skipped is InstallmentOpResult.Done)
        val allowed = coordinator.editDetails(
            planId, "Renamed Loan", null, fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "EUR", dueDay
        )
        assertTrue(allowed is InstallmentOpResult.Done)
        assertEquals("AUTO_RECORD", fx.planDao.getPlanById(planId)!!.fulfilmentMode)
    }

    // ------------------------------------------------------------------ schedule positions (D-d)

    @Test
    fun `extend after a shorten continues from the last non-cancelled entry with no gap`() = runBlocking {
        val planId = standalonePlan(count = 6)
        assertTrue(coordinator.shorten(planId, remainingCount = 4, respread = false) is InstallmentOpResult.Done)

        val result = coordinator.extend(planId, additionalCount = 2, respread = false)

        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        assertEquals(8, entries.size)
        // Entry 4 is due 2027-07-10, so the new entries (seq 7 and 8, after the cancelled 5 and 6) follow it.
        assertEquals(day(2027, 7, 10), bySeq(entries, 4).dueDate)
        assertEquals(day(2027, 8, 10), bySeq(entries, 7).dueDate)
        assertEquals(day(2027, 9, 10), bySeq(entries, 8).dueDate)
        assertEquals("SCHEDULED", bySeq(entries, 7).status)
        assertEquals("CANCELLED", bySeq(entries, 5).status)
        assertEquals(8, fx.planDao.getPlanById(planId)!!.installmentCount)
    }

    @Test
    fun `change due day before the declaration date is refused and writes nothing`() = runBlocking {
        // Declared 2027-03-12, first due 2027-03-20 (anchor 20).
        val planId = standalonePlan(terms = fx.standaloneTerms(count = 4).copy(firstDueEpochDay = day(2027, 3, 20)))
        val before = fx.tableCounts()
        val duesBefore = fx.entries(planId).map { it.dueDate }

        val refused = coordinator.changeDueDay(planId, 5)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE), refused)
        fx.assertTablesUnchanged(before)
        assertEquals(duesBefore, fx.entries(planId).map { it.dueDate })
        assertEquals(20, fx.planDao.getPlanById(planId)!!.anchorDayOfMonth)

        val allowed = coordinator.changeDueDay(planId, 25)

        assertTrue(allowed is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        assertEquals(day(2027, 3, 25), bySeq(entries, 1).dueDate)
        assertEquals(day(2027, 6, 25), bySeq(entries, 4).dueDate)
        assertEquals(25, fx.planDao.getPlanById(planId)!!.anchorDayOfMonth)
    }

    @Test
    fun `change due day recomputes by position among non-cancelled entries after a shorten and extend`() = runBlocking {
        val planId = standalonePlan(count = 6)
        assertTrue(coordinator.shorten(planId, remainingCount = 4, respread = false) is InstallmentOpResult.Done)
        assertTrue(coordinator.extend(planId, additionalCount = 1, respread = false) is InstallmentOpResult.Done)

        val result = coordinator.changeDueDay(planId, 15)

        assertTrue(result is InstallmentOpResult.Done)
        val entries = fx.entries(planId)
        assertEquals(day(2027, 4, 15), bySeq(entries, 1).dueDate)
        assertEquals(day(2027, 7, 15), bySeq(entries, 4).dueDate)
        // Seq 7 is the fifth non-cancelled entry (the cancelled 5 and 6 take no month).
        assertEquals(day(2027, 8, 15), bySeq(entries, 7).dueDate)
        assertEquals("CANCELLED", bySeq(entries, 5).status)
    }

    @Test
    fun `corrupt stored first due date or anchor day fails closed as CONFLICT without throwing`() = runBlocking {
        val planId = standalonePlan()
        val before = fx.tableCounts()

        fx.exec("UPDATE installment_plans SET anchorDayOfMonth = 99 WHERE id = $planId")
        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), coordinator.extend(planId, 1, false))
        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), coordinator.changeDueDay(planId, 15))

        fx.exec("UPDATE installment_plans SET anchorDayOfMonth = 10, firstDueDate = -1 WHERE id = $planId")
        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), coordinator.extend(planId, 1, false))
        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT), coordinator.changeDueDay(planId, 15))

        fx.assertTablesUnchanged(before)
    }
}
