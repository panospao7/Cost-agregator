package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
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
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingUncappedFlow(): Flow<List<PendingReviewWithReceipt>>

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    fun getPendingFlow(limit: Int): Flow<List<PendingReviewWithReceipt>>

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getPending(limit: Int): List<PendingReviewWithReceipt>

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPendingUncapped(): List<PendingReviewWithReceipt>

    suspend fun getPending(): List<PendingReviewWithReceipt> = getPendingUncapped()

    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getById(id: Long): PendingReview?

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getPendingWithReceiptById(id: Long): PendingReviewWithReceipt?

    @Query("SELECT * FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun getByRawId(rawId: Long): PendingReview?

    @Transaction
    suspend fun upsertByRawNotificationId(review: PendingReview): Long {
        val rawId = review.rawNotificationId ?: return insert(review)
        val existing = getByRawId(rawId) ?: return insert(review)
        update(
            review.copy(
                id = existing.id,
                scannedReceiptId = existing.scannedReceiptId,
                createdAt = existing.createdAt,
                status = existing.status
            )
        )
        return existing.id
    }

    @Query("DELETE FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun deleteByRawId(rawId: Long)

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: PendingReviewStatus)

    @Query("UPDATE pending_reviews SET status = :newStatus WHERE id = :id AND status = :expectedStatus")
    suspend fun transitionStatus(id: Long, expectedStatus: PendingReviewStatus, newStatus: PendingReviewStatus): Int

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

    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' AND suggestedDate >= :startDate AND suggestedDate < :endDate")
    suspend fun getPendingReviewsInDateRange(startDate: Long, endDate: Long): List<PendingReview>

    @Query("""
        SELECT * FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :merchantKey
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
    """)
    suspend fun getPendingReviewsByMerchantKeyAndDateRange(
        merchantKey: String,
        startDate: Long,
        endDate: Long
    ): List<PendingReview>

    @Query("""
        SELECT * FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchant = :merchantName
          AND suggestedMerchantKey IS NULL
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
    """)
    suspend fun getPendingReviewsByMerchantNameAndDateRange(
        merchantName: String,
        startDate: Long,
        endDate: Long
    ): List<PendingReview>

    @Transaction
    suspend fun getPendingReviewsByMerchantAndDateRange(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long
    ): List<PendingReview> {
        val keyed = getPendingReviewsByMerchantKeyAndDateRange(merchantKey, startDate, endDate)
        if (keyed.isNotEmpty()) return keyed
        return getPendingReviewsByMerchantNameAndDateRange(merchantName, startDate, endDate)
    }

    @Query("""
        SELECT * FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :merchantKey
    """)
    suspend fun getPendingByMerchantKey(merchantKey: String): List<PendingReview>

    @Query("""
        SELECT * FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchant = :merchantName
          AND suggestedMerchantKey IS NULL
    """)
    suspend fun getPendingByMerchantName(merchantName: String): List<PendingReview>

    @Transaction
    suspend fun getPendingByMerchant(merchantKey: String, merchantName: String): List<PendingReview> {
        val keyed = getPendingByMerchantKey(merchantKey)
        if (keyed.isNotEmpty()) return keyed
        return getPendingByMerchantName(merchantName)
    }

    @Query("""
        UPDATE pending_reviews
        SET suggestedCategoryId = :categoryId
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :merchantKey
    """)
    suspend fun bulkUpdateCategoryByMerchantKey(merchantKey: String, categoryId: Long)

    @Query("""
        UPDATE pending_reviews
        SET suggestedCategoryId = :categoryId
        WHERE status = 'PENDING'
          AND suggestedMerchant = :merchantName
          AND suggestedMerchantKey IS NULL
    """)
    suspend fun bulkUpdateCategoryByMerchantName(merchantName: String, categoryId: Long)

    @Transaction
    suspend fun bulkUpdateCategoryByMerchant(merchantKey: String, merchantName: String, categoryId: Long) {
        bulkUpdateCategoryByMerchantKey(merchantKey, categoryId)
        bulkUpdateCategoryByMerchantName(merchantName, categoryId)
    }

    @Query("""
        UPDATE pending_reviews
        SET suggestedMerchant = :newMerchant,
            suggestedMerchantKey = :newMerchantKey
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :oldMerchantKey
    """)
    suspend fun bulkRenameMerchantByKey(
        oldMerchantKey: String,
        newMerchant: String,
        newMerchantKey: String
    )

    @Query("""
        UPDATE pending_reviews
        SET suggestedMerchant = :newMerchant,
            suggestedMerchantKey = :newMerchantKey
        WHERE status = 'PENDING'
          AND suggestedMerchant = :oldMerchant
          AND suggestedMerchantKey IS NULL
    """)
    suspend fun bulkRenameMerchantByName(
        oldMerchant: String,
        newMerchant: String,
        newMerchantKey: String
    )

    @Transaction
    suspend fun bulkRenameMerchant(
        oldMerchantKey: String,
        oldMerchant: String,
        newMerchant: String,
        newMerchantKey: String
    ) {
        bulkRenameMerchantByKey(oldMerchantKey, newMerchant, newMerchantKey)
        bulkRenameMerchantByName(oldMerchant, newMerchant, newMerchantKey)
    }

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM pending_reviews
            WHERE status = 'PENDING'
              AND suggestedMerchantKey = :merchantKey
              AND suggestedDate >= :startDate
              AND suggestedDate < :endDate
              AND suggestedAmount BETWEEN :minAmount AND :maxAmount
              AND UPPER(suggestedCurrency) = UPPER(:currency)
        )
    """)
    suspend fun hasPendingDuplicateByMerchantKeyInRange(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM pending_reviews
            WHERE status = 'PENDING'
              AND suggestedMerchant = :merchantName
              AND suggestedMerchantKey IS NULL
              AND suggestedDate >= :startDate
              AND suggestedDate < :endDate
              AND suggestedAmount BETWEEN :minAmount AND :maxAmount
              AND UPPER(suggestedCurrency) = UPPER(:currency)
        )
    """)
    suspend fun hasPendingDuplicateByMerchantNameInRange(
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): Boolean

    @Transaction
    suspend fun hasPendingDuplicateInRange(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): Boolean {
        return hasPendingDuplicateByMerchantKeyInRange(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency
        ) || hasPendingDuplicateByMerchantNameInRange(
            merchantName = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency
        )
    }

    @Query("""
        SELECT *
        FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :merchantKey
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
          AND suggestedAmount BETWEEN :minAmount AND :maxAmount
          AND UPPER(suggestedCurrency) = UPPER(:currency)
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun getPendingDuplicateCandidateByMerchantKeyInRange(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): PendingReview?

    @Query("""
        SELECT *
        FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchant = :merchantName
          AND suggestedMerchantKey IS NULL
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
          AND suggestedAmount BETWEEN :minAmount AND :maxAmount
          AND UPPER(suggestedCurrency) = UPPER(:currency)
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun getPendingDuplicateCandidateByMerchantNameInRange(
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): PendingReview?

    @Transaction
    suspend fun getPendingDuplicateCandidateInRange(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String
    ): PendingReview? {
        return getPendingDuplicateCandidateByMerchantKeyInRange(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency
        ) ?: getPendingDuplicateCandidateByMerchantNameInRange(
            merchantName = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency
        )
    }

    // ── Currency + transaction-type–aware duplicate queries (A.4) ──

    /**
     * Check existence of a pending review duplicate by **merchantKey**,
     * restricted to the given currency and compatible transaction type.
     *
     * Type compatibility: if [transactionType] is `'UNKNOWN'`, any type matches;
     * otherwise the existing row must be `UNKNOWN` or equal to [transactionType].
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM pending_reviews
            WHERE status = 'PENDING'
              AND suggestedMerchantKey = :merchantKey
              AND suggestedDate >= :startDate
              AND suggestedDate < :endDate
              AND suggestedAmount BETWEEN :minAmount AND :maxAmount
              AND UPPER(suggestedCurrency) = UPPER(:currency)
              AND (
                  :transactionType = 'UNKNOWN'
                  OR suggestedType = 'UNKNOWN'
                  OR suggestedType = :transactionType
              )
        )
    """)
    suspend fun hasPendingDuplicateByMerchantKeyInRangeTypeAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Check existence of a pending review duplicate by **merchantKey prefix containment**.
     *
     * Mirrors [ExpenseDao.existsByMerchantKeyPrefixInRangeCurrencyAware] for the
     * pending_reviews table. Catches cross-source duplicates where one source
     * includes the store branch/address in the merchant name and the other does not.
 *
 * The `LENGTH >= 4` guard mirrors [DuplicateDetectionPolicy.MIN_MERCHANT_KEY_PREFIX_LENGTH];
 * keep both in sync — Room SQL cannot reference Kotlin constants.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM pending_reviews
            WHERE status = 'PENDING'
            AND (
                :merchantKey LIKE suggestedMerchantKey || '%'
                OR suggestedMerchantKey LIKE :merchantKey || '%'
            )
            AND LENGTH(suggestedMerchantKey) >= 4
            AND LENGTH(:merchantKey) >= 4
            AND suggestedDate >= :startDate
            AND suggestedDate < :endDate
            AND suggestedAmount BETWEEN :minAmount AND :maxAmount
            AND UPPER(suggestedCurrency) = UPPER(:currency)
            AND (
                :transactionType = 'UNKNOWN'
                OR suggestedType = 'UNKNOWN'
                OR suggestedType = :transactionType
            )
        )
    """)
    suspend fun hasPendingDuplicateByMerchantKeyPrefixInRangeTypeAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Check existence of a pending review duplicate by raw **merchant name**
     * (fallback for legacy rows without merchantKey), restricted to the given
     * currency and compatible transaction type.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM pending_reviews
            WHERE status = 'PENDING'
              AND suggestedMerchant = :merchantName
              AND suggestedMerchantKey IS NULL
              AND suggestedDate >= :startDate
              AND suggestedDate < :endDate
              AND suggestedAmount BETWEEN :minAmount AND :maxAmount
              AND UPPER(suggestedCurrency) = UPPER(:currency)
              AND (
                  :transactionType = 'UNKNOWN'
                  OR suggestedType = 'UNKNOWN'
                  OR suggestedType = :transactionType
              )
        )
    """)
    suspend fun hasPendingDuplicateByMerchantNameInRangeTypeAware(
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Composite pending-review duplicate-existence check with currency **and**
     * transaction-type awareness.
     *
     * Falls back from merchantKey to raw merchantName, preserving the same
     * fallback semantics as [hasPendingDuplicateInRange].
     */
    @Transaction
    suspend fun hasPendingDuplicateInRangeTypeAware(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean {
        return hasPendingDuplicateByMerchantKeyInRangeTypeAware(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency,
            transactionType = transactionType
        ) || hasPendingDuplicateByMerchantKeyPrefixInRangeTypeAware(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency,
            transactionType = transactionType
        ) || hasPendingDuplicateByMerchantNameInRangeTypeAware(
            merchantName = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency,
            transactionType = transactionType
        )
    }

    /**
     * Fetch the best pending-review duplicate candidate by **merchantKey**,
     * restricted to currency and compatible transaction type.
     */
    @Query("""
        SELECT *
        FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchantKey = :merchantKey
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
          AND suggestedAmount BETWEEN :minAmount AND :maxAmount
          AND UPPER(suggestedCurrency) = UPPER(:currency)
          AND (
              :transactionType = 'UNKNOWN'
              OR suggestedType = 'UNKNOWN'
              OR suggestedType = :transactionType
          )
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun getPendingDuplicateCandidateByMerchantKeyInRangeTypeAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): PendingReview?

    /**
     * Fetch the best pending-review duplicate candidate by raw **merchant name**
     * (fallback for legacy rows without merchantKey), restricted to currency and
     * compatible transaction type.
     */
    @Query("""
        SELECT *
        FROM pending_reviews
        WHERE status = 'PENDING'
          AND suggestedMerchant = :merchantName
          AND suggestedMerchantKey IS NULL
          AND suggestedDate >= :startDate
          AND suggestedDate < :endDate
          AND suggestedAmount BETWEEN :minAmount AND :maxAmount
          AND UPPER(suggestedCurrency) = UPPER(:currency)
          AND (
              :transactionType = 'UNKNOWN'
              OR suggestedType = 'UNKNOWN'
              OR suggestedType = :transactionType
          )
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun getPendingDuplicateCandidateByMerchantNameInRangeTypeAware(
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): PendingReview?

    /**
     * Composite pending-review duplicate-candidate retrieval with currency
     * **and** transaction-type awareness.
     *
     * Falls back from merchantKey to raw merchantName, preserving the same
     * fallback semantics as [getPendingDuplicateCandidateInRange].
     */
    @Transaction
    suspend fun getPendingDuplicateCandidateInRangeTypeAware(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): PendingReview? {
        return getPendingDuplicateCandidateByMerchantKeyInRangeTypeAware(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency,
            transactionType = transactionType
        ) ?: getPendingDuplicateCandidateByMerchantNameInRangeTypeAware(
            merchantName = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = currency,
            transactionType = transactionType
        )
    }
}
