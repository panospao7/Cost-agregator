package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SourceLearningPolicy
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import dagger.Lazy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TransactionSideEffectPlanner].
 *
 * Validates U-SIDEEFFECT-02 fix: trigger types and idempotency keys must
 * reflect the actual lifecycle event (CREATED vs UPDATED), not be hardcoded.
 */
class TransactionSideEffectPlannerTest {

    private lateinit var planner: TransactionSideEffectPlanner
    private val timeProvider = FakeTimeProvider(1_712_000_000_000L)

    @Before
    fun setup() {
        planner = TransactionSideEffectPlanner(
            budgetMonitor = Lazy { mockk<BudgetMonitor>(relaxed = true) },
            anomalyAlertOrchestrator = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = mockk(relaxed = true),
            recurringLifecycleCoordinator = Lazy { mockk<RecurringLifecycleCoordinator>(relaxed = true) },
            expenseDao = mockk(relaxed = true),
            categoryDao = mockk(relaxed = true),
            timeProvider = timeProvider
        )
    }

    // --- U-SIDEEFFECT-02: planCreated uses EXPENSE_CREATED trigger ---

    @Test
    fun `planCreated uses EXPENSE_CREATED trigger for merchant category learning`() {
        val batch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-1")
        val action = batch.actions.first { it.name == "merchant_category_pattern_learning" }

        assertEquals(SideEffectTriggerType.EXPENSE_CREATED, action.triggerType)
        assertTrue(
            "Idempotency key should contain 'expense_created', got: ${action.idempotencyKey}",
            action.idempotencyKey.contains("expense_created")
        )
    }

    @Test
    fun `planCreated uses EXPENSE_CREATED trigger for merchant canonical stats`() {
        val batch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-1")
        val action = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals(SideEffectTriggerType.EXPENSE_CREATED, action.triggerType)
        assertTrue(
            "Idempotency key should contain 'expense_created', got: ${action.idempotencyKey}",
            action.idempotencyKey.contains("expense_created")
        )
    }

    // --- U-SIDEEFFECT-02: planUpdated uses EXPENSE_UPDATED trigger ---

    @Test
    fun `planUpdated FULL uses EXPENSE_UPDATED trigger for merchant category learning`() {
        val batch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)
        val action = batch.actions.first { it.name == "merchant_category_pattern_learning" }

        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, action.triggerType)
        assertTrue(
            "Idempotency key should contain 'expense_updated', got: ${action.idempotencyKey}",
            action.idempotencyKey.contains("expense_updated")
        )
    }

    @Test
    fun `planUpdated FULL does not include merchant canonical stats`() {
        val batch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("FULL update should not include merchant canonical stats (avoids double-count)", !hasStats)
    }

    @Test
    fun `planUpdated MERCHANT does not include merchant canonical stats`() {
        val batch = planner.planUpdated(1L, "manual", "corr-3", TransactionUpdateKind.MERCHANT)
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("MERCHANT update should not include canonical stats", !hasStats)
    }

    // --- U-SIDEEFFECT-02: Idempotency key uniqueness between create and update ---

    @Test
    fun `create and update produce distinct idempotency keys for merchant category learning`() {
        val createBatch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-1")
        val updateBatch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)

        val createKey = createBatch.actions.first { it.name == "merchant_category_pattern_learning" }.idempotencyKey
        val updateKey = updateBatch.actions.first { it.name == "merchant_category_pattern_learning" }.idempotencyKey

        assertNotEquals(
            "Create and update idempotency keys must be distinct to avoid false dedup",
            createKey, updateKey
        )
    }

    @Test
    fun `planCreated includes merchant canonical stats`() {
        val batch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-1")
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("planCreated should include merchant canonical stats", hasStats)
    }

    // --- Idempotency key format verification ---

    @Test
    fun `idempotency key format is expense_id_triggertype_actionname`() {
        val batch = planner.planCreated(42L, ExpenseSource.REVIEW_APPROVAL, "corr-x")
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        val stats = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals("expense:42:expense_created:merchant_category_learning", learning.idempotencyKey)
        assertEquals("expense:42:expense_created:merchant_stats", stats.idempotencyKey)
    }

    @Test
    fun `planUpdated AMOUNT does not include merchant canonical stats`() {
        val batch = planner.planUpdated(42L, "bank_sync", "corr-y", TransactionUpdateKind.AMOUNT)
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("AMOUNT update should not include merchant canonical stats", !hasStats)
    }

    // --- planUpdated CATEGORY_ONLY does NOT include merchant learning ---

    @Test
    fun `planUpdated CATEGORY_ONLY includes merchant category learning`() {
        val batch = planner.planUpdated(1L, "manual", "corr-4", TransactionUpdateKind.CATEGORY_ONLY)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, learning.triggerType)
        val statsPresent = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("CATEGORY_ONLY should not include canonical stats update", !statsPresent)
    }

    // --- planUpdated LOCATION_ONLY returns empty ---

    @Test
    fun `planUpdated LOCATION_ONLY returns empty batch`() {
        val batch = planner.planUpdated(1L, "manual", "corr-5", TransactionUpdateKind.LOCATION_ONLY)
        assertTrue("LOCATION_ONLY should produce empty batch", batch.actions.isEmpty())
    }

    // --- planDeleted uses EXPENSE_DELETED ---

    @Test
    fun `planDeleted uses EXPENSE_DELETED trigger`() {
        val batch = planner.planDeleted(1L, "manual", "corr-6")
        val budgetAction = batch.actions.first { it.name == "budget_check" }
        assertEquals(SideEffectTriggerType.EXPENSE_DELETED, budgetAction.triggerType)
        assertTrue(budgetAction.idempotencyKey.contains("expense_deleted"))
    }

    @Test
    fun `planDeleted does not include merchant canonical stats`() {
        val batch = planner.planDeleted(1L, "manual", "corr-6")
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("Deleted expense should not trigger merchant stats update", !hasStats)
    }

    // --- SourceLearningPolicy ---

    @Test
    fun `isTrustedForLearning returns true for MANUAL_ENTRY`() {
        assertTrue(SourceLearningPolicy.isTrustedForLearning(ExpenseSource.MANUAL_ENTRY))
    }

    @Test
    fun `isTrustedForLearning returns false for NOTIFICATION_AUTO_ACCEPT`() {
        assertTrue(!SourceLearningPolicy.isTrustedForLearning(ExpenseSource.NOTIFICATION_AUTO_ACCEPT))
    }

    @Test
    fun `isTrustedForLearning String overload parses correctly`() {
        assertTrue(SourceLearningPolicy.isTrustedForLearning("manual_entry"))
        assertTrue(!SourceLearningPolicy.isTrustedForLearning("notification_auto_accept"))
        assertTrue(!SourceLearningPolicy.isTrustedForLearning("nonexistent_source"))
    }

    @Test
    fun `isTrustedForLearning returns true for USER_EDIT production string`() {
        assertTrue(SourceLearningPolicy.isTrustedForLearning("USER_EDIT"))
    }

    @Test
    fun `isTrustedForLearning returns false for SYSTEM production string`() {
        assertTrue(!SourceLearningPolicy.isTrustedForLearning("SYSTEM"))
    }

    // --- planCreated source-aware learning ---

    @Test
    fun `planCreated with MANUAL_ENTRY includes merchant category learning`() {
        val batch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-7")
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        assertEquals(SideEffectTriggerType.EXPENSE_CREATED, learning.triggerType)
    }

    @Test
    fun `planCreated with NOTIFICATION_AUTO_ACCEPT skips merchant category learning`() {
        val batch = planner.planCreated(1L, ExpenseSource.NOTIFICATION_AUTO_ACCEPT, "corr-8")
        val hasLearning = batch.actions.any { it.name == "merchant_category_pattern_learning" }
        assertTrue("NOTIFICATION_AUTO_ACCEPT should not trigger merchant learning", !hasLearning)
    }

    @Test
    fun `planCreated with NOTIFICATION_AUTO_ACCEPT still includes merchant canonical stats`() {
        val batch = planner.planCreated(1L, ExpenseSource.NOTIFICATION_AUTO_ACCEPT, "corr-8")
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("planCreated should include merchant canonical stats regardless of source", hasStats)
    }

    // --- planUpdated source-aware learning ---

    @Test
    fun `planUpdated AMOUNT does not include merchant category learning`() {
        val batch = planner.planUpdated(1L, "bank_sync", "corr-9", TransactionUpdateKind.AMOUNT)
        val hasLearning = batch.actions.any { it.name == "merchant_category_pattern_learning" }
        assertTrue("AMOUNT update should not include merchant category learning", !hasLearning)
    }

    @Test
    fun `planUpdated FULL with untrusted source skips merchant category learning`() {
        val batch = planner.planUpdated(1L, "bank_sync", "corr-10", TransactionUpdateKind.FULL)
        val hasLearning = batch.actions.any { it.name == "merchant_category_pattern_learning" }
        assertTrue("FULL with untrusted source should skip merchant category learning", !hasLearning)
    }

    @Test
    fun `planUpdated FULL with trusted source includes merchant category learning`() {
        val batch = planner.planUpdated(1L, "manual", "corr-11", TransactionUpdateKind.FULL)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, learning.triggerType)
    }

    @Test
    fun `planUpdated FULL with USER_EDIT source includes merchant category learning`() {
        val batch = planner.planUpdated(1L, "USER_EDIT", "corr-12", TransactionUpdateKind.FULL)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, learning.triggerType)
    }

    @Test
    fun `planUpdated CATEGORY_ONLY with USER_EDIT source includes merchant category learning`() {
        val batch = planner.planUpdated(1L, "USER_EDIT", "corr-13", TransactionUpdateKind.CATEGORY_ONLY)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, learning.triggerType)
    }

    @Test
    fun `planUpdated FULL with SYSTEM source skips merchant category learning`() {
        val batch = planner.planUpdated(1L, "SYSTEM", "corr-14", TransactionUpdateKind.FULL)
        val hasLearning = batch.actions.any { it.name == "merchant_category_pattern_learning" }
        assertTrue("SYSTEM source should not trigger merchant category learning", !hasLearning)
    }

    @Test
    fun `planUpdated BUSINESS_FLAGS_ONLY does not include merchant canonical stats`() {
        val batch = planner.planUpdated(1L, "manual", "corr-15", TransactionUpdateKind.BUSINESS_FLAGS_ONLY)
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("BUSINESS_FLAGS_ONLY should not include merchant canonical stats", !hasStats)
    }

    @Test
    fun `planUpdated FULL with SYSTEM source does not include merchant canonical stats`() {
        val batch = planner.planUpdated(1L, "SYSTEM", "corr-16", TransactionUpdateKind.FULL)
        val hasStats = batch.actions.any { it.name == "merchant_canonical_stats_update" }
        assertTrue("FULL update with SYSTEM source should not include merchant canonical stats", !hasStats)
    }
}
