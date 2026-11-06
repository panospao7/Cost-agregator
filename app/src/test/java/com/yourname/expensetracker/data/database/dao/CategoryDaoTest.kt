package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentRecommendation
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.SpendingChallengeEntity
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
 * Room-backed tests for [CategoryDao].
 *
 * `concurrent_get_or_insert_by_name_returns_one_category_without_supplemental_index`
 * deliberately stays on `runBlocking` (not `runTest`): it fans out 24 real
 * `Dispatchers.IO` workers guarded by `withTimeout(30_000L)`. Under `runTest`
 * that `withTimeout` runs on the virtual-time test scheduler, which skips
 * delays while the test body is suspended on `awaitAll`, so the 30 s timeout
 * would fire immediately instead of bounding real wall-clock time. `runBlocking`
 * keeps the timeout real and the concurrency identical to the original
 * instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CategoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        categoryDao = database.categoryDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private fun makeCategory(
        name: String,
        icon: String,
        color: String,
        isDefault: Boolean = false
    ) = Category(
        name = name,
        icon = icon,
        color = color,
        isDefault = isDefault
    )

    @Test
    fun insert_category_then_retrieve_by_id_returns_persisted_category() = runTest {
        val id = categoryDao.insert(
            makeCategory(name = "Groceries", icon = "🛒", color = "#4CAF50")
        )

        val stored = categoryDao.getById(id)

        assertTrue(id > 0)
        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals("Groceries", stored.name)
    }

    @Test
    fun query_all_categories_returns_defaults_first_then_custom_categories() = runTest {
        categoryDao.insert(makeCategory(name = "Food", icon = "🍔", color = "#E53935", isDefault = true))
        categoryDao.insert(makeCategory(name = "Transport", icon = "🚌", color = "#1E88E5", isDefault = true))
        categoryDao.insert(makeCategory(name = "Pets", icon = "🐾", color = "#8E24AA", isDefault = false))

        val all = categoryDao.getAll()

        assertEquals(3, all.size)
        assertTrue(all[0].isDefault)
        assertTrue(all[1].isDefault)
        assertTrue(!all[2].isDefault)
        assertEquals(listOf("Food", "Transport", "Pets"), all.map { it.name })
    }

    @Test
    fun update_category_name_icon_and_color_persists_changes() = runTest {
        val id = categoryDao.insert(
            makeCategory(name = "Bills", icon = "💳", color = "#546E7A")
        )

        val existing = categoryDao.getById(id)!!
        categoryDao.update(
            existing.copy(
                name = "Utilities",
                icon = "💡",
                color = "#FFB300"
            )
        )

        val updated = categoryDao.getById(id)
        assertNotNull(updated)
        assertEquals("Utilities", updated!!.name)
        assertEquals("💡", updated.icon)
        assertEquals("#FFB300", updated.color)
    }

    @Test
    fun get_or_insert_by_name_reuses_case_insensitive_category_without_overwriting_it() = runTest {
        val first = categoryDao.getOrInsertByNameNoCase(
            makeCategory(name = "Travel", icon = "T", color = "#3949AB")
        )
        val second = categoryDao.getOrInsertByNameNoCase(
            makeCategory(name = "travel", icon = "t", color = "#00897B")
        )

        assertTrue(first.id > 0)
        assertEquals(first, second)
        assertEquals(1, categoryDao.getCount())
        assertEquals(first, categoryDao.getByName("TRAVEL"))
    }

    @Test
    fun concurrent_get_or_insert_by_name_returns_one_category_without_supplemental_index() = runBlocking {
        database.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_categories_name_nocase'"
        ).use { cursor ->
            assertTrue("fresh Room schema must not have the supplemental index", !cursor.moveToFirst())
        }
        val start = CompletableDeferred<Unit>()
        val saved = withTimeout(30_000L) {
            val requests = (0 until 24).map { index ->
                async(Dispatchers.IO) {
                    start.await()
                    categoryDao.getOrInsertByNameNoCase(
                        makeCategory(
                            name = if (index % 2 == 0) "Travel" else "travel",
                            icon = "T",
                            color = "#3949AB"
                        )
                    )
                }
            }
            start.complete(Unit)
            requests.awaitAll()
        }

        assertTrue(saved.all { it.id > 0 })
        assertEquals(1, saved.map { it.id }.distinct().size)
        assertEquals(1, categoryDao.getCount())
        assertEquals(1, categoryDao.existsByName("TRAVEL"))
    }

    @Test
    fun duplicate_category_name_is_handled_without_crash() = runTest {
        val firstId = categoryDao.insert(
            makeCategory(name = "Travel", icon = "✈️", color = "#3949AB")
        )
        val secondId = categoryDao.insert(
            makeCategory(name = "Travel", icon = "🚆", color = "#00897B")
        )

        val all = categoryDao.getAll()
        val byName = categoryDao.getByName("Travel")

        assertTrue(firstId > 0)
        assertTrue(secondId > 0)
        assertEquals(2, all.count { it.name == "Travel" })
        assertNotNull(byName)
        assertEquals("Travel", byName!!.name)
    }

    // ── Item 144: category delete / merge / rename (C1) ──────────────────────

    private val now = 1_700_000_000_000L

    /** One reference row per referencing table/column, all pointing at the source category. */
    private class Ref(
        val label: String,
        val table: String,
        val column: String,
        val idColumn: String,
        val id: Any
    )

    private class Seeded(
        val source: Long,
        val target: Long,
        val bystander: Long,
        val expenseId: Long,
        val refs: List<Ref>,
        val bystanderRefs: List<Ref>
    )

    private fun scalarLong(sql: String, vararg args: Any): Long? =
        database.openHelper.readableDatabase.query(sql, arrayOf<Any?>(*args)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun columnValue(ref: Ref): Long? =
        scalarLong("SELECT ${ref.column} FROM ${ref.table} WHERE ${ref.idColumn} = ?", ref.id)

    private fun assertRefsPointTo(refs: List<Ref>, expectedCategoryId: Long) {
        refs.forEach { ref ->
            assertEquals(
                "reference ${ref.label} (${ref.table}.${ref.column})",
                expectedCategoryId,
                columnValue(ref)
            )
        }
    }

    private suspend fun insertCategory(name: String, isDefault: Boolean = false): Long {
        val id = categoryDao.insert(makeCategory(name, "I", "#112233", isDefault))
        assertTrue("fixture: category insert failed", id > 0L)
        return id
    }

    private fun positive(label: String, id: Long): Long {
        assertTrue("fixture: $label insert failed", id > 0L)
        return id
    }

    private suspend fun insertExpense(categoryId: Long?, merchant: String = "Synthetic Shop"): Long =
        positive(
            "expense",
            database.expenseDao().insert(
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
        )

    private suspend fun insertBudget(categoryId: Long, active: Boolean, amount: Double = 100.0): Long =
        positive(
            "budget",
            database.budgetDao().insert(
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
        )

    private suspend fun insertPlannedExpense(categoryId: Long): Long =
        positive(
            "planned expense",
            database.plannedExpenseDao().insertPlannedExpense(
                PlannedExpense(description = "Synthetic plan", amount = 5.0, date = now, categoryId = categoryId)
            )
        )

    /** Seeds one row per referencing table/column on [source], plus bystander rows on [bystander]. */
    private suspend fun seedAllReferences(): Seeded {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val bystander = insertCategory("Bystander")

        val expenseId = insertExpense(source)
        val budgetId = insertBudget(source, active = true)
        val recommendationId = positive(
            "recommendation",
            database.budgetAdjustmentDao().insertRecommendation(
                BudgetAdjustmentRecommendation(
                    budgetId = budgetId,
                    categoryId = source,
                    categoryName = "Source",
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
        )
        database.merchantCategoryDao().upsertUserCorrection(
            MerchantCategory(merchantPattern = "synthetic-pattern", categoryId = source)
        )
        database.merchantCategoryDao().upsertUserCorrection(
            MerchantCategory(merchantPattern = "bystander-pattern", categoryId = bystander)
        )
        val canonicalId = positive(
            "merchant canonical",
            database.merchantNormalizationDao().insertCanonical(
                MerchantCanonical(
                    normalizedName = "Synthetic Canonical",
                    searchKey = "syntheticcanonical",
                    categoryId = source,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        val pendingReviewId = positive(
            "pending review",
            database.pendingReviewDao().insert(
                PendingReview(
                    rawNotificationId = null,
                    suggestedAmount = 25.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Synthetic Shop",
                    suggestedType = TransactionType.PURCHASE.name,
                    suggestedCategoryId = source,
                    confidence = 0.9f,
                    packageName = "com.example.synthetic",
                    notificationTitle = null,
                    notificationText = null,
                    createdAt = now
                )
            )
        )
        val receiptId = positive(
            "scanned receipt",
            database.scannedReceiptDao().insert(
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
        )
        fun item(suggested: Long?, userCorrected: Long?) = ReceiptItemCategorization(
            receiptId = receiptId,
            itemDescription = "synthetic item",
            itemAmount = 1.0,
            suggestedCategoryId = suggested,
            suggestedCategoryName = null,
            confidence = 0.5f,
            aiRationale = null,
            alternativeCategoriesJson = null,
            userCorrectedCategoryId = userCorrected,
            userCorrectedCategoryName = null,
            userCorrectedAt = if (userCorrected != null) now else null,
            taxAmount = null,
            createdAt = now,
            updatedAt = now
        )
        val itemSuggestedId = positive(
            "receipt item (suggested)",
            database.receiptItemCategorizationDao().insert(item(suggested = source, userCorrected = null))
        )
        val itemCorrectedId = positive(
            "receipt item (user corrected)",
            database.receiptItemCategorizationDao().insert(item(suggested = null, userCorrected = source))
        )
        val occurrenceId = positive(
            "recurring occurrence",
            database.recurringOccurrenceDao().insert(
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 1L,
                    occurrenceKey = "RECURRING_RULE|1|$now|MONTHLY",
                    dueDate = now,
                    status = "PLANNED",
                    expectedAmount = 9.0,
                    expectedCurrency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY.name,
                    merchant = "Synthetic Shop",
                    categoryId = source,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        val manualRecurringId = positive(
            "manual recurring expense",
            database.manualRecurringExpenseDao().insert(
                ManualRecurringExpense(
                    merchant = "Synthetic Shop",
                    amount = 9.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = now,
                    categoryId = source,
                    createdAt = now
                )
            )
        )
        val planId = positive(
            "installment plan",
            database.installmentPlanDao().insertPlan(
                InstallmentPlan(
                    planUuid = "synthetic-plan-uuid",
                    kind = "STANDALONE",
                    displayName = "Synthetic plan",
                    currency = "EUR",
                    splitMethod = "EQUAL_SPLIT",
                    declaredTotal = 300.0,
                    installmentCount = 3,
                    frequency = "MONTHLY",
                    firstDueDate = 20_000L,
                    anchorDayOfMonth = 15,
                    residualPlacement = "FIRST",
                    categoryId = source,
                    fulfilmentMode = "CONFIRM",
                    declaredOnDate = 20_000L,
                    status = "ACTIVE",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        val plannedId = insertPlannedExpense(source)
        val bystanderPlannedId = insertPlannedExpense(bystander)
        val challengeId = positive(
            "spending challenge",
            database.spendingChallengeDao().insert(
                SpendingChallengeEntity(
                    name = "Synthetic challenge",
                    type = "CATEGORY_SPECIFIC",
                    startDate = now,
                    endDate = now + 86_400_000L,
                    categoryId = source,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        val bankConnectionId = positive(
            "bank connection",
            database.bankConnectionDao().insert(
                BankConnection(
                    bankId = "synthetic-bank",
                    bankName = "Synthetic Bank",
                    countryCode = "GR",
                    defaultCategoryId = source,
                    createdAt = now
                )
            )
        )
        fun correction(original: Long?, corrected: Long?) = UserCorrection(
            packageName = "com.example.synthetic",
            originalMerchant = "Synthetic Shop",
            correctedMerchant = null,
            originalAmount = 1.0,
            correctedAmount = null,
            originalCategoryId = original,
            correctedCategoryId = corrected,
            originalType = null,
            correctedType = null,
            notificationTitle = null,
            notificationText = null,
            createdAt = now
        )
        val correctionOriginalId = positive(
            "user correction (original column)",
            database.userCorrectionDao().insert(correction(original = source, corrected = bystander))
        )
        val correctionCorrectedId = positive(
            "user correction (corrected column)",
            database.userCorrectionDao().insert(correction(original = bystander, corrected = source))
        )

        val refs = listOf(
            Ref("budget", "budgets", "categoryId", "id", budgetId),
            Ref("budget adjustment recommendation", "budget_adjustment_recommendations", "categoryId", "id", recommendationId),
            Ref("merchant category", "merchant_categories", "categoryId", "merchantPattern", "synthetic-pattern"),
            Ref("merchant canonical", "merchant_canonicals", "categoryId", "id", canonicalId),
            Ref("pending review", "pending_reviews", "suggestedCategoryId", "id", pendingReviewId),
            Ref("receipt item suggested", "receipt_item_categorizations", "suggestedCategoryId", "id", itemSuggestedId),
            Ref("receipt item user corrected", "receipt_item_categorizations", "userCorrectedCategoryId", "id", itemCorrectedId),
            Ref("recurring occurrence", "recurring_occurrences", "categoryId", "id", occurrenceId),
            Ref("manual recurring expense", "manual_recurring_expenses", "categoryId", "id", manualRecurringId),
            Ref("installment plan", "installment_plans", "categoryId", "id", planId),
            Ref("planned expense", "planned_expenses", "categoryId", "id", plannedId),
            Ref("spending challenge", "spending_challenges", "categoryId", "id", challengeId),
            Ref("bank connection default", "bank_connections", "defaultCategoryId", "id", bankConnectionId),
            Ref("user correction original", "user_corrections", "originalCategoryId", "id", correctionOriginalId),
            Ref("user correction corrected", "user_corrections", "correctedCategoryId", "id", correctionCorrectedId)
        )
        val bystanderRefs = listOf(
            Ref("bystander merchant category", "merchant_categories", "categoryId", "merchantPattern", "bystander-pattern"),
            Ref("bystander planned expense", "planned_expenses", "categoryId", "id", bystanderPlannedId),
            Ref("bystander user correction (corrected column)", "user_corrections", "correctedCategoryId", "id", correctionOriginalId),
            Ref("bystander user correction (original column)", "user_corrections", "originalCategoryId", "id", correctionCorrectedId)
        )
        return Seeded(source, target, bystander, expenseId, refs, bystanderRefs)
    }

    @Test
    fun reassignAllReferencesExceptExpenses_moves_every_referencing_table_to_the_target() = runTest {
        val seeded = seedAllReferences()
        assertRefsPointTo(seeded.refs, seeded.source) // control: the fixture really points at the source

        categoryDao.reassignAllReferencesExceptExpenses(seeded.source, seeded.target)

        assertRefsPointTo(seeded.refs, seeded.target)
    }

    @Test
    fun reassignAllReferencesExceptExpenses_leaves_bystander_references_untouched() = runTest {
        val seeded = seedAllReferences()

        categoryDao.reassignAllReferencesExceptExpenses(seeded.source, seeded.target)

        assertRefsPointTo(seeded.bystanderRefs, seeded.bystander)
    }

    @Test
    fun reassignAllReferencesExceptExpenses_does_not_touch_expenses() = runTest {
        val seeded = seedAllReferences()
        val secondExpense = insertExpense(seeded.source, merchant = "Second Shop")
        val targetExpense = insertExpense(seeded.target, merchant = "Target Shop")

        categoryDao.reassignAllReferencesExceptExpenses(seeded.source, seeded.target)

        assertEquals(seeded.source, database.expenseDao().getById(seeded.expenseId)!!.categoryId)
        assertEquals(seeded.source, database.expenseDao().getById(secondExpense)!!.categoryId)
        assertEquals(seeded.target, database.expenseDao().getById(targetExpense)!!.categoryId)
        assertEquals(2, categoryDao.countExpensesForCategory(seeded.source))
    }

    @Test
    fun reassignAllReferencesExceptExpenses_keeps_merchant_mappings_alive_after_the_source_delete() = runTest {
        val seeded = seedAllReferences()

        categoryDao.reassignAllReferencesExceptExpenses(seeded.source, seeded.target)
        categoryDao.delete(categoryDao.getById(seeded.source)!!)

        assertNull(categoryDao.getById(seeded.source))
        assertEquals(
            seeded.target,
            scalarLong("SELECT categoryId FROM merchant_categories WHERE merchantPattern = ?", "synthetic-pattern")
        )
        assertEquals(
            1L,
            scalarLong("SELECT COUNT(*) FROM merchant_categories WHERE categoryId = ?", seeded.target)
        )
    }

    @Test
    fun budget_conflict_active_on_both_deactivates_the_source_budget_and_leaves_the_target_unchanged() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val sourceBudget = insertBudget(source, active = true, amount = 50.0)
        val targetBudget = insertBudget(target, active = true, amount = 200.0)

        categoryDao.reassignAllReferencesExceptExpenses(source, target)

        // Source budget merged into the target but deactivated (no unique-key violation).
        assertEquals(target, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(0L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", sourceBudget))
        assertNull(scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", sourceBudget))
        assertNull(scalarLong("SELECT activeOverallKey FROM budgets WHERE id = ?", sourceBudget))
        // Target budget is exactly as it was.
        assertEquals(target, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", targetBudget))
        assertEquals(1L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", targetBudget))
        assertEquals(target, scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", targetBudget))
        assertEquals(200L, scalarLong("SELECT CAST(amount AS INTEGER) FROM budgets WHERE id = ?", targetBudget))
        assertEquals(
            1L,
            scalarLong("SELECT COUNT(*) FROM budgets WHERE categoryId = ? AND isActive = 1", target)
        )
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM budgets WHERE categoryId = ?", source))
    }

    @Test
    fun budget_merge_active_source_without_active_target_budget_moves_and_stays_active() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val sourceBudget = insertBudget(source, active = true)

        categoryDao.reassignAllReferencesExceptExpenses(source, target)

        assertEquals(target, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(1L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(target, scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", sourceBudget))
    }

    @Test
    fun budget_merge_inactive_source_budget_moves_inactive_and_does_not_disturb_the_active_target() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val sourceBudget = insertBudget(source, active = false)
        val targetBudget = insertBudget(target, active = true)

        categoryDao.reassignAllReferencesExceptExpenses(source, target)

        assertEquals(target, scalarLong("SELECT categoryId FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(0L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", sourceBudget))
        assertNull(scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", sourceBudget))
        assertEquals(1L, scalarLong("SELECT isActive FROM budgets WHERE id = ?", targetBudget))
        assertEquals(target, scalarLong("SELECT activeCategoryKey FROM budgets WHERE id = ?", targetBudget))
    }

    @Test
    fun budget_merge_lets_the_source_category_be_deleted_afterwards_without_a_restrict_violation() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        insertBudget(source, active = true)
        insertBudget(target, active = true)

        categoryDao.reassignAllReferencesExceptExpenses(source, target)
        categoryDao.delete(categoryDao.getById(source)!!)

        assertNull(categoryDao.getById(source))
        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM budgets WHERE categoryId = ?", target))
    }

    @Test
    fun reassignAllReferencesExceptExpenses_rejects_the_same_id_and_changes_nothing() = runTest {
        val seeded = seedAllReferences()

        try {
            categoryDao.reassignAllReferencesExceptExpenses(seeded.source, seeded.source)
            fail("Expected IllegalArgumentException for source == target")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertRefsPointTo(seeded.refs, seeded.source)
    }

    @Test
    fun reassignAllReferencesExceptExpenses_rejects_a_missing_target_and_changes_nothing() = runTest {
        val seeded = seedAllReferences()
        val missingTarget = 987_654L
        assertNull(categoryDao.getById(missingTarget))

        try {
            categoryDao.reassignAllReferencesExceptExpenses(seeded.source, missingTarget)
            fail("Expected IllegalArgumentException for a missing target")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        assertRefsPointTo(seeded.refs, seeded.source)
    }

    @Test
    fun reassignAllReferencesExceptExpenses_rejects_a_missing_source() = runTest {
        val target = insertCategory("Target")
        val missingSource = 987_654L
        assertNull(categoryDao.getById(missingSource))

        try {
            categoryDao.reassignAllReferencesExceptExpenses(missingSource, target)
            fail("Expected IllegalArgumentException for a missing source")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun updateNameById_renames_only_the_name_and_reports_one_row() = runTest {
        val id = categoryDao.insert(makeCategory("Bills", "B", "#546E7A"))
        val other = categoryDao.insert(makeCategory("Other", "O", "#112233"))

        val updated = categoryDao.updateNameById(id, "Utilities")

        assertEquals(1, updated)
        val renamed = categoryDao.getById(id)!!
        assertEquals("Utilities", renamed.name)
        assertEquals("B", renamed.icon)
        assertEquals("#546E7A", renamed.color)
        assertFalse(renamed.isDefault)
        assertEquals("Other", categoryDao.getById(other)!!.name)
    }

    private fun scalarString(sql: String, vararg args: Any): String? =
        database.openHelper.readableDatabase.query(sql, arrayOf<Any?>(*args)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    /** Seeds a recommendation and a receipt item (suggested + user-corrected) naming [name]; returns (recommendationId, itemId). */
    private suspend fun seedNamedRows(categoryId: Long, name: String): Pair<Long, Long> {
        val budgetId = insertBudget(categoryId, active = false)
        val recommendationId = positive(
            "recommendation",
            database.budgetAdjustmentDao().insertRecommendation(
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
        )
        val receiptId = positive(
            "scanned receipt",
            database.scannedReceiptDao().insert(
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
        )
        val itemId = positive(
            "receipt item",
            database.receiptItemCategorizationDao().insert(
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
        )
        return recommendationId to itemId
    }

    private fun namesOf(rows: Pair<Long, Long>): List<String?> = listOf(
        scalarString("SELECT categoryName FROM budget_adjustment_recommendations WHERE id = ?", rows.first),
        scalarString("SELECT suggestedCategoryName FROM receipt_item_categorizations WHERE id = ?", rows.second),
        scalarString("SELECT userCorrectedCategoryName FROM receipt_item_categorizations WHERE id = ?", rows.second)
    )

    @Test
    fun updateDenormalizedCategoryNames_rewrites_all_three_name_columns_of_that_category_only() = runTest {
        val id = insertCategory("Old")
        val other = insertCategory("Other")
        val mine = seedNamedRows(id, "Old")
        val theirs = seedNamedRows(other, "Other")
        assertEquals("control", listOf<String?>("Old", "Old", "Old"), namesOf(mine))

        categoryDao.updateDenormalizedCategoryNames(id, "New")

        assertEquals(listOf<String?>("New", "New", "New"), namesOf(mine))
        assertEquals(listOf<String?>("Other", "Other", "Other"), namesOf(theirs))
    }

    @Test
    fun reassignAllReferencesExceptExpenses_sets_moved_rows_to_the_target_name_and_spares_bystanders() = runTest {
        val source = insertCategory("Source")
        val target = insertCategory("Target")
        val bystander = insertCategory("Bystander")
        val moved = seedNamedRows(source, "Source")
        val untouched = seedNamedRows(bystander, "Bystander")

        categoryDao.reassignAllReferencesExceptExpenses(source, target)

        assertEquals(listOf<String?>("Target", "Target", "Target"), namesOf(moved))
        assertEquals(listOf<String?>("Bystander", "Bystander", "Bystander"), namesOf(untouched))
    }

    @Test
    fun updateNameById_for_a_missing_id_updates_nothing() = runTest {
        val id = categoryDao.insert(makeCategory("Bills", "B", "#546E7A"))

        val updated = categoryDao.updateNameById(987_654L, "Ghost")

        assertEquals(0, updated)
        assertEquals("Bills", categoryDao.getById(id)!!.name)
        assertEquals(1, categoryDao.getCount())
    }

    @Test
    fun countExpensesForCategory_counts_only_that_category_and_is_read_only() = runTest {
        val food = insertCategory("Food")
        val travel = insertCategory("Travel")
        assertEquals("control: no expenses yet", 0, categoryDao.countExpensesForCategory(food))
        insertExpense(food, merchant = "One")
        insertExpense(food, merchant = "Two")
        insertExpense(travel, merchant = "Three")
        insertExpense(null, merchant = "Four")

        assertEquals(2, categoryDao.countExpensesForCategory(food))
        assertEquals(2, categoryDao.countExpensesForCategory(food))
        assertEquals(1, categoryDao.countExpensesForCategory(travel))
        assertEquals(0, categoryDao.countExpensesForCategory(987_654L))
        assertEquals(4L, scalarLong("SELECT COUNT(*) FROM expenses"))
    }
}
