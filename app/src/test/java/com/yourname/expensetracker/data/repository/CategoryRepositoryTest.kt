package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentRecommendation
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
 * Item 144 (category delete / merge / rename). Real in-memory Room database, real
 * [DatabaseWriteBarrier] (mutable maintenance mode) and the real
 * [TransactionLifecycleCoordinator]; the categorization engine, classifier, post-commit runner
 * and side-effect planner are mocks (peripheral effects).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner
    private lateinit var repository: CategoryRepository
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val hybridExpenseClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private var mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL
    private val now = 1_700_000_000_000L

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } answers { mode }
        every { maintenanceMode.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        barrier = DatabaseWriteBarrier(maintenanceMode)

        planner = mockk(relaxed = true)
        every { planner.planBulkUpdated(any(), any(), any(), any()) } answers {
            PostCommitActionBatch(thirdArg<String?>() ?: "no-correlation", emptyList())
        }
        runner = mockk(relaxed = true)

        val coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = FakeTimeProvider.forDate(2026, 5, 1),
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = barrier,
            currencySettingsRepository = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = com.yourname.expensetracker.testfixtures.noLinkedReceiptsLinkService()
        )

        repository = CategoryRepository(
            writeBarrier = barrier,
            database = db,
            categoryDao = db.categoryDao(),
            merchantCategoryDao = db.merchantCategoryDao(),
            budgetDao = db.budgetDao(),
            categorizationEngine = categorizationEngine,
            hybridExpenseClassifier = object : Lazy<HybridExpenseClassifier> {
                override fun get(): HybridExpenseClassifier = hybridExpenseClassifier
            },
            transactionLifecycleCoordinator = object : Lazy<TransactionLifecycleCoordinator> {
                override fun get(): TransactionLifecycleCoordinator = coordinator
            }
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private suspend fun insertCategory(name: String, isDefault: Boolean = false): Long {
        val id = db.categoryDao().insert(Category(name = name, icon = "I", color = "#112233", isDefault = isDefault))
        assertTrue("fixture: category insert failed", id > 0L)
        return id
    }

    private suspend fun insertExpense(categoryId: Long?, merchant: String = "Synthetic Shop"): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = 10.0,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = now,
                categoryId = categoryId
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        return id
    }

    private suspend fun insertBudget(categoryId: Long, active: Boolean, amount: Double = 100.0): Long {
        val id = db.budgetDao().insert(
            Budget(
                categoryId = categoryId,
                amount = amount,
                period = BudgetPeriod.MONTHLY,
                startDate = now,
                isActive = active,
                createdAt = now,
                activeCategoryKey = if (active) categoryId else null
            )
        )
        assertTrue("fixture: budget insert failed", id > 0L)
        return id
    }

    private suspend fun insertPlannedExpense(categoryId: Long): Long {
        val id = db.plannedExpenseDao().insertPlannedExpense(
            PlannedExpense(description = "Synthetic plan", amount = 5.0, date = now, categoryId = categoryId)
        )
        assertTrue("fixture: planned expense insert failed", id > 0L)
        return id
    }

    private fun scalarLong(sql: String, vararg args: Any): Long? =
        db.openHelper.readableDatabase.query(sql, arrayOf<Any?>(*args)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private suspend fun categoryOf(expenseId: Long): Long? = db.expenseDao().getById(expenseId)!!.categoryId

    private suspend fun bulkEvents() =
        db.transactionEventDao().getEventsByType(LifecycleEventType.BULK_UPDATED.name, 100)

    private suspend fun assertNoCacheInvalidation() {
        coVerify(exactly = 0) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 0) { hybridExpenseClassifier.invalidateCategorySnapshot() }
    }

    private suspend fun assertCachesInvalidatedOnce() {
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
    }

    private fun blockWrites() {
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
    }

    // ── addCategory / learn / bulk-correction (unchanged behaviour) ──────────

    @Test
    fun `addCategory returns the persisted category and invalidates engine cache and classifier snapshot`() = runTest {
        val added = repository.addCategory("Dining", "icon", "#FF0000")

        assertTrue(added.id > 0L)
        assertEquals("Dining", added.name)
        assertEquals(added, db.categoryDao().getById(added.id))
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `learnMerchantCategory delegates to engine path for centralized invalidation`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) } returns Unit

        repository.learnMerchantCategory("Coffee Lab", 7L)

        coVerify(exactly = 1) { categorizationEngine.learnMerchantCategory("Coffee Lab", 7L) }
        assertEquals(
            "the repository must not write a mapping itself",
            0L,
            scalarLong("SELECT COUNT(*) FROM merchant_categories")
        )
    }

    @Test
    fun `addCategory trims but preserves display case and returns existing category on duplicate`() = runTest {
        val existingId = insertCategory("Dining Out")

        val result = repository.addCategory("  DINING OUT ", "food", "#FF0000")

        // E3-NOW-008: duplicate detection is case-insensitive and the existing row wins.
        assertEquals(existingId, result.id)
        assertEquals("Dining Out", result.name)
        assertEquals(1, db.categoryDao().getCount())
    }

    @Test
    fun `addCategory stores the trimmed display name with its original case`() = runTest {
        val result = repository.addCategory("  Gym MEMBERSHIP ", "g", "#00FF00")

        assertEquals("Gym MEMBERSHIP", result.name)
        assertEquals("Gym MEMBERSHIP", db.categoryDao().getById(result.id)!!.name)
    }

    @Test
    fun `updateExpenseCategoryBulk future only and default scope learn mapping`() = runTest {
        coEvery { categorizationEngine.learnMerchantCategory("Starbucks", 5L) } returns Unit

        val explicit = repository.updateExpenseCategoryBulk(
            merchant = "Starbucks",
            newCategoryId = 5L,
            scope = CategoryCorrectionScope.FUTURE_ONLY
        )
        val defaulted = repository.updateExpenseCategoryBulk("Starbucks", 5L)

        assertEquals("Row explicit FUTURE_ONLY", CategoryCorrectionResult.Learned("Starbucks", 5L), explicit)
        assertEquals("Row default scope", CategoryCorrectionResult.Learned("Starbucks", 5L), defaulted)
        coVerify(exactly = 2) { categorizationEngine.learnMerchantCategory("Starbucks", 5L) }
    }

    @Test
    fun `updateExpenseCategoryBulk backfill scopes return not yet implemented and learn nothing`() = runTest {
        listOf(CategoryCorrectionScope.BACKFILL_ALL, CategoryCorrectionScope.BACKFILL_SELECTED).forEach { scope ->
            val result = repository.updateExpenseCategoryBulk(
                merchant = "Starbucks",
                newCategoryId = 5L,
                scope = scope
            )

            assertEquals(
                "Row $scope",
                CategoryCorrectionResult.BackfillNotYetImplemented("Starbucks", 5L),
                result
            )
        }
        coVerify(exactly = 0) { categorizationEngine.learnMerchantCategory(any(), any()) }
    }

    // ── deleteCategory: success ──────────────────────────────────────────────

    @Test
    fun `deleteCategory moves expenses and every reference to the replacement and deletes the source`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val e1 = insertExpense(source, "Shop One")
        val e2 = insertExpense(source, "Shop Two")
        val untouched = insertExpense(replacement, "Shop Three")
        val plannedId = insertPlannedExpense(source)
        db.merchantCategoryDao().upsertUserCorrection(MerchantCategory(merchantPattern = "shop one", categoryId = source))

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertNull(db.categoryDao().getById(source))
        assertEquals(replacement, categoryOf(e1))
        assertEquals(replacement, categoryOf(e2))
        assertEquals(replacement, categoryOf(untouched))
        assertEquals(replacement, scalarLong("SELECT categoryId FROM planned_expenses WHERE id = ?", plannedId))
        assertEquals(
            "the merchant mapping must survive the delete (moved, not cascaded away)",
            replacement,
            scalarLong("SELECT categoryId FROM merchant_categories WHERE merchantPattern = ?", "shop one")
        )
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `deleteCategory writes one BULK_UPDATED event for the expense move and runs its batch once after commit`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        insertExpense(source, "Shop One")
        insertExpense(source, "Shop Two")

        repository.deleteCategory(source, replacement)

        val events = bulkEvents()
        assertEquals(1, events.size)
        val metadata = JSONObject(events.single().metadata!!)
        assertEquals(source, metadata.getLong("fromCategoryId"))
        assertEquals(replacement, metadata.getLong("toCategoryId"))
        assertEquals(2, metadata.getInt("affectedCount"))
        coVerify(exactly = 1) { runner.run(match { it.correlationId == events.single().correlationId }) }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `deleteCategory refreshes caches before the post-commit batch`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        insertExpense(source)

        repository.deleteCategory(source, replacement)

        coVerifyOrder {
            categorizationEngine.invalidateCache()
            hybridExpenseClassifier.invalidateCategorySnapshot()
            runner.run(any())
        }
    }

    @Test
    fun `deleteCategory with no expenses writes no event and still deletes`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertNull(db.categoryDao().getById(source))
        assertEquals(0, bulkEvents().size)
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
    }

    @Test
    fun `deleteCategory merges budgets into the replacement instead of refusing`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val sourceBudget = insertBudget(source, active = true, amount = 50.0)
        val replacementBudget = insertBudget(replacement, active = true, amount = 200.0)

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertNull(db.categoryDao().getById(source))
        // Conflicting active source budget: merged but deactivated.
        assertEquals(replacement, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(0L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", sourceBudget))
        assertNull(scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", sourceBudget))
        // Replacement budget unchanged.
        assertEquals(1L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", replacementBudget))
        assertEquals(replacement, scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", replacementBudget))
        assertEquals(200L, scalarLong("SELECT CAST(amount AS INTEGER) FROM budgets WHERE id = ?", replacementBudget))
    }

    @Test
    fun `deleteCategory keeps a lone active source budget active under the replacement`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val sourceBudget = insertBudget(source, active = true)

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertEquals(replacement, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(1L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(replacement, scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", sourceBudget))
    }

    @Test
    fun `deleteCategory accepts a default category as the replacement`() = runTest {
        val source = insertCategory("Source")
        val uncategorized = insertCategory("Uncategorized", isDefault = true)
        val expense = insertExpense(source)

        val result = repository.deleteCategory(source, uncategorized)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertEquals(uncategorized, categoryOf(expense))
        assertNotNull(db.categoryDao().getById(uncategorized))
    }

    // ── deleteCategory: rejections ───────────────────────────────────────────

    @Test
    fun `deleteCategory refuses a default category and changes nothing`() = runTest {
        val defaultCategory = insertCategory("Default", isDefault = true)
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(defaultCategory)

        val result = repository.deleteCategory(defaultCategory, replacement)

        assertEquals(DeleteCategoryResult.CannotDeleteDefault, result)
        assertNotNull(db.categoryDao().getById(defaultCategory))
        assertEquals(defaultCategory, categoryOf(expense))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `deleteCategory reports NotFound for a missing source and changes nothing`() = runTest {
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(replacement)

        val result = repository.deleteCategory(987_654L, replacement)

        assertEquals(DeleteCategoryResult.NotFound, result)
        assertEquals(replacement, categoryOf(expense))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
    }

    @Test
    fun `deleteCategory reports ReplacementInvalid for a missing replacement and changes nothing`() = runTest {
        val source = insertCategory("Source")
        val expense = insertExpense(source)
        val budget = insertBudget(source, active = true)

        val result = repository.deleteCategory(source, 987_654L)

        assertEquals(DeleteCategoryResult.ReplacementInvalid, result)
        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(source, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", budget))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
    }

    @Test
    fun `deleteCategory reports SameAsSource when the replacement is the source and changes nothing`() = runTest {
        val source = insertCategory("Source")
        val expense = insertExpense(source)

        val result = repository.deleteCategory(source, source)

        assertEquals(DeleteCategoryResult.SameAsSource, result)
        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
    }

    // ── mergeCategories ──────────────────────────────────────────────────────

    @Test
    fun `mergeCategories moves expenses and returns how many moved`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val e1 = insertExpense(source, "Shop One")
        val e2 = insertExpense(source, "Shop Two")
        val budget = insertBudget(source, active = true)

        val moved = repository.mergeCategories(source, target)

        assertEquals(2, moved)
        assertNull(db.categoryDao().getById(source))
        assertEquals(target, categoryOf(e1))
        assertEquals(target, categoryOf(e2))
        assertEquals(target, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", budget))
        assertEquals(1, bulkEvents().size)
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `mergeCategories into itself is rejected and changes nothing`() = runTest {
        val source = insertCategory("Source")
        val expense = insertExpense(source)

        try {
            repository.mergeCategories(source, source)
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
    }

    @Test
    fun `mergeCategories into a missing target is rejected and changes nothing`() = runTest {
        val source = insertCategory("Source")
        val expense = insertExpense(source)

        try {
            repository.mergeCategories(source, 987_654L)
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
    }

    @Test
    fun `mergeCategories of a default source rolls back the expense move and the event`() = runTest {
        val defaultSource = insertCategory("Default", isDefault = true)
        val target = insertCategory("Target")
        val expense = insertExpense(defaultSource)
        val budget = insertBudget(defaultSource, active = true)

        try {
            repository.mergeCategories(defaultSource, target)
            fail("Expected IllegalArgumentException from the default-category delete protection")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertNotNull(db.categoryDao().getById(defaultSource))
        assertEquals(defaultSource, categoryOf(expense))
        assertEquals(defaultSource, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", budget))
        assertEquals(0, bulkEvents().size)
        assertNoCacheInvalidation()
        coVerify(exactly = 0) { runner.run(any()) }
    }

    // ── write barrier ────────────────────────────────────────────────────────

    @Test
    fun `a blocked write barrier refuses deleteCategory without any write`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        blockWrites()

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected DatabaseAccessBlockedException")
        } catch (expected: DatabaseAccessBlockedException) {
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_PREPARING, expected.mode)
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertNoCacheInvalidation()
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `a blocked write barrier refuses mergeCategories without any write`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val expense = insertExpense(source)
        blockWrites()

        try {
            repository.mergeCategories(source, target)
            fail("Expected DatabaseAccessBlockedException")
        } catch (expected: DatabaseAccessBlockedException) {
            // expected
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertNoCacheInvalidation()
    }

    @Test
    fun `a blocked write barrier refuses renameCategory without any write`() = runTest {
        val id = insertCategory("Old Name")
        blockWrites()

        try {
            repository.renameCategory(id, "New Name")
            fail("Expected DatabaseAccessBlockedException")
        } catch (expected: DatabaseAccessBlockedException) {
            // expected
        }

        assertEquals("Old Name", db.categoryDao().getById(id)!!.name)
        assertNoCacheInvalidation()
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `deleteCategory works again once the barrier returns to normal`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        blockWrites()
        try {
            repository.deleteCategory(source, replacement)
            fail("Expected DatabaseAccessBlockedException")
        } catch (_: DatabaseAccessBlockedException) {
            // control: blocked
        }
        mode = RestoreMaintenanceMode.Mode.NORMAL

        assertEquals(DeleteCategoryResult.Deleted, repository.deleteCategory(source, replacement))
    }

    // ── rollback / cancellation ──────────────────────────────────────────────

    @Test
    fun `a failure after the expense move rolls back the move the event and the reference changes`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val e1 = insertExpense(source, "Shop One")
        val e2 = insertExpense(source, "Shop Two")
        val budget = insertBudget(source, active = true)
        val plannedId = insertPlannedExpense(source)
        db.merchantCategoryDao().upsertUserCorrection(MerchantCategory(merchantPattern = "shop one", categoryId = source))
        // Forces the final source delete to fail, i.e. mid-transaction, after the expense move,
        // the lifecycle event and every reference re-point already ran.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER forced_category_delete_failure BEFORE DELETE ON categories " +
                "BEGIN SELECT RAISE(ABORT, 'forced_category_delete_failure'); END"
        )

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected the forced failure to propagate")
        } catch (expected: RuntimeException) {
            assertTrue(
                "unexpected failure: ${expected.javaClass.simpleName}",
                expected is android.database.sqlite.SQLiteConstraintException ||
                    expected.message.orEmpty().contains("forced_category_delete_failure")
            )
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(e1))
        assertEquals(source, categoryOf(e2))
        assertEquals(source, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", budget))
        assertEquals(source, scalarLong("SELECT categoryId FROM planned_expenses WHERE id = ?", plannedId))
        assertEquals(
            source,
            scalarLong("SELECT categoryId FROM merchant_categories WHERE merchantPattern = ?", "shop one")
        )
        assertEquals("no lifecycle event may survive the rollback", 0, bulkEvents().size)
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertNoCacheInvalidation()
        coVerify(exactly = 0) { runner.run(any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `a cancellation inside the transaction propagates and rolls everything back`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        // The coordinator calls the planner inside the repository transaction.
        every { planner.planBulkUpdated(any(), any(), any(), any()) } throws CancellationException("synthetic cancel")

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic cancel"))
        }

        assertNotNull(db.categoryDao().getById(source))
        assertEquals(source, categoryOf(expense))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertNoCacheInvalidation()
        coVerify(exactly = 0) { runner.run(any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `a cancelled post-commit run propagates after the commit and the caches were already refreshed`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { runner.run(any()) } throws CancellationException("synthetic post-commit cancel")

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic post-commit cancel"))
        }

        // The transaction had committed before the post-commit run.
        assertNull(db.categoryDao().getById(source))
        assertEquals(replacement, categoryOf(expense))
        assertEquals(1, bulkEvents().size)
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `an ordinary post-commit failure is best effort and the delete still succeeds`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { runner.run(any()) } throws IllegalStateException("synthetic side effect failure")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertNull(db.categoryDao().getById(source))
        assertEquals(replacement, categoryOf(expense))
        assertCachesInvalidatedOnce()
    }

    // ── post-commit steps are best-effort (cache / snapshot / batch) ─────────

    private suspend fun assertDeleteCommitted(source: Long, replacement: Long, expense: Long) {
        assertNull(db.categoryDao().getById(source))
        assertEquals(replacement, categoryOf(expense))
        assertEquals(1, bulkEvents().size)
    }

    @Test
    fun `deleteCategory still succeeds when cache invalidation throws and later steps still run`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { categorizationEngine.invalidateCache() } throws RuntimeException("synthetic cache failure")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertDeleteCommitted(source, replacement, expense)
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `deleteCategory still succeeds when snapshot invalidation throws and the batch still runs`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws RuntimeException("synthetic snapshot failure")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertDeleteCommitted(source, replacement, expense)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `deleteCategory still succeeds when every post-commit step throws`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { categorizationEngine.invalidateCache() } throws RuntimeException("synthetic cache failure")
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws RuntimeException("synthetic snapshot failure")
        coEvery { runner.run(any()) } throws RuntimeException("synthetic batch failure")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertDeleteCommitted(source, replacement, expense)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `deleteCategory propagates a cancellation from cache invalidation and the delete stays committed`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { categorizationEngine.invalidateCache() } throws CancellationException("synthetic cache cancel")

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic cache cancel"))
        }

        assertDeleteCommitted(source, replacement, expense)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `deleteCategory propagates a cancellation from snapshot invalidation and the delete stays committed`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val expense = insertExpense(source)
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws CancellationException("synthetic snapshot cancel")

        try {
            repository.deleteCategory(source, replacement)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic snapshot cancel"))
        }

        assertDeleteCommitted(source, replacement, expense)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `mergeCategories still returns the moved count when cache invalidation throws and later steps still run`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val e1 = insertExpense(source, "Shop One")
        val e2 = insertExpense(source, "Shop Two")
        coEvery { categorizationEngine.invalidateCache() } throws RuntimeException("synthetic cache failure")

        val moved = repository.mergeCategories(source, target)

        assertEquals(2, moved)
        assertNull(db.categoryDao().getById(source))
        assertEquals(target, categoryOf(e1))
        assertEquals(target, categoryOf(e2))
        assertEquals(1, bulkEvents().size)
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `mergeCategories still returns the moved count when snapshot invalidation throws and the batch still runs`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val e1 = insertExpense(source, "Shop One")
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws RuntimeException("synthetic snapshot failure")

        val moved = repository.mergeCategories(source, target)

        assertEquals(1, moved)
        assertNull(db.categoryDao().getById(source))
        assertEquals(target, categoryOf(e1))
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
        coVerify(exactly = 1) { runner.run(any()) }
    }

    @Test
    fun `mergeCategories still returns the moved count when the post-commit batch throws`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val e1 = insertExpense(source, "Shop One")
        val e2 = insertExpense(source, "Shop Two")
        coEvery { runner.run(any()) } throws RuntimeException("synthetic batch failure")

        val moved = repository.mergeCategories(source, target)

        assertEquals(2, moved)
        assertNull(db.categoryDao().getById(source))
        assertEquals(target, categoryOf(e1))
        assertEquals(target, categoryOf(e2))
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `mergeCategories propagates a cancellation from cache invalidation and the merge stays committed`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val expense = insertExpense(source)
        coEvery { categorizationEngine.invalidateCache() } throws CancellationException("synthetic cache cancel")

        try {
            repository.mergeCategories(source, target)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic cache cancel"))
        }

        assertDeleteCommitted(source, target, expense)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `mergeCategories propagates a cancellation from snapshot invalidation and the merge stays committed`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val expense = insertExpense(source)
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws CancellationException("synthetic snapshot cancel")

        try {
            repository.mergeCategories(source, target)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic snapshot cancel"))
        }

        assertDeleteCommitted(source, target, expense)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `renameCategory still returns Renamed when cache invalidation throws and the snapshot is still invalidated`() = runTest {
        val id = insertCategory("Old Name")
        coEvery { categorizationEngine.invalidateCache() } throws RuntimeException("synthetic cache failure")

        val result = repository.renameCategory(id, "New Name")

        assertEquals(RenameCategoryResult.Renamed, result)
        assertEquals("New Name", db.categoryDao().getById(id)!!.name)
        coVerify(exactly = 1) { hybridExpenseClassifier.invalidateCategorySnapshot() }
    }

    @Test
    fun `renameCategory still returns Renamed when snapshot invalidation throws`() = runTest {
        val id = insertCategory("Old Name")
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws RuntimeException("synthetic snapshot failure")

        val result = repository.renameCategory(id, "New Name")

        assertEquals(RenameCategoryResult.Renamed, result)
        assertEquals("New Name", db.categoryDao().getById(id)!!.name)
        coVerify(exactly = 1) { categorizationEngine.invalidateCache() }
    }

    @Test
    fun `renameCategory propagates a cancellation from cache invalidation and the rename stays committed`() = runTest {
        val id = insertCategory("Old Name")
        coEvery { categorizationEngine.invalidateCache() } throws CancellationException("synthetic cache cancel")

        try {
            repository.renameCategory(id, "New Name")
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic cache cancel"))
        }

        assertEquals("New Name", db.categoryDao().getById(id)!!.name)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `renameCategory propagates a cancellation from snapshot invalidation and the rename stays committed`() = runTest {
        val id = insertCategory("Old Name")
        coEvery { hybridExpenseClassifier.invalidateCategorySnapshot() } throws CancellationException("synthetic snapshot cancel")

        try {
            repository.renameCategory(id, "New Name")
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("synthetic snapshot cancel"))
        }

        assertEquals("New Name", db.categoryDao().getById(id)!!.name)
        assertEquals(0, barrier.inFlightWriteCount())
    }

    // ── renameCategory ───────────────────────────────────────────────────────

    @Test
    fun `renameCategory renames trims and invalidates caches`() = runTest {
        val id = insertCategory("Old Name")

        val result = repository.renameCategory(id, "  New Name  ")

        assertEquals(RenameCategoryResult.Renamed, result)
        val renamed = db.categoryDao().getById(id)!!
        assertEquals("New Name", renamed.name)
        assertEquals("I", renamed.icon)
        assertEquals("#112233", renamed.color)
        assertEquals(false, renamed.isDefault)
        assertCachesInvalidatedOnce()
    }

    @Test
    fun `renameCategory does not touch expenses or write lifecycle events`() = runTest {
        val id = insertCategory("Old Name")
        val expense = insertExpense(id)

        repository.renameCategory(id, "New Name")

        assertEquals(id, categoryOf(expense))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
    }

    @Test
    fun `renameCategory rejects an empty or blank name`() = runTest {
        val id = insertCategory("Keep Me")

        assertEquals("empty", RenameCategoryResult.EmptyName, repository.renameCategory(id, ""))
        assertEquals("spaces", RenameCategoryResult.EmptyName, repository.renameCategory(id, "    "))
        assertEquals("whitespace only", RenameCategoryResult.EmptyName, repository.renameCategory(id, " \t\n "))

        assertEquals("Keep Me", db.categoryDao().getById(id)!!.name)
        assertNoCacheInvalidation()
    }

    @Test
    fun `renameCategory rejects control characters`() = runTest {
        val id = insertCategory("Keep Me")

        assertEquals("newline", RenameCategoryResult.InvalidCharacters, repository.renameCategory(id, "Bad\nName"))
        assertEquals("tab", RenameCategoryResult.InvalidCharacters, repository.renameCategory(id, "Bad\tName"))
        assertEquals("nul", RenameCategoryResult.InvalidCharacters, repository.renameCategory(id, "Bad\u0000Name"))
        assertEquals("bell", RenameCategoryResult.InvalidCharacters, repository.renameCategory(id, "Bad\u0007Name"))

        assertEquals("Keep Me", db.categoryDao().getById(id)!!.name)
        assertNoCacheInvalidation()
    }

    @Test
    fun `renameCategory rejects names over 50 characters and accepts exactly 50`() = runTest {
        val id = insertCategory("Keep Me")
        val fifty = "x".repeat(50)
        val fiftyOne = "x".repeat(51)

        assertEquals(RenameCategoryResult.InvalidCharacters, repository.renameCategory(id, fiftyOne))
        assertEquals("Keep Me", db.categoryDao().getById(id)!!.name)
        assertNoCacheInvalidation()

        assertEquals(RenameCategoryResult.Renamed, repository.renameCategory(id, fifty))
        assertEquals(fifty, db.categoryDao().getById(id)!!.name)
    }

    @Test
    fun `renameCategory length limit is applied after trimming`() = runTest {
        val id = insertCategory("Keep Me")
        val fiftyPadded = "  " + "y".repeat(50) + "  "

        assertEquals(RenameCategoryResult.Renamed, repository.renameCategory(id, fiftyPadded))
        assertEquals("y".repeat(50), db.categoryDao().getById(id)!!.name)
    }

    @Test
    fun `renameCategory rejects a name used by another category case-insensitively`() = runTest {
        val travel = insertCategory("Travel")
        val other = insertCategory("Other")

        assertEquals(RenameCategoryResult.Duplicate, repository.renameCategory(other, "travel"))
        assertEquals(RenameCategoryResult.Duplicate, repository.renameCategory(other, "TRAVEL"))
        assertEquals(RenameCategoryResult.Duplicate, repository.renameCategory(other, "  Travel "))

        assertEquals("Other", db.categoryDao().getById(other)!!.name)
        assertEquals("Travel", db.categoryDao().getById(travel)!!.name)
        assertNoCacheInvalidation()
    }

    @Test
    fun `renameCategory rejects a name used by a default category`() = runTest {
        insertCategory("Groceries", isDefault = true)
        val mine = insertCategory("Mine")

        assertEquals(RenameCategoryResult.Duplicate, repository.renameCategory(mine, "groceries"))
        assertEquals("Mine", db.categoryDao().getById(mine)!!.name)
    }

    @Test
    fun `renameCategory allows changing only the casing of its own name`() = runTest {
        val id = insertCategory("Travel")

        assertEquals(RenameCategoryResult.Renamed, repository.renameCategory(id, "TRAVEL"))
        assertEquals("TRAVEL", db.categoryDao().getById(id)!!.name)
        assertEquals(RenameCategoryResult.Renamed, repository.renameCategory(id, "TRAVEL"))
        assertEquals(RenameCategoryResult.Renamed, repository.renameCategory(id, "  travel "))
        assertEquals("travel", db.categoryDao().getById(id)!!.name)
        assertEquals(1, db.categoryDao().getCount())
    }

    @Test
    fun `renameCategory refuses a default category`() = runTest {
        val defaultId = insertCategory("Groceries", isDefault = true)

        val result = repository.renameCategory(defaultId, "Food Shopping")

        assertEquals(RenameCategoryResult.CannotRenameDefault, result)
        assertEquals("Groceries", db.categoryDao().getById(defaultId)!!.name)
        assertNoCacheInvalidation()
    }

    @Test
    fun `renameCategory reports NotFound for a missing category`() = runTest {
        val result = repository.renameCategory(987_654L, "Whatever")

        assertEquals(RenameCategoryResult.NotFound, result)
        assertEquals(0, db.categoryDao().getCount())
        assertNoCacheInvalidation()
    }

    // ── denormalized category names stay in sync (1.0.4) ─────────────────────

    private class DenormRows(val recommendationId: Long, val itemId: Long)

    private fun scalarString(sql: String, vararg args: Any): String? =
        db.openHelper.readableDatabase.query(sql, arrayOf<Any?>(*args)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    /** Seeds one recommendation and one receipt item (suggested + user-corrected) all naming [name]. */
    private suspend fun seedDenormalizedRows(categoryId: Long, name: String): DenormRows {
        val budgetId = insertBudget(categoryId, active = false)
        val recommendationId = db.budgetAdjustmentDao().insertRecommendation(
            BudgetAdjustmentRecommendation(
                budgetId = budgetId,
                categoryId = categoryId,
                categoryName = name,
                currentBudget = 100.0,
                recommendedBudget = 120.0,
                delta = 20.0,
                deltaPercentage = 20.0,
                reason = "synthetic",
                confidence = 0.8,
                trend = BudgetTrend.STABLE,
                generatedAt = now
            )
        )
        assertTrue("fixture: recommendation insert failed", recommendationId > 0L)
        val receiptId = db.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "synthetic",
                parsedTotal = 12.0,
                parsedMerchant = "Synthetic Merchant",
                parsedDate = now,
                parsedItems = "[]",
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = now,
                updatedAt = now,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT"
            )
        )
        assertTrue("fixture: receipt insert failed", receiptId > 0L)
        val itemId = db.receiptItemCategorizationDao().insert(
            ReceiptItemCategorization(
                receiptId = receiptId,
                itemDescription = "synthetic item",
                itemAmount = 1.0,
                suggestedCategoryId = categoryId,
                suggestedCategoryName = name,
                confidence = 0.5f,
                aiRationale = null,
                alternativeCategoriesJson = null,
                userCorrectedCategoryId = categoryId,
                userCorrectedCategoryName = name,
                userCorrectedAt = now,
                taxAmount = null,
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("fixture: receipt item insert failed", itemId > 0L)
        return DenormRows(recommendationId, itemId)
    }

    private fun assertDenormNames(rows: DenormRows, expected: String, label: String) {
        assertEquals(
            "$label recommendation categoryName",
            expected,
            scalarString("SELECT categoryName FROM budget_adjustment_recommendations WHERE id = ?", rows.recommendationId)
        )
        assertEquals(
            "$label suggestedCategoryName",
            expected,
            scalarString("SELECT suggestedCategoryName FROM receipt_item_categorizations WHERE id = ?", rows.itemId)
        )
        assertEquals(
            "$label userCorrectedCategoryName",
            expected,
            scalarString("SELECT userCorrectedCategoryName FROM receipt_item_categorizations WHERE id = ?", rows.itemId)
        )
    }

    @Test
    fun `renameCategory rewrites denormalized names of its rows and leaves other categories rows unchanged`() = runTest {
        val id = insertCategory("Old Name")
        val other = insertCategory("Other")
        val mine = seedDenormalizedRows(id, "Old Name")
        val theirs = seedDenormalizedRows(other, "Other")
        assertDenormNames(mine, "Old Name", "control before rename")

        val result = repository.renameCategory(id, "  New Name ")

        assertEquals(RenameCategoryResult.Renamed, result)
        assertDenormNames(mine, "New Name", "renamed category")
        assertDenormNames(theirs, "Other", "other category")
    }

    @Test
    fun `deleteCategory moved rows carry the replacement name`() = runTest {
        val source = insertCategory("Source")
        val replacement = insertCategory("Replacement")
        val bystander = insertCategory("Bystander")
        val moved = seedDenormalizedRows(source, "Source")
        val untouched = seedDenormalizedRows(bystander, "Bystander")

        val result = repository.deleteCategory(source, replacement)

        assertEquals(DeleteCategoryResult.Deleted, result)
        assertDenormNames(moved, "Replacement", "moved rows")
        assertEquals(
            replacement,
            scalarLong("SELECT categoryId FROM budget_adjustment_recommendations WHERE id = ?", moved.recommendationId)
        )
        assertEquals(
            replacement,
            scalarLong("SELECT suggestedCategoryId FROM receipt_item_categorizations WHERE id = ?", moved.itemId)
        )
        assertEquals(
            replacement,
            scalarLong("SELECT userCorrectedCategoryId FROM receipt_item_categorizations WHERE id = ?", moved.itemId)
        )
        assertDenormNames(untouched, "Bystander", "bystander rows")
    }

    @Test
    fun `mergeCategories moved rows carry the target name`() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val moved = seedDenormalizedRows(source, "Source")
        val existing = seedDenormalizedRows(target, "Target")

        repository.mergeCategories(source, target)

        assertDenormNames(moved, "Target", "moved rows")
        assertDenormNames(existing, "Target", "target's own rows")
    }

    @Test
    fun `a Duplicate rename refusal changes no denormalized name`() = runTest {
        insertCategory("Travel")
        val id = insertCategory("Mine")
        val rows = seedDenormalizedRows(id, "Mine")

        assertEquals(RenameCategoryResult.Duplicate, repository.renameCategory(id, "TRAVEL"))

        assertDenormNames(rows, "Mine", "refused rename")
        assertEquals("Mine", db.categoryDao().getById(id)!!.name)
    }

    @Test
    fun `a CannotRenameDefault refusal changes no denormalized name`() = runTest {
        val defaultId = insertCategory("Groceries", isDefault = true)
        val rows = seedDenormalizedRows(defaultId, "Groceries")

        assertEquals(RenameCategoryResult.CannotRenameDefault, repository.renameCategory(defaultId, "Food Shopping"))

        assertDenormNames(rows, "Groceries", "refused default rename")
        assertEquals("Groceries", db.categoryDao().getById(defaultId)!!.name)
    }

    // ── countExpensesForCategory ─────────────────────────────────────────────

    @Test
    fun `countExpensesForCategory reports the expenses of that category only`() = runTest {
        val food = insertCategory("Food")
        val travel = insertCategory("Travel")
        insertExpense(food, "One")
        insertExpense(food, "Two")
        insertExpense(travel, "Three")

        assertEquals(2, repository.countExpensesForCategory(food))
        assertEquals(1, repository.countExpensesForCategory(travel))
        assertEquals(0, repository.countExpensesForCategory(987_654L))
        // read-only: nothing moved
        assertEquals(2, repository.countExpensesForCategory(food))
    }
}
