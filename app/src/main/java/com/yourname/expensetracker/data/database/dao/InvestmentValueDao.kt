package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentValueDao {
    
    @Insert
    suspend fun insert(value: InvestmentValue): Long
    
    @Insert
    suspend fun insertAll(values: List<InvestmentValue>)
    
    @Query("SELECT * FROM investment_values WHERE investmentId = :investmentId ORDER BY timestamp DESC")
    fun getValuesForInvestment(investmentId: Long): Flow<List<InvestmentValue>>
    
    @Query("SELECT * FROM investment_values WHERE investmentId = :investmentId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestValue(investmentId: Long): InvestmentValue?
    
    @Query("SELECT * FROM investment_values WHERE investmentId = :investmentId AND timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp ASC")
    suspend fun getValuesBetween(investmentId: Long, startDate: Long, endDate: Long): List<InvestmentValue>
    
    @Query("DELETE FROM investment_values WHERE investmentId = :investmentId AND timestamp < :olderThan")
    suspend fun deleteOldValues(investmentId: Long, olderThan: Long)
    
    @Query("SELECT AVG(price) FROM investment_values WHERE investmentId = :investmentId AND timestamp >= :startDate")
    suspend fun getAveragePrice(investmentId: Long, startDate: Long): Double?
    
    @Query("SELECT MIN(price) FROM investment_values WHERE investmentId = :investmentId AND timestamp >= :startDate")
    suspend fun getMinPrice(investmentId: Long, startDate: Long): Double?
    
    @Query("SELECT MAX(price) FROM investment_values WHERE investmentId = :investmentId AND timestamp >= :startDate")
    suspend fun getMaxPrice(investmentId: Long, startDate: Long): Double?
}
