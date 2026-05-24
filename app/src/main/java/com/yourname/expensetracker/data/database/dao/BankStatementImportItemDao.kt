package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.BankStatementImportItem

@Dao
interface BankStatementImportItemDao {

    @Insert
    suspend fun insert(item: BankStatementImportItem): Long

    @Query("SELECT * FROM bank_statement_import_items WHERE runId = :runId ORDER BY itemIndex")
    suspend fun getByRunId(runId: Long): List<BankStatementImportItem>

    @Query("SELECT COUNT(*) FROM bank_statement_import_items WHERE runId = :runId AND status = :status")
    suspend fun countByRunAndStatus(runId: Long, status: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM bank_statement_import_items WHERE runId = :runId AND transactionFingerprint = :fingerprint)")
    suspend fun existsByRunAndFingerprint(runId: Long, fingerprint: String): Boolean

    @Query("SELECT * FROM bank_statement_import_items WHERE runId = :runId AND itemIndex = :itemIndex LIMIT 1")
    suspend fun getByRunAndIndex(runId: Long, itemIndex: Int): BankStatementImportItem?
}
