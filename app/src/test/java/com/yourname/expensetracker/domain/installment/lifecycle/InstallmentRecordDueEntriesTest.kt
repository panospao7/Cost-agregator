package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.SeedEntry
import com.yourname.expensetracker.domain.installment.model.InstallmentDeferralReason
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Loan-installments S7a-1 part B: `recordDueEntries` on a real in-memory Room database with the
 * real transaction lifecycle coordinator and the real candidate probe. Covers AE-1 (catch-up),
 * AE-10a/c, AE-15a/b/c, AE-16 (the latch), AE-17 (candidate deferral only), AE-19 (DST), AE-29
 * (no user-present rate flag, sentinel row with one MISSING_RATE), AE-31 (blank plan name), the
 * reconcile-first rule (D-18), independent per-entry failure and the privacy of the report and the
 * logs (INV-31). Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentRecordDueEntriesTest {

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

    /** Standalone FIXED_PAYMENT 50.00 plan in AUTO_RECORD mode: entry n is due on the 10th of month 3 + n (2027-04-10 first). */
    private suspend fun autoStandalone(
        uuid: String = "auto-uuid",
        count: Int = 6,
        currency: String = "EUR"
    ): Long {
        val done = coordinator.createStandalone(
            fx.standaloneTerms(planUuid = uuid, count = count, mode = InstallmentFulfilmentMode.AUTO_RECORD, currency = currency),
            fx.categoryId,
            PaymentMethod.CARD
        ) as InstallmentOpResult.Done
        return done.value.planId
    }

    private suspend fun entry(planId: Long, seq: Int) = fx.entries(planId).first { it.seq == seq }

    private fun installmentExpenseIds(): List<Long> =
        fx.db.openHelper.readableDatabase.query("SELECT id FROM expenses WHERE source = 'INSTALLMENT_PLAN' ORDER BY id").use {
            val ids = ArrayList<Long>()
            while (it.moveToNext()) ids += it.getLong(0)
            ids
        }

    private suspend fun runDue(
        today: Long,
        c: InstallmentPlanLifecycleCoordinator = coordinator
    ): InstallmentOpResult.Done<InstallmentDueRunReport> {
        val result = c.recordDueEntries(today, ATHENS)
        return result as InstallmentOpResult.Done
    }

    /** Plan DAO whose claim compare-and-set can be scripted per attempt: a forced row count, null for the real one, or a throw. */
    private class ScriptedClaimDao(private val d: InstallmentPlanDao) : InstallmentPlanDao by d {
        var attempts = 0
        var script: (Int) -> Int? = { null }

        override suspend fun claimEntryForExpense(
            entryId: Long,
            expenseId: Long,
            linkOrigin: String,
            markAutoRecordConsumed: Boolean,
            now: Long
        ): Int {
            attempts++
            return script(attempts) ?: d.claimEntryForExpense(entryId, expenseId, linkOrigin, markAutoRecordConsumed, now)
        }
    }

    // ------------------------------------------------------------------ AE-1 catch-up

    @Test
    fun AE1_catch_up_records_each_due_entry_at_noon_local_and_latches_it_and_a_repeat_creates_nothing() = runTest {
        val (planId, _) = fx.declareAe1(coordinator)

        val done = runDue(day(2027, 6, 12))

        assertEquals(3, done.value.recorded)
        assertEquals(3, done.postCommit.size)
        assertTrue(done.value.refused.isEmpty() && done.value.failed.isEmpty() && done.value.deferred.isEmpty())
        for ((seq, month) in listOf(1 to 4, 2 to 5, 3 to 6)) {
            val stored = entry(planId, seq)
            assertEquals("PAID", stored.status)
            assertEquals("AUTO_RECORDED", stored.linkOrigin)
            assertTrue(stored.autoRecordConsumed)
            assertEquals(0, stored.recordGeneration)
            val expense = fx.expenseDao.getById(stored.linkedExpenseId!!)!!
            assertEquals(50.0, expense.amount, 0.0)
            assertEquals("EUR", expense.currency)
            assertEquals("Demo Electronics", expense.merchant)
            assertEquals(athensMillis(2027, month, 10, 12, 0), expense.date)
            assertEquals(fx.categoryId, expense.categoryId)
            assertNull(expense.notes)
            assertNull(expense.spendingExclusionReason)
            assertEquals("INSTALLMENT_PLAN", expense.source)
            assertTrue(expense.dedupeKey!!.endsWith("installment:plan-uuid-1:REGULAR:$seq:0"))
        }
        assertEquals("SCHEDULED", entry(planId, 4).status)
        assertEquals(0.0, fx.spending(2027, 3), 0.0)
        for (month in 4..6) assertEquals(50.0, fx.spending(2027, month), 0.0)

        val repeat = runDue(day(2027, 6, 12))
        assertEquals(0, repeat.value.recorded)
        assertTrue(repeat.postCommit.isEmpty())
        assertEquals(3, installmentExpenseIds().size)

        val nextMonth = runDue(day(2027, 7, 10))
        assertEquals(1, nextMonth.value.recorded)
        assertEquals("PAID", entry(planId, 4).status)
        assertEquals(4, installmentExpenseIds().size)
    }

    // ------------------------------------------------------------------ AE-10

    @Test
    fun AE10a_auto_record_creates_nothing_before_the_due_date_and_catches_up_with_the_due_date_stamp() = runTest {
        val planId = autoStandalone()

        val early = runDue(day(2027, 5, 9))
        assertEquals(1, early.value.recorded)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertEquals(1, installmentExpenseIds().size)

        val late = runDue(day(2027, 5, 13))

        assertEquals(1, late.value.recorded)
        val second = entry(planId, 2)
        assertEquals("PAID", second.status)
        assertEquals(athensMillis(2027, 5, 10, 12, 0), fx.expenseDao.getById(second.linkedExpenseId!!)!!.date)
        assertEquals(50.0, fx.spending(2027, 5), 0.0)
    }

    @Test
    fun AE10c_entries_due_before_the_declaration_date_are_never_auto_recorded() = runTest {
        val planId = fx.seedPlan(
            uuid = "late-declared",
            entries = listOf(
                SeedEntry(1, dueEpochDay = day(2027, 2, 15)),
                SeedEntry(2, dueEpochDay = day(2027, 3, 10)),
                SeedEntry(3, dueEpochDay = day(2027, 4, 10))
            ),
            mode = "AUTO_RECORD"
        )

        val done = runDue(day(2027, 4, 12))

        assertEquals(1, done.value.recorded)
        assertTrue(done.value.refused.isEmpty() && done.value.deferred.isEmpty() && done.value.failed.isEmpty())
        for (seq in 1..2) {
            val stored = entry(planId, seq)
            assertEquals("SCHEDULED", stored.status)
            assertFalse(stored.autoRecordConsumed)
            assertNull(stored.linkedExpenseId)
        }
        assertEquals("PAID", entry(planId, 3).status)
        assertEquals(0.0, fx.spending(2027, 2), 0.0)
        assertEquals(0.0, fx.spending(2027, 3), 0.0)
        assertEquals(50.0, fx.spending(2027, 4), 0.0)
    }

    @Test
    fun confirm_mode_cancelled_and_completed_plans_are_never_touched() = runTest {
        fx.seedPlan("confirm-plan", listOf(SeedEntry(1), SeedEntry(2)), mode = "CONFIRM")
        fx.seedPlan("cancelled-plan", listOf(SeedEntry(1)), status = "CANCELLED", mode = "AUTO_RECORD")
        fx.seedPlan("completed-plan", listOf(SeedEntry(1, status = "SKIPPED")), status = "COMPLETED", mode = "AUTO_RECORD")
        val before = fx.tableCounts()

        val done = runDue(day(2027, 6, 12))

        assertEquals(0, done.value.recorded)
        assertTrue(done.value.refused.isEmpty() && done.value.deferred.isEmpty() && done.value.failed.isEmpty())
        assertTrue(done.postCommit.isEmpty())
        fx.assertTablesUnchanged(before)
    }

    // ------------------------------------------------------------------ AE-15

    @Test
    fun AE15a_a_rolled_back_transaction_leaves_no_expense_and_the_next_run_creates_exactly_one() = runTest {
        val planId = fx.seedPlan(
            uuid = "ae15-uuid",
            entries = listOf(
                SeedEntry(1, status = "PAID_BEFORE_TRACKING", dueEpochDay = day(2027, 4, 10)),
                SeedEntry(2, dueEpochDay = day(2027, 5, 10)),
                SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            ),
            mode = "AUTO_RECORD"
        )
        val dying = ScriptedClaimDao(fx.planDao)
        dying.script = { if (it == 1) 0 else null }

        val first = runDue(day(2027, 5, 12), fx.coordinator(dao = dying))

        assertEquals(mapOf(InstallmentOpFailure.CONFLICT to 1), first.value.failed)
        assertEquals(0, first.value.recorded)
        assertTrue(installmentExpenseIds().isEmpty())
        val untouched = entry(planId, 2)
        assertEquals("SCHEDULED", untouched.status)
        assertFalse(untouched.autoRecordConsumed)

        val next = runDue(day(2027, 5, 12))

        assertEquals(1, next.value.recorded)
        val ids = installmentExpenseIds()
        assertEquals(1, ids.size)
        assertTrue(fx.expenseDao.getById(ids.single())!!.dedupeKey!!.endsWith("installment:ae15-uuid:REGULAR:2:0"))
        assertEquals("PAID", entry(planId, 2).status)
    }

    @Test
    fun AE15b_a_second_run_over_an_already_recorded_entry_creates_neither_a_second_expense_nor_a_second_claim() = runTest {
        val planId = autoStandalone(count = 2)
        val first = runDue(day(2027, 4, 12))
        assertEquals(1, first.value.recorded)

        val second = runDue(day(2027, 4, 12))

        assertEquals(0, second.value.recorded)
        assertTrue(second.value.failed.isEmpty() && second.value.refused.isEmpty() && second.value.deferred.isEmpty())
        assertEquals(1, installmentExpenseIds().size)
        assertEquals("PAID", entry(planId, 1).status)
        assertEquals("SCHEDULED", entry(planId, 2).status)
    }

    @Test
    fun AE15c_cancellation_propagates_keeps_earlier_entries_and_rolls_the_current_one_back() = runTest {
        val planId = autoStandalone()
        val cancelling = ScriptedClaimDao(fx.planDao)
        cancelling.script = { if (it == 2) throw CancellationException("synthetic cancellation") else null }

        try {
            runDue(day(2027, 5, 12), fx.coordinator(dao = cancelling))
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated, never converted to a count
        }

        assertEquals("PAID", entry(planId, 1).status)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertFalse(entry(planId, 2).autoRecordConsumed)
        assertEquals(1, installmentExpenseIds().size)
    }

    @Test
    fun a_failing_entry_rolls_back_alone_is_counted_by_enum_and_the_run_goes_on() = runTest {
        val planId = autoStandalone()
        val flaky = ScriptedClaimDao(fx.planDao)
        flaky.script = { if (it == 1) 0 else null }

        val done = runDue(day(2027, 5, 12), fx.coordinator(dao = flaky))

        assertEquals(mapOf(InstallmentOpFailure.CONFLICT to 1), done.value.failed)
        assertEquals(1, done.value.recorded)
        assertEquals(1, done.postCommit.size)
        assertEquals("SCHEDULED", entry(planId, 1).status)
        assertFalse(entry(planId, 1).autoRecordConsumed)
        assertEquals("PAID", entry(planId, 2).status)
        assertEquals(1, installmentExpenseIds().size)

        val retry = runDue(day(2027, 5, 12))
        assertEquals(1, retry.value.recorded)
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun an_unmapped_exception_of_one_entry_is_contained_and_logs_no_message() = runTest {
        val planId = autoStandalone()
        val throwing = ScriptedClaimDao(fx.planDao)
        throwing.script = { if (it == 1) throw IllegalStateException("sentinel-detail-xyz") else null }
        val tree = CapturingTree()
        tree.plant()
        val done = try {
            runDue(day(2027, 5, 12), fx.coordinator(dao = throwing))
        } finally {
            tree.uproot()
        }

        assertEquals(mapOf(InstallmentOpFailure.RECORD_FAILED to 1), done.value.failed)
        assertEquals(1, done.value.recorded)
        assertEquals("SCHEDULED", entry(planId, 1).status)
        assertEquals("PAID", entry(planId, 2).status)
        assertTrue(tree.lines.none { it.contains("sentinel-detail-xyz") })
        assertTrue(tree.lines.any { it.startsWith("InstallmentDueRun ") && it.contains("failed=1") })
    }

    // ------------------------------------------------------------------ AE-16 latch, reconcile first

    @Test
    fun AE16_a_deleted_auto_recorded_expense_reopens_the_entry_but_no_run_re_creates_it() = runTest {
        val planId = autoStandalone()
        runDue(day(2027, 5, 12))
        val recordedEntry = entry(planId, 2)
        fx.deleteExpenseRow(recordedEntry.linkedExpenseId!!)

        val done = runDue(day(2027, 5, 12))

        assertEquals(1, done.value.reconcile.r2Reopened)
        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        val reopened = entry(planId, 2)
        assertEquals("SCHEDULED", reopened.status)
        assertEquals(1, reopened.recordGeneration)
        assertTrue(reopened.autoRecordConsumed)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, installmentExpenseIds().size)
        assertEquals(0.0, fx.spending(2027, 5), 0.0)

        // A later run (still before entry 3 is due) never re-creates the consumed entry either.
        assertEquals(0, runDue(day(2027, 5, 20)).value.recorded)
        assertEquals("SCHEDULED", entry(planId, 2).status)
        assertEquals(1, installmentExpenseIds().size)

        val skipped = coordinator.skip(reopened.id, InstallmentStatusReason.USER_SKIP_LENDER_HOLIDAY)
        assertTrue(skipped is InstallmentOpResult.Done)
        assertEquals("SKIPPED", entry(planId, 2).status)
    }

    @Test
    fun reconcile_runs_first_and_its_report_and_batches_are_carried_into_the_result() = runTest {
        val (_, purchaseId) = fx.declareAe1(coordinator)
        fx.setFlag(purchaseId, null)

        val done = runDue(day(2027, 4, 12))

        assertEquals(1, done.value.reconcile.r1Reflagged)
        assertEquals(1, done.value.recorded)
        assertEquals(SpendingExclusionReason.INSTALLMENT_PURCHASE.name, fx.flagOf(purchaseId))
        assertEquals("one reconcile batch plus one record batch", 2, done.postCommit.size)
    }

    // ------------------------------------------------------------------ AE-17 candidate deferral

    private suspend fun candidatePlan(): Long = fx.seedPlan(
        uuid = "ae17-uuid",
        entries = listOf(
            SeedEntry(1, status = "PAID_BEFORE_TRACKING", dueEpochDay = day(2027, 4, 10)),
            SeedEntry(2, dueEpochDay = day(2027, 5, 10)),
            SeedEntry(3, dueEpochDay = day(2027, 6, 10))
        ),
        mode = "AUTO_RECORD"
    )

    @Test
    fun AE17_a_captured_charge_in_the_window_defers_the_entry_and_nothing_is_created() = runTest {
        val planId = candidatePlan()
        val captured = fx.seedCharge(athensMillis(2027, 5, 9))
        val before = fx.tableCounts()

        val done = runDue(day(2027, 5, 10))

        assertEquals(mapOf(InstallmentDeferralReason.AUTO_RECORD_DEFERRED_CANDIDATE to 1), done.value.deferred)
        assertEquals(0, done.value.recorded)
        assertTrue(done.postCommit.isEmpty())
        fx.assertTablesUnchanged(before)
        val deferred = entry(planId, 2)
        assertEquals("SCHEDULED", deferred.status)
        assertFalse("a deferral never latches the entry", deferred.autoRecordConsumed)

        val linked = coordinator.linkExpense(deferred.id, captured)
        assertTrue(linked is InstallmentOpResult.Done)
        assertEquals("USER_LINKED", entry(planId, 2).linkOrigin)
        assertEquals(50.0, fx.spending(2027, 5), 0.0)
        assertEquals(0, runDue(day(2027, 5, 10)).value.recorded)
        assertEquals(1, fx.count("expenses"))
    }

    @Test
    fun AE17_a_charge_with_another_amount_does_not_defer() = runTest {
        val planId = candidatePlan()
        fx.seedCharge(athensMillis(2027, 5, 9), amount = 56.0)

        val done = runDue(day(2027, 5, 10))

        assertEquals(1, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty())
        assertEquals("PAID", entry(planId, 2).status)
    }

    @Test
    fun AE17_a_charge_outside_the_window_does_not_defer() = runTest {
        val planId = candidatePlan()
        fx.seedCharge(athensMillis(2027, 5, 1))

        val done = runDue(day(2027, 5, 10))

        assertEquals(1, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty())
        assertEquals("PAID", entry(planId, 2).status)
    }

    // ------------------------------------------------------------------ AE-19 DST

    @Test
    fun AE19_an_entry_due_on_the_dst_start_day_is_stamped_noon_local_and_stays_in_march() = runTest {
        val done = coordinator.createStandalone(
            fx.standaloneTerms(planUuid = "dst-plan", mode = InstallmentFulfilmentMode.AUTO_RECORD)
                .copy(firstDueEpochDay = day(2027, 3, 28)),
            fx.categoryId,
            PaymentMethod.CARD
        ) as InstallmentOpResult.Done
        val planId = done.value.planId

        val run = runDue(day(2027, 3, 28))

        assertEquals(1, run.value.recorded)
        val expense = fx.expenseDao.getById(entry(planId, 1).linkedExpenseId!!)!!
        assertEquals(athensMillis(2027, 3, 28, 12, 0), expense.date)
        assertEquals(50.0, fx.spending(2027, 3), 0.0)
    }

    // ------------------------------------------------------------------ AE-29 no user-present rate flag

    @Test
    fun AE29_the_expense_request_carries_no_user_present_rate_requirement() = runTest {
        autoStandalone()
        val spy = spyk(fx.tlc)
        val captured = slot<CreateExpenseRequest>()

        val done = runDue(day(2027, 4, 12), fx.coordinator(lifecycle = spy))

        assertEquals(1, done.value.recorded)
        coVerify(exactly = 1) { spy.createExpenseDbOnlyV2(capture(captured)) }
        assertNull(captured.captured.userPresentRate)
        // mockk cannot generate any() for the inline CurrencyCode parameter; assert the strict mock was never touched.
        coVerify { fx.evaluator wasNot Called }
    }

    @Test
    fun AE29_a_usd_plan_with_an_empty_rate_table_records_a_sentinel_row_with_one_MISSING_RATE_and_no_refusal() = runTest {
        val planId = fx.seedPlan(
            uuid = "usd-plan",
            entries = listOf(SeedEntry(1, dueEpochDay = day(2027, 4, 10))),
            currency = "USD",
            mode = "AUTO_RECORD"
        )
        val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettings.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val converter = mockk<CurrencyConverter>()
        coEvery { converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns null
        val diagnostics = mockk<DiagnosticEventWriter>(relaxed = true)
        val tlc = TransactionLifecycleCoordinator(
            database = fx.db,
            expenseDao = fx.db.expenseDao(),
            transactionEventDao = fx.db.transactionEventDao(),
            timeProvider = fx.time,
            currencyConverter = converter,
            sideEffectDispatcher = mockk(relaxed = true),
            planner = fx.planner,
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = fx.barrier,
            currencySettingsRepository = currencySettings,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = diagnostics,
            rateEligibilityEvaluator = fx.evaluator
        )

        val done = runDue(day(2027, 4, 12), fx.coordinator(lifecycle = tlc))

        assertEquals(1, done.value.recorded)
        assertTrue(done.value.refused.isEmpty() && done.value.failed.isEmpty())
        val stored = entry(planId, 1)
        assertEquals("PAID", stored.status)
        val expense = fx.expenseDao.getById(stored.linkedExpenseId!!)!!
        assertEquals("USD", expense.currency)
        assertEquals(50.0, expense.amount, 0.0)
        assertEquals(0.0, expense.baseAmount, 0.0)
        assertEquals("", expense.baseCurrency)
        assertEquals(0.0, expense.exchangeRateUsed, 0.0)
        coVerify(exactly = 1) { diagnostics.emit(match { it.reasonCode == DiagnosticReasonCode.MISSING_RATE }) }
        // mockk cannot generate any() for the inline CurrencyCode parameter; assert the strict mock was never touched.
        coVerify { fx.evaluator wasNot Called }
    }

    // ------------------------------------------------------------------ AE-31 blank plan name

    /** ACTIVE AUTO_RECORD EUR plan with entries 3 (2027-05-10) and 4 (2027-06-10) SCHEDULED, 1 and 2 paid before tracking. */
    private suspend fun blankNamePlan(displayName: String, mode: String = "AUTO_RECORD"): Long = fx.seedPlan(
        uuid = "ae31-uuid",
        entries = listOf(
            SeedEntry(1, status = "PAID_BEFORE_TRACKING", dueEpochDay = day(2027, 3, 10)),
            SeedEntry(2, status = "PAID_BEFORE_TRACKING", dueEpochDay = day(2027, 4, 10)),
            SeedEntry(3, dueEpochDay = day(2027, 5, 10)),
            SeedEntry(4, dueEpochDay = day(2027, 6, 10))
        ),
        mode = mode,
        displayName = displayName
    )

    private suspend fun runBlankNameScenario(displayName: String) {
        val planId = blankNamePlan(displayName)
        val today = day(2027, 6, 12)
        val spy = spyk(fx.tlc)
        val c = fx.coordinator(lifecycle = spy)
        val before = fx.tableCounts()
        val eventsBefore = fx.count("transaction_events")

        val blank = runDue(today, c)

        assertEquals(mapOf(InstallmentRefusalCode.PLAN_NAME_REQUIRED to 2), blank.value.refused)
        assertEquals(0, blank.value.recorded)
        assertTrue(blank.value.deferred.isEmpty() && blank.value.failed.isEmpty())
        assertTrue(blank.postCommit.isEmpty())
        coVerify(exactly = 0) { spy.createExpenseDbOnlyV2(any()) }
        fx.assertTablesUnchanged(before)
        assertEquals(eventsBefore, fx.count("transaction_events"))
        for (seq in 3..4) {
            val stored = entry(planId, seq)
            assertEquals("SCHEDULED", stored.status)
            assertFalse(stored.autoRecordConsumed)
            assertEquals(0, stored.recordGeneration)
        }

        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED),
            c.confirmEntry(entry(planId, 3).id, today, ATHENS)
        )
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED),
            c.payOff(planId, null, today, today, ATHENS)
        )
        fx.assertTablesUnchanged(before)

        val captured = fx.seedCharge(athensMillis(2027, 5, 10))
        val linked = c.linkExpense(entry(planId, 3).id, captured)
        assertTrue(linked is InstallmentOpResult.Done)
        assertEquals("PAID", entry(planId, 3).status)
        assertEquals("USER_LINKED", entry(planId, 3).linkOrigin)

        val renamed = c.editDetails(planId, "Laptop", null, fx.categoryId, InstallmentFulfilmentMode.AUTO_RECORD, "EUR", today)
        assertTrue(renamed is InstallmentOpResult.Done)

        val next = runDue(today, c)

        assertEquals(1, next.value.recorded)
        assertTrue(next.value.refused.isEmpty())
        coVerify(exactly = 1) { spy.createExpenseDbOnlyV2(any()) }
        val ids = installmentExpenseIds()
        assertEquals(1, ids.size)
        val expense = fx.expenseDao.getById(ids.single())!!
        assertEquals("Laptop", expense.merchant)
        assertEquals(50.0, expense.amount, 0.0)
        assertEquals("EUR", expense.currency)
        assertEquals(athensMillis(2027, 6, 10, 12, 0), expense.date)
        assertTrue(expense.dedupeKey!!.endsWith("installment:ae31-uuid:REGULAR:4:0"))
        assertEquals("PAID", entry(planId, 4).status)
        assertEquals(50.0, fx.spending(2027, 6), 0.0)
    }

    @Test
    fun AE31_a_blank_plan_name_records_nothing_counts_PLAN_NAME_REQUIRED_and_recovers_after_a_rename() = runTest {
        runBlankNameScenario("")
    }

    @Test
    fun AE31_a_whitespace_plan_name_behaves_like_a_blank_one() = runTest {
        runBlankNameScenario("   ")
    }

    @Test
    fun AE31_a_blank_named_confirm_plan_is_left_alone_and_confirm_is_refused() = runTest {
        val planId = blankNamePlan("", mode = "CONFIRM")
        val before = fx.tableCounts()

        val done = runDue(day(2027, 6, 12))

        assertEquals(0, done.value.recorded)
        assertTrue(done.value.refused.isEmpty() && done.value.deferred.isEmpty() && done.value.failed.isEmpty())
        fx.assertTablesUnchanged(before)
        assertEquals(
            InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED),
            coordinator.confirmEntry(entry(planId, 3).id, day(2027, 6, 12), ATHENS)
        )
    }

    // ------------------------------------------------------------------ probe seam, refusals, closed barrier

    /** Fake candidate probe: counts calls and records whether each call ran inside a Room transaction. */
    private class RecordingProbe(private val db: AppDatabase, private val answer: Boolean = false) :
        InstallmentCandidateChargeProbe {
        var calls = 0
        val inTransactionAtCall = ArrayList<Boolean>()

        override suspend fun hasCandidate(plan: InstallmentPlan, entry: InstallmentEntry, zone: ZoneId): Boolean {
            calls++
            inTransactionAtCall += db.inTransaction()
            return answer
        }
    }

    private fun coordinatorWith(
        probe: InstallmentCandidateChargeProbe,
        dao: InstallmentPlanDao = fx.planDao
    ): InstallmentPlanLifecycleCoordinator =
        InstallmentPlanLifecycleCoordinator(fx.db, dao, fx.tlc, fx.facts, fx.barrier, fx.time, probe)

    /** Plan DAO that runs [onClaim] right after the [closeAfterClaims]-th claim has been applied (inside that entry's transaction). */
    private class ClaimHookDao(
        private val d: InstallmentPlanDao,
        private val closeAfterClaims: Int,
        private val onClaim: () -> Unit
    ) : InstallmentPlanDao by d {
        var claims = 0

        override suspend fun claimEntryForExpense(
            entryId: Long,
            expenseId: Long,
            linkOrigin: String,
            markAutoRecordConsumed: Boolean,
            now: Long
        ): Int {
            val rows = d.claimEntryForExpense(entryId, expenseId, linkOrigin, markAutoRecordConsumed, now)
            claims++
            if (claims == closeAfterClaims) onClaim()
            return rows
        }
    }

    @Test
    fun the_candidate_probe_is_called_inside_the_entry_transaction() = runTest {
        autoStandalone()
        val probe = RecordingProbe(fx.db, answer = false)

        val done = runDue(day(2027, 5, 12), coordinatorWith(probe))

        assertEquals(2, done.value.recorded)
        assertEquals(2, probe.calls)
        assertTrue("every probe call must run inside the Room transaction", probe.inTransactionAtCall.all { it })
    }

    @Test
    fun a_blank_named_plan_with_a_candidate_charge_counts_PLAN_NAME_REQUIRED_and_never_calls_the_probe() = runTest {
        val planId = blankNamePlan("")
        fx.seedCharge(athensMillis(2027, 5, 10))
        val probe = RecordingProbe(fx.db, answer = true)
        val before = fx.tableCounts()
        val eventsBefore = fx.count("transaction_events")

        val done = runDue(day(2027, 6, 12), coordinatorWith(probe))

        assertEquals(mapOf(InstallmentRefusalCode.PLAN_NAME_REQUIRED to 2), done.value.refused)
        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.failed.isEmpty())
        assertTrue(done.postCommit.isEmpty())
        assertEquals("the name check runs before the probe", 0, probe.calls)
        fx.assertTablesUnchanged(before)
        assertEquals(eventsBefore, fx.count("transaction_events"))
        for (seq in 3..4) {
            val stored = entry(planId, seq)
            assertEquals("SCHEDULED", stored.status)
            assertFalse(stored.autoRecordConsumed)
            assertNull(stored.linkedExpenseId)
        }
    }

    @Test
    fun an_entry_due_on_an_unsupported_day_is_refused_INVALID_DATE_and_writes_nothing() = runTest {
        val planId = fx.seedPlan(
            uuid = "far-future",
            entries = listOf(SeedEntry(1, dueEpochDay = day(2201, 1, 1))),
            mode = "AUTO_RECORD"
        )
        val probe = RecordingProbe(fx.db, answer = false)
        val before = fx.tableCounts()

        val done = runDue(day(2201, 2, 1), coordinatorWith(probe))

        assertEquals(mapOf(InstallmentRefusalCode.INVALID_DATE to 1), done.value.refused)
        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.failed.isEmpty())
        assertTrue(done.postCommit.isEmpty())
        assertEquals(1, probe.calls)
        fx.assertTablesUnchanged(before)
        val stored = entry(planId, 1)
        assertEquals("SCHEDULED", stored.status)
        assertFalse(stored.autoRecordConsumed)
        assertNull(stored.linkedExpenseId)
    }

    @Test
    fun a_barrier_that_closes_mid_run_stops_the_run_returns_Done_with_the_batches_and_leaves_the_rest_untouched() = runTest {
        val planId = autoStandalone()
        val closing = ClaimHookDao(fx.planDao, closeAfterClaims = 1) {
            fx.setMode(RestoreMaintenanceMode.Mode.RESTORE_STAGING)
        }
        val probe = RecordingProbe(fx.db, answer = false)
        val tree = CapturingTree()
        tree.plant()

        // Owner approval 39(a): the run does not throw; it returns Done with a bounded marker.
        val done = try {
            runDue(day(2027, 6, 12), coordinatorWith(probe, closing))
        } finally {
            tree.uproot()
        }

        assertTrue(done.value.stoppedByBarrier)
        assertEquals(1, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        assertEquals("the batch of the entry committed before the stop is returned", 1, done.postCommit.size)

        // Entry 1 committed before the barrier closed and stays committed.
        val first = entry(planId, 1)
        assertEquals("PAID", first.status)
        assertTrue(first.autoRecordConsumed)
        assertEquals(1, installmentExpenseIds().size)
        // The run stopped: entries 2 and 3 were neither attempted nor counted as failures.
        assertEquals("only the first entry reached the probe", 1, probe.calls)
        for (seq in 2..3) {
            val untouched = entry(planId, seq)
            assertEquals("SCHEDULED", untouched.status)
            assertFalse(untouched.autoRecordConsumed)
            assertNull(untouched.linkedExpenseId)
        }
        // The log line shows the barrier stop and no per-entry failure count.
        val dueLines = tree.lines.filter { it.startsWith("InstallmentDueRun ") }
        assertEquals(1, dueLines.size)
        assertTrue(dueLines.single(), dueLines.single().contains("recorded=1"))
        assertTrue(dueLines.single(), dueLines.single().contains("stoppedByBarrier=true"))
        assertTrue(dueLines.single(), dueLines.single().contains("failed=0"))

        // Once the barrier reopens, the next run picks up exactly the untouched entries.
        fx.setMode(RestoreMaintenanceMode.Mode.NORMAL)
        val next = runDue(day(2027, 6, 12))
        assertFalse(next.value.stoppedByBarrier)
        assertEquals(2, next.value.recorded)
        assertTrue(next.value.failed.isEmpty() && next.value.refused.isEmpty())
        assertEquals(3, installmentExpenseIds().size)
    }

    @Test
    fun a_barrier_stop_returns_the_reconcile_batches_together_with_those_of_entries_committed_earlier() = runTest {
        val (planId, purchaseId) = fx.declareAe1(coordinator)
        fx.setFlag(purchaseId, null)
        val closing = ClaimHookDao(fx.planDao, closeAfterClaims = 1) {
            fx.setMode(RestoreMaintenanceMode.Mode.RESTORE_STAGING)
        }

        val done = runDue(day(2027, 6, 12), coordinatorWith(RecordingProbe(fx.db, answer = false), closing))

        assertTrue(done.value.stoppedByBarrier)
        assertEquals(1, done.value.reconcile.r1Reflagged)
        assertEquals(1, done.value.recorded)
        assertEquals("one reconcile batch plus the batch of the entry committed before the stop", 2, done.postCommit.size)
        assertEquals("PAID", entry(planId, 1).status)
        assertEquals("SCHEDULED", entry(planId, 2).status)
    }

    // ------------------------------------------------------------------ owner approval 39(b): the latch on every claim

    @Test
    fun owner39b_a_user_confirmed_entry_of_an_auto_record_plan_is_never_re_created_after_its_expense_is_deleted() = runTest {
        val planId = autoStandalone(count = 3)
        val confirmed = coordinator.confirmEntry(entry(planId, 1).id, day(2027, 4, 12), ATHENS)
        assertTrue(confirmed is InstallmentOpResult.Done)
        val claimed = entry(planId, 1)
        assertEquals("USER_CONFIRMED", claimed.linkOrigin)
        assertTrue("a user confirm latches the entry", claimed.autoRecordConsumed)
        fx.deleteExpenseRow(claimed.linkedExpenseId!!)

        val done = runDue(day(2027, 4, 12))

        assertEquals(1, done.value.reconcile.r2Reopened)
        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        val reopened = entry(planId, 1)
        assertEquals("SCHEDULED", reopened.status)
        assertTrue(reopened.autoRecordConsumed)
        assertNull(reopened.linkedExpenseId)
        assertTrue(installmentExpenseIds().isEmpty())
        // The user can still confirm it manually.
        assertTrue(coordinator.confirmEntry(reopened.id, day(2027, 4, 12), ATHENS) is InstallmentOpResult.Done)
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun owner39b_a_user_linked_entry_of_an_auto_record_plan_is_never_re_created_after_its_expense_is_deleted() = runTest {
        val planId = autoStandalone(count = 3)
        val charge = fx.seedCharge(athensMillis(2027, 4, 10))
        val linked = coordinator.linkExpense(entry(planId, 1).id, charge)
        assertTrue(linked is InstallmentOpResult.Done)
        val claimed = entry(planId, 1)
        assertEquals("USER_LINKED", claimed.linkOrigin)
        assertTrue("a user link latches the entry", claimed.autoRecordConsumed)
        fx.deleteExpenseRow(charge)

        val done = runDue(day(2027, 4, 12))

        assertEquals(1, done.value.reconcile.r2Reopened)
        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        val reopened = entry(planId, 1)
        assertEquals("SCHEDULED", reopened.status)
        assertTrue(reopened.autoRecordConsumed)
        assertNull(reopened.linkedExpenseId)
        assertTrue(installmentExpenseIds().isEmpty())
    }

    // ------------------------------------------------------------------ stale pre-filter snapshot (final-gate finding 1)

    /**
     * Plan DAO whose `getEntriesForPlan` returns [snapshot] once armed. It arms itself on the
     * `getPlansByStatus` call, which `recordDueEntries` makes right after its reconcile, so the
     * reconcile reads live rows and only the due-entry pre-filter sees the stale list.
     */
    private class StaleSnapshotDao(
        private val d: InstallmentPlanDao,
        private val snapshot: List<InstallmentEntry>
    ) : InstallmentPlanDao by d {
        private var armed = false

        override suspend fun getPlansByStatus(status: String): List<InstallmentPlan> {
            armed = true
            return d.getPlansByStatus(status)
        }

        override suspend fun getEntriesForPlan(planId: Long): List<InstallmentEntry> =
            if (armed) snapshot else d.getEntriesForPlan(planId)
    }

    @Test
    fun a_stale_snapshot_entry_that_another_run_already_recorded_is_not_recorded_again() = runTest {
        val planId = autoStandalone(count = 3)
        val stale = fx.entries(planId)
        assertEquals(1, runDue(day(2027, 4, 12)).value.recorded)
        assertEquals("PAID", entry(planId, 1).status)
        val expensesBefore = fx.count("expenses")
        val probe = RecordingProbe(fx.db, answer = false)

        val done = runDue(day(2027, 4, 12), coordinatorWith(probe, StaleSnapshotDao(fx.planDao, stale)))

        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        assertEquals(expensesBefore, fx.count("expenses"))
        assertEquals(1, installmentExpenseIds().size)
        assertEquals("the in-transaction re-read rejects the entry before the probe", 0, probe.calls)
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun a_stale_snapshot_entry_in_the_AE16_state_consumed_then_reopened_is_not_re_created() = runTest {
        val planId = autoStandalone(count = 3)
        val stale = fx.entries(planId)
        assertEquals(1, runDue(day(2027, 4, 12)).value.recorded)
        fx.deleteExpenseRow(entry(planId, 1).linkedExpenseId!!)
        assertEquals(1, runDue(day(2027, 4, 12)).value.reconcile.r2Reopened)
        val reopened = entry(planId, 1)
        assertEquals("SCHEDULED", reopened.status)
        assertTrue(reopened.autoRecordConsumed)
        val expensesBefore = fx.count("expenses")
        val probe = RecordingProbe(fx.db, answer = false)

        val done = runDue(day(2027, 4, 12), coordinatorWith(probe, StaleSnapshotDao(fx.planDao, stale)))

        assertEquals(0, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        assertEquals(expensesBefore, fx.count("expenses"))
        assertTrue(installmentExpenseIds().isEmpty())
        assertEquals("the in-transaction re-read rejects the entry before the probe", 0, probe.calls)
        val after = entry(planId, 1)
        assertEquals("SCHEDULED", after.status)
        assertTrue(after.autoRecordConsumed)
        assertNull(after.linkedExpenseId)
    }

    // ------------------------------------------------------------------ reconcile first (D-18, final-gate finding 3)

    @Test
    fun reconcile_first_detaches_a_plan_whose_purchase_was_deleted_so_the_due_entry_still_records() = runTest {
        val (planId, purchaseId) = fx.declareAe1(coordinator)
        val attached = fx.planDao.getPlanById(planId)!!
        assertEquals("PURCHASE_ATTACHED", attached.kind)
        fx.deleteExpenseRow(purchaseId)

        val done = runDue(day(2027, 4, 12))

        // R5 detaches the plan first; recording first would have refused the entry on the gone purchase.
        assertEquals(1, done.value.reconcile.r5Detached)
        assertEquals(1, done.value.recorded)
        assertTrue(done.value.deferred.isEmpty() && done.value.refused.isEmpty() && done.value.failed.isEmpty())
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("STANDALONE", plan.kind)
        assertNull(plan.purchaseExpenseId)
        assertEquals("PAID", entry(planId, 1).status)
        assertEquals(1, installmentExpenseIds().size)
    }

    // ------------------------------------------------------------------ INV-31 privacy

    @Test
    fun the_report_and_the_logs_carry_no_name_amount_date_or_id() = runTest {
        val secretName = "Zorblax Private Loan"
        val planId = fx.seedPlan(
            uuid = "priv-a",
            entries = listOf(
                SeedEntry(1, dueEpochDay = day(2027, 4, 10)),
                SeedEntry(2, dueEpochDay = day(2027, 5, 10)),
                SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            ),
            mode = "AUTO_RECORD",
            displayName = secretName
        )
        fx.seedPlan("priv-b", listOf(SeedEntry(1, dueEpochDay = day(2027, 4, 10))), mode = "AUTO_RECORD", displayName = "")
        fx.seedCharge(athensMillis(2027, 5, 9))
        val flaky = ScriptedClaimDao(fx.planDao)
        flaky.script = { if (it == 1) 0 else null }
        val tree = CapturingTree()
        tree.plant()
        val done = try {
            runDue(day(2027, 6, 12), fx.coordinator(dao = flaky))
        } finally {
            tree.uproot()
        }

        assertEquals(1, done.value.recorded)
        assertEquals(mapOf(InstallmentDeferralReason.AUTO_RECORD_DEFERRED_CANDIDATE to 1), done.value.deferred)
        assertEquals(mapOf(InstallmentRefusalCode.PLAN_NAME_REQUIRED to 1), done.value.refused)
        assertEquals(mapOf(InstallmentOpFailure.CONFLICT to 1), done.value.failed)
        assertEquals("PAID", entry(planId, 3).status)

        val dueLines = tree.lines.filter { it.startsWith("InstallmentDueRun ") }
        assertEquals(1, dueLines.size)
        val shape = Regex("""InstallmentDueRun recorded=\d+ deferred=\d+ refused=\d+ failed=\d+ stoppedByBarrier=(true|false) cause=\w+""")
        assertTrue(dueLines.single(), shape.matches(dueLines.single()))
        val forbidden = listOf(secretName, "Zorblax", "2027", "50.0", "Synthetic Lender Charge", "Synthetic Plan")
        for (line in tree.lines) for (word in forbidden) assertFalse("$word leaked into a log line", line.contains(word))
        val reportText = done.value.toString()
        for (word in forbidden) assertFalse("$word leaked into the report", reportText.contains(word))
    }
}
