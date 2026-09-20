package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RP-12 12c (P3-008): explicit asset ownership for the save-first OCR design.
 *
 * Once a receipt image path is created it is an UNCOMMITTED temporary asset
 * until a receipt row referencing it commits. [cleanupUncommittedAsset] is the
 * only sanctioned way to dispose of one:
 *
 *  1. under a database write transaction, the path's reference count
 *     ([ScannedReceiptDao.countReferencesToImagePath]) must be zero;
 *  2. an attempt-local cleanup claim is recorded so two attempts can never
 *     delete the same path concurrently (paths are UUID-owned per attempt and
 *     never reused, so a later insert cannot race the claim for the same path
 *     — a successful insert commits in its own transaction and the count check
 *     then refuses the cleanup);
 *  3. the claimed unreferenced file is deleted and the claim released.
 *
 * Cleanup is BEST-EFFORT: failures return false with controlled diagnostics
 * and never mask the original error the caller is handling. A path referenced
 * by any receipt row is never deleted.
 *
 * Diagnostics never carry file paths — attempt ids, booleans, and controlled
 * reason strings only.
 */
@Singleton
class AssetCleanupCoordinator @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val assetStore: ReceiptAssetStore,
    private val writeBarrier: DatabaseWriteBarrier,
    private val transactionRunner: DomainTransactionRunner
) {

    /** path -> attemptId for in-flight cleanups; released in a finally block. */
    private val claimedPaths = ConcurrentHashMap<String, String>()

    /**
     * Attempts best-effort cleanup of an uncommitted receipt asset.
     *
     * @param path the saved image path owned by this attempt (null/blank = no-op)
     * @param attemptId unique id of the processing attempt that owns the path
     * @return true only when the unreferenced file was actually deleted
     */
    suspend fun cleanupUncommittedAsset(
        path: String?,
        attemptId: String,
        reason: String
    ): Boolean {
        if (path.isNullOrBlank()) return false

        val existingClaim = claimedPaths.putIfAbsent(path, attemptId)
        if (existingClaim != null && existingClaim != attemptId) {
            Timber.d("Asset cleanup skipped: path claimed by another attempt")
            return false
        }
        try {
            try {
                writeBarrier.checkWritesAllowed("AssetCleanupCoordinator.cleanupUncommittedAsset")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Asset cleanup blocked: writes not allowed (%s)", reason)
                return false
            }

            val unreferenced = try {
                transactionRunner.runInTransaction(
                    correlationId = CorrelationIds.newId(),
                    operationId = "receipt.asset.cleanup",
                    source = "AssetCleanupCoordinator"
                ) { _ ->
                    scannedReceiptDao.countReferencesToImagePath(path) == 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Asset cleanup reference check failed (%s)", reason)
                false
            }
            if (!unreferenced) {
                Timber.d("Asset cleanup refused: path referenced by a receipt row (%s)", reason)
                return false
            }

            return try {
                assetStore.deleteAsset(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // G-CANCEL-01: never swallow cancellation in best-effort cleanup.
                if (e is CancellationException) throw e
                Timber.w("Asset cleanup delete failed (%s)", reason)
                false
            }
        } finally {
            claimedPaths.remove(path, attemptId)
        }
    }
}
