package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(review: PendingReview): Long

    @Update
    suspend fun update(review: PendingReview)

    @Delete
    suspend fun delete(review: PendingReview)

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    fun getPendingFlow(limit: Int = 100): Flow<List<PendingReviewWithReceipt>>

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getPending(limit: Int = 500): List<PendingReviewWithReceipt>

    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getById(id: Long): PendingReview?

    @Query("SELECT * FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun getByRawId(rawId: Long): PendingReview?

    @Query("DELETE FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun deleteByRawId(rawId: Long)

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun updateStatusIfPending(id: Long, status: String): Int

    @Query("SELECT * FROM pending_reviews ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PendingReview>>

    @Query("DELETE FROM pending_reviews WHERE status != 'PENDING'")
    suspend fun clearResolved()

    @Query("DELETE FROM pending_reviews")
    suspend fun deleteAll()

    @Query("UPDATE pending_reviews SET status = 'APPROVED' WHERE status = 'PENDING'")
    suspend fun approveAllPending()

    @Query("UPDATE pending_reviews SET status = 'REJECTED' WHERE status = 'PENDING'")
    suspend fun rejectAllPending()

    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' AND suggestedDate BETWEEN :startDate AND :endDate")
    suspend fun getPendingReviewsInDateRange(startDate: Long, endDate: Long): List<PendingReview>

    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' AND suggestedMerchant LIKE '%' || :merchantPattern || '%' AND suggestedDate BETWEEN :startDate AND :endDate")
    suspend fun getPendingReviewsByMerchantAndDateRange(merchantPattern: String, startDate: Long, endDate: Long): List<PendingReview>

    @Query("SELECT * FROM pending_reviews WHERE suggestedMerchant = :merchantName AND status = 'PENDING'")
    suspend fun getPendingByMerchant(merchantName: String): List<PendingReview>

    @Query("UPDATE pending_reviews SET suggestedCategoryId = :categoryId WHERE suggestedMerchant = :merchantName AND status = 'PENDING'")
    suspend fun bulkUpdateCategoryByMerchant(merchantName: String, categoryId: Long)

    @Query("UPDATE pending_reviews SET suggestedMerchant = :newMerchant WHERE suggestedMerchant = :oldMerchant AND status = 'PENDING'")
    suspend fun bulkRenameMerchant(oldMerchant: String, newMerchant: String)
}
