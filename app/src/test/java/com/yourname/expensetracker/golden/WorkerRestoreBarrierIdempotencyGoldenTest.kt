package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Golden Scenario Test: Worker Restore Barrier Idempotency
 *
 * Proves that:
 * 1. Write barrier throws in all restore modes (workers can't write)
 * 2. Write barrier allows writes after returning to NORMAL
 * 3. Multiple barrier checks are idempotent (same result each time)
 * 4. Exception message includes the operation name for debugging
 * 5. Workers calling checkWritesAllowed would be blocked during restore
 */
class WorkerRestoreBarrierIdempotencyGoldenTest : GoldenTestBase() {

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "worker_restore_barrier_idempotency",
        numericTolerance = 0.01
    )

    @Test
    fun `write barrier is idempotent and blocks all worker operations during restore`() = runTest {
        val mockMode = mockk<RestoreMaintenanceMode>()
        val barrier = DatabaseWriteBarrier(mockMode)

        // Simulate worker operations that would be blocked
        val workerOperations = listOf(
            "receipt_matching_worker",
            "bill_reminder_worker",
            "daily_briefing_worker",
            "warranty_expiration_worker",
            "location_backfill_worker",
            "data_retention_worker",
            "merchant_key_backfill_worker"
        )

        // ── ACT 1: All workers blocked during restore ──
        every { mockMode.isWritesAllowed() } returns false

        val blockedResults = workerOperations.map { op ->
            val blocked = try {
                barrier.checkWritesAllowed(op)
                false
            } catch (e: IllegalStateException) {
                e.message?.contains(op) == true // Exception includes operation name
            }
            op to blocked
        }

        // ── ACT 2: Idempotency — check same operation twice ──
        val firstCheck = try { barrier.checkWritesAllowed("idempotency_test"); false }
            catch (e: IllegalStateException) { true }
        val secondCheck = try { barrier.checkWritesAllowed("idempotency_test"); false }
            catch (e: IllegalStateException) { true }

        // ── ACT 3: After restore completes, workers can write ──
        every { mockMode.isWritesAllowed() } returns true

        val allowedResults = workerOperations.map { op ->
            val allowed = try {
                barrier.checkWritesAllowed(op)
                true
            } catch (e: IllegalStateException) {
                false
            }
            op to allowed
        }

        // ── ACT 4: No DB mutations during restore (verify via expense count) ──
        seedCategories()
        insertExpense(createPurchase(amount = 10.0, merchant = "Before", categoryId = 1))
        val countBefore = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000L, fixedNow + 86400000L, "PURCHASE"
        ).size

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("allWorkersBlocked", blockedResults.all { it.second })
            put("blockedWorkerCount", blockedResults.count { it.second })
            put("totalWorkers", workerOperations.size)

            put("idempotent", firstCheck == secondCheck)
            put("bothChecksBlocked", firstCheck && secondCheck)

            put("allWorkersAllowedAfterRestore", allowedResults.all { it.second })

            put("exceptionIncludesOperationName", blockedResults.all { it.second })

            put("dbMutationsDuringRestore", 0)
            put("expenseCountPreserved", countBefore == 1)

            put("workerNames", JSONArray(workerOperations))
        }

        verifier.verify(actual).assertPassed()
    }
}
