package com.yourname.expensetracker.domain.installment.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.SeedEntry
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunnerImpl
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectEventWriter
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionUpdateKind
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loan-installments S6c-1: the TLC post-commit reaction. [TransactionSideEffectPlanner] plans an
 * installment action for deletes and for the money/type update kinds; it calls the plan
 * coordinator, dispatches every `Done.postCommit` batch through the runner TLC uses, and maps the
 * result. Closes AE-11 (delete part), AE-16 (entry fields), AE-20 (category edit), AE-25 and
 * spec 7.6 (a currency or type change unlinks).
 *
 * The end-to-end tests use a real in-memory Room database, the real planner, the real plan
 * coordinator, the real TLC and the real runner (only the event writer and peripheral
 * collaborators are mocked). The mapping tests use a mocked plan coordinator and a recording
 * stand-in for the dispatch runner. Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentExpenseReactionTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var tlc: TransactionLifecycleCoordinator
    private lateinit var runner: PostCommitActionRunner

    private val events = mockk<SideEffectEventWriter>(relaxed = true)
    private val startedNames = CopyOnWriteArrayList<String>()
    private val completedNames = CopyOnWriteArrayList<String>()
    private val failedNames = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking {
        fx = InstallmentCoordinatorFixture.create(ApplicationProvider.getApplicationContext())
        coEvery { events.started(any()) } answers {
            startedNames.add(firstArg<PostCommitAction>().name)
            Unit
        }
        coEvery { events.completed(any()) } answers {
            completedNames.add(firstArg<PostCommitAction>().name)
            Unit
        }
        coEvery { events.failed(any(), any(), any(), any()) } answers {
            failedNames.add(firstArg<PostCommitAction>().name)
            Unit
        }
        runner = PostCommitActionRunnerImpl(events)

        val recurring = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        coEvery { recurring.reconcileExpenseLinkAfterUpdate(any(), any()) } answers {
            RecurringExpenseReconcileResult.NoMatch(firstArg(), "TEST_NO_MATCH")
        }
        coEvery { recurring.unlinkExpenseFromOccurrenceDetailed(any(), any()) } answers {
            RecurringExpenseReconcileResult.Skipped(firstArg(), "TEST_SKIPPED")
        }
        planner = TransactionSideEffectPlanner(
            budgetMonitor = Lazy { mockk<BudgetMonitor>(relaxed = true) },
            anomalyAlertOrchestrator = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = mockk(relaxed = true),
            recurringLifecycleCoordinator = Lazy { recurring },
            expenseDao = fx.db.expenseDao(),
            categoryDao = fx.db.categoryDao(),
            timeProvider = fx.time,
            installmentPlanLifecycleCoordinator = Lazy { coordinator },
            postCommitActionRunner = Lazy { runner }
        )
        val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettings.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        tlc = TransactionLifecycleCoordinator(
            database = fx.db,
            expenseDao = fx.db.expenseDao(),
            transactionEventDao = fx.db.transactionEventDao(),
            timeProvider = fx.time,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = fx.barrier,
            currencySettingsRepository = currencySettings,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            rateEligibilityEvaluator = fx.evaluator
        )
        coordinator = fx.coordinator(lifecycle = tlc)
    }

    @After
    fun tearDown() {
        if (::fx.isInitialized) fx.close()
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun entry(planId: Long, seq: Int) = fx.entries(planId).first { it.seq == seq }

    private fun SideEffectBatchResult.outcomeOf(name: String): SideEffectOutcome =
        outcomes.single { it.name == name }.outcome

    private fun installmentOnly(batch: PostCommitActionBatch): PostCommitActionBatch =
        batch.copy(actions = batch.actions.filter { it.category == SideEffectCategory.INSTALLMENT_PLAN })

    /** Every plan, entry and expense row plus the event count, as comparable text. */
    private fun snapshot(): List<String> =
        listOf("expenses", "installment_plans", "installment_entries").flatMap { table ->
            fx.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                val rows = ArrayList<String>()
                while (cursor.moveToNext()) {
                    rows += table + ":" + (0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "null" }
                }
                rows
            }
        } + "transaction_events=${fx.count("transaction_events")}"

    private fun installmentTables(): List<String> = snapshot().filter { it.startsWith("installment_") }

    private suspend fun purchasePlan(uuid: String, purchase: Long): Long = fx.seedPlan(
        uuid = uuid,
        kind = "PURCHASE_ATTACHED",
        purchaseExpenseId = purchase,
        entries = listOf(SeedEntry(1))
    )

    /** A STANDALONE plan with entry 1 PAID against [charge] and entry 2 SCHEDULED. */
    private suspend fun planWithPaidEntry(uuid: String, charge: Long): Long = fx.seedPlan(
        uuid = uuid,
        entries = listOf(
            SeedEntry(1, status = "PAID", linkedExpenseId = charge),
            SeedEntry(2, dueEpochDay = day(2027, 5, 10))
        )
    )

    // ------------------------------------------------------------------ planning contract (kind table)

    @Test
    fun only_FULL_TYPE_CURRENCY_TRANSFER_DETAILS_and_PAYMENT_CORE_plan_the_installment_reaction() {
        val reacting = setOf(
            TransactionUpdateKind.FULL,
            TransactionUpdateKind.TYPE,
            TransactionUpdateKind.CURRENCY,
            TransactionUpdateKind.TRANSFER_DETAILS,
            TransactionUpdateKind.PAYMENT_CORE
        )
        for (kind in TransactionUpdateKind.entries) {
            val actions = planner.planUpdated(5L, "USER_EDIT", "corr", kind).actions
            val installment = actions.filter { it.category == SideEffectCategory.INSTALLMENT_PLAN }
            if (kind in reacting) {
                assertEquals("[$kind]", listOf("installment_expense_changed"), installment.map { it.name })
                assertEquals("[$kind] target", 5L, installment.single().targetEntityId)
                assertEquals("[$kind] trigger", SideEffectTriggerType.EXPENSE_UPDATED, installment.single().triggerType)
                assertEquals("[$kind] is last", installment.single(), actions.last())
            } else {
                assertTrue("[$kind] must not react", installment.isEmpty())
            }
        }
    }

    @Test
    fun SPENDING_INCLUSION_plans_only_a_budget_check_so_the_coordinators_own_flag_writes_never_loop() {
        val batch = planner.planUpdated(5L, "INSTALLMENT_PLAN", "corr", TransactionUpdateKind.SPENDING_INCLUSION)

        assertEquals(listOf("budget_check"), batch.actions.map { it.name })
    }

    @Test
    fun planCreated_and_planBulkUpdated_plan_no_installment_action_and_planDeleted_plans_exactly_one() {
        val created = planner.planCreated(5L, com.yourname.expensetracker.domain.transaction.ExpenseSource.MANUAL_ENTRY, "corr")
        val bulk = planner.planBulkUpdated("USER_EDIT", 3, "corr")
        val deleted = planner.planDeleted(5L, "USER_ACTION", "corr")

        assertTrue(created.actions.none { it.category == SideEffectCategory.INSTALLMENT_PLAN })
        assertTrue(bulk.actions.none { it.category == SideEffectCategory.INSTALLMENT_PLAN })
        assertEquals(
            listOf("installment_expense_deleted"),
            deleted.actions.filter { it.category == SideEffectCategory.INSTALLMENT_PLAN }.map { it.name }
        )
        assertEquals(SideEffectTriggerType.EXPENSE_DELETED, deleted.actions.last().triggerType)
        assertEquals("expense:5:deleted:installment_deleted", deleted.actions.last().idempotencyKey)
    }

    // ------------------------------------------------------------------ delete reactions

    @Test
    fun AE16_deleting_an_auto_recorded_installment_expense_reopens_its_entry_and_spending_drops() = runTest {
        val first = fx.seedCharge(athensMillis(2027, 4, 10))
        val second = fx.seedCharge(athensMillis(2027, 5, 10))
        val planId = fx.seedPlan(
            uuid = "ae16",
            entries = listOf(
                SeedEntry(1, status = "PAID", linkedExpenseId = first, autoRecordConsumed = true, dueEpochDay = day(2027, 4, 10)),
                SeedEntry(2, status = "PAID", linkedExpenseId = second, autoRecordConsumed = true, dueEpochDay = day(2027, 5, 10)),
                SeedEntry(3, dueEpochDay = day(2027, 6, 10))
            )
        )
        assertEquals(50.0, fx.spending(2027, 5), 0.0)

        val deleted = tlc.deleteExpense(fx.expenseDao.getById(second)!!)

        assertTrue(deleted.isSuccess)
        val reopened = entry(planId, 2)
        assertEquals("SCHEDULED", reopened.status)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, reopened.recordGeneration)
        assertTrue("autoRecordConsumed is unchanged", reopened.autoRecordConsumed)
        assertEquals("PAID", entry(planId, 1).status)
        assertEquals("ACTIVE", fx.planDao.getPlanById(planId)!!.status)
        assertEquals(0.0, fx.spending(2027, 5), 0.0)
        assertEquals(1, startedNames.count { it == "installment_expense_deleted" })
        assertTrue("installment_expense_deleted" in completedNames)
        assertFalse("installment_expense_deleted" in failedNames)
    }

    @Test
    fun AE11_deleting_the_last_installment_expense_of_a_completed_plan_reopens_the_plan() = runTest {
        val first = fx.seedCharge(athensMillis(2027, 4, 10))
        val last = fx.seedCharge(athensMillis(2027, 5, 10))
        val planId = fx.seedPlan(
            uuid = "ae11",
            status = "COMPLETED",
            entries = listOf(
                SeedEntry(1, status = "PAID", linkedExpenseId = first, dueEpochDay = day(2027, 4, 10)),
                SeedEntry(2, status = "PAID", linkedExpenseId = last, autoRecordConsumed = true, dueEpochDay = day(2027, 5, 10))
            )
        )

        val deleted = tlc.deleteExpense(fx.expenseDao.getById(last)!!)

        assertTrue(deleted.isSuccess)
        val reopened = entry(planId, 2)
        assertEquals("SCHEDULED", reopened.status)
        assertNull(reopened.linkedExpenseId)
        assertEquals(1, reopened.recordGeneration)
        assertTrue(reopened.autoRecordConsumed)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("ACTIVE", plan.status)
        assertNull(plan.terminalAt)
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun AE25_deleting_the_purchase_on_the_standard_path_detaches_the_plan_and_changes_no_month() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        val first = fx.seedCharge(athensMillis(2027, 4, 10))
        val second = fx.seedCharge(athensMillis(2027, 5, 10))
        coordinator.linkExpense(fx.entries(planId)[0].id, first)
        coordinator.linkExpense(fx.entries(planId)[1].id, second)
        val entriesBefore = fx.entries(planId)
        val totalsBefore = listOf(3, 4, 5).map { fx.spending(2027, it) }
        assertEquals(listOf(0.0, 50.0, 50.0), totalsBefore)

        val deleted = tlc.deleteExpense(fx.expenseDao.getById(purchase)!!)

        assertTrue(deleted.isSuccess)
        val plan = fx.planDao.getPlanById(planId)!!
        assertEquals("STANDALONE", plan.kind)
        assertNull(plan.purchaseExpenseId)
        assertEquals("ACTIVE", plan.status)
        assertEquals(entriesBefore, fx.entries(planId))
        assertEquals(totalsBefore, listOf(3, 4, 5).map { fx.spending(2027, it) })
        assertTrue("installment_expense_deleted" in completedNames)
    }

    // ------------------------------------------------------------------ update reactions

    @Test
    fun AE20_a_category_edit_of_a_flagged_purchase_leaves_plan_rows_and_events_untouched() = runTest {
        val (planId, purchase) = fx.declareAe1(coordinator)
        val other = fx.db.categoryDao().insert(Category(name = "Other", icon = "O", color = "#112233"))
        val tablesBefore = installmentTables()
        val eventsBefore = fx.eventTypes()
        val flagBefore = fx.flagOf(purchase)

        tlc.updateCategory(purchase, other)

        assertEquals(other, fx.expenseDao.getById(purchase)!!.categoryId)
        assertEquals(tablesBefore, installmentTables())
        assertEquals(flagBefore, fx.flagOf(purchase))
        assertEquals(fx.categoryId, fx.planDao.getPlanById(planId)!!.categoryId)
        // Exactly TLC's own single UPDATED event for the category edit is new; the reaction wrote none.
        assertEquals(listOf("UPDATED"), fx.eventTypes().drop(eventsBefore.size))
        assertTrue(startedNames.none { it.startsWith("installment_") })
    }

    @Test
    fun S76_a_type_change_of_a_paid_entrys_expense_unlinks_the_entry() = runTest {
        val charge = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = planWithPaidEntry("type-change", charge)

        tlc.updateType(charge, TransactionType.DEPOSIT)

        val unlinked = entry(planId, 1)
        assertEquals("SCHEDULED", unlinked.status)
        assertNull(unlinked.linkedExpenseId)
        assertEquals(1, unlinked.recordGeneration)
        assertEquals(1, startedNames.count { it == "installment_expense_changed" })
        assertTrue("installment_expense_changed" in completedNames)
        assertFalse("installment_expense_changed" in failedNames)
    }

    @Test
    fun S76_a_currency_change_of_a_paid_entrys_expense_unlinks_the_entry() = runTest {
        val charge = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = planWithPaidEntry("currency-change", charge)
        fx.exec("UPDATE expenses SET currency = 'USD' WHERE id = $charge")

        val result = runner.run(planner.planUpdated(charge, "USER_EDIT", "corr-currency", TransactionUpdateKind.CURRENCY))

        assertEquals(SideEffectOutcome.Completed, result.outcomeOf("installment_expense_changed"))
        val unlinked = entry(planId, 1)
        assertEquals("SCHEDULED", unlinked.status)
        assertNull(unlinked.linkedExpenseId)
        assertEquals(1, unlinked.recordGeneration)
    }

    @Test
    fun kinds_outside_the_reaction_table_never_touch_a_drifted_entry_and_reconcile_stays_the_backstop() = runTest {
        val charge = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = planWithPaidEntry("outside-table", charge)
        fx.exec("UPDATE expenses SET currency = 'USD' WHERE id = $charge")
        val before = installmentTables()
        val outside = TransactionUpdateKind.entries - setOf(
            TransactionUpdateKind.FULL,
            TransactionUpdateKind.TYPE,
            TransactionUpdateKind.CURRENCY,
            TransactionUpdateKind.TRANSFER_DETAILS,
            TransactionUpdateKind.PAYMENT_CORE
        ).toSet()

        for (kind in outside) {
            runner.run(planner.planUpdated(charge, "USER_EDIT", "corr-$kind", kind))
        }

        assertEquals(before, installmentTables())
        assertEquals("PAID", entry(planId, 1).status)
        assertTrue(startedNames.none { it.startsWith("installment_") })

        val done = coordinator.reconcile() as InstallmentOpResult.Done
        assertEquals(1, done.value.r4Unlinked)
    }

    @Test
    fun an_expense_without_a_plan_produces_no_plan_writes_for_update_and_delete() = runTest {
        val unrelated = fx.seedExpense(merchant = "No Plan Shop")
        val someoneElses = fx.seedCharge(athensMillis(2027, 4, 10))
        planWithPaidEntry("bystander", someoneElses)
        val before = snapshot()

        val updated = runner.run(planner.planUpdated(unrelated, "USER_EDIT", "corr-u", TransactionUpdateKind.FULL))
        val deleted = runner.run(planner.planDeleted(unrelated, "USER_ACTION", "corr-d"))

        assertEquals(
            SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK),
            updated.outcomeOf("installment_expense_changed")
        )
        assertEquals(
            SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK),
            deleted.outcomeOf("installment_expense_deleted")
        )
        assertEquals(before, snapshot())
    }

    // ------------------------------------------------------------------ loop

    @Test
    fun the_reaction_does_not_loop_on_the_coordinators_own_flag_writes() = runTest {
        val purchase = fx.seedExpense()
        purchasePlan("loop", purchase)
        assertNull(fx.flagOf(purchase))
        val batch = planner.planUpdated(purchase, "USER_EDIT", "corr-loop", TransactionUpdateKind.FULL)

        val first = runner.run(batch)

        // R1 re-flagged the purchase through TLC; that flag write planned a SPENDING_INCLUSION
        // batch, which was dispatched (its budget check ran) but did not re-enter the reaction.
        assertEquals("INSTALLMENT_PURCHASE", fx.flagOf(purchase))
        assertEquals(SideEffectOutcome.Completed, first.outcomeOf("installment_expense_changed"))
        assertEquals(1, startedNames.count { it == "installment_expense_changed" })
        assertEquals("top-level budget check plus the dispatched nested batch", 2, startedNames.count { it == "budget_check" })

        val settled = snapshot()
        val second = runner.run(batch)

        // At most one further invocation, and it changes 0 rows and dispatches nothing.
        assertEquals(2, startedNames.count { it == "installment_expense_changed" })
        assertEquals(
            SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK),
            second.outcomeOf("installment_expense_changed")
        )
        assertEquals(settled, snapshot())
        assertEquals(3, startedNames.count { it == "budget_check" })
    }

    // ------------------------------------------------------------------ result mapping (mocked coordinator)

    private class RecordingRunner : PostCommitActionRunner {
        val dispatched = ArrayList<PostCommitActionBatch>()
        var failFor: Set<String> = emptySet()
        var cancelFor: Set<String> = emptySet()

        override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
            dispatched += batch
            if (batch.correlationId in cancelFor) throw CancellationException("synthetic cancellation")
            if (batch.correlationId in failFor) throw IllegalStateException("sentinel-private-detail")
            return SideEffectBatchResult(batch.correlationId, 0, 0, 0, 0, 0, emptyList())
        }
    }

    private fun markerBatch(correlationId: String) = PostCommitActionBatch(
        correlationId,
        listOf(
            PostCommitAction(
                pipeline = AppPipeline.TRANSACTION,
                name = "marker_$correlationId",
                category = SideEffectCategory.BUDGET,
                triggerType = SideEffectTriggerType.EXPENSE_UPDATED,
                targetEntityType = "EXPENSE",
                targetEntityId = 7L,
                source = "test",
                correlationId = correlationId,
                causationId = null,
                idempotencyKey = "marker:$correlationId"
            ) { SideEffectOutcome.Completed }
        )
    )

    private fun plannerWith(
        coordinatorMock: InstallmentPlanLifecycleCoordinator,
        inner: PostCommitActionRunner
    ) = TransactionSideEffectPlanner(
        budgetMonitor = Lazy { mockk<BudgetMonitor>(relaxed = true) },
        anomalyAlertOrchestrator = mockk(relaxed = true),
        merchantCategoryRepository = mockk(relaxed = true),
        merchantNormalizationRepository = mockk(relaxed = true),
        recurringLifecycleCoordinator = Lazy { mockk<RecurringLifecycleCoordinator>(relaxed = true) },
        expenseDao = mockk(relaxed = true),
        categoryDao = mockk(relaxed = true),
        timeProvider = fx.time,
        installmentPlanLifecycleCoordinator = Lazy { coordinatorMock },
        postCommitActionRunner = Lazy { inner }
    )

    private suspend fun changedOutcome(p: TransactionSideEffectPlanner): SideEffectOutcome =
        runner.run(installmentOnly(p.planUpdated(7L, "USER_EDIT", "corr", TransactionUpdateKind.FULL)))
            .outcomes.single().outcome

    private suspend fun deletedOutcome(p: TransactionSideEffectPlanner): SideEffectOutcome =
        runner.run(installmentOnly(p.planDeleted(7L, "USER_ACTION", "corr")))
            .outcomes.single().outcome

    private fun assertBoundedFailure(outcome: SideEffectOutcome, reason: String, errorClass: String?) {
        assertTrue("expected FailedRetryable but was $outcome", outcome is SideEffectOutcome.FailedRetryable)
        outcome as SideEffectOutcome.FailedRetryable
        assertEquals(reason, outcome.reason)
        assertEquals(errorClass, outcome.errorClass)
        assertFalse(outcome.toString().contains("sentinel-private-detail"))
    }

    @Test
    fun a_non_Done_result_is_FailedRetryable_for_changed_and_deleted() = runTest {
        val results = listOf(
            InstallmentOpResult.AlreadyInState(InstallmentReconcileReport()),
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION),
            InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT)
        )
        for (result in results) {
            val mock = mockk<InstallmentPlanLifecycleCoordinator>()
            coEvery { mock.onExpenseChanged(any()) } returns result
            coEvery { mock.onExpenseDeleted(any()) } returns result
            val p = plannerWith(mock, RecordingRunner())

            val changed = changedOutcome(p)
            val deleted = deletedOutcome(p)

            assertBoundedFailure(changed, "installment_reaction_not_done", result.javaClass.simpleName)
            assertBoundedFailure(deleted, "installment_reaction_not_done", result.javaClass.simpleName)
        }
    }

    @Test
    fun plansFailed_or_orphanFlagsFailed_is_FailedRetryable_but_the_committed_batches_are_still_dispatched() = runTest {
        for (report in listOf(InstallmentReconcileReport(plansFailed = 1), InstallmentReconcileReport(orphanFlagsFailed = 1))) {
            val mock = mockk<InstallmentPlanLifecycleCoordinator>()
            coEvery { mock.onExpenseChanged(any()) } returns
                InstallmentOpResult.Done(report, listOf(markerBatch("committed-1"), markerBatch("committed-2")))
            val inner = RecordingRunner()

            val outcome = changedOutcome(plannerWith(mock, inner))

            assertBoundedFailure(outcome, "installment_reaction_reconcile_incomplete", "InstallmentReconcileReport")
            assertEquals(listOf("committed-1", "committed-2"), inner.dispatched.map { it.correlationId })
        }
    }

    @Test
    fun an_exception_is_FailedRetryable_with_the_class_name_only() = runTest {
        val mock = mockk<InstallmentPlanLifecycleCoordinator>()
        coEvery { mock.onExpenseChanged(any()) } throws IllegalStateException("sentinel-private-detail")
        coEvery { mock.onExpenseDeleted(any()) } throws IllegalStateException("sentinel-private-detail")
        val p = plannerWith(mock, RecordingRunner())

        assertBoundedFailure(changedOutcome(p), "installment_reaction_exception", "IllegalStateException")
        assertBoundedFailure(deletedOutcome(p), "installment_reaction_exception", "IllegalStateException")
    }

    @Test
    fun a_closed_write_barrier_is_FailedRetryable_and_writes_nothing() = runTest {
        val charge = fx.seedCharge(athensMillis(2027, 4, 10))
        val planId = planWithPaidEntry("blocked", charge)
        fx.deleteExpenseRow(charge)
        val before = snapshot()
        fx.setMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)

        val outcome = runner.run(installmentOnly(planner.planDeleted(charge, "USER_ACTION", "corr-blocked")))
            .outcomes.single().outcome

        fx.setMode(RestoreMaintenanceMode.Mode.NORMAL)
        assertBoundedFailure(outcome, "installment_reaction_exception", "DatabaseAccessBlockedException")
        assertEquals(before, snapshot())
        assertEquals("PAID", entry(planId, 1).status)
    }

    @Test
    fun a_failing_batch_does_not_stop_the_remaining_batches_and_is_FailedRetryable() = runTest {
        val mock = mockk<InstallmentPlanLifecycleCoordinator>()
        coEvery { mock.onExpenseChanged(any()) } returns InstallmentOpResult.Done(
            InstallmentReconcileReport(r1Reflagged = 1),
            listOf(markerBatch("b1"), markerBatch("b2"), markerBatch("b3"))
        )
        val inner = RecordingRunner().apply { failFor = setOf("b1") }

        val outcome = changedOutcome(plannerWith(mock, inner))

        assertBoundedFailure(outcome, "installment_reaction_dispatch_failed", "IllegalStateException")
        assertEquals(listOf("b1", "b2", "b3"), inner.dispatched.map { it.correlationId })
    }

    @Test
    fun every_Done_batch_is_dispatched_in_order_and_a_changed_report_is_Completed() = runTest {
        val mock = mockk<InstallmentPlanLifecycleCoordinator>()
        coEvery { mock.onExpenseChanged(any()) } returns InstallmentOpResult.Done(
            InstallmentReconcileReport(r4Unlinked = 1),
            listOf(markerBatch("b1"), markerBatch("b2"))
        )
        coEvery { mock.onExpenseDeleted(any()) } returns InstallmentOpResult.Done(InstallmentReconcileReport())
        val inner = RecordingRunner()
        val p = plannerWith(mock, inner)

        assertEquals(SideEffectOutcome.Completed, changedOutcome(p))
        assertEquals(listOf("b1", "b2"), inner.dispatched.map { it.correlationId })
        assertEquals(SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK), deletedOutcome(p))
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun CancellationException_from_the_coordinator_propagates_out_of_the_action() = runTest {
        val mock = mockk<InstallmentPlanLifecycleCoordinator>()
        coEvery { mock.onExpenseChanged(any()) } throws CancellationException("synthetic cancellation")
        coEvery { mock.onExpenseDeleted(any()) } throws CancellationException("synthetic cancellation")
        val p = plannerWith(mock, RecordingRunner())

        try {
            changedOutcome(p)
            fail("CancellationException must propagate out of the changed reaction")
        } catch (expected: CancellationException) {
            // propagated, never mapped to FailedRetryable
        }
        try {
            deletedOutcome(p)
            fail("CancellationException must propagate out of the deleted reaction")
        } catch (expected: CancellationException) {
            // propagated, never mapped to FailedRetryable
        }
    }

    @Test
    fun CancellationException_while_dispatching_a_batch_propagates() = runTest {
        val mock = mockk<InstallmentPlanLifecycleCoordinator>()
        coEvery { mock.onExpenseChanged(any()) } returns InstallmentOpResult.Done(
            InstallmentReconcileReport(r1Reflagged = 1),
            listOf(markerBatch("b1"), markerBatch("b2"))
        )
        val inner = RecordingRunner().apply { cancelFor = setOf("b1") }

        try {
            changedOutcome(plannerWith(mock, inner))
            fail("CancellationException must propagate out of the batch dispatch")
        } catch (expected: CancellationException) {
            // propagated; the second batch is not attempted after cancellation
        }
        assertEquals(listOf("b1"), inner.dispatched.map { it.correlationId })
    }
}
