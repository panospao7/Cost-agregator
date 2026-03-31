package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface WarrantyDao {
    @Query("SELECT * FROM warranties ORDER BY warrantyEndDate ASC")
    fun getAllWarranties(): Flow<List<Warranty>>

    @Query("SELECT * FROM warranties WHERE status = :status ORDER BY warrantyEndDate ASC")
    fun getWarrantiesByStatus(status: WarrantyStatus): Flow<List<Warranty>>

    @Query("SELECT * FROM warranties WHERE warrantyEndDate > :currentTime AND status = 'ACTIVE' ORDER BY warrantyEndDate ASC")
    fun getActiveWarranties(currentTime: Long = System.currentTimeMillis()): Flow<List<Warranty>>

    @Query("SELECT * FROM warranties WHERE warrantyEndDate <= :futureTime AND warrantyEndDate > :currentTime AND status = 'ACTIVE' ORDER BY warrantyEndDate ASC")
    suspend fun getWarrantiesExpiringSoon(
        futureTime: Long,
        currentTime: Long = System.currentTimeMillis()
    ): List<Warranty>

    @Query("SELECT * FROM warranties WHERE warrantyEndDate < :currentTime AND status = 'ACTIVE'")
    suspend fun getRecentlyExpiredWarranties(currentTime: Long = System.currentTimeMillis()): List<Warranty>

    @Query("SELECT * FROM warranties WHERE receiptId = :receiptId")
    suspend fun getWarrantyByReceiptId(receiptId: Long): Warranty?

    @Query("SELECT * FROM warranties WHERE expenseId = :expenseId")
    suspend fun getWarrantyByExpenseId(expenseId: Long): Warranty?

    @Insert
    suspend fun insertWarranty(warranty: Warranty): Long

    @Update
    suspend fun updateWarranty(warranty: Warranty)

    @Delete
    suspend fun deleteWarranty(warranty: Warranty)

    @Query("DELETE FROM warranties WHERE id = :warrantyId")
    suspend fun deleteWarrantyById(warrantyId: Long)

    @Query("UPDATE warranties SET status = :status, updatedAt = :updatedAt WHERE id = :warrantyId")
    suspend fun updateWarrantyStatus(
        warrantyId: Long,
        status: WarrantyStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT COUNT(*) FROM warranties WHERE status = 'ACTIVE' AND warrantyEndDate > :currentTime")
    suspend fun getActiveWarrantyCount(currentTime: Long = System.currentTimeMillis()): Int

    @Query("SELECT SUM(CASE WHEN expenseId IS NOT NULL THEN (SELECT amount FROM expenses WHERE id = expenseId) ELSE 0 END) FROM warranties WHERE status = 'ACTIVE'")
    suspend fun getTotalProtectedValue(): Double?
}
