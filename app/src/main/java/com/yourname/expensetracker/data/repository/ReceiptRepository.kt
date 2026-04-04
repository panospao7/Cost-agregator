package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import java.util.Date
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
// import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber

import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.data.database.AppDatabase

@Singleton
class ReceiptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val pendingReviewDao: PendingReviewDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val statementParser: BankStatementParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val crossSourceDeduplication: CrossSourceDeduplication,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    private val warrantyUseCase: AutoCreateWarrantyFromReceiptUseCase
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     *
     * @param imageUri URI of the image to process
     * @param autoCreateReview Whether to automatically create a PendingReview entry (true for batch, false for manual)
     */
    suspend fun processReceipt(
        imageUri: Uri,
        autoCreateReview: Boolean = false
    ): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Run OCR (Separate Try-Catch to distinguish OCR failure vs Parse failure)
        val ocrResult = try {
            ocrService.processUri(imageUri)
        } catch (e: Exception) {
            Timber.e(e, "OCR Failed for $imageUri")
            // Fallback: Try to save the image using manual record logic
            return saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
                val failedReceipt = receipt.copy(
                    rawOcrText = "Scan Failed: ${e.message}", 
                    confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK
                )
                scannedReceiptDao.update(failedReceipt)
                Pair(failedReceipt, parsed)
            }
        }

        try {
            // 2. Parse the OCR text
            val parsed = receiptParser.parse(ocrResult.fullText)

            // 3. Normalize merchant if found
            val lookupResult = parsed.merchantName?.let {
                merchantNormalizer.normalize(it, autoCreate = true)
            }
            val normalizedMerchant = lookupResult?.canonical?.normalizedName

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

            // F1: Trigger warranty extraction after receipt is saved
            try {
                val warrantyResult = warrantyUseCase.execute(receiptId, ocrResult.fullText)
                when (warrantyResult) {
                    is WarrantyCreationResult.Success -> {
                        Timber.d("Warranty created for receipt $receiptId with confidence ${warrantyResult.confidence}%")
                    }
                    is WarrantyCreationResult.LowConfidence -> {
                        // Low-confidence extractions are persisted as needsReview drafts in the use case.
                        Timber.d("Warranty review draft persisted for receipt $receiptId (confidence ${warrantyResult.extractedData.confidence}%)")
                    }
                    is WarrantyCreationResult.AlreadyExists -> {
                        Timber.d("Warranty already exists for receipt $receiptId (id=${warrantyResult.existingWarrantyId})")
                    }
                    is WarrantyCreationResult.Failure -> {
                        Timber.d("Warranty extraction skipped for receipt $receiptId: ${warrantyResult.error}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Warranty extraction failed for receipt $receiptId")
                // Don't fail the whole process if warranty extraction fails
            }

            // 5. Optionally create a PendingReview (True for Batch, False for FAB Manual Scan)
            if (autoCreateReview) {
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = parsed.total ?: 0.0,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = normalizedMerchant ?: parsed.merchantName ?: "Unknown Merchant",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedDate = parsed.date, // Preserving the date found by parser
                    confidence = parsed.confidence,
                    packageName = "receipt.scan",
                    notificationTitle = "Scanned Receipt",
                    notificationText = ocrResult.fullText.take(200), // Preview snippet
                    suggestedCategoryId = normalizedMerchant?.let { 
                         hybridClassifier.classify(it, parsed.total ?: 0.0).categoryId.takeIf { id -> id > 0 }
                    }
                )
                pendingReviewDao.insert(review)
            }
            return Pair(receipt.copy(id = receiptId), parsed)

        } catch (e: Exception) {
            // Parsing Logic Failed, but we HAVE the OCR text!
            // Save it so user can manually edit without losing the text.
            Timber.e(e, "Parsing Failed for $imageUri")
            
            val failedReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText, // PRESERVED!
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null, 
                parsedItems = null,
                parsedTaxAmount = null, // Explicitly null for failed parse
                currency = "EUR",
                confidence = 0f
            )
            val receiptId = scannedReceiptDao.insert(failedReceipt)
            
            if (autoCreateReview) {
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Parsing Failed",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedCategoryId = null, // No category for failed parse
                    confidence = 0f,
                    packageName = "receipt.scan.error",
                    notificationTitle = "Parsing Failed",
                    notificationText = "OCR Text preserved. Manual entry required."
                )
                pendingReviewDao.insert(review)
            }

            return Pair(failedReceipt.copy(id = receiptId), ReceiptParser.ParsedReceipt(null, null, null, null, timeProvider.now(), "EUR", emptyList(), 0f))
        }
    }

    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Try to at least copy the image for display if possible, or use original
        // For simplicity, we'll try to get ocrService to at least give us a path if it can load the bitmap
        val path = try {
            // We'll reuse the OCR service's image saving logic if possible
            // But if it fails, we fall back to the original URI string (not ideal but better than nothing)
            ocrService.processImage(imageUri).savedImagePath
        } catch (e: Exception) {
            imageUri.toString()
        }

        val receipt = ScannedReceipt(
            imagePath = path,
            rawOcrText = "[OCR Failed or Skipped]",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = timeProvider.now(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0f
        )
        val receiptId = scannedReceiptDao.insert(receipt)
        
        return Pair(
            receipt.copy(id = receiptId),
            ReceiptParser.ParsedReceipt(
                merchantName = null,
                total = null,
                subtotal = null,
                tax = null,
                date = timeProvider.now(),
                currency = "EUR",
                lineItems = emptyList(),
                confidence = 0f
            )
        )
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
        date: Long = timeProvider.now(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationSource: String? = null
    ): com.yourname.expensetracker.domain.model.Result<Long> {
        // 1. Normalize merchant
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }

        // 3. Atomic insert with dedupe key
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            merchantKey = MerchantKeyGenerator.generate(normalizedMerchant),
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            createdAt = timeProvider.now(),
            paymentMethod = paymentMethod,
            isManualEntry = true,
            notes = notes ?: "Scanned from receipt",
            dedupeKey = Expense.generateDedupeKey(amount, normalizedMerchant, date),
            latitude = latitude,
            longitude = longitude,
            locationSource = locationSource
        )

        val expenseId = expenseDao.insertAtomic(expense)

        if (expenseId <= 0) {
            return com.yourname.expensetracker.domain.model.Result.Duplicate
        }

        // 4. Link receipt to expense
        scannedReceiptDao.linkToExpense(receiptId, expenseId)

        // 5. Check budgets
        budgetMonitor.checkBudgets()

        // 6. Check for anomalies and alert
        val enrichedExpense = expense.copy(id = expenseId)
        val expenseWithCategory = com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
            expense = enrichedExpense,
            category = finalCategoryId?.let { database.categoryDao().getById(it) }
        )
        anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)

        // 7. Learn merchant → category mapping
        if (finalCategoryId != null) {
            try {
                hybridClassifier.learnFromCorrection(
                    merchantName = normalizedMerchant,
                    correctCategoryId = finalCategoryId,
                    amount = amount
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to learn categorization")
            }
            merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
        }

        return com.yourname.expensetracker.domain.model.Result.Success(expenseId)
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun updateCategorizationStatus(receiptId: Long, status: CategorizationStatus) {
        scannedReceiptDao.updateCategorizationStatus(receiptId, status.name)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        receipt.imagePath?.let { ocrService.deleteImage(it) }
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }

    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val errors: List<String>,
        val debugData: com.yourname.expensetracker.ui.screens.debug.DebugData? = null
    )

    /**
     * Process multiple receipts in parallel with a concurrency limit to prevent OOM.
     * Optimized: Sequential processing with semaphore limits memory usage (Issue 2.16)
     */
    suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult {
        val uniqueUris = uris.distinctBy { it.toString() }
        if (uniqueUris.size < uris.size) {
            Timber.d("Removed ${uris.size - uniqueUris.size} duplicate URIs")
        }

        val semaphore = java.util.concurrent.Semaphore(3)
        val total = uniqueUris.size
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()

        for (uri in uniqueUris) {
            semaphore.acquire()
            try {
                processReceipt(uri, autoCreateReview = true)
                successes++
            } catch (e: Exception) {
                failures++
                errors.add("Failed to process $uri: ${e.message}")
            } finally {
                semaphore.release()
            }
            onProgress(successes + failures, total)
        }

        return BatchResult(successes, failures, errors)
    }

    /**
     * Process an image URI as a bank statement: extracting multiple transactions
     */
    suspend fun processStatement(imageUri: Uri): BatchResult {
        val startTime = timeProvider.now()
        val parsingLogs = mutableListOf<String>()
        
        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processUri(imageUri)

        // 2. Parse as multiple transactions using spatial data
        val parsedTransactions = statementParser.parse(ocrResult.blocks)
        
        if (parsedTransactions.isEmpty()) {
            parsingLogs.add("No transactions found in bank statement")
            val debugData = com.yourname.expensetracker.ui.screens.debug.DebugData(
                rawText = ocrResult.fullText,
                parsedTransactions = emptyList(),
                parsingLogs = parsingLogs,
                processingTimeMs = timeProvider.now() - startTime,
                parserUsed = "BankStatementParser"
            )
            return BatchResult(0, 1, listOf("No transactions found in screenshot"), debugData)
        }

        // 3. Save common scanned receipt record
        val receiptRecord = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = null, // Varies per transaction
            parsedMerchant = "Bank Statement",
            parsedDate = timeProvider.now(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = parsedTransactions.firstOrNull()?.currency ?: "EUR",
            confidence = 0.8f
        )
        val receiptId = scannedReceiptDao.insert(receiptRecord)

        // Fetch duplicates context once before the loop
        val allExpenses = expenseDao.getAllFlow(1000).first()
        val allPendingReviews = pendingReviewDao.getPending(500).map { it.review }

        // 4. Create a PendingReview for EACH transaction found
        var successCount = 0
        val errors = mutableListOf<String>()

        parsedTransactions.forEach { tx ->
            try {
                // Normalize merchant
                val lookupResult = merchantNormalizer.normalize(tx.merchant, autoCreate = true)
                val normalizedMerchant = lookupResult.canonical.normalizedName
                
                val classification = hybridClassifier.classify(
                    merchantName = normalizedMerchant,
                    amount = tx.amount
                )

                val transactionDate = tx.date ?: timeProvider.now()
                
                // 1. Check for duplicates in Expense table (Unified logic)
                val expenseDuplicate = crossSourceDeduplication.findExpenseDuplicate(
                    amount = tx.amount,
                    merchant = normalizedMerchant,
                    date = transactionDate,
                    expenses = allExpenses
                )
                
                if (expenseDuplicate != null) {
                    val existingSource = if (expenseDuplicate.rawNotificationId != null) "notification" else "other"
                    parsingLogs.add("SKIP: Duplicate in Expenses from $existingSource for ${tx.merchant} €${tx.amount}")
                    return@forEach
                }

                // 2. Check for duplicates in PendingReview table (Review Zone Expansion)
                val pendingReviewDuplicate = crossSourceDeduplication.findPendingReviewDuplicate(
                    amount = tx.amount,
                    merchant = normalizedMerchant,
                    date = transactionDate,
                    pendingReviews = allPendingReviews
                )
                
                if (pendingReviewDuplicate != null) {
                    val resolution = crossSourceDeduplication.resolvePendingReviewDuplicate(
                        existingReview = pendingReviewDuplicate,
                        newSource = "statement"
                    )
                    
                    when (resolution) {
                        com.yourname.expensetracker.domain.intelligence.DuplicateResolution.KeepExisting -> {
                            parsingLogs.add("SKIP: Pending review already exists for ${tx.merchant} €${tx.amount}")
                            return@forEach
                        }
                        com.yourname.expensetracker.domain.intelligence.DuplicateResolution.ReplaceExisting -> {
                            parsingLogs.add("REPLACE: Replacing existing pending review with statement data for ${tx.merchant}")
                            pendingReviewDao.delete(pendingReviewDuplicate)
                        }
                        com.yourname.expensetracker.domain.intelligence.DuplicateResolution.DiscardNew -> {
                            parsingLogs.add("SKIP: Discarding new transaction ${tx.merchant} €${tx.amount}")
                            return@forEach
                        }
                    }
                }

                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = tx.amount,
                    suggestedCurrency = tx.currency,
                    suggestedMerchant = normalizedMerchant,
                    suggestedType = tx.type.name,
                    suggestedCategoryId = classification.categoryId.takeIf { id -> id > 0 },
                    suggestedDate = tx.date ?: timeProvider.now(),
                    confidence = tx.confidence,
                    packageName = "statement.import",
                    notificationTitle = "Bank Screenshot",
                    notificationText = "Imported from screenshot: ${tx.merchant}"
                )
                pendingReviewDao.insert(review)
                successCount++
            } catch (e: Exception) {
                val errorMsg = "Failed to save transaction ${tx.merchant}: ${e.message}"
                errors.add(errorMsg)
                parsingLogs.add(errorMsg)
            }
        }
        
        // Add low confidence warnings to logs
        parsedTransactions.filter { it.confidence < 0.7f }.forEach { tx ->
            parsingLogs.add("Low confidence (${(tx.confidence * 100).toInt()}%) for ${tx.merchant}")
        }
        
        // Detect issues automatically
        val issues = com.yourname.expensetracker.ui.screens.debug.DebugIssueDetector.detectIssues(
            context = context,
            rawText = ocrResult.fullText,
            transactions = parsedTransactions,
            processingTimeMs = timeProvider.now() - startTime
        )
        
        // Create debug data
        val debugData = com.yourname.expensetracker.ui.screens.debug.DebugData(
            rawText = ocrResult.fullText,
            parsedTransactions = parsedTransactions,
            parsingLogs = parsingLogs,
            processingTimeMs = timeProvider.now() - startTime,
            parserUsed = "BankStatementParser (${parsedTransactions.size} transactions)",
            issues = issues
        )

        return BatchResult(successCount, parsedTransactions.size - successCount, errors, debugData)
    }

    suspend fun clearAllScannedReceipts() {
        val receipts = scannedReceiptDao.getAll()
        receipts.forEach { receipt ->
            receipt.imagePath?.let { path -> ocrService.deleteImage(path) }
        }
        scannedReceiptDao.deleteAll()
    }

    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    suspend fun exportParserDebugData(): String {
        val totalCount = scannedReceiptDao.getCount()
        val sb = StringBuilder()
        sb.append("=== EXPORTED PARSER DEBUG DATA ($totalCount RECEIPTS) ===\n\n")
        
        val pageSize = 100
        var offset = 0
        var processedCount = 0
        
        while (true) {
            val page = scannedReceiptDao.getReceiptsPaged(pageSize, offset)
            if (page.isEmpty()) break
            
            page.forEachIndexed { index, receipt ->
                sb.append("--- RECEIPT #${offset + index + 1} (ID: ${receipt.id}) ---\n")
                sb.append(formatReceiptDebug(receipt))
                sb.append("\n\n")
            }
            processedCount += page.size
            offset += pageSize
        }
        return sb.toString()
    }

    /**
     * Debug function to get detailed info about a scanned receipt
     */
    suspend fun debugReceipt(receiptId: Long): String {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return "Not found"
        return formatReceiptDebug(receipt)
    }

    private fun formatReceiptDebug(receipt: ScannedReceipt): String {
        return """
            ═════════════════════════════════════════
            RECEIPT DEBUG REPORT (ID: ${receipt.id})
            ═════════════════════════════════════════
            
            IMAGE PATH: ${receipt.imagePath}
            
            RAW OCR TEXT:
            ┌─────────────────────────────────────┐
            ${receipt.rawOcrText}
            └─────────────────────────────────────┘
            
            PARSED VALUES:
            • Merchant:  ${receipt.parsedMerchant ?: "NULL"}
            • Total:     ${receipt.parsedTotal ?: "NULL"}
            • Date:      ${receipt.parsedDate?.let { Date(it) } ?: "NULL"}
            • Tax:       ${receipt.parsedTaxAmount ?: "NULL"}
            • Currency:  ${receipt.currency}
            • Confidence: ${receipt.confidence}
            
            LINE ITEMS:
            ${receipt.parsedItems ?: "None"}
            
            ═════════════════════════════════════════
        """.trimIndent()
    }

    // Receipt Matching Methods
    suspend fun getUnmatchedReceipts(): List<com.yourname.expensetracker.data.database.entity.ScannedReceipt> {
        return scannedReceiptDao.getUnmatchedReceipts()
    }

    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        confidence: Double
    ) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val updated = receipt.copy(
            expenseId = expenseId,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.AUTO_MATCHED,
            matchConfidence = confidence.toFloat()
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Linked receipt $receiptId to expense $expenseId with confidence $confidence")
    }

    suspend fun saveMatchSuggestion(
        receiptId: Long,
        suggestedExpenseId: Long,
        confidence: Double
    ) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val updated = receipt.copy(
            suggestedExpenseId = suggestedExpenseId,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.SUGGESTED,
            matchConfidence = confidence.toFloat()
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Saved match suggestion for receipt $receiptId: expense $suggestedExpenseId with confidence $confidence")
    }

    suspend fun approveMatchSuggestion(receiptId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val suggestedId = receipt.suggestedExpenseId ?: return
        
        val updated = receipt.copy(
            expenseId = suggestedId,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.MANUALLY_MATCHED
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Manually approved match for receipt $receiptId to expense $suggestedId")
    }

    suspend fun rejectAllSuggestions(receiptId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val updated = receipt.copy(
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.REJECTED,
            suggestedExpenseId = null
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Rejected all match suggestions for receipt $receiptId")
    }

    suspend fun getReceiptsWithSuggestions(): List<com.yourname.expensetracker.data.database.entity.ScannedReceipt> {
        return scannedReceiptDao.getReceiptsWithSuggestions()
    }

    suspend fun getExpenseById(id: Long): com.yourname.expensetracker.data.database.entity.Expense? {
        return expenseDao.getById(id)
    }

    suspend fun clearMatchForReceipt(receiptId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val updated = receipt.copy(
            expenseId = null,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.UNMATCHED,
            suggestedExpenseId = null,
            matchConfidence = null
        )
        scannedReceiptDao.update(updated)
    }

    suspend fun getCandidateExpensesForReceipt(
        receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt,
        lookbackDays: Int = 14,
        limit: Int = 20
    ): List<com.yourname.expensetracker.data.database.entity.Expense> {
        val anchorDate = receipt.parsedDate ?: receipt.createdAt
        val dayMs = 86_400_000L
        val startDate = anchorDate - lookbackDays * dayMs
        val endDate = anchorDate + lookbackDays * dayMs
        val receiptAmount = receipt.parsedTotal

        return expenseDao.getExpensesBetween(startDate, endDate)
            .asSequence()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .sortedBy { expense ->
                val amountPenalty = receiptAmount?.let { kotlin.math.abs(it - expense.amount) } ?: 0.0
                val datePenalty = kotlin.math.abs(anchorDate - expense.date) / dayMs.toDouble()
                amountPenalty + datePenalty
            }
            .take(limit)
            .toList()
    }
}
