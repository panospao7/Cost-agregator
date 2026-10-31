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

    /**
     * ai-fixes INV-11: sole SQL encoding of the "past end date" transition into EXPIRED.
     * Covers ACTIVE and PENDING_REVIEW rows (same `warrantyEndDate < now` predicate).
     * Leaves needsReview, autoDetected, extractionConfidence and extractionSource untouched,
     * so an expired pending draft stays "expired unconfirmed" (status=EXPIRED, needsReview=1).
     */
    @Query("UPDATE warranties SET status = 'EXPIRED', updatedAt = :updatedAt WHERE warrantyEndDate < :currentTime AND status IN ('ACTIVE', 'PENDING_REVIEW')")
    suspend fun markExpiredWarranties(currentTime: Long, updatedAt: Long): Int

    /**
     * ai-fixes INV-11: number of PENDING_REVIEW rows that [markExpiredWarranties] would expire
     * for the same [currentTime]. Must be read in the same transaction, before the update.
     */
    @Query("SELECT COUNT(*) FROM warranties WHERE warrantyEndDate < :currentTime AND status = 'PENDING_REVIEW'")
    suspend fun getExpirablePendingWarrantyCount(currentTime: Long): Int

    /** Fresh by-id read used by the repository's confirm transaction (ai-fixes INV-13). */
    @Query("SELECT * FROM warranties WHERE id = :warrantyId")
    suspend fun getWarrantyById(warrantyId: Long): Warranty?

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
    @Deprecated(
        message = "Use getTotalProtectedValueByCurrency() for multi-currency safety",
        level = DeprecationLevel.WARNING
    )
    // spending-exempt: ASSET_VALUE
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

    // spending-exempt: ASSET_VALUE
    @Query("""
        SELECT UPPER(COALESCE(e.currency, 'EUR')) AS currency,
               SUM(COALESCE(CASE 
                   WHEN e.isNotMine = 1 THEN 0.0 
                   WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount 
                   WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0 
                   ELSE e.amount END, 0)) AS total,
               COUNT(*) AS txCount
        FROM warranties w
        LEFT JOIN expenses e ON e.id = w.expenseId
        WHERE w.status = 'ACTIVE' AND w.warrantyEndDate > :currentTime
        GROUP BY UPPER(COALESCE(e.currency, 'EUR'))
        ORDER BY total DESC
    """)
    suspend fun getTotalProtectedValueByCurrency(currentTime: Long): List<CurrencyTotal>
}
