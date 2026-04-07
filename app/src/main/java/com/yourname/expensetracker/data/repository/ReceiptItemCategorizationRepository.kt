package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptItemCategorizationRepository @Inject constructor(
    private val dao: ReceiptItemCategorizationDao
) {
    suspend fun getByReceiptId(receiptId: Long): List<ReceiptItemCategorization> =
        dao.getByReceiptId(receiptId)

    /**
     * Returns cached categorization results for a receipt as domain snapshots.
     * The entity→snapshot mapping is performed here at the data/domain boundary.
     */
    suspend fun getByReceiptIdAsSnapshots(receiptId: Long): List<ReceiptItemCategorizationSnapshot> =
        dao.getByReceiptId(receiptId).map { it.toSnapshot() }

    /**
     * Deletes all categorization rows for the given receipt.
     */
    suspend fun deleteByReceiptId(receiptId: Long) =
        dao.deleteByReceiptId(receiptId)

    /**
     * Persists a full categorization result for a receipt.
     * Builds the Room entity from domain model fields here at the boundary.
     */
    suspend fun saveCategorizationResult(
        receiptId: Long,
        result: ReceiptItemCategorizationResult,
        now: Long
    ) {
        result.items.forEach { item ->
            val alternativesJson = JSONArray().apply {
                item.alternatives.forEach { alt ->
                    put(JSONObject().apply {
                        put("id", alt.categoryId)
                        put("name", alt.categoryName)
                        put("confidence", alt.confidence)
                    })
                }
            }.toString()

            val categorization = ReceiptItemCategorization(
                receiptId = receiptId,
                itemDescription = item.itemDescription,
                itemAmount = item.amount,
                suggestedCategoryId = item.suggestedCategory?.categoryId,
                suggestedCategoryName = item.suggestedCategory?.categoryName,
                confidence = item.confidence,
                aiRationale = item.rationale,
                alternativeCategoriesJson = alternativesJson,
                userCorrectedCategoryId = null,
                userCorrectedCategoryName = null,
                userCorrectedAt = null,
                taxAmount = result.taxDistribution[item.suggestedCategory?.categoryId],
                isNewCategorySuggestion = item.suggestedCategory?.isNewCategorySuggestion ?: false,
                createdAt = now,
                updatedAt = now
            )
            dao.insert(categorization)
        }
    }

    suspend fun updateUserCorrection(
        itemId: Long,
        categoryId: Long?,
        categoryName: String?,
        timestamp: Long
    ) = dao.updateUserCorrection(
        itemId = itemId,
        categoryId = categoryId,
        categoryName = categoryName,
        timestamp = timestamp
    )

    // --- Private mapper: entity → domain snapshot ---

    private fun ReceiptItemCategorization.toSnapshot() = ReceiptItemCategorizationSnapshot(
        id = id,
        receiptId = receiptId,
        expenseId = expenseId,
        itemDescription = itemDescription,
        itemAmount = itemAmount,
        suggestedCategoryId = suggestedCategoryId,
        suggestedCategoryName = suggestedCategoryName,
        confidence = confidence,
        aiRationale = aiRationale,
        alternativeCategoriesJson = alternativeCategoriesJson,
        userCorrectedCategoryId = userCorrectedCategoryId,
        userCorrectedCategoryName = userCorrectedCategoryName,
        userCorrectedAt = userCorrectedAt,
        taxAmount = taxAmount,
        isNewCategorySuggestion = isNewCategorySuggestion,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
