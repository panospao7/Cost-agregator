package com.yourname.expensetracker.domain.ai.model

/**
 * Domain-level input for warranty extraction AI providers.
 */
data class WarrantyExtractionInput(
    val receiptText: String,
    val merchant: String?,
    val totalAmount: Double?,
    val purchaseDate: Long?,
    val currency: String
)

/**
 * Domain-level output from warranty extraction AI providers.
 */
data class WarrantyExtractionResult(
    val productName: String,
    val warrantyMonths: Int,
    val warrantyType: String,
    val supportPhone: String?,
    val supportEmail: String?,
    val returnDays: Int?,
    val returnConditions: String?,
    val confidence: Float
)
