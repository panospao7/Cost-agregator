package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.RateEligibility
import com.yourname.expensetracker.domain.currency.RateEligibilityPath
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import com.yourname.expensetracker.domain.currency.RateUnsupportedReason
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loan-installments S6a, test pass 2: declare, create-standalone and delete on a real in-memory
 * Room database with the real TLC and the real write barrier. Oracle: the domain spec (AE-1, AE-5,
 * AE-6, AE-14, AE-15c/d, AE-22, AE-26/27 declaration parts, AE-28, G2-12 refusal pin,
 * INV-1/2/3/21/23/24/27/31/32/33). Expected values are written out from the spec examples.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanLifecycleCoordinatorDeclareTest {

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

    // ------------------------------------------------------------------ AE-1 declaration state

    @Test
    fun AE1_declareOnExpense_creates_active_plan_with_12_scheduled_unlinked_entries_of_50() = runTest {
        val expenseId = fx.seedExpense()

        val result = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS)

        val done = result as InstallmentOpResult.Done
        assertEquals(InstallmentPlanStatus.ACTIVE, done.value.planStatus)
        val plan = fx.planDao.getPlanById(done.value.planId)!!
        assertEquals("PURCHASE_ATTACHED", plan.kind)
        assertEquals(expenseId, plan.purchaseExpenseId)
        assertEquals("ACTIVE", plan.status)
        assertEquals("EUR", plan.currency)
        assertEquals("plan-uuid-1", plan.planUuid)
        assertEquals(day(2027, 3, 12), plan.declaredOnDate)
        val entries = fx.entries(plan.id)
        assertEquals(12, entries.size)
        assertEquals((1..12).toList(), entries.map { it.seq })
        assertTrue(entries.all { it.scheduledAmount == 50.0 })
        assertEquals(60_000L, entries.sumOf { Math.round(it.scheduledAmount * 100) })
        assertEquals(
            (0L until 12L).map { LocalDate.of(2027, 4, 10).plusMonths(it).toEpochDay() },
            entries.map { it.dueDate }
        )
        assertTrue(entries.all { it.status == "SCHEDULED" })
        assertTrue(entries.all { it.linkedExpenseId == null && it.linkOrigin == null })
        assertTrue(entries.all { it.recordGeneration == 0 && !it.autoRecordConsumed })
    }

    @Test
    fun AE1_declareOnExpense_flags_purchase_and_purchase_month_spending_drops_to_zero() = runTest {
        val expenseId = fx.seedExpense()
        assertEquals(600.0, fx.spending(2027, 3), 0.0)

        coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS)

        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(expenseId))
        assertEquals(0.0, fx.spending(2027, 3), 0.0)
        // No installment expense is created by a declaration; E1 is untouched otherwise.
        assertEquals(1, fx.count("expenses"))
        assertEquals(600.0, fx.expenseDao.getById(expenseId)!!.amount, 0.0)
    }

    @Test
    fun AE1_declareOnExpense_flag_goes_through_TLC_one_event_and_returns_one_batch() = runTest {
        val expenseId = fx.seedExpense()

        val done = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS) as InstallmentOpResult.Done

        assertEquals(1, done.postCommit.size)
        val events = fx.db.transactionEventDao().getEventsForExpense(expenseId)
        assertEquals(1, events.size)
        assertEquals("UPDATED", events.single().eventType)
        assertEquals("INSTALLMENT_PURCHASE", events.single().reason)
    }

    @Test
    fun INV33_declareOnExpense_copies_category_and_payment_method_from_the_stored_purchase() = runTest {
        val expenseId = fx.seedExpense(paymentMethod = PaymentMethod.CASH)

        val done = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS) as InstallmentOpResult.Done

        val plan = fx.planDao.getPlanById(done.value.planId)!!
        assertEquals(fx.categoryId, plan.categoryId)
        assertEquals(PaymentMethod.CASH, plan.paymentMethod)
    }

    @Test
    fun INV11_declareOnExpense_stores_exact_two_decimal_values_not_the_raw_input() = runTest {
        val expenseId = fx.seedExpense(amount = 100.0)

        val done = coordinator.declareOnExpense(
            expenseId,
            fx.terms(total = 100.0, count = 3),
            ATHENS
        ) as InstallmentOpResult.Done

        val entries = fx.entries(done.value.planId)
        assertEquals(listOf(33.34, 33.33, 33.33), entries.map { it.scheduledAmount })
        assertEquals(100.0, fx.planDao.getPlanById(done.value.planId)!!.declaredTotal!!, 0.0)
    }

    @Test
    fun AE26_declare_with_k4_marks_first_four_entries_paid_before_tracking_unlinked() = runTest {
        val purchase = fx.seedExpense(
            amount = 1200.0,
            dateMillis = InstallmentCoordinatorFixture.athensMillis(2027, 1, 5, 11, 0),
            merchant = "Demo Furniture"
        )
        val terms = fx.terms(
            total = 1200.0,
            firstDue = day(2027, 2, 5),
            paidBeforeTracking = 4,
            declaredOn = day(2027, 6, 20)
        )

        val done = coordinator.declareOnExpense(purchase, terms, ATHENS) as InstallmentOpResult.Done

        val entries = fx.entries(done.value.planId)
        assertEquals(List(4) { "PAID_BEFORE_TRACKING" } + List(8) { "SCHEDULED" }, entries.map { it.status })
        assertTrue(entries.all { it.scheduledAmount == 100.0 })
        assertTrue(entries.all { it.linkedExpenseId == null })
        assertEquals(1, fx.count("expenses"))
        assertEquals(InstallmentPlanStatus.ACTIVE, done.value.planStatus)
        // 2027-01 drops from 1200.00 to 0.00: the purchase is neutralised, the k entries add nothing.
        assertEquals(0.0, fx.spending(2027, 1), 0.0)
    }

    @Test
    fun AE26_declare_with_k6_is_refused_INVALID_PAID_BEFORE_TRACKING_and_writes_nothing() = runTest {
        val purchase = fx.seedExpense(
            amount = 1200.0,
            dateMillis = InstallmentCoordinatorFixture.athensMillis(2027, 1, 5, 11, 0)
        )
        val before = fx.tableCounts()

        val result = coordinator.declareOnExpense(
            purchase,
            fx.terms(total = 1200.0, firstDue = day(2027, 2, 5), paidBeforeTracking = 6, declaredOn = day(2027, 6, 20)),
            ATHENS
        )

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING), result)
        fx.assertTablesUnchanged(before)
        assertNull(fx.flagOf(purchase))
    }

    @Test
    fun AE27_declare_with_every_entry_paid_before_tracking_commits_the_plan_completed_and_flagged() = runTest {
        val purchase = fx.seedExpense(
            amount = 300.0,
            dateMillis = InstallmentCoordinatorFixture.athensMillis(2027, 1, 5, 11, 0)
        )

        val done = coordinator.declareOnExpense(
            purchase,
            fx.terms(total = 300.0, count = 3, firstDue = day(2027, 2, 5), paidBeforeTracking = 3, declaredOn = day(2027, 6, 20)),
            ATHENS
        ) as InstallmentOpResult.Done

        assertEquals(InstallmentPlanStatus.COMPLETED, done.value.planStatus)
        val plan = fx.planDao.getPlanById(done.value.planId)!!
        assertEquals("COMPLETED", plan.status)
        assertEquals("AUTO_COMPLETED", plan.statusReason)
        assertNotNull(plan.terminalAt)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchase))
        assertEquals(0.0, fx.spending(2027, 1), 0.0)
        assertTrue(fx.entries(plan.id).all { it.status == "PAID_BEFORE_TRACKING" })
    }

    // ------------------------------------------------------------------ AE-6 rollback and retry

    @Test
    fun AE6_failure_between_entries_insert_and_flag_write_persists_nothing_and_retry_succeeds_once() = runTest {
        val expenseId = fx.seedExpense()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.afterInsertEntriesHook = { throw IllegalStateException("synthetic failure after entries insert") }
        val failing = fx.coordinator(dao = hooked)

        try {
            failing.declareOnExpense(expenseId, fx.terms(), ATHENS)
            fail("the injected failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic failure after entries insert", expected.message)
        }

        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(expenseId))
        assertEquals(600.0, fx.spending(2027, 3), 0.0)
        assertEquals(0, fx.db.transactionEventDao().getEventsForExpense(expenseId).size)

        hooked.afterInsertEntriesHook = null
        val retry = failing.declareOnExpense(expenseId, fx.terms(), ATHENS)
        assertTrue(retry is InstallmentOpResult.Done)
        val again = failing.declareOnExpense(expenseId, fx.terms(), ATHENS)
        assertTrue(again is InstallmentOpResult.AlreadyInState)

        assertEquals(1, fx.count("installment_plans"))
        assertEquals(12, fx.count("installment_entries"))
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(expenseId))
        assertEquals(0.0, fx.spending(2027, 3), 0.0)
        assertEquals(1, fx.db.transactionEventDao().getEventsForExpense(expenseId).size)
    }

    @Test
    fun AE6_constraint_raised_by_the_flag_write_maps_to_CONSTRAINT_VIOLATION_without_message_and_rolls_back() = runTest {
        val expenseId = fx.seedExpense()
        fx.exec(
            "CREATE TRIGGER synthetic_fail_flag BEFORE UPDATE OF spendingExclusionReason ON expenses " +
                "BEGIN SELECT RAISE(ABORT, 'sentinel-sql-detail'); END"
        )
        val tree = CapturingTree()
        tree.plant()
        try {
            val result = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS)

            assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.CONSTRAINT_VIOLATION), result)
        } finally {
            tree.uproot()
        }

        assertTrue(tree.lines.none { it.contains("sentinel-sql-detail") })
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(expenseId))
        assertEquals(600.0, fx.spending(2027, 3), 0.0)

        fx.exec("DROP TRIGGER synthetic_fail_flag")
        assertTrue(coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS) is InstallmentOpResult.Done)
        assertEquals(1, fx.count("installment_plans"))
    }

    @Test
    fun AE15c_cancellation_inside_the_declare_transaction_propagates_and_rolls_back() = runTest {
        val expenseId = fx.seedExpense()
        val hooked = HookedPlanDao(fx.planDao)
        hooked.afterInsertEntriesHook = { throw CancellationException("synthetic cancellation") }
        val cancelled = fx.coordinator(dao = hooked)

        try {
            cancelled.declareOnExpense(expenseId, fx.terms(), ATHENS)
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated, never converted to a result
        }

        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(expenseId))
    }

    @Test
    fun barrier_blocked_mode_stops_declare_before_any_read_or_write() = runTest {
        val expenseId = fx.seedExpense()
        val before = fx.tableCounts()
        fx.setMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)

        try {
            coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS)
            fail("a blocked write barrier must refuse the operation")
        } catch (expected: Exception) {
            assertEquals("DatabaseAccessBlockedException", expected.javaClass.simpleName)
        }

        fx.setMode(RestoreMaintenanceMode.Mode.NORMAL)
        fx.assertTablesUnchanged(before)
        assertNull(fx.flagOf(expenseId))
    }

    // ------------------------------------------------------------------ AE-15d idempotency

    @Test
    fun AE15d_same_planUuid_submitted_twice_creates_exactly_one_plan() = runTest {
        val expenseId = fx.seedExpense()

        val first = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS) as InstallmentOpResult.Done
        val second = coordinator.declareOnExpense(expenseId, fx.terms(), ATHENS)

        assertEquals(InstallmentOpResult.AlreadyInState(first.value), second)
        assertEquals(1, fx.count("installment_plans"))
        assertEquals(12, fx.count("installment_entries"))
        assertEquals(1, fx.db.transactionEventDao().getEventsForExpense(expenseId).size)
    }

    @Test
    fun AE15d_replayed_planUuid_on_another_expense_is_ALREADY_DECLARED_and_writes_nothing() = runTest {
        val first = fx.seedExpense()
        val other = fx.seedExpense(merchant = "Other Shop")
        coordinator.declareOnExpense(first, fx.terms(), ATHENS)
        val before = fx.tableCounts()

        val result = coordinator.declareOnExpense(other, fx.terms(), ATHENS)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.ALREADY_DECLARED), result)
        fx.assertTablesUnchanged(before)
        assertNull(fx.flagOf(other))
    }

    // ------------------------------------------------------------------ AE-22 refusals

    private suspend fun assertRefusedNoWrites(
        expenseId: Long,
        terms: InstallmentPlanTerms,
        code: InstallmentRefusalCode,
        zone: ZoneId = ATHENS
    ) {
        val flagBefore = fx.flagOf(expenseId)
        val tablesBefore = fx.tableCounts()
        val eventsBefore = fx.eventTypes().size

        val result = coordinator.declareOnExpense(expenseId, terms, zone)

        assertEquals(InstallmentOpResult.Refused(code), result)
        fx.assertTablesUnchanged(tablesBefore)
        assertEquals(flagBefore, fx.flagOf(expenseId))
        assertEquals(eventsBefore, fx.eventTypes().size)
    }

    @Test
    fun AE22_not_mine_purchase_is_refused_NOT_MINE() = runTest {
        assertRefusedNoWrites(fx.seedExpense(isNotMine = true), fx.terms(), InstallmentRefusalCode.NOT_MINE)
    }

    @Test
    fun AE22_shared_purchase_is_refused_SHARED_NOT_SUPPORTED() = runTest {
        assertRefusedNoWrites(
            fx.seedExpense(isShared = true, myShareAmount = 300.0),
            fx.terms(),
            InstallmentRefusalCode.SHARED_NOT_SUPPORTED
        )
    }

    @Test
    fun AE22_split_purchase_is_refused_SPLIT_NOT_SUPPORTED() = runTest {
        assertRefusedNoWrites(
            fx.seedExpense(splitVisualization = "60/40"),
            fx.terms(),
            InstallmentRefusalCode.SPLIT_NOT_SUPPORTED
        )
    }

    @Test
    fun AE22_income_type_purchase_is_refused_NOT_A_SPENDING_EXPENSE() = runTest {
        assertRefusedNoWrites(
            fx.seedExpense(type = TransactionType.DEPOSIT),
            fx.terms(),
            InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE
        )
    }

    @Test
    fun AE22_second_declaration_of_the_same_purchase_is_refused_ALREADY_DECLARED() = runTest {
        val expenseId = fx.seedExpense()
        coordinator.declareOnExpense(expenseId, fx.terms(planUuid = "first"), ATHENS)

        assertRefusedNoWrites(expenseId, fx.terms(planUuid = "second"), InstallmentRefusalCode.ALREADY_DECLARED)
    }

    @Test
    fun AE22_first_due_before_the_purchase_date_is_refused_FIRST_DUE_BEFORE_PURCHASE() = runTest {
        assertRefusedNoWrites(
            fx.seedExpense(),
            fx.terms(firstDue = day(2027, 3, 9)),
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE
        )
    }

    @Test
    fun AE22_a_single_installment_is_refused_INVALID_COUNT() = runTest {
        assertRefusedNoWrites(fx.seedExpense(), fx.terms(count = 1), InstallmentRefusalCode.INVALID_COUNT)
    }

    @Test
    fun AE22_unsupported_currency_is_refused_UNSUPPORTED_CURRENCY() = runTest {
        assertRefusedNoWrites(fx.seedExpense(), fx.terms(currency = "CHF"), InstallmentRefusalCode.UNSUPPORTED_CURRENCY)
    }

    @Test
    fun AE22_missing_purchase_is_refused_PURCHASE_NOT_FOUND() = runTest {
        val before = fx.tableCounts()

        val result = coordinator.declareOnExpense(9_999L, fx.terms(), ATHENS)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.PURCHASE_NOT_FOUND), result)
        fx.assertTablesUnchanged(before)
    }

    @Test
    fun AE13_purchase_linked_to_a_recurring_occurrence_is_refused_LINKED_ELSEWHERE() = runTest {
        val expenseId = fx.seedExpense()
        fx.linkToOccurrence(expenseId)

        assertRefusedNoWrites(expenseId, fx.terms(), InstallmentRefusalCode.LINKED_ELSEWHERE)
    }

    @Test
    fun AE13_purchase_linked_to_an_installment_entry_is_refused_LINKED_ELSEWHERE() = runTest {
        val (_, expenseId) = fx.declareAe1(coordinator)
        // A second plan cannot take a purchase that already pays an entry of the first.
        val payer = fx.seedCharge(InstallmentCoordinatorFixture.athensMillis(2027, 4, 10))
        val entry = fx.entries(fx.planDao.getPlanByPurchaseExpenseId(expenseId)!!.id).first()
        assertTrue(coordinator.linkExpense(entry.id, payer) is InstallmentOpResult.Done)

        assertRefusedNoWrites(payer, fx.terms(planUuid = "plan-uuid-2"), InstallmentRefusalCode.LINKED_ELSEWHERE)
    }

    @Test
    fun AE5_plan_currency_may_differ_from_the_purchase_currency() = runTest {
        val usdPurchase = fx.seedExpense(amount = 240.0, currency = "USD")

        val done = coordinator.declareOnExpense(
            usdPurchase,
            fx.terms(total = 224.0, count = 4, currency = "EUR"),
            ATHENS
        ) as InstallmentOpResult.Done

        val plan = fx.planDao.getPlanById(done.value.planId)!!
        assertEquals("EUR", plan.currency)
        assertEquals(listOf(56.0, 56.0, 56.0, 56.0), fx.entries(plan.id).map { it.scheduledAmount })
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(usdPurchase))
    }

    // ------------------------------------------------------------------ standalone

    @Test
    fun STANDALONE_createStandalone_is_always_FIXED_PAYMENT_with_no_purchase_flag_or_expense() = runTest {
        // The caller offers EQUAL_SPLIT with a total; the coordinator forces FIXED_PAYMENT.
        val offered = fx.standaloneTerms().copy(splitMethod = InstallmentSplitMethod.EQUAL_SPLIT, declaredTotal = 600.0)

        val done = coordinator.createStandalone(offered, fx.categoryId, PaymentMethod.CARD) as InstallmentOpResult.Done

        val plan = fx.planDao.getPlanById(done.value.planId)!!
        assertEquals("STANDALONE", plan.kind)
        assertNull(plan.purchaseExpenseId)
        assertEquals("FIXED_PAYMENT", plan.splitMethod)
        assertNull(plan.declaredTotal)
        assertEquals(50.0, plan.fixedPaymentAmount!!, 0.0)
        assertEquals(fx.categoryId, plan.categoryId)
        assertEquals(6, fx.entries(plan.id).size)
        assertTrue(fx.entries(plan.id).all { it.scheduledAmount == 50.0 && it.status == "SCHEDULED" })
        assertEquals(0, fx.count("expenses"))
        assertTrue(done.postCommit.isEmpty())
        verify(exactly = 0) { fx.planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun STANDALONE_category_is_required_and_must_exist_after_the_schedule_inputs_are_valid() = runTest {
        val before = fx.tableCounts()

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_REQUIRED),
            coordinator.createStandalone(fx.standaloneTerms(), null, PaymentMethod.CARD)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND),
            coordinator.createStandalone(fx.standaloneTerms(), 987_654L, PaymentMethod.CARD)
        )
        // Spec 7.0 order: a bad schedule input wins over a missing category.
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT),
            coordinator.createStandalone(fx.standaloneTerms(count = 1), null, PaymentMethod.CARD)
        )
        fx.assertTablesUnchanged(before)
    }

    @Test
    fun AE15d_standalone_replay_of_the_same_planUuid_is_AlreadyInState() = runTest {
        val first = coordinator.createStandalone(fx.standaloneTerms(), fx.categoryId, PaymentMethod.CARD)
            as InstallmentOpResult.Done

        val second = coordinator.createStandalone(fx.standaloneTerms(), fx.categoryId, PaymentMethod.CARD)

        assertEquals(InstallmentOpResult.AlreadyInState(first.value), second)
        assertEquals(1, fx.count("installment_plans"))
    }

    // ------------------------------------------------------------------ AE-14 / AE-28 delete

    @Test
    fun AE14_delete_removes_plan_and_entries_unflags_the_purchase_and_restores_its_spending() = runTest {
        val (planId, expenseId) = fx.declareAe1(coordinator)
        assertEquals(0.0, fx.spending(2027, 3), 0.0)

        val result = coordinator.delete(planId)

        val done = result as InstallmentOpResult.Done
        assertEquals(1, done.postCommit.size)
        assertNull(fx.planDao.getPlanById(planId))
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(expenseId))
        assertEquals(600.0, fx.spending(2027, 3), 0.0)
        // INV-23: the expense is never deleted.
        assertEquals(1, fx.count("expenses"))
    }

    @Test
    fun AE14_delete_with_a_paid_entry_is_refused_PLAN_HAS_PAYMENTS_and_changes_nothing() = runTest {
        val (planId, expenseId) = fx.declareAe1(coordinator)
        val payer = fx.seedCharge(InstallmentCoordinatorFixture.athensMillis(2027, 4, 10))
        assertTrue(coordinator.linkExpense(fx.entries(planId).first().id, payer) is InstallmentOpResult.Done)
        val before = fx.tableCounts()

        val result = coordinator.delete(planId)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_HAS_PAYMENTS), result)
        fx.assertTablesUnchanged(before)
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(expenseId))
        assertEquals("PAID", fx.entries(planId).first().status)
    }

    @Test
    fun AE28_delete_is_allowed_with_paid_before_tracking_entries_and_unflags_the_purchase() = runTest {
        val purchase = fx.seedExpense(
            amount = 1200.0,
            dateMillis = InstallmentCoordinatorFixture.athensMillis(2027, 1, 5, 11, 0)
        )
        val done = coordinator.declareOnExpense(
            purchase,
            fx.terms(total = 1200.0, firstDue = day(2027, 2, 5), paidBeforeTracking = 4, declaredOn = day(2027, 6, 20)),
            ATHENS
        ) as InstallmentOpResult.Done
        assertEquals(0.0, fx.spending(2027, 1), 0.0)

        val deleted = coordinator.delete(done.value.planId)

        assertTrue(deleted is InstallmentOpResult.Done)
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        assertNull(fx.flagOf(purchase))
        assertEquals(1200.0, fx.spending(2027, 1), 0.0)
        assertEquals(1, fx.count("expenses"))
    }

    @Test
    fun AE28_delete_of_a_cancelled_plan_without_payments_unflags_the_purchase() = runTest {
        val purchase = fx.seedExpense()
        fx.setFlag(purchase, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        val planId = fx.seedPlan(
            uuid = "cancelled-plan",
            kind = "PURCHASE_ATTACHED",
            status = "CANCELLED",
            purchaseExpenseId = purchase,
            entries = listOf(
                InstallmentCoordinatorFixture.SeedEntry(1, status = "PAID_BEFORE_TRACKING"),
                InstallmentCoordinatorFixture.SeedEntry(2, status = "CANCELLED")
            )
        )

        val result = coordinator.delete(planId)

        assertTrue(result is InstallmentOpResult.Done)
        assertNull(fx.flagOf(purchase))
        assertEquals(0, fx.count("installment_plans"))
    }

    @Test
    fun delete_of_a_standalone_plan_touches_no_expense_and_returns_no_batch() = runTest {
        val planId = (coordinator.createStandalone(fx.standaloneTerms(), fx.categoryId, PaymentMethod.CARD)
            as InstallmentOpResult.Done).value.planId

        val done = coordinator.delete(planId) as InstallmentOpResult.Done

        assertTrue(done.postCommit.isEmpty())
        assertEquals(0, fx.count("installment_plans"))
        verify(exactly = 0) { fx.planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun delete_of_a_missing_plan_fails_PLAN_NOT_FOUND_and_of_a_completed_plan_is_illegal() = runTest {
        assertEquals(
            InstallmentOpResult.Failed(InstallmentOpFailure.PLAN_NOT_FOUND),
            coordinator.delete(424_242L)
        )
        val completed = fx.seedPlan(
            uuid = "completed-plan",
            status = "COMPLETED",
            entries = listOf(InstallmentCoordinatorFixture.SeedEntry(1, status = "PAID_BEFORE_TRACKING"))
        )

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            coordinator.delete(completed)
        )
        assertNotNull(fx.planDao.getPlanById(completed))
    }

    // ------------------------------------------------------------------ declareWithNewExpense

    @Test
    fun INV18_declareWithNewExpense_creates_flagged_purchase_and_plan_in_one_transaction() = runTest {
        val done = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms()) as InstallmentOpResult.Done

        val plan = fx.planDao.getPlanById(done.value.planId)!!
        val purchase = fx.expenseDao.getById(plan.purchaseExpenseId!!)!!
        assertEquals(600.0, purchase.amount, 0.0)
        assertEquals("INSTALLMENT_PURCHASE", purchase.spendingExclusionReason)
        // Provenance: the home-currency purchase stores an identity snapshot.
        assertEquals("EUR", purchase.baseCurrency)
        assertEquals(600.0, purchase.baseAmount, 0.0)
        assertEquals(1.0, purchase.exchangeRateUsed, 0.0)
        assertEquals(fx.categoryId, plan.categoryId)
        assertEquals(PaymentMethod.CARD, plan.paymentMethod)
        assertEquals(12, fx.entries(plan.id).size)
        assertEquals(1, fx.count("expenses"))
        // Created batch plus flag batch, returned for the caller to dispatch after commit.
        assertEquals(2, done.postCommit.size)
        assertEquals(0.0, fx.spending(2027, 3), 0.0)
    }

    @Test
    fun INV18_declareWithNewExpense_passes_the_entry_zone_to_the_rate_evaluation_for_a_foreign_purchase() = runTest {
        coEvery { fx.evaluator.evaluate(CurrencyCode("USD"), CurrencyCode("EUR"), any(), any()) } returns RateEligibility.Eligible(
            rate = 0.5,
            rateDate = LocalDate.of(2027, 3, 9),
            ageDays = 1,
            path = RateEligibilityPath.DIRECT,
            sourceLabel = RateSourceLabel.ECB
        )
        val zone = ZoneId.of("Europe/Athens")

        val done = coordinator.declareWithNewExpense(
            fx.purchaseRequest(amount = 600.0, currency = "USD"),
            zone,
            fx.terms(currency = "EUR")
        ) as InstallmentOpResult.Done

        val purchase = fx.expenseDao.getById(fx.planDao.getPlanById(done.value.planId)!!.purchaseExpenseId!!)!!
        assertEquals("USD", purchase.currency)
        assertEquals("EUR", purchase.baseCurrency)
        assertEquals(300.0, purchase.baseAmount, 0.0)
        assertEquals(0.5, purchase.exchangeRateUsed, 0.0)
        coVerify(exactly = 1) { fx.evaluator.evaluate(CurrencyCode("USD"), CurrencyCode("EUR"), any(), zone) }
    }

    @Test
    fun G2_12_rate_refusal_is_a_normal_return_with_two_audit_events_and_no_expense_plan_or_entry_rows() = runTest {
        coEvery { fx.evaluator.evaluate(CurrencyCode("USD"), CurrencyCode("EUR"), any(), any()) } returns RateEligibility.None
        val tablesBefore = fx.tableCounts()
        val eventsBefore = fx.eventTypes()

        val result = coordinator.declareWithNewExpense(
            fx.purchaseRequest(currency = "USD"),
            ATHENS,
            fx.terms()
        )

        assertEquals(InstallmentOpResult.RateRefused(RateRefusalReason.NO_RATE), result)
        fx.assertTablesUnchanged(tablesBefore)
        val added = fx.eventTypes().drop(eventsBefore.size)
        assertEquals(listOf("CREATE_ATTEMPTED", "CREATE_VALIDATION_FAILED"), added)
        // Empty batch: TLC planned nothing, the plan coordinator planned nothing.
        verify(exactly = 0) { fx.planner.planCreated(any(), any(), any()) }
        verify(exactly = 0) { fx.planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun G2_12_each_refusal_reason_maps_one_to_one() = runTest {
        val cases = listOf(
            RateEligibility.None to RateRefusalReason.NO_RATE,
            RateEligibility.Stale(LocalDate.of(2027, 1, 4), 66) to RateRefusalReason.STALE_RATE,
            RateEligibility.Unsupported(RateUnsupportedReason.NOT_SELECTABLE) to RateRefusalReason.UNSUPPORTED_CURRENCY
        )
        for ((eligibility, reason) in cases) {
            coEvery { fx.evaluator.evaluate(CurrencyCode("USD"), CurrencyCode("EUR"), any(), any()) } returns eligibility
            val before = fx.tableCounts()

            val result = coordinator.declareWithNewExpense(fx.purchaseRequest(currency = "USD"), ATHENS, fx.terms())

            assertTrue("expected RateRefused for $reason but was $result", result is InstallmentOpResult.RateRefused)
            assertEquals(reason, (result as InstallmentOpResult.RateRefused).reason)
            fx.assertTablesUnchanged(before)
        }
    }

    @Test
    fun PURCHASE_DUPLICATE_SKIPPED_is_a_normal_failure_with_the_TLC_duplicate_audit_and_no_rows() = runTest {
        val existing = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms(planUuid = "first"))
        assertTrue(existing is InstallmentOpResult.Done)
        // Remove the first plan so only the captured purchase remains, then replay the same capture.
        val firstPlanId = fx.planDao.getPlanByUuid("first")!!.id
        assertTrue(coordinator.delete(firstPlanId) is InstallmentOpResult.Done)
        val tablesBefore = fx.tableCounts()
        val eventsBefore = fx.eventTypes()

        val result = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms(planUuid = "second"))

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_DUPLICATE_SKIPPED), result)
        fx.assertTablesUnchanged(tablesBefore)
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
        val added = fx.eventTypes().drop(eventsBefore.size)
        assertEquals(2, added.size)
        assertEquals("CREATE_ATTEMPTED", added[0])
        assertTrue("second event must be the duplicate audit but was ${added[1]}", added[1].contains("DUPLICATE"))
        // The existing purchase is not adopted: it stays unflagged.
        val onlyExpense = fx.expenseDao.getTotalCount()
        assertEquals(1, onlyExpense)
    }

    @Test
    fun declareWithNewExpense_precheck_refusal_creates_nothing_and_writes_no_events() = runTest {
        val tablesBefore = fx.tableCounts()
        val eventsBefore = fx.eventTypes()

        val result = coordinator.declareWithNewExpense(fx.purchaseRequest(isNotMine = true), ATHENS, fx.terms())

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.NOT_MINE), result)
        fx.assertTablesUnchanged(tablesBefore)
        assertEquals(eventsBefore, fx.eventTypes())
    }

    @Test
    fun declareWithNewExpense_schedule_refusal_creates_no_purchase() = runTest {
        val tablesBefore = fx.tableCounts()

        val result = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms(firstDue = day(2027, 3, 9)))

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE), result)
        fx.assertTablesUnchanged(tablesBefore)
    }

    @Test
    fun F10_stored_facts_refused_after_create_aborts_and_rolls_the_purchase_back() = runTest {
        val drifting = HookedFactsReader(fx.facts)
        drifting.factsTransform = { _, real -> real?.copy(isNotMine = true) }
        val abortable = fx.coordinator(reader = drifting)

        val result = abortable.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms())

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.NOT_MINE), result)
        assertEquals(0, fx.count("expenses"))
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))
    }

    @Test
    fun declareWithNewExpense_stored_row_missing_after_create_fails_EXPENSE_NOT_FOUND_and_rolls_back() = runTest {
        val vanishing = HookedFactsReader(fx.facts)
        vanishing.factsTransform = { _, _ -> null }
        val abortable = fx.coordinator(reader = vanishing)

        val result = abortable.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms())

        assertEquals(InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND), result)
        assertEquals(0, fx.count("expenses"))
        assertEquals(0, fx.count("installment_plans"))
    }

    @Test
    fun AE6_failure_after_the_purchase_create_rolls_the_purchase_back_too_and_retry_succeeds_once() = runTest {
        val hooked = HookedPlanDao(fx.planDao)
        hooked.afterInsertEntriesHook = { throw IllegalStateException("synthetic failure after entries insert") }
        val failing = fx.coordinator(dao = hooked)

        try {
            failing.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms())
            fail("the injected failure must propagate")
        } catch (expected: IllegalStateException) {
            // rolled back below
        }
        assertEquals(0, fx.count("expenses"))
        assertEquals(0, fx.count("installment_plans"))
        assertEquals(0, fx.count("installment_entries"))

        hooked.afterInsertEntriesHook = null
        assertTrue(failing.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms()) is InstallmentOpResult.Done)
        assertTrue(failing.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms()) is InstallmentOpResult.AlreadyInState)
        assertEquals(1, fx.count("expenses"))
        assertEquals(1, fx.count("installment_plans"))
    }

    @Test
    fun AE15d_declareWithNewExpense_replay_of_the_same_planUuid_creates_no_second_purchase() = runTest {
        val first = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms()) as InstallmentOpResult.Done
        val eventsBefore = fx.eventTypes()

        val second = coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms())

        assertEquals(InstallmentOpResult.AlreadyInState(first.value), second)
        assertEquals(1, fx.count("expenses"))
        assertEquals(1, fx.count("installment_plans"))
        assertEquals(eventsBefore, fx.eventTypes())
        assertFalse(fx.flagOf(fx.planDao.getPlanById(first.value.planId)!!.purchaseExpenseId!!) == null)
    }

    // ------------------------------------------------------------------ owner item 33: blank plan name

    @Test
    fun declareOnExpense_with_an_empty_or_whitespace_name_is_refused_INVALID_VALUE_and_writes_nothing() = runTest {
        val expenseId = fx.seedExpense()

        assertRefusedNoWrites(expenseId, fx.terms(displayName = ""), InstallmentRefusalCode.INVALID_VALUE)
        assertRefusedNoWrites(expenseId, fx.terms(displayName = " \t "), InstallmentRefusalCode.INVALID_VALUE)
        assertNull(fx.flagOf(expenseId))
    }

    @Test
    fun declareWithNewExpense_with_a_blank_name_is_refused_INVALID_VALUE_before_the_purchase_is_created() = runTest {
        val tablesBefore = fx.tableCounts()
        val eventsBefore = fx.eventTypes()

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE),
            coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms(displayName = ""))
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE),
            coordinator.declareWithNewExpense(fx.purchaseRequest(), ATHENS, fx.terms(displayName = "   "))
        )

        fx.assertTablesUnchanged(tablesBefore)
        assertEquals(eventsBefore, fx.eventTypes())
    }

    @Test
    fun createStandalone_with_a_blank_name_is_refused_INVALID_VALUE_and_writes_nothing() = runTest {
        val tablesBefore = fx.tableCounts()

        val empty = coordinator.createStandalone(fx.standaloneTerms().copy(displayName = ""), fx.categoryId, PaymentMethod.CARD)
        val spaces = coordinator.createStandalone(fx.standaloneTerms().copy(displayName = "  \t"), fx.categoryId, PaymentMethod.CARD)

        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE), empty)
        assertEquals(InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE), spaces)
        fx.assertTablesUnchanged(tablesBefore)
    }
}
