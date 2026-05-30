package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetForecastDao {
    
    /**
     * Raw insert. Uses [OnConflictStrategy.ABORT] (P6-CURRENT-008).
     *
     * The unique index is `(budgetId, targetPeriodStart, forecastDate)`. A normal
     * refresh writes a new row with `forecastDate = now()`, which never collides;
     * ABORT therefore only triggers on a genuine same-millisecond duplicate. On
     * collision SQLite raises [android.database.sqlite.SQLiteConstraintException]
     * instead of silently overwriting a historical row (the prior REPLACE behaviour).
     *
     * Production never calls this directly — all writes go through
     * [insertWithDeactivation], which preserves history via deactivation.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(forecast: BudgetForecast): Long
    
    @Update
    suspend fun update(forecast: BudgetForecast)
    
    @Query("SELECT * FROM budget_forecasts WHERE id = :id")
    suspend fun getById(id: Long): BudgetForecast?

    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId ORDER BY forecastDate DESC")
    fun getForecastsForBudget(budgetId: Long): Flow<List<BudgetForecast>>
    
    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId AND isActive = 1 ORDER BY forecastDate DESC LIMIT 1")
    suspend fun getLatestActiveForecast(budgetId: Long): BudgetForecast?
    
    @Query("SELECT * FROM budget_forecasts WHERE budgetId = :budgetId AND targetPeriodStart <= :date AND targetPeriodEnd > :date AND isActive = 1 LIMIT 1")
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

    /**
     * Deactivates all currently-active forecasts for a given budget + target period.
     *
     * Used by [insertWithDeactivation] to keep a single active forecast for the
     * same budget + target period at the app layer.
     */
    @Query("""
        UPDATE budget_forecasts SET isActive = 0
        WHERE budgetId = :budgetId
          AND targetPeriodStart = :targetPeriodStart
          AND targetPeriodEnd = :targetPeriodEnd
          AND isActive = 1
    """)
    suspend fun deactivateForPeriod(budgetId: Long, targetPeriodStart: Long, targetPeriodEnd: Long)

    /**
     * Atomically deactivates any existing active forecast for the same
     * (budgetId, targetPeriodStart, targetPeriodEnd) and then inserts a new one.
     *
     * This preserves the invariant that only the newest forecast remains active
     * for a given budget + target period.
     */
    @Transaction
    suspend fun insertWithDeactivation(forecast: BudgetForecast): Long {
        deactivateForPeriod(forecast.budgetId, forecast.targetPeriodStart, forecast.targetPeriodEnd)
        return insert(forecast)
    }
    
    @Query("DELETE FROM budget_forecasts WHERE budgetId = :budgetId")
    suspend fun deleteForecastsForBudget(budgetId: Long)
}
