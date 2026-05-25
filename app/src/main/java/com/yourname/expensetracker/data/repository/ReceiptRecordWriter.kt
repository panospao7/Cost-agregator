package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptTimestampPolicy
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single low-level writer for Pipeline 3 receipt persistence.
 *
 * Replaces ad-hoc [ScannedReceiptDao.insert] + `require(id > 0)` patterns.
 * All Pipeline 3 production paths must route through this writer or
 * [ReceiptInsertResolver] directly — never interpret raw insert results.
 *
 * P3-4052-05 / PR 4: Typed duplicate insert handling.
 */
@Singleton
class ReceiptRecordWriter @Inject constructor(
    private val receiptInsertResolver: ReceiptInsertResolver,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) {

    suspend fun insertOrResolve(receipt: ScannedReceipt): ReceiptRecordWriteResult {
        writeBarrier.checkWritesAllowed("ReceiptRecordWriter.insertOrResolve")
        val normalized = ReceiptTimestampPolicy.forInsert(receipt, timeProvider.now())
        return when (val result = receiptInsertResolver.insertOrResolve(normalized)) {
            is ReceiptInsertResult.Inserted ->
                ReceiptRecordWriteResult.Inserted(normalized.copy(id = result.receiptId))
            is ReceiptInsertResult.Duplicate ->
                ReceiptRecordWriteResult.Duplicate(result.existingReceipt, result.reason)
            is ReceiptInsertResult.ConflictUnresolved ->
                ReceiptRecordWriteResult.Failed(result.reason)
        }
    }
}
