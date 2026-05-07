package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentDao {
    
    @Insert
    suspend fun insert(investment: Investment): Long
    
    @Update
    suspend fun update(investment: Investment)
    
    @Delete
    suspend fun delete(investment: Investment)
    
    @Query("SELECT * FROM investments WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getAllActiveInvestments(): Flow<List<Investment>>
    
    @Query("SELECT * FROM investments ORDER BY createdAt DESC")
    suspend fun getAllInvestments(): List<Investment>
    
    @Query("SELECT * FROM investments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Investment?
    
    @Query("SELECT * FROM investments WHERE type = :type AND isActive = 1")
    fun getByType(type: InvestmentType): Flow<List<Investment>>
    
    // TODO (I06): Include fees in DAO aggregate or remove raw DAO methods from production; use tracker math only.
    //             These SUM queries exclude fees (broker commissions, transaction costs, etc.),
    //             which can cause the DAO totals to disagree with the investment tracker's net calculations.
    @Query("SELECT SUM(currentPrice * quantity) FROM investments WHERE isActive = 1")
    suspend fun getTotalPortfolioValue(): Double?
    
    @Query("""
        SELECT SUM((currentPrice - purchasePrice) * quantity) 
        FROM investments 
        WHERE isActive = 1
    """)
    suspend fun getTotalUnrealizedGainLoss(): Double?
    
    @Query("""
        SELECT SUM(purchasePrice * quantity) 
        FROM investments 
        WHERE isActive = 1
    """)
    suspend fun getTotalInvestedAmount(): Double?
    
    @Query("UPDATE investments SET currentPrice = :price, lastUpdated = :timestamp WHERE id = :investmentId")
    suspend fun updatePrice(investmentId: Long, price: Double, timestamp: Long)
}
