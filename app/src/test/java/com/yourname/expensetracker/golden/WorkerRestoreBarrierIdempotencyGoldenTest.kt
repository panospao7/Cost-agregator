package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Golden Scenario Test: Worker Restore Barrier Idempotency
 *
 * Proves that:
 * 1. Write barrier rejects every simulated worker operation in RESTORE_PREPARING
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
        seedCategories()
        insertExpense(createPurchase(amount = 10.0, merchant = "Before", categoryId = 1))
        val countBefore = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000L, fixedNow + 86400000L, "PURCHASE"
        ).size

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
        every { mockMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val blockedResults = workerOperations.map { op ->
            val blocked = try {
                barrier.runWrite(DatabaseAccessOperation(op)) {
                    insertExpense(createPurchase(amount = 1.0, merchant = "Blocked $op", categoryId = 1))
                }
                false
            } catch (e: DatabaseAccessBlockedException) {
                assertEquals(DatabaseAccessType.WRITE, e.accessType)
                assertEquals(RestoreMaintenanceMode.Mode.RESTORE_PREPARING, e.mode)
                assertEquals(op, e.operation.name)
                e.message?.contains(op) == true // Exception includes operation name
            }
            op to blocked
        }

        // ── ACT 2: Idempotency — check same operation twice ──
        val firstCheck = try { barrier.checkWritesAllowed("idempotency_test"); false }
            catch (e: DatabaseAccessBlockedException) {
                e.accessType == DatabaseAccessType.WRITE &&
                    e.mode == RestoreMaintenanceMode.Mode.RESTORE_PREPARING &&
                    e.operation.name == "idempotency_test"
            }
        val secondCheck = try { barrier.checkWritesAllowed("idempotency_test"); false }
            catch (e: DatabaseAccessBlockedException) {
                e.accessType == DatabaseAccessType.WRITE &&
                    e.mode == RestoreMaintenanceMode.Mode.RESTORE_PREPARING &&
                    e.operation.name == "idempotency_test"
            }

        val countDuringRestore = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000L, fixedNow + 86400000L, "PURCHASE"
        ).size

        // ── ACT 3: After restore completes, workers can write ──
        every { mockMode.isWritesAllowed() } returns true
        every { mockMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL

        val allowedResults = workerOperations.map { op ->
            val allowed = try {
                barrier.runWrite(DatabaseAccessOperation(op)) { true }
            } catch (e: DatabaseAccessBlockedException) {
                false
            }
            op to allowed
        }

        // ── ACT 4: No DB mutations during restore (verify via expense count) ──
        val countAfter = database.expenseDao().getExpensesByTypeBetween(
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

            put("dbMutationsDuringRestore", countDuringRestore - countBefore)
            put("expenseCountPreserved", countBefore == 1 && countAfter == countBefore)

            put("workerNames", JSONArray(workerOperations))
        }

        verifier.verify(actual).assertPassed()
    }
}
