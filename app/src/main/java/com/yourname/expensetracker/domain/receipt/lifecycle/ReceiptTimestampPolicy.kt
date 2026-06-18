package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.ScannedReceipt

/**
 * Centralizes timestamp normalization for [ScannedReceipt] insert and update
 * operations so that no persisted receipt row ends up with `createdAt = 0` or
 * `updatedAt = 0`.
 *
 * Every Pipeline 3 path that constructs a [ScannedReceipt] for persistence
 * MUST use [forInsert] or [forUpdate].
 */
internal object ReceiptTimestampPolicy {

    /**
     * Prepares a receipt for initial insertion into the database.
     *
     * - Sets [ScannedReceipt.createdAt] to [now] when it is 0 (sentinel).
     * - Sets [ScannedReceipt.updatedAt] to [now] when it is 0 (sentinel).
     * - Preserves existing valid (> 0) timestamps so callers who already
     *   set them are not overridden.
     */
    fun forInsert(receipt: ScannedReceipt, now: Long): ScannedReceipt {
        val created = receipt.createdAt.takeIf { it > 0L } ?: now
        val updated = receipt.updatedAt.takeIf { it > 0L } ?: now
        return receipt.copy(createdAt = created, updatedAt = updated)
    }

    /**
     * Prepares a receipt for an update operation.
     *
     * - Sets [ScannedReceipt.updatedAt] to [now].
     * - Repairs [ScannedReceipt.createdAt] from 0 to [now] as a safety net
     *   (should not be necessary after forInsert, but guards legacy data).
     */
    fun forUpdate(receipt: ScannedReceipt, now: Long): ScannedReceipt {
        val created = receipt.createdAt.takeIf { it > 0L } ?: now
        return receipt.copy(createdAt = created, updatedAt = now)
    }
}
