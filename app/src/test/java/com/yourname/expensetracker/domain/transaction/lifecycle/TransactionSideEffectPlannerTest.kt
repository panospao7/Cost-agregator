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

    @Before
    fun setup() {
        planner = TransactionSideEffectPlanner(
            budgetMonitor = Lazy { mockk<BudgetMonitor>(relaxed = true) },
            anomalyAlertOrchestrator = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = mockk(relaxed = true),
            recurringLifecycleCoordinator = Lazy { mockk<RecurringLifecycleCoordinator>(relaxed = true) },
            expenseDao = mockk(relaxed = true),
            categoryDao = mockk(relaxed = true)
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
    fun `planUpdated FULL uses EXPENSE_UPDATED trigger for merchant canonical stats`() {
        val batch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)
        val action = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, action.triggerType)
        assertTrue(
            "Idempotency key should contain 'expense_updated', got: ${action.idempotencyKey}",
            action.idempotencyKey.contains("expense_updated")
        )
    }

    @Test
    fun `planUpdated MERCHANT uses EXPENSE_UPDATED trigger for merchant learning actions`() {
        val batch = planner.planUpdated(1L, "manual", "corr-3", TransactionUpdateKind.MERCHANT)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        val stats = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, learning.triggerType)
        assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, stats.triggerType)
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
    fun `create and update produce distinct idempotency keys for merchant canonical stats`() {
        val createBatch = planner.planCreated(1L, ExpenseSource.MANUAL_ENTRY, "corr-1")
        val updateBatch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)

        val createKey = createBatch.actions.first { it.name == "merchant_canonical_stats_update" }.idempotencyKey
        val updateKey = updateBatch.actions.first { it.name == "merchant_canonical_stats_update" }.idempotencyKey

        assertNotEquals(
            "Create and update idempotency keys must be distinct to avoid false dedup",
            createKey, updateKey
        )
    }

    // --- Idempotency key format verification ---

    @Test
    fun `idempotency key format is expense_id_triggertype_actionname`() {
        val batch = planner.planCreated(42L, ExpenseSource.EMAIL_RECEIPT, "corr-x")
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        val stats = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals("expense:42:expense_created:merchant_category_learning", learning.idempotencyKey)
        assertEquals("expense:42:expense_created:merchant_stats", stats.idempotencyKey)
    }

    @Test
    fun `planUpdated idempotency key format uses expense_updated`() {
        val batch = planner.planUpdated(42L, "bank_sync", "corr-y", TransactionUpdateKind.AMOUNT)
        val learning = batch.actions.first { it.name == "merchant_category_pattern_learning" }
        val stats = batch.actions.first { it.name == "merchant_canonical_stats_update" }

        assertEquals("expense:42:expense_updated:merchant_category_learning", learning.idempotencyKey)
        assertEquals("expense:42:expense_updated:merchant_stats", stats.idempotencyKey)
    }

    // --- planUpdated CATEGORY_ONLY does NOT include merchant learning ---

    @Test
    fun `planUpdated CATEGORY_ONLY does not include merchant learning actions`() {
        val batch = planner.planUpdated(1L, "manual", "corr-4", TransactionUpdateKind.CATEGORY_ONLY)
        val merchantActions = batch.actions.filter {
            it.name == "merchant_category_pattern_learning" || it.name == "merchant_canonical_stats_update"
        }
        assertTrue("CATEGORY_ONLY should not trigger merchant learning", merchantActions.isEmpty())
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
}
