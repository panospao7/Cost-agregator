package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.InvestmentTransaction

@Dao
interface InvestmentTransactionDao {
    @Insert
    suspend fun insert(transaction: InvestmentTransaction): Long

    @Query("SELECT * FROM investment_transactions WHERE holdingId = :holdingId ORDER BY date DESC")
    suspend fun getByHoldingId(holdingId: Long): List<InvestmentTransaction>
}
