package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized resolver for [ScannedReceipt] insert conflicts.
 *
 * P3-BLOCKER-11 / P3-P1-06: Replaces ad-hoc `require(id > 0)` checks
 * scattered across Pipeline 3 code. When [ScannedReceiptDao.insert] is
 * ignored/conflicted (returns <= 0), this resolver looks up the existing
 * receipt by its strongest fingerprint and returns a typed outcome.
 */
@Singleton
class ReceiptInsertResolver @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao
) {

    /**
     * Attempts to insert [receipt] and resolves conflicts deterministically.
     *
     * - Returns [Inserted] when the insert succeeds (id > 0).
     * - Returns [Duplicate] when a conflict is found and the existing receipt
     *   is identified by one of its fingerprint fields.
     * - Returns [ConflictUnresolved] when the insert is ignored but no
     *   existing receipt could be matched.
     */
    suspend fun insertOrResolve(receipt: ScannedReceipt): ReceiptInsertResult {
        val id = scannedReceiptDao.insert(receipt)
        if (id > 0) return ReceiptInsertResult.Inserted(id)

        // Insert was ignored — resolve the pre-existing receipt.
        // Resolution order: imageHash (exact) > sourceFingerprint > textFingerprint > semanticFingerprint
        val existing = resolveConflict(receipt)
        return if (existing != null) {
            ReceiptInsertResult.Duplicate(existing, "insert_ignored")
        } else {
            ReceiptInsertResult.ConflictUnresolved("Insert conflicted but no existing receipt matched")
        }
    }

    private suspend fun resolveConflict(receipt: ScannedReceipt): ScannedReceipt? {
        if (!receipt.imageHash.isNullOrBlank()) {
            scannedReceiptDao.getByImageHash(receipt.imageHash!!)?.let { return it }
        }
        if (!receipt.sourceFingerprint.isNullOrBlank()) {
            scannedReceiptDao.getBySourceFingerprint(receipt.sourceFingerprint!!)?.let { return it }
        }
        if (!receipt.textFingerprint.isNullOrBlank()) {
            scannedReceiptDao.getByTextFingerprint(receipt.textFingerprint!!)?.let { return it }
        }
        if (!receipt.semanticFingerprint.isNullOrBlank()) {
            scannedReceiptDao.getBySemanticFingerprint(receipt.semanticFingerprint!!)?.let { return it }
        }
        return null
    }
}

sealed interface ReceiptInsertResult {
    data class Inserted(val receiptId: Long) : ReceiptInsertResult
    data class Duplicate(val existingReceipt: ScannedReceipt, val reason: String) : ReceiptInsertResult
    data class ConflictUnresolved(val reason: String) : ReceiptInsertResult
}
