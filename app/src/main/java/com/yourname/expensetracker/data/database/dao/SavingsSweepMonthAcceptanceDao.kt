package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance

@Dao
interface SavingsSweepMonthAcceptanceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(acceptance: SavingsSweepMonthAcceptance): Long

    // Overlap also fails closed if the device timezone changes within a month.
    @Query("SELECT EXISTS(SELECT 1 FROM savings_sweep_month_acceptance WHERE monthStart < :endExclusive AND monthEndExclusive > :startInclusive)")
    suspend fun hasAcceptanceBetween(startInclusive: Long, endExclusive: Long): Boolean

    @Query("SELECT * FROM savings_sweep_month_acceptance ORDER BY monthStart")
    suspend fun getAll(): List<SavingsSweepMonthAcceptance>
}
