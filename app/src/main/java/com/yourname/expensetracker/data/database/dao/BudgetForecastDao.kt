package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetForecastDao {
    
    @Insert
    suspend fun insert(forecast: BudgetForecast): Long
    
    @Update
    suspend fun update(forecast: BudgetForecast)
    
    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId ORDER BY forecastDate DESC")
    fun getForecastsForBudget(budgetId: Long): Flow<List<BudgetForecast>>
    
    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId AND isActive = 1 ORDER BY forecastDate DESC LIMIT 1")
    suspend fun getLatestActiveForecast(budgetId: Long): BudgetForecast?
    
    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId AND targetPeriodStart <= :date AND targetPeriodEnd >= :date AND isActive = 1 LIMIT 1")
    suspend fun getForecastForDate(budgetId: Long, date: Long): BudgetForecast?
    
    @Query("SELECT * FROM budget_forecasts WHERE isActive = 1 AND targetPeriodEnd < :now")
    suspend fun getExpiredForecasts(now: Long): List<BudgetForecast>
    
    @Query("""
        SELECT AVG(forecastAccuracy) FROM budget_forecasts 
        WHERE budgetId = :budgetId 
        AND forecastAccuracy IS NOT NULL 
        AND forecastDate >= :since
    """)
    suspend fun getAverageAccuracy(budgetId: Long, since: Long): Double?
    
    @Query("""
        SELECT COUNT(*) FROM budget_forecasts 
        WHERE budgetId = :budgetId 
        AND riskLevel = :riskLevel
        AND forecastDate >= :since
    """)
    suspend fun getRiskLevelCount(budgetId: Long, riskLevel: ForecastRiskLevel, since: Long): Int
    
    @Query("UPDATE budget_forecasts SET isActive = 0 WHERE id = :forecastId")
    suspend fun deactivateForecast(forecastId: Long)
    
    @Query("DELETE FROM budget_forecasts WHERE budgetId = :budgetId")
    suspend fun deleteForecastsForBudget(budgetId: Long)
}
