package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptItemCategorizationInputBuilder @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptParser: ReceiptParser
) {
    /**
     * Builds input for receipt item categorization.
     */
    suspend fun build(receipt: ScannedReceipt): ReceiptItemCategorizationInput {
        // Parse line items from JSON
        val lineItems = receipt.parsedItems?.let {
            receiptParser.lineItemsFromJson(it)
        } ?: emptyList()
        
        // Get user's categories
        val categories = categoryRepository.getAll()
        
        return ReceiptItemCategorizationInput(
            receiptId = receipt.id,
            merchant = receipt.parsedMerchant ?: "Unknown Merchant",
            lineItems = lineItems,
            userCategories = categories,
            totalTax = receipt.parsedTaxAmount,
            currency = receipt.currency
        )
    }
}
