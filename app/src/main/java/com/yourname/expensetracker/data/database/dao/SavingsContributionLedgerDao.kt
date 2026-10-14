 package com.yourname.expensetracker.data.database.dao

 import androidx.room.Dao
 import androidx.room.Insert
 import androidx.room.OnConflictStrategy
 import androidx.room.Query
 import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry

 @Dao
 interface SavingsContributionLedgerDao {
     @Insert(onConflict = OnConflictStrategy.IGNORE)
     suspend fun insert(entry: SavingsContributionLedgerEntry): Long

     @Query("SELECT * FROM savings_contribution_ledger WHERE idempotencyKey = :idempotencyKey LIMIT 1")
     suspend fun getByIdempotencyKey(idempotencyKey: String): SavingsContributionLedgerEntry?

     @Query("SELECT * FROM savings_contribution_ledger ORDER BY timestamp ASC, id ASC")
     suspend fun getAll(): List<SavingsContributionLedgerEntry>

     @Query("SELECT EXISTS(SELECT 1 FROM savings_contribution_ledger WHERE source = 'sweep' AND timestamp >= :startInclusive AND timestamp < :endExclusive)")
     suspend fun hasSweepBetween(startInclusive: Long, endExclusive: Long): Boolean
 }
