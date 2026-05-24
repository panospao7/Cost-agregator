package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun

@Dao
interface BankStatementImportRunDao {

    @Insert
    suspend fun insert(run: BankStatementImportRun): Long

    @Query("SELECT * FROM bank_statement_import_runs WHERE id = :id")
    suspend fun getById(id: Long): BankStatementImportRun?

    @Query("""
        UPDATE bank_statement_import_runs
        SET status = :status,
            completedAt = :completedAt,
            totalItems = :totalItems,
            processedItems = :processedItems,
            createdReviewCount = :createdReviewCount,
            duplicateExpenseCount = :duplicateExpenseCount,
            duplicatePendingCount = :duplicatePendingCount,
            failedItemCount = :failedItemCount,
            errorSummary = :errorSummary
        WHERE id = :runId
    """)
    suspend fun finalize(
        runId: Long,
        status: String,
        completedAt: Long,
        totalItems: Int,
        processedItems: Int,
        createdReviewCount: Int,
        duplicateExpenseCount: Int,
        duplicatePendingCount: Int,
        failedItemCount: Int,
        errorSummary: String?
    )

    @Query("SELECT * FROM bank_statement_import_runs WHERE status = 'RUNNING' AND startedAt < :cutoffMs")
    suspend fun getStaleRunningRuns(cutoffMs: Long): List<BankStatementImportRun>

    @Query("""
        UPDATE bank_statement_import_runs
        SET status = 'STALE_FAILED',
            completedAt = :now,
            errorSummary = :reason
        WHERE id = :runId
    """)
    suspend fun markStaleFailed(runId: Long, now: Long, reason: String)

    @Query("""
        UPDATE bank_statement_import_runs
        SET statementReceiptId = :receiptId
        WHERE id = :runId
    """)
    suspend fun attachReceipt(runId: Long, receiptId: Long)
}
