package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

/**
 * Repository for receipt item categorization data.
 *
 * ## N4: Item categorization status consistency
 * Status consistency (e.g. ensuring that a receipt's
 * [com.yourname.expensetracker.data.database.entity.CategorizationStatus] is
 * correctly updated when categorizations are saved or corrected) is handled
 * by the calling use cases ([com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase]
 * and related flows). This repository focuses on persistence and retrieval
 * — it does not independently manage status transitions.
 *
 * Batch G fixes verified that:
 * - [saveCategorizationResult] inserts items without side-effecting receipt status
 * - [updateUserCorrection] records corrections without overriding status
 * - Status transitions are driven by the domain layer, not this repository
 */
@Singleton
class ReceiptItemCategorizationRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
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
    suspend fun deleteByReceiptId(receiptId: Long) {
        writeBarrier.checkWritesAllowed("ReceiptItemCategorizationRepository.deleteByReceiptId")
        dao.deleteByReceiptId(receiptId)
    }

    /**
     * Persists a full categorization result for a receipt.
     * Builds the Room entity from domain model fields here at the boundary.
     *
     * @return The number of items successfully inserted (0 if none).
     */
    suspend fun saveCategorizationResult(
        receiptId: Long,
        result: ReceiptItemCategorizationResult,
        now: Long
    ): Int {
        writeBarrier.checkWritesAllowed("ReceiptItemCategorizationRepository.saveCategorizationResult")
        var insertedCount = 0
        val allocation = result.taxAllocation as? com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Available
        require(result.items.all { it.amount.isFinite() && (it.amount >= 0.0 || it.isAdjustment) }) {
            "RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED"
        }
        require(result.taxDistribution.isEmpty() || allocation != null) { "RECEIPT_ITEM_TAX_ALLOCATION_REQUIRED" }
        require(allocation == null || allocation.itemAmounts.size == result.items.size) { "RECEIPT_ITEM_TAX_COUNT_MISMATCH" }
        if (allocation != null) {
            require((allocation.itemAmounts + allocation.categoryAmounts.values + allocation.unallocatedTax)
                .all { it.currency == allocation.requestedTax.currency } &&
                allocation.itemAggregate.displayCurrency == allocation.requestedTax.currency
            ) {
                "RECEIPT_ITEM_TAX_CURRENCY_MISMATCH"
            }
            require(allocation.requestedTax.amount.isFinite() && allocation.requestedTax.amount >= 0.0 &&
                (allocation.itemAmounts + allocation.categoryAmounts.values + allocation.unallocatedTax)
                    .all { it.amount.isFinite() }
            ) { "RECEIPT_ITEM_TAX_INVALID_AMOUNT" }
            require(result.taxDistribution == allocation.categoryAmounts.mapValues { it.value.amount }) {
                "RECEIPT_ITEM_TAX_COMPATIBILITY_MISMATCH"
            }
            val requested = java.math.BigDecimal.valueOf(allocation.requestedTax.amount)
            val allocated = allocation.itemAmounts.fold(java.math.BigDecimal.ZERO) { total, share ->
                total.add(java.math.BigDecimal.valueOf(share.amount))
            }
            require(allocated.compareTo(requested) == 0) {
                "RECEIPT_ITEM_TAX_NOT_CONSERVED"
            }
            val projected = allocation.categoryAmounts.values.fold(java.math.BigDecimal.ZERO) { total, share ->
                total.add(java.math.BigDecimal.valueOf(share.amount))
            }.add(java.math.BigDecimal.valueOf(allocation.unallocatedTax.amount))
            require(projected.compareTo(requested) == 0) {
                "RECEIPT_ITEM_TAX_PROJECTION_NOT_CONSERVED"
            }
            val expectedCategories = linkedMapOf<Long, java.math.BigDecimal>()
            var expectedUnallocated = java.math.BigDecimal.ZERO
            result.items.forEachIndexed { index, item ->
                val share = java.math.BigDecimal.valueOf(allocation.itemAmounts[index].amount)
                val categoryId = item.suggestedCategory?.categoryId?.takeIf { it > 0 }
                if (categoryId == null) {
                    expectedUnallocated = expectedUnallocated.add(share)
                } else {
                    expectedCategories[categoryId] = (expectedCategories[categoryId] ?: java.math.BigDecimal.ZERO).add(share)
                }
            }
            require(allocation.categoryAmounts.keys.all { it in expectedCategories } &&
                expectedCategories.all { (categoryId, amount) ->
                    amount.compareTo(java.math.BigDecimal.valueOf(allocation.categoryAmounts[categoryId]?.amount ?: 0.0)) == 0
                } && expectedUnallocated.compareTo(java.math.BigDecimal.valueOf(allocation.unallocatedTax.amount)) == 0
            ) { "RECEIPT_ITEM_TAX_CATEGORY_MISMATCH" }
        }
        result.items.forEachIndexed { index, item ->
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
                taxAmount = allocation?.itemAmounts?.get(index)?.amount,
                isNewCategorySuggestion = item.suggestedCategory?.isNewCategorySuggestion ?: false,
                createdAt = now,
                updatedAt = now
            )
            val resultId = dao.insert(categorization)
            if (resultId > 0) {
                insertedCount++
            }
        }
        return insertedCount
    }

    suspend fun updateUserCorrection(
        itemId: Long,
        categoryId: Long?,
        categoryName: String?,
        timestamp: Long
    ) {
        writeBarrier.checkWritesAllowed("ReceiptItemCategorizationRepository.updateUserCorrection")
        dao.updateUserCorrection(
            itemId = itemId,
            categoryId = categoryId,
            categoryName = categoryName,
            timestamp = timestamp
        )
    }

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
