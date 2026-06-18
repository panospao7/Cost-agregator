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
    val warrantyMonths: Int?,
    val warrantyType: String,
    val supportPhone: String?,
    val supportEmail: String?,
    val returnDays: Int?,
    val returnConditions: String?,
    val confidence: Float
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f) {
            "WarrantyExtractionResult.confidence must be finite and within [0, 1]"
        }
        require(warrantyMonths == null || warrantyMonths > 0) {
            "WarrantyExtractionResult.warrantyMonths must be > 0 when provided"
        }
        require(returnDays == null || returnDays > 0) {
            "WarrantyExtractionResult.returnDays must be > 0 when provided"
        }
    }
}
