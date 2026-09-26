package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunnerImpl
import com.yourname.expensetracker.domain.sideeffect.SideEffectEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.*
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests verifying that targeted lifecycle updates (C1 migration) dispatch side effects.
 *
 * These tests use a real in-memory Room database, side-effect planner and runner,
 * with mocked budget and [RecurringLifecycleCoordinator] dependencies to verify
 * that [TransactionLifecycleCoordinator] dispatches post-update side effects
 * correctly for [updateCategory], [updateMerchant], and [updateType].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionTargetedUpdateSideEffectsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var sideEffectDispatcher: TransactionSideEffectDispatcher
    private lateinit var recurringLifecycleCoordinator: RecurringLifecycleCoordinator
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private lateinit var budgetMonitor: BudgetMonitor
    private lateinit var sideEffectEvents: SideEffectEventWriter
    private var foodCategoryId: Long = 0L
    private var shoppingCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        // ── Seed categories ────────────────────────────────────────────
        foodCategoryId = db.categoryDao().insert(
            Category(name = "Food", icon = "🍕", color = "#FF5733")
        )
        shoppingCategoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "🛒", color = "#33FF57")
        )

        // ── Mocked side-effect dependencies ────────────────────────────
        sideEffectDispatcher = mockk(relaxed = true)
        recurringLifecycleCoordinator = mockk(relaxed = true)
        coEvery { recurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate(any(), any()) } answers {
            RecurringExpenseReconcileResult.NoMatch(firstArg(), "TEST_NO_MATCH")
        }
        budgetMonitor = mockk(relaxed = true)
        every { budgetMonitor.checkBudgets() } answers {
            assertFalse("Budget checks must run after the Room transaction commits", db.inTransaction())
        }
        sideEffectEvents = mockk(relaxed = true)
        val planner = TransactionSideEffectPlanner(
            budgetMonitor = dagger.Lazy { budgetMonitor },
            anomalyAlertOrchestrator = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = mockk(relaxed = true),
            recurringLifecycleCoordinator = dagger.Lazy { recurringLifecycleCoordinator },
            expenseDao = db.expenseDao(),
            categoryDao = db.categoryDao(),
            timeProvider = timeProvider
        )
        val maintenanceMode = mockk<RestoreMaintenanceMode> {
            every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            every { isWritesAllowed() } returns true
        }

        // ── Build coordinator with real DB + mocked side effects ───────
        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = sideEffectDispatcher,
            planner = planner,
            runner = PostCommitActionRunnerImpl(sideEffectEvents),
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            currencySettingsRepository = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun assertBudgetCheckedAfterUpdate(expenseId: Long) {
        verify(exactly = 1) { budgetMonitor.checkBudgets() }
        coVerify(exactly = 1) {
            sideEffectEvents.completed(match { it.name == "budget_check" && it.targetEntityId == expenseId })
        }
        coVerify(exactly = 0) { sideEffectEvents.failed(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: updateCategory dispatches budget side effect
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateCategory dispatches budget side effect`() = runTest {
        // GIVEN: categories "Food", "Shopping" (seeded in setUp)

        // AND: a monthly Food budget of €100
        db.budgetDao().insert(
            Budget(
                categoryId = foodCategoryId,
                amount = 100.0,
                period = BudgetPeriod.MONTHLY,
                startDate = timeProvider.now(),
                currency = "EUR",
                activeCategoryKey = foodCategoryId,
                createdAt = timeProvider.now()
            )
        )

        // AND: an expense of €50 in Food category
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 50.0,
                currency = "EUR",
                merchant = "Some Store",
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                categoryId = foodCategoryId,
                source = "scenario_test",
                createdAt = timeProvider.now()
            )
        )
        assertTrue("Expense ID should be positive", expenseId > 0L)

        // WHEN: calling coordinator.updateCategory(expenseId, shoppingCategoryId)
        coordinator.updateCategory(expenseId, shoppingCategoryId)

        // The real planner/runner must perform the budget check after commit.
        assertBudgetCheckedAfterUpdate(expenseId)

        // AND: expense categoryId changed to Shopping in DB
        val updatedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist in DB", updatedExpense)
        assertEquals(
            "Category should be Shopping",
            shoppingCategoryId,
            updatedExpense!!.categoryId
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: updateMerchant dispatches side effects and recurring reconciliation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateMerchant dispatches side effects and recurring reconciliation`() = runTest {
        // GIVEN: an expense with merchant "OldName"
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 25.0,
                currency = "EUR",
                merchant = "OldName",
                merchantKey = "old_merchant_key",
                dedupeKey = "old_dedupe_key",
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                categoryId = foodCategoryId,
                source = "scenario_test",
                createdAt = timeProvider.now()
            )
        )
        assertTrue("Expense ID should be positive", expenseId > 0L)

        // Capture original key values for before/after comparison
        val originalExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Original expense should exist", originalExpense)
        val originalMerchantKey = originalExpense!!.merchantKey
        val originalDedupeKey = originalExpense.dedupeKey

        // WHEN: calling coordinator.updateMerchant(expenseId, "NewName")
        coordinator.updateMerchant(expenseId, "NewName")

        // THEN: the planner's budget action completed after the DB transaction
        assertBudgetCheckedAfterUpdate(expenseId)

        // AND: merchant changed to "NewName" in DB
        val updatedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Updated expense should exist", updatedExpense)
        assertEquals("Merchant should be NewName", "NewName", updatedExpense!!.merchant)

        // AND: merchantKey + dedupeKey regenerated
        assertNotNull("merchantKey should not be null", updatedExpense.merchantKey)
        assertNotNull("dedupeKey should not be null", updatedExpense.dedupeKey)
        assertTrue(
            "merchantKey should differ from original: $originalMerchantKey",
            originalMerchantKey == null || updatedExpense.merchantKey != originalMerchantKey
        )
        assertTrue(
            "dedupeKey should differ from original: $originalDedupeKey",
            originalDedupeKey == null || updatedExpense.dedupeKey != originalDedupeKey
        )

        // AND: recurring reconciliation was triggered
        coVerify(exactly = 1) {
            recurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate(expenseId, "transaction_update:USER_EDIT")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: updateType dispatches side effects
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateType dispatches side effects`() = runTest {
        // GIVEN: a PURCHASE expense
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 30.0,
                currency = "EUR",
                merchant = "Some Store",
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                categoryId = foodCategoryId,
                source = "scenario_test",
                createdAt = timeProvider.now()
            )
        )
        assertTrue("Expense ID should be positive", expenseId > 0L)

        // WHEN: calling coordinator.updateType(expenseId, DEPOSIT)
        coordinator.updateType(expenseId, TransactionType.DEPOSIT)

        // THEN: the planner's budget action completed after the DB transaction
        assertBudgetCheckedAfterUpdate(expenseId)

        // AND: transactionType changed to DEPOSIT in DB
        val updatedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Updated expense should exist", updatedExpense)
        assertEquals(
            "TransactionType should be DEPOSIT",
            TransactionType.DEPOSIT,
            updatedExpense!!.transactionType
        )

        // AND: recurring reconciliation is triggered for type changes
        coVerify(exactly = 1) {
            recurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate(expenseId, "transaction_update:USER_EDIT")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: category DB state changes after update
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `category DB state changes after update`() = runTest {
        // GIVEN: categories seeded via ScenarioSeeder
        val currentTime = timeProvider.now()
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 50.0,
                    merchant = "Some Store",
                    categoryName = "Food",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Expense in Food category, then updateCategory to Shopping"
        )

        val seeder = ScenarioSeeder(db)
        val seedResult = seeder.seedState(seed)
        assertTrue("Expense should have been seeded", seedResult.expenseIds.isNotEmpty())
        val expenseId = seedResult.expenseIds.single()
        val shoppingId = seedResult.categoryIds["Shopping"]
        assertNotNull("Shopping category ID should exist", shoppingId)

        // Verify initial state: expense categoryId = Food (not Shopping)
        val expenseBefore = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist before update", expenseBefore)
        assertEquals(
            "Initial category should be Food",
            seedResult.categoryIds["Food"],
            expenseBefore!!.categoryId
        )

        // WHEN: calling updateCategory to change to Shopping
        coordinator.updateCategory(expenseId, shoppingId)
        assertBudgetCheckedAfterUpdate(expenseId)

        // THEN: DB query shows categoryId = Shopping ID
        val expenseAfter = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist after update", expenseAfter)
        assertEquals(
            "Category should now be Shopping",
            shoppingId,
            expenseAfter!!.categoryId
        )
    }
}
