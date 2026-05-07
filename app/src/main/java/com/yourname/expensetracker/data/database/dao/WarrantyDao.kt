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
    fun getActiveWarranties(currentTime: Long): Flow<List<Warranty>>

    @Query("SELECT * FROM warranties WHERE warrantyEndDate >= :currentTime AND warrantyEndDate < :futureTime AND status = 'ACTIVE' ORDER BY warrantyEndDate ASC")
    suspend fun getWarrantiesExpiringSoon(
        futureTime: Long,
        currentTime: Long
    ): List<Warranty>

    @Query("SELECT * FROM warranties WHERE warrantyEndDate < :currentTime AND status = 'ACTIVE'")
    suspend fun getRecentlyExpiredWarranties(currentTime: Long): List<Warranty>

    @Query("SELECT * FROM warranties WHERE receiptId = :receiptId")
    suspend fun getWarrantyByReceiptId(receiptId: Long?): Warranty?

    @Query("SELECT * FROM warranties WHERE expenseId = :expenseId")
    suspend fun getWarrantyByExpenseId(expenseId: Long): Warranty?

    @Insert
    suspend fun insertWarranty(warranty: Warranty): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWarrantyIgnore(warranty: Warranty): Long

    @Update
    suspend fun updateWarranty(warranty: Warranty)

    @Delete
    suspend fun deleteWarranty(warranty: Warranty)

    @Query("DELETE FROM warranties WHERE id = :warrantyId")
    suspend fun deleteWarrantyById(warrantyId: Long)

    @Query("UPDATE warranties SET expenseId = :expenseId, updatedAt = :updatedAt WHERE receiptId = :receiptId")
    suspend fun updateExpenseIdByReceiptId(
        receiptId: Long,
        expenseId: Long?,
        updatedAt: Long
    )

    @Query("UPDATE warranties SET status = :status, claimedAt = :claimedAt, updatedAt = :updatedAt WHERE id = :warrantyId")
    suspend fun updateWarrantyStatus(
        warrantyId: Long,
        status: WarrantyStatus,
        claimedAt: Long? = null,
        updatedAt: Long
    )

    @Query("UPDATE warranties SET status = 'EXPIRED', updatedAt = :updatedAt WHERE warrantyEndDate < :currentTime AND status = 'ACTIVE'")
    suspend fun markExpiredWarranties(currentTime: Long, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM warranties WHERE status = 'ACTIVE' AND warrantyEndDate > :currentTime")
    suspend fun getActiveWarrantyCount(currentTime: Long): Int

    /**
     * WRN-8: Changed from `e.amount` (gross amount) to the ownership-adjusted
     * amount (effective amount logic inlined in SQL) so that shared/not-mine
     * expenses are correctly valued at the user's actual liability, not the
     * full posted amount. For non-shared expenses, this collapses to e.amount.
     *
     * Note: effectiveAmount is a computed Kotlin property, not a column, so
     * the equivalent logic is expressed in SQL as a CASE expression.
     */
    // TODO (W01): Use MoneyAggregate for protected value instead of raw Double
    // to ensure proper currency handling and formatting.
    @Query(
        """
        SELECT SUM(COALESCE(
            CASE
                WHEN e.isNotMine = 1 THEN 0.0
                WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount
                WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0
                ELSE e.amount
            END,
        0))
        FROM warranties w
        LEFT JOIN expenses e ON e.id = w.expenseId
        WHERE w.status = 'ACTIVE'
        AND w.warrantyEndDate > :currentTime
        """
    )
    suspend fun getTotalProtectedValue(currentTime: Long): Double?
}
