package com.yourname.expensetracker.data.repository

import android.net.Uri
import androidx.room.withTransaction
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
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.DuplicateResolution
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.debug.DebugIssueDetector
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import com.yourname.expensetracker.di.IoDispatcher
import dagger.Lazy
// import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

import com.yourname.expensetracker.data.database.AppDatabase

@Singleton
class ReceiptRepository @Inject constructor(
    private val database: AppDatabase,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val statementParser: BankStatementParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val crossSourceDeduplication: CrossSourceDeduplication,
    private val debugIssueDetector: DebugIssueDetector,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    private val warrantyUseCase: Lazy<AutoCreateWarrantyFromReceiptUseCase>,
    private val coordinator: TransactionLifecycleCoordinator,
    private val receiptLinkService: ReceiptLinkService
) {
    private companion object {
        // Use the canonical policy for all duplicate detection constants.
        private val STATEMENT_DEDUPE_WINDOW_MS = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
        private val AMOUNT_TOLERANCE = DuplicateDetectionPolicy.AMOUNT_TOLERANCE

        /**
         * **UI-PLACEHOLDER ONLY — never becomes a real expense amount.**
         *
         * The [approveReview] function in [ReviewQueueRepository] explicitly
         * blocks approval when `suggestedAmount == null` without a user override.
         * The [TransactionLifecycleCoordinator] also rejects creation requests
         * with null amounts.
         */
    }

    private enum class StatementInsertOutcome {
        INSERTED,
        REPLACED_AND_INSERTED,
        SKIPPED_EXPENSE_DUPLICATE,
        SKIPPED_PENDING_EXISTING,
        SKIPPED_DISCARD_NEW,
        SKIPPED_PENDING_DUPLICATE_RACE
    }

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
        return withContext(ioDispatcher) {
            // 1. Run OCR (Separate Try-Catch to distinguish OCR failure vs Parse failure)
            val ocrResult = try {
                ocrService.processUri(imageUri)
            } catch (e: Exception) {
                Timber.e(e, "OCR Failed for $imageUri")
                // Fallback: Try to save the image using manual record logic
                return@withContext saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
                    val failedReceipt = receipt.copy(
                        rawOcrText = "Scan Failed: ${e.message}",
                        confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK,
                        updatedAt = timeProvider.now()
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

                val receiptId = database.withTransaction {
                    val insertedReceiptId = scannedReceiptDao.insert(receipt)

                    // 5. Optionally create a PendingReview (True for Batch, False for FAB Manual Scan)
                    //
                    //    "Unknown Merchant" is a UI-placeholder fallback when neither
                    //    the normalizer nor the parser could extract a merchant name.
                    //    It will be shown in the review queue and must be edited by
                    //    the user before approval — approveReview() blocks approval
                    //    without a user override.  This placeholder never becomes a
                    //    real expense merchant value.
                    if (autoCreateReview) {
                        val suggestedMerchant = normalizedMerchant ?: parsed.merchantName ?: "Unknown Merchant"
                        val suggestedAmount = parsed.total // null when parser didn't extract a total
                        val review = PendingReview(
                            rawNotificationId = null,
                            scannedReceiptId = insertedReceiptId,
                            suggestedAmount = suggestedAmount,
                            suggestedCurrency = parsed.currency,
                            suggestedMerchant = suggestedMerchant,
                            suggestedMerchantKey = MerchantKeyGenerator.generate(suggestedMerchant),
                            suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                            suggestedDate = parsed.date, // Preserving the date found by parser
                            confidence = parsed.confidence,
                            packageName = "receipt.scan",
                            notificationTitle = "Scanned Receipt",
                            notificationText = ocrResult.fullText.take(200), // Preview snippet
                            suggestedCategoryId = normalizedMerchant?.let {
                                hybridClassifier.classify(it, suggestedAmount ?: 0.0).categoryId.takeIf { id -> id > 0 }
                            }
                        )
                        pendingReviewDao.insert(review)
                    }

                    insertedReceiptId
                }

                // F1: Trigger warranty extraction after receipt is saved
                try {
                    val warrantyResult = warrantyUseCase.get().execute(receiptId, ocrResult.fullText)
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

                return@withContext Pair(receipt.copy(id = receiptId), parsed)

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
                
                // ── Parse-failure placeholder review (never becomes real expense) ──
                // When the parser threw an exception (e.g. malformed OCR text) we
                // still save the OCR text for manual recovery.  The PendingReview
                // gets sentinel values ("Parsing Failed", null suggestedAmount)
                // that are UI placeholders only.  The user must edit these before
                // approval; approveReview() blocks approval without overrides.
                if (autoCreateReview) {
                    val review = PendingReview(
                        rawNotificationId = null,
                        scannedReceiptId = receiptId,
                        suggestedAmount = null,
                        suggestedCurrency = "EUR",
                        suggestedMerchant = "Parsing Failed",
                        suggestedMerchantKey = MerchantKeyGenerator.generate("Parsing Failed"),
                        suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                        suggestedCategoryId = null, // No category for failed parse
                        confidence = 0f,
                        packageName = "receipt.scan.error",
                        notificationTitle = "Parsing Failed",
                        notificationText = "OCR Text preserved. Manual entry required."
                    )
                    pendingReviewDao.insert(review)
                }

                return@withContext Pair(failedReceipt.copy(id = receiptId), ReceiptParser.ParsedReceipt(null, null, null, null, timeProvider.now(), "EUR", emptyList(), 0f))
            }
        }
    }

    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Persist a display copy without re-running OCR recognition.
        val path = try {
            ocrService.persistImageCopy(imageUri)
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
     *
     * Uses [TransactionLifecycleCoordinator] for the full lifecycle:
     * validate → normalize → dedupe → insert atomic → event logging
     * → post-creation side effects (via TransactionSideEffectDispatcher).
     * Source-specific side effect that remains here:
     *  - Receipt-to-expense linking
     *  - Hybrid classifier correction learning
     *
     * @Deprecated Prefer using [TransactionLifecycleCoordinator.createExpense]
     * directly with [ReceiptLinkService.linkReceiptToExpense] for the linking
     * step. This method remains for backward compatibility but will be removed
     * in a future release.
     */
    @Deprecated(
        message = "Use TransactionLifecycleCoordinator.createExpense() directly " +
            "with ReceiptLinkService.linkReceiptToExpense() for receipt-expense linking. " +
            "This method bypasses the full lifecycle and will be removed.",
        replaceWith = ReplaceWith(
            expression = "coordinator.createExpense(request)",
            imports = ["com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator"]
        )
    )
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

        // 3. Create expense via TransactionLifecycleCoordinator (validate → normalize → dedupe → insert → event)
        val request = CreateExpenseRequest(
            merchant = normalizedMerchant,
            amount = amount,
            currency = currency,
            date = date,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.RECEIPT_SCAN,
            categoryId = finalCategoryId,
            notes = notes ?: "Scanned from receipt",
            paymentMethod = paymentMethod,
            isManualEntry = true,
            latitude = latitude,
            longitude = longitude,
            locationSource = locationSource
        )

        return when (val result = coordinator.createExpense(request)) {
            is CreateExpenseResult.Created -> {
                val expenseId = result.expenseId

                // Link receipt to the newly created expense via ReceiptLinkService
                receiptLinkService.linkReceiptToExpense(
                    receiptId = receiptId,
                    expenseId = expenseId,
                    linkType = "DIRECT_SAVE",
                    source = ExpenseSource.RECEIPT_SCAN.name
                )

                // RCP-2: Update receipt item categorizations with the expense ID
                // so that each line item points to the created expense.
                database.receiptItemCategorizationDao().linkToExpense(
                    receiptId = receiptId,
                    expenseId = expenseId,
                    timestamp = timeProvider.now()
                )

                // ── Source-specific post-commit side effects ─────────────────
                if (finalCategoryId != null) {
                    runPostCommitSafely(
                        action = "classifier correction learning after receipt expense insert (expenseId=$expenseId, merchant=$normalizedMerchant)"
                    ) {
                        hybridClassifier.learnFromCorrection(
                            merchantName = normalizedMerchant,
                            correctCategoryId = finalCategoryId,
                            amount = amount
                        )
                    }
                }

                com.yourname.expensetracker.domain.model.Result.Success(expenseId)
            }
            is CreateExpenseResult.DuplicateSkipped -> {
                com.yourname.expensetracker.domain.model.Result.Duplicate
            }
            is CreateExpenseResult.ValidationFailed -> {
                com.yourname.expensetracker.domain.model.Result.Error(
                    message = "Validation failed: ${result.errors.joinToString(", ")}"
                )
            }
            is CreateExpenseResult.InsertConflict -> {
                com.yourname.expensetracker.domain.model.Result.Error(
                    message = "Insert conflict: ${result.dedupeKey}"
                )
            }
            is CreateExpenseResult.Error -> {
                com.yourname.expensetracker.domain.model.Result.Error(
                    exception = result.exception,
                    message = result.exception.message ?: "Unknown error"
                )
            }
        }
    }

    private suspend fun runPostCommitSafely(
        action: String,
        block: suspend () -> Unit
    ) {
        runCatching {
            block()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.e(error, "Post-commit action failed: %s", action)
        }
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun insertReceipt(receipt: ScannedReceipt): Long {
        return scannedReceiptDao.insert(receipt)
    }

    suspend fun getRecentReceipts(since: Long, limit: Int = Int.MAX_VALUE): List<ScannedReceipt> {
        return scannedReceiptDao.getRecentReceipts(since, limit)
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
        val debugData: DebugData? = null
    )

    /**
     * Process multiple receipts in parallel with a concurrency limit to prevent OOM.
     * Optimized: Coroutine-bounded concurrency to avoid blocking worker threads.
     */
    suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult {
        val uniqueUris = uris.distinctBy { it.toString() }
        if (uniqueUris.size < uris.size) {
            Timber.d("Removed ${uris.size - uniqueUris.size} duplicate URIs")
        }

        val maxConcurrency = 3
        val semaphore = Semaphore(permits = maxConcurrency)
        val total = uniqueUris.size
        val processedCount = AtomicInteger(0)
        val progressMutex = Mutex()

        data class BatchItemResult(val success: Boolean, val error: String?)

        val results = coroutineScope {
            uniqueUris.map { uri ->
                async {
                    val result = withContext(ioDispatcher) {
                        semaphore.withPermit {
                            try {
                                processReceipt(uri, autoCreateReview = true)
                                BatchItemResult(success = true, error = null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                BatchItemResult(
                                    success = false,
                                    error = "Failed to process $uri: ${e.message}"
                                )
                            }
                        }
                    }

                    val processed = processedCount.incrementAndGet()
                    progressMutex.withLock {
                        onProgress(processed, total)
                    }

                    result
                }
            }.awaitAll()
        }

        val successCount = results.count { it.success }
        val errors = results.mapNotNull { it.error }
        return BatchResult(
            successCount = successCount,
            failureCount = total - successCount,
            errors = errors
        )
    }

    /**
     * Runs OCR on the given URI and returns the raw result.
     *
     * Used by [BankStatementLifecycleProcessor] to obtain OCR output without
     * triggering the full statement processing pipeline.
     */
    suspend fun runStatementOcr(imageUri: Uri): OcrResult {
        return ocrService.processUri(imageUri)
    }

    /**
     * Process an image URI as a bank statement: extracting multiple transactions
     */
    suspend fun processStatement(imageUri: Uri): BatchResult {
        return withContext(ioDispatcher) {
            val startTime = timeProvider.now()
            val parsingLogs = mutableListOf<String>()
            
            // 1. Run OCR
            val ocrResult: OcrResult = ocrService.processUri(imageUri)

            // 2. Parse as multiple transactions using spatial data
            val parsedTransactions = statementParser.parse(ocrResult.blocks)
            
            if (parsedTransactions.isEmpty()) {
                parsingLogs.add("No transactions found in bank statement")
                val debugData = DebugData(
                    rawText = ocrResult.fullText,
                    parsedTransactions = emptyList(),
                    parsingLogs = parsingLogs,
                    processingTimeMs = timeProvider.now() - startTime,
                    parserUsed = "BankStatementParser"
                )
                return@withContext BatchResult(0, 1, listOf("No transactions found in screenshot"), debugData)
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
                    val merchantKey = MerchantKeyGenerator.generate(normalizedMerchant)
                    val transactionType = tx.type.toDbTransactionType()
                    val startDate = transactionDate - STATEMENT_DEDUPE_WINDOW_MS
                    val endDate = transactionDate + STATEMENT_DEDUPE_WINDOW_MS + 1
                    val minAmount = tx.amount - AMOUNT_TOLERANCE
                    val maxAmount = tx.amount + AMOUNT_TOLERANCE

                    val prefetchedPendingReviewDuplicate = pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                        merchantKey = merchantKey,
                        merchantName = normalizedMerchant,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        currency = tx.currency,
                        transactionType = transactionType.name
                    )
                    val prefetchedDuplicateResolution = prefetchedPendingReviewDuplicate?.let {
                        crossSourceDeduplication.resolvePendingReviewDuplicate(
                            existingReview = it,
                            newSource = "statement"
                        )
                    }

                    val review = PendingReview(
                        rawNotificationId = null,
                        scannedReceiptId = receiptId,
                        suggestedAmount = tx.amount,
                        suggestedCurrency = tx.currency,
                        suggestedMerchant = normalizedMerchant,
                        suggestedMerchantKey = MerchantKeyGenerator.generate(normalizedMerchant),
                        suggestedType = tx.type.toDbTransactionType().name,
                        suggestedCategoryId = classification.categoryId.takeIf { id -> id > 0 },
                        suggestedDate = tx.date ?: timeProvider.now(),
                        confidence = tx.confidence,
                        packageName = "statement.import",
                        notificationTitle = "Bank Screenshot",
                        notificationText = "Imported from screenshot: ${tx.merchant}"
                    )

                    // Window-based duplicate logic (merchant/date/amount/currency ± tolerance)
                    // cannot be fully enforced with a strict DB unique index. Keep read+write
                    // in one transaction to avoid non-atomic check/delete/insert races.
                    val outcome = database.withTransaction {
                        val hasExpenseDuplicate = hasExpenseDuplicateInRangeCurrencyAware(
                            merchantKey = merchantKey,
                            merchantName = normalizedMerchant,
                            startDate = startDate,
                            endDate = endDate,
                            minAmount = minAmount,
                            maxAmount = maxAmount,
                            currency = tx.currency,
                            transactionType = transactionType.name
                        )

                        if (hasExpenseDuplicate) {
                            return@withTransaction StatementInsertOutcome.SKIPPED_EXPENSE_DUPLICATE
                        }

                        val transactionalPendingReviewDuplicate = pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                            merchantKey = merchantKey,
                            merchantName = normalizedMerchant,
                            startDate = startDate,
                            endDate = endDate,
                            minAmount = minAmount,
                            maxAmount = maxAmount,
                            currency = tx.currency,
                            transactionType = transactionType.name
                        )

                        if (transactionalPendingReviewDuplicate != null) {
                            if (prefetchedPendingReviewDuplicate?.id != transactionalPendingReviewDuplicate.id) {
                                return@withTransaction StatementInsertOutcome.SKIPPED_PENDING_DUPLICATE_RACE
                            }

                            when (prefetchedDuplicateResolution ?: DuplicateResolution.KeepExisting) {
                                DuplicateResolution.KeepExisting -> {
                                    return@withTransaction StatementInsertOutcome.SKIPPED_PENDING_EXISTING
                                }

                                DuplicateResolution.ReplaceExisting -> {
                                    pendingReviewDao.delete(transactionalPendingReviewDuplicate)
                                    val insertedId = pendingReviewDao.insert(review)
                                    return@withTransaction if (insertedId > 0) {
                                        StatementInsertOutcome.REPLACED_AND_INSERTED
                                    } else {
                                        StatementInsertOutcome.SKIPPED_PENDING_DUPLICATE_RACE
                                    }
                                }

                                DuplicateResolution.DiscardNew -> {
                                    return@withTransaction StatementInsertOutcome.SKIPPED_DISCARD_NEW
                                }
                            }
                        }

                        val insertedId = pendingReviewDao.insert(review)
                        if (insertedId > 0) {
                            StatementInsertOutcome.INSERTED
                        } else {
                            StatementInsertOutcome.SKIPPED_PENDING_DUPLICATE_RACE
                        }
                    }

                    when (outcome) {
                        StatementInsertOutcome.INSERTED -> {
                            successCount++
                        }

                        StatementInsertOutcome.REPLACED_AND_INSERTED -> {
                            parsingLogs.add("REPLACE: Replacing existing pending review with statement data for ${tx.merchant}")
                            successCount++
                        }

                        StatementInsertOutcome.SKIPPED_EXPENSE_DUPLICATE -> {
                            parsingLogs.add("SKIP: Duplicate in Expenses for ${tx.merchant} €${tx.amount}")
                        }

                        StatementInsertOutcome.SKIPPED_PENDING_EXISTING -> {
                            parsingLogs.add("SKIP: Pending review already exists for ${tx.merchant} €${tx.amount}")
                        }

                        StatementInsertOutcome.SKIPPED_DISCARD_NEW -> {
                            parsingLogs.add("SKIP: Discarding new transaction ${tx.merchant} €${tx.amount}")
                        }

                        StatementInsertOutcome.SKIPPED_PENDING_DUPLICATE_RACE -> {
                            parsingLogs.add("SKIP: Pending review duplicate exists for ${tx.merchant} €${tx.amount}")
                        }
                    }
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
            val issues = debugIssueDetector.detectIssues(
                rawText = ocrResult.fullText,
                transactions = parsedTransactions,
                processingTimeMs = timeProvider.now() - startTime
            )
            
            // Create debug data
            val debugData = DebugData(
                rawText = ocrResult.fullText,
                parsedTransactions = parsedTransactions,
                parsingLogs = parsingLogs,
                processingTimeMs = timeProvider.now() - startTime,
                parserUsed = "BankStatementParser (${parsedTransactions.size} transactions)",
                issues = issues
            )

            return@withContext BatchResult(successCount, parsedTransactions.size - successCount, errors, debugData)
        }
    }

    private suspend fun hasExpenseDuplicateInRange(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Boolean {
        return expenseDao.existsByMerchantKeyInRange(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount
        ) || expenseDao.existsByMerchantInRange(
            merchant = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount
        )
    }

    private suspend fun hasExpenseDuplicateInRangeCurrencyAware(
        merchantKey: String,
        merchantName: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean {
        val normalizedCurrency = DuplicateDetectionPolicy.normalizeCurrency(currency)
        return expenseDao.existsByMerchantKeyInRangeCurrencyAware(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = normalizedCurrency,
            transactionType = transactionType
        ) || expenseDao.existsByMerchantInRangeCurrencyAware(
            merchant = merchantName,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = normalizedCurrency,
            transactionType = transactionType
        )
    }

    suspend fun clearAllScannedReceipts() {
        val receipts = scannedReceiptDao.getAll()
        receipts.forEach { receipt ->
            receipt.imagePath?.let { path -> ocrService.deleteImage(path) }
        }
        scannedReceiptDao.deleteAll()
    }

    /**
     * Raw OCR text retention is handled by [DataRetentionWorker], which purges
     * [ScannedReceipt.rawOcrText] after the configured retention period
     * (see [ScannedReceipt.rawOcrTextPurgedAt]).
     *
     * The purge clears the text content and sets [ScannedReceipt.rawOcrTextPurgedAt]
     * to the purge timestamp. Downstream consumers must check this field before
     * attempting OCR re-processing.
     *
     * Concatenates all raw OCR text from the database for debugging/parsing refinement.
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

    /**
     * Returns receipts eligible for automated matching: UNMATCHED and SUGGESTED.
     * Excludes AUTO_MATCHED, MANUALLY_MATCHED, and REJECTED.
     */
    suspend fun getProcessableReceipts(): List<com.yourname.expensetracker.data.database.entity.ScannedReceipt> {
        return scannedReceiptDao.getProcessableReceipts()
    }

    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        confidence: Double
    ) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        // RCP-22: Clear suggestedExpenseId when auto-linking to prevent stale
        // references from being reused if the receipt is later unlinked.
        val updated = receipt.copy(
            expenseId = expenseId,
            suggestedExpenseId = null,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.AUTO_MATCHED,
            matchConfidence = confidence.toFloat(),
            updatedAt = timeProvider.now()
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
            matchConfidence = confidence.toFloat(),
            updatedAt = timeProvider.now()
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Saved match suggestion for receipt $receiptId: expense $suggestedExpenseId with confidence $confidence")
    }

    suspend fun approveMatchSuggestion(receiptId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val suggestedId = receipt.suggestedExpenseId ?: return
        
        // RCP-22: Clear suggestedExpenseId after approval to prevent stale
        // references from being reused if the receipt is later unlinked.
        val updated = receipt.copy(
            expenseId = suggestedId,
            suggestedExpenseId = null,
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.MANUALLY_MATCHED,
            updatedAt = timeProvider.now()
        )
        scannedReceiptDao.update(updated)
        timber.log.Timber.d("Manually approved match for receipt $receiptId to expense $suggestedId")
    }

    suspend fun rejectAllSuggestions(receiptId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return
        val updated = receipt.copy(
            matchStatus = com.yourname.expensetracker.data.database.entity.MatchStatus.REJECTED,
            suggestedExpenseId = null,
            updatedAt = timeProvider.now()
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
            matchConfidence = null,
            updatedAt = timeProvider.now()
        )
        scannedReceiptDao.update(updated)
    }

    suspend fun getCandidateExpensesForReceipt(
        receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt,
        lookbackDays: Int = 14,
        limit: Int = 20
    ): List<com.yourname.expensetracker.data.database.entity.Expense> {
        val anchorDate = receipt.parsedDate ?: receipt.createdAt
        // DAY_IN_MILLIS constant for lookback window — acceptable TTL usage (not calendar math)
        val dayMs = 86_400_000L
        val startDate = anchorDate - lookbackDays * dayMs
        val endDate = anchorDate + lookbackDays * dayMs
        val receiptAmount = receipt.parsedTotal

        return expenseDao.getExpensesBetweenUncapped(startDate, endDate)
            .asSequence()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .sortedBy { expense ->
                val amountPenalty = receiptAmount?.let { kotlin.math.abs(it - expense.effectiveAmount) } ?: 0.0
                val datePenalty = kotlin.math.abs(anchorDate - expense.date) / dayMs.toDouble()
                amountPenalty + datePenalty
            }
            .take(limit)
            .toList()
    }
}
