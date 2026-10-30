package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SourceLearningPolicy
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import dagger.Lazy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TransactionSideEffectPlanner].
 *
 * Validates U-SIDEEFFECT-02 fix: trigger types and idempotency keys must
 * reflect the actual lifecycle event (CREATED vs UPDATED), not be hardcoded.
 *
 * B6-16: same-scenario groups are matrices; every row asserts the exact ordered
 * planned action list (name, trigger type, idempotency key), which subsumes the
 * former per-action "has/has no merchant stats / learning" assertions.
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
            timeProvider = timeProvider,
            installmentPlanLifecycleCoordinator = Lazy { mockk<InstallmentPlanLifecycleCoordinator>(relaxed = true) },
            postCommitActionRunner = Lazy { mockk<PostCommitActionRunner>(relaxed = true) }
        )
    }

    /** One planned action reduced to the fields the lifecycle contract pins. */
    private data class Planned(
        val name: String,
        val trigger: SideEffectTriggerType,
        val idempotencyKey: String
    )

    private fun PostCommitActionBatch.planned(): List<Planned> =
        actions.map { Planned(it.name, it.triggerType, it.idempotencyKey) }

    private fun created(name: String, key: String) =
        Planned(name, SideEffectTriggerType.EXPENSE_CREATED, key)

    private fun updated(name: String, key: String) =
        Planned(name, SideEffectTriggerType.EXPENSE_UPDATED, key)

    // --- U-SIDEEFFECT-02 + planCreated source-aware learning (matrix) ---

    private class CreatedRow(
        val name: String,
        val expenseId: Long,
        val source: ExpenseSource,
        val correlationId: String,
        val expected: List<Planned>
    )

    @Test
    fun `planCreated by source plans exact ordered EXPENSE_CREATED actions and learns only from trusted sources`() {
        val rows = listOf(
            CreatedRow(
                name = "MANUAL_ENTRY trusted -> learning appended",
                expenseId = 1L, source = ExpenseSource.MANUAL_ENTRY, correlationId = "corr-1",
                expected = listOf(
                    created("budget_check", "expense:1:expense_created:budget_check"),
                    created("anomaly_alert_check", "expense:1:expense_created:anomaly_alert_check"),
                    created("merchant_canonical_stats_update", "expense:1:expense_created:merchant_stats"),
                    created("recurring_occurrence_matching", "expense:1:created:recurring_matching"),
                    created("merchant_category_pattern_learning", "expense:1:expense_created:merchant_category_learning")
                )
            ),
            CreatedRow(
                name = "REVIEW_APPROVAL trusted, id 42 -> key format expense_id_triggertype_actionname",
                expenseId = 42L, source = ExpenseSource.REVIEW_APPROVAL, correlationId = "corr-x",
                expected = listOf(
                    created("budget_check", "expense:42:expense_created:budget_check"),
                    created("anomaly_alert_check", "expense:42:expense_created:anomaly_alert_check"),
                    created("merchant_canonical_stats_update", "expense:42:expense_created:merchant_stats"),
                    created("recurring_occurrence_matching", "expense:42:created:recurring_matching"),
                    created("merchant_category_pattern_learning", "expense:42:expense_created:merchant_category_learning")
                )
            ),
            CreatedRow(
                name = "NOTIFICATION_AUTO_ACCEPT untrusted -> no learning, stats still planned",
                expenseId = 1L, source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT, correlationId = "corr-8",
                expected = listOf(
                    created("budget_check", "expense:1:expense_created:budget_check"),
                    created("anomaly_alert_check", "expense:1:expense_created:anomaly_alert_check"),
                    created("merchant_canonical_stats_update", "expense:1:expense_created:merchant_stats"),
                    created("recurring_occurrence_matching", "expense:1:created:recurring_matching")
                )
            )
        )
        assertEquals("planCreated matrix size guard", 3, rows.size)

        for (row in rows) {
            val batch = planner.planCreated(row.expenseId, row.source, row.correlationId)
            assertEquals("[${row.name}] correlation id", row.correlationId, batch.correlationId)
            assertEquals("[${row.name}] planned actions", row.expected, batch.planned())
        }
    }

    // --- U-SIDEEFFECT-02 + planUpdated by kind and source (matrix) ---

    private class UpdatedRow(
        val name: String,
        val kind: TransactionUpdateKind,
        val source: String,
        val correlationId: String,
        val expected: List<Planned>,
        val expenseId: Long = 1L
    )

    private val updatedBudget = updated("budget_check", "expense:1:expense_updated:budget_check")
    private val updatedAnomaly = updated("anomaly_alert_check", "expense:1:expense_updated:anomaly_alert_check")
    private val updatedReconcile = updated("recurring_reconcile", "expense:1:updated:recurring_reconcile")
    private val updatedLearning =
        updated("merchant_category_pattern_learning", "expense:1:expense_updated:merchant_category_learning")
    // Loan-installments S6c-1: appended last, only for FULL / TYPE / CURRENCY / TRANSFER_DETAILS / PAYMENT_CORE.
    private val updatedInstallment = updated("installment_expense_changed", "expense:1:updated:installment_changed")

    @Test
    fun `planUpdated by kind and source plans exact ordered EXPENSE_UPDATED actions and never merchant stats`() {
        val rows = listOf(
            UpdatedRow(
                "FULL manual (trusted)", TransactionUpdateKind.FULL, "manual", "corr-2",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile, updatedLearning, updatedInstallment)
            ),
            // B6 nit (46): non-default id with literal keys (mirrors the planCreated id-42 row), so the
            // expense id is proven to be interpolated into every key rather than hard-wired to 1.
            UpdatedRow(
                "FULL manual, id 42 -> key format expense_id_triggertype_actionname",
                TransactionUpdateKind.FULL, "manual", "corr-42",
                listOf(
                    updated("budget_check", "expense:42:expense_updated:budget_check"),
                    updated("anomaly_alert_check", "expense:42:expense_updated:anomaly_alert_check"),
                    updated("recurring_reconcile", "expense:42:updated:recurring_reconcile"),
                    updated("merchant_category_pattern_learning", "expense:42:expense_updated:merchant_category_learning"),
                    updated("installment_expense_changed", "expense:42:updated:installment_changed")
                ),
                expenseId = 42L
            ),
            UpdatedRow(
                "FULL USER_EDIT (trusted production string)", TransactionUpdateKind.FULL, "USER_EDIT", "corr-12",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile, updatedLearning, updatedInstallment)
            ),
            UpdatedRow(
                "FULL bank_sync (untrusted) skips learning", TransactionUpdateKind.FULL, "bank_sync", "corr-10",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile, updatedInstallment)
            ),
            UpdatedRow(
                "FULL SYSTEM (untrusted) skips learning", TransactionUpdateKind.FULL, "SYSTEM", "corr-14",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile, updatedInstallment)
            ),
            UpdatedRow(
                "MERCHANT manual has no learning", TransactionUpdateKind.MERCHANT, "manual", "corr-3",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile)
            ),
            UpdatedRow(
                "AMOUNT bank_sync has no learning", TransactionUpdateKind.AMOUNT, "bank_sync", "corr-9",
                listOf(updatedBudget, updatedAnomaly, updatedReconcile)
            ),
            UpdatedRow(
                "CATEGORY_ONLY manual learns, no recurring reconcile", TransactionUpdateKind.CATEGORY_ONLY, "manual", "corr-4",
                listOf(updatedBudget, updatedAnomaly, updatedLearning)
            ),
            UpdatedRow(
                "CATEGORY_ONLY USER_EDIT learns, no recurring reconcile", TransactionUpdateKind.CATEGORY_ONLY, "USER_EDIT", "corr-13",
                listOf(updatedBudget, updatedAnomaly, updatedLearning)
            ),
            UpdatedRow(
                "BUSINESS_FLAGS_ONLY manual", TransactionUpdateKind.BUSINESS_FLAGS_ONLY, "manual", "corr-15",
                listOf(updatedBudget, updatedAnomaly)
            ),
            UpdatedRow(
                "LOCATION_ONLY manual returns empty batch", TransactionUpdateKind.LOCATION_ONLY, "manual", "corr-5",
                emptyList()
            )
        )
        assertEquals("planUpdated matrix size guard", 11, rows.size)

        for (row in rows) {
            val batch = planner.planUpdated(row.expenseId, row.source, row.correlationId, row.kind)
            assertEquals("[${row.name}] correlation id", row.correlationId, batch.correlationId)
            assertEquals("[${row.name}] planned actions", row.expected, batch.planned())
        }
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

    // --- planDeleted uses EXPENSE_DELETED ---

    @Test
    fun `planDeleted plans exact EXPENSE_DELETED actions and no merchant canonical stats`() {
        val batch = planner.planDeleted(1L, "manual", "corr-6")

        assertEquals("corr-6", batch.correlationId)
        assertEquals(
            listOf(
                Planned("budget_check", SideEffectTriggerType.EXPENSE_DELETED, "expense:1:expense_deleted:budget_check"),
                Planned("recurring_unlink", SideEffectTriggerType.EXPENSE_DELETED, "expense:1:deleted:recurring_unlink"),
                Planned("installment_expense_deleted", SideEffectTriggerType.EXPENSE_DELETED, "expense:1:deleted:installment_deleted")
            ),
            batch.planned()
        )
    }

    // --- SourceLearningPolicy (matrices) ---

    @Test
    fun `isTrustedForLearning enum overload trusts MANUAL_ENTRY and rejects NOTIFICATION_AUTO_ACCEPT`() {
        val rows = listOf(
            ExpenseSource.MANUAL_ENTRY to true,
            ExpenseSource.NOTIFICATION_AUTO_ACCEPT to false
        )
        assertEquals("enum overload matrix size guard", 2, rows.size)

        for ((source, expected) in rows) {
            assertEquals("[$source]", expected, SourceLearningPolicy.isTrustedForLearning(source))
        }
    }

    @Test
    fun `isTrustedForLearning String overload parses enum names and production strings`() {
        val rows = listOf(
            "manual_entry" to true,
            "notification_auto_accept" to false,
            "nonexistent_source" to false,
            "USER_EDIT" to true,
            "SYSTEM" to false
        )
        assertEquals("String overload matrix size guard", 5, rows.size)

        for ((source, expected) in rows) {
            assertEquals("[$source]", expected, SourceLearningPolicy.isTrustedForLearning(source))
        }
    }
}
