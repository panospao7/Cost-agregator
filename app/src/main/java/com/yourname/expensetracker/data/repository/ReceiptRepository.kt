package com.yourname.expensetracker.data.repository

import android.net.Uri
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: MerchantNormalizer,
    private val budgetMonitor: BudgetMonitor
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     */
    suspend fun processReceipt(imageUri: Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processImage(imageUri)

        // 2. Parse the OCR text
        val parsed = receiptParser.parse(ocrResult.fullText)

        // 3. Normalize merchant if found
        val normalizedMerchant = parsed.merchantName?.let {
            merchantNormalizer.applyUserCorrections(it)
        }

        // 4. Save scanned receipt record
        val receipt = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = parsed.total,
            parsedMerchant = normalizedMerchant ?: parsed.merchantName,
            parsedDate = parsed.date,
            parsedItems = if (parsed.lineItems.isNotEmpty())
                receiptParser.lineItemsToJson(parsed.lineItems) else null,
            parsedTaxAmount = parsed.tax,
            currency = parsed.currency,
            confidence = parsed.confidence
        )

        val receiptId = scannedReceiptDao.insert(receipt)

        return Pair(receipt.copy(id = receiptId), parsed)
    }

    /**
     * Create an expense from a scanned receipt (after user review/edit)
     */
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): Long {
        // 1. Normalize merchant
        val normalizedMerchant = merchantNormalizer.applyUserCorrections(merchant)

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: categorizationEngine.categorize(normalizedMerchant)

        // 3. Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = 60000 // 1 minute window for manual/scan entries
        )
        if (isDuplicate) return -1L

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true, // Scanned receipts are treated as manual entries
            notes = notes ?: "Scanned from receipt"
        )

        val expenseId = expenseDao.insert(expense)

        // 5. Link receipt to expense
        if (expenseId > 0) {
            scannedReceiptDao.linkToExpense(receiptId, expenseId)

            // 6. Check budgets
            budgetMonitor.checkBudgets()

            // 7. Learn merchant → category mapping
            if (finalCategoryId != null) {
                val pattern = categorizationEngine.normalize(normalizedMerchant)
                if (pattern.isNotEmpty()) {
                    merchantCategoryDao.insert(
                        MerchantCategory(
                            merchantPattern = pattern,
                            categoryId = finalCategoryId,
                            confidence = 1.0f
                        )
                    )
                }
            }
        }

        return expenseId
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        ocrService.deleteImage(receipt.imagePath)
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }
}
