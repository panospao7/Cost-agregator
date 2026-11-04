package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder

@Dao
interface ReceiptItemCategorizationDao {
    
    /**
     * RCP-N4: Changed from REPLACE to ABORT to prevent silent data loss.
     * REPLACE would silently delete existing rows with the same primary key,
     * discarding audit fields (userCorrectedCategoryId, userCorrectedAt, etc.).
     * ABORT fails fast so callers must handle conflicts explicitly.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ReceiptItemCategorization): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<ReceiptItemCategorization>): List<Long>
    
    @Update
    suspend fun update(item: ReceiptItemCategorization)
    
    @Query("SELECT * FROM receipt_item_categorizations WHERE receiptId = :receiptId ORDER BY id ASC")
    suspend fun getByReceiptId(receiptId: Long): List<ReceiptItemCategorization>
    
    @Query("SELECT * FROM receipt_item_categorizations WHERE expenseId = :expenseId ORDER BY id ASC")
    suspend fun getByExpenseId(expenseId: Long): List<ReceiptItemCategorization>
    
    @Query("SELECT * FROM receipt_item_categorizations WHERE id = :id")
    suspend fun getById(id: Long): ReceiptItemCategorization?
    
    @Query("""
        UPDATE receipt_item_categorizations 
        SET userCorrectedCategoryId = :categoryId,
            userCorrectedCategoryName = :categoryName,
            userCorrectedAt = :timestamp,
            updatedAt = :timestamp
        WHERE id = :itemId
    """)
    suspend fun updateUserCorrection(
        itemId: Long, 
        categoryId: Long?, 
        categoryName: String?, 
        timestamp: Long
    )
    
    @Query("""
        UPDATE receipt_item_categorizations 
        SET expenseId = :expenseId,
            updatedAt = :timestamp
        WHERE receiptId = :receiptId
    """)
    suspend fun linkToExpense(receiptId: Long, expenseId: Long, timestamp: Long)
    
    @Query("""
        UPDATE receipt_item_categorizations 
        SET suggestedCategoryId = :categoryId,
            suggestedCategoryName = :categoryName,
            isNewCategorySuggestion = 0,
            updatedAt = :timestamp
        WHERE suggestedCategoryName = :suggestedName AND isNewCategorySuggestion = 1
    """)
    suspend fun updateCategoryForSuggestion(
        suggestedName: String,
        categoryId: Long,
        categoryName: String,
        timestamp: Long
    )
    
    @Query("""
        SELECT * FROM receipt_item_categorizations 
        WHERE receiptId = :receiptId 
        AND confidence < :threshold 
        AND userCorrectedCategoryId IS NULL
        ORDER BY confidence ASC
    """)
    suspend fun getUncertainItems(receiptId: Long, threshold: Float = 0.7f): List<ReceiptItemCategorization>
    
    @Query("DELETE FROM receipt_item_categorizations WHERE receiptId = :receiptId")
    suspend fun deleteByReceiptId(receiptId: Long)
    
    @Query("""
        SELECT COUNT(*) FROM receipt_item_categorizations 
        WHERE receiptId = :receiptId 
        AND userCorrectedCategoryId IS NOT NULL
    """)
    suspend fun getCorrectionCount(receiptId: Long): Int
    
    @Query("""
        SELECT SUM(itemAmount) FROM receipt_item_categorizations 
        WHERE (suggestedCategoryId = :categoryId OR userCorrectedCategoryId = :categoryId)
        AND expenseId = :expenseId
    """)
    suspend fun getTotalForCategoryInExpense(expenseId: Long, categoryId: Long): Double?

    @Query("""
        UPDATE receipt_item_categorizations 
        SET expenseId = NULL,
            updatedAt = :timestamp
        WHERE receiptId = :receiptId
    """)
    suspend fun clearExpenseId(receiptId: Long, timestamp: Long)

    /**
     * Fills stored placeholder rows (items a cloud call did not cover) with a retry result.
     * Each replacement is applied to its row only if that row is STILL an unsent placeholder
     * and has no user correction. The row is read and written in this one Room transaction, so
     * a row that changed since the caller read it (a user correction, an earlier retry, a real
     * result) is left untouched. Only the suggestion fields change; the row's description,
     * amount, tax share, expense link and user-correction fields are kept as stored.
     *
     * @return the number of rows actually updated.
     */
    @androidx.room.Transaction
    suspend fun updatePlaceholderRowsIfStillUncategorized(
        receiptId: Long,
        replacements: List<ReceiptItemCategorization>
    ): Int {
        var updated = 0
        for (replacement in replacements) {
            val current = getById(replacement.id) ?: continue
            if (current.receiptId != receiptId) continue
            val stillPlaceholder = ReceiptItemPlaceholder.isPlaceholder(
                hasSuggestedCategory = current.suggestedCategoryId != null ||
                    !current.suggestedCategoryName.isNullOrBlank(),
                confidence = current.confidence,
                rationale = current.aiRationale,
                hasAlternatives = ReceiptItemPlaceholder.hasAlternativesJson(current.alternativeCategoriesJson)
            )
            val userTouched = ReceiptItemPlaceholder.isUserTouched(
                correctedCategoryId = current.userCorrectedCategoryId,
                correctedCategoryName = current.userCorrectedCategoryName,
                correctedAt = current.userCorrectedAt
            )
            if (!stillPlaceholder || userTouched) continue
            update(
                current.copy(
                    suggestedCategoryId = replacement.suggestedCategoryId,
                    suggestedCategoryName = replacement.suggestedCategoryName,
                    confidence = replacement.confidence,
                    aiRationale = replacement.aiRationale,
                    alternativeCategoriesJson = replacement.alternativeCategoriesJson,
                    isNewCategorySuggestion = replacement.isNewCategorySuggestion,
                    updatedAt = replacement.updatedAt
                )
            )
            updated++
        }
        return updated
    }

    @androidx.room.Transaction
    suspend fun redactForReceiptsOlderThan(cutoffMs: Long): Int =
        redactForReceiptsOlderThanInternal(cutoffMs)

    @Query("""
        UPDATE receipt_item_categorizations
        SET itemDescription = '',
            aiRationale = NULL,
            suggestedCategoryName = NULL,
            alternativeCategoriesJson = NULL
        WHERE receiptId IN (
            SELECT id FROM scanned_receipts
            WHERE createdAt < :cutoffMs
        )
          AND (itemDescription <> '' OR aiRationale IS NOT NULL OR suggestedCategoryName IS NOT NULL OR alternativeCategoriesJson IS NOT NULL)
    """)
    suspend fun redactForReceiptsOlderThanInternal(cutoffMs: Long): Int
}
