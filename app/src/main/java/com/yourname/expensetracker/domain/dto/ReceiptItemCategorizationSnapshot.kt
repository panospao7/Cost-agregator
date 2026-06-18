package com.yourname.expensetracker.domain.dto

/**
 * Domain-level snapshot of a previously-analyzed receipt item categorization.
 *
 * Contains only the fields needed to render / edit reviewable results in the UI,
 * without dragging the full Room entity
 * [com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization].
 *
 * The [id] field is preserved so that UI correction flows can still target the
 * stored row for updates without importing the Room entity directly.
 *
 * Mappers in the data / adapter layer should convert [ReceiptItemCategorization]
 * entities to [ReceiptItemCategorizationSnapshot] at the repository boundary.
 */
data class ReceiptItemCategorizationSnapshot(
    val id: Long,
    val receiptId: Long,
    val expenseId: Long?,
    val itemDescription: String,
    val itemAmount: Double,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String?,
    val confidence: Float,
    val aiRationale: String?,
    val alternativeCategoriesJson: String?,
    val userCorrectedCategoryId: Long?,
    val userCorrectedCategoryName: String?,
    val userCorrectedAt: Long?,
    val taxAmount: Double?,
    val isNewCategorySuggestion: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
