package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.yourname.expensetracker.data.database.entity.MileageTracking
import kotlinx.coroutines.flow.Flow

@Dao
interface MileageTrackingDao {
    
    @Insert
    suspend fun insert(mileage: MileageTracking): Long
    
    @Update
    suspend fun update(mileage: MileageTracking)
    
    @Delete
    suspend fun delete(mileage: MileageTracking)
    
    @Query("SELECT * FROM mileage_tracking ORDER BY date DESC")
    fun getAllMileage(): Flow<List<MileageTracking>>
    
    @Query("SELECT * FROM mileage_tracking WHERE isBusinessTrip = 1 ORDER BY date DESC")
    fun getBusinessMileage(): Flow<List<MileageTracking>>
    
    @Query("SELECT * FROM mileage_tracking WHERE date >= :startDate AND date < :endDate ORDER BY date DESC")
    suspend fun getMileageBetween(startDate: Long, endDate: Long): List<MileageTracking>
    
    @Query("SELECT * FROM mileage_tracking WHERE isBusinessTrip = 1 AND date >= :startDate AND date < :endDate ORDER BY date DESC")
    suspend fun getBusinessMileageBetween(startDate: Long, endDate: Long): List<MileageTracking>
    
    @Query("SELECT SUM(distanceKm) FROM mileage_tracking WHERE isBusinessTrip = 1 AND date >= :startDate AND date < :endDate")
    suspend fun getTotalBusinessDistanceBetween(startDate: Long, endDate: Long): Double?
    
    /** @deprecated Use [getTotalDeductionWithFallback] which handles NULL calculatedDeduction via CASE. */
    @Deprecated(
        message = "Use getTotalDeductionWithFallback which accounts for NULL calculatedDeduction values",
        replaceWith = ReplaceWith(
            "getTotalDeductionWithFallback(startDate, endDate, ratePerKm)",
            "com.yourname.expensetracker.data.database.dao.MileageTrackingDao.getTotalDeductionWithFallback"
        )
    )
    @Query("SELECT SUM(calculatedDeduction) FROM mileage_tracking WHERE isBusinessTrip = 1 AND date >= :startDate AND date < :endDate")
    suspend fun getTotalDeductionBetween(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT COALESCE(SUM(
            CASE 
                WHEN calculatedDeduction IS NOT NULL THEN calculatedDeduction 
                ELSE distanceKm * :ratePerKm 
            END
        ), 0.0) 
        FROM mileage_tracking 
        WHERE isBusinessTrip = 1 AND date >= :startDate AND date < :endDate
    """)
    suspend fun getTotalDeductionWithFallback(startDate: Long, endDate: Long, ratePerKm: Double): Double
    
    @Query("SELECT * FROM mileage_tracking WHERE linkedExpenseId = :expenseId")
    suspend fun getMileageForExpense(expenseId: Long): List<MileageTracking>
    
    @Query("DELETE FROM mileage_tracking WHERE id = :id")
    suspend fun deleteById(id: Long)
}
