package com.yourname.expensetracker.data.repository

import android.net.Uri
import androidx.room.withTransaction
import java.time.Instant
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.ExtractionState
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
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
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAssetStore
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptTimestampPolicy
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.debug.DebugIssueDetector
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceContext
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
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
import kotlinx.coroutines.flow.first
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
    private val coordinator: TransactionLifecycleCoordinator,
    private val receiptLinkService: ReceiptLinkService,
    private val assetStore: ReceiptAssetStore,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val receiptLifecycleCoordinator: Lazy<ReceiptLifecycleCoordinator>,
    private val writeBarrier: DatabaseWriteBarrier,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val receiptEventDao: ReceiptEventDao,
    private val pendingReviewSourceLinkService: PendingReviewSourceLinkService
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

    private suspend fun sanitizeOcrBeforeInsert(rawOcrText: String?): String {
        val mode = privacySettingsRepository.getSettings().rawOcrStorageMode
        return RawContentSanitizer.sanitizeRawOcr(rawOcrText, mode)
    }

    /**
     * Draft produced by the OCR/parse stage that carries all the information
     * the coordinator needs to persist a [ScannedReceipt] row, but does NOT
     * write to the database itself (P3-P1-01: draft-first lifecycle).
     */
    data class ReceiptProcessingDraft(
        val imagePath: String?,
        val rawOcrTextForPersistence: String,
        val ephemeralRawOcrText: String?,
        val parsedTotal: Double?,
        val parsedMerchant: String?,
        val normalizedMerchant: String?,
        val parsedDate: Long?,
        val parsedItems: String?,
        val parsedTaxAmount: Double?,
        val currency: String,
        val confidence: Float,
        val processingStatus: String,
        val parseFailureReason: String?,
        val ocrFailureReason: String?,
        val pagesProcessed: Int? = null,
        val totalPages: Int? = null,
        val taxInclusive: Boolean = false
    )

    /**
     * Result of [processReceipt], carrying the receipt, parsed data, and
     * PDF truncation metadata (P2-15).
     *
     * @param ephemeralRawOcrText The original unsanitized OCR text for fingerprinting.
     *   This is NOT persisted — it exists only for the coordinator to compute
     *   text fingerprints before sanitization discards the raw content.
     */
    data class ProcessReceiptResult(
        val receipt: ScannedReceipt,
        val parsed: ReceiptParser.ParsedReceipt,
        val pagesProcessed: Int? = null,
        val totalPages: Int? = null,
        val ephemeralRawOcrText: String? = null,
        val duplicateOfReceiptId: Long? = null,
        val isPreExistingDuplicate: Boolean = false
    )

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     *
     * @param imageUri URI of the image to process
     * @param autoCreateReview Whether to automatically create a PendingReview entry (true for batch, false for manual)
     */
    suspend fun processReceipt(
        imageUri: Uri,
        autoCreateReview: Boolean = false,
        resolvedMimeType: String? = null
    ): ProcessReceiptResult {
        return withContext(ioDispatcher) {
            // 0. Pre-OCR exact-hash dedup: skip expensive OCR if this exact file was already processed
            // TODO P3-CURRENT-006: computeUriHash reads from content resolver while imageHash
            // stored on receipts is computed from the persisted file. If the URI is a content://
            // provider that transforms bytes (e.g. EXIF stripping), hashes may diverge.
            // Consider always computing hash from the persisted file path after persistReceiptAsset.
            val uriHashResult = assetStore.computeUriHash(imageUri)
            if (uriHashResult.isSuccess) {
            val existingMatch = scannedReceiptDao.getByImageHash(uriHashResult.getOrThrow())
            if (existingMatch != null) {
                Timber.d("Duplicate receipt detected pre-OCR by exact hash: existingId=${existingMatch.id}")
                // P3-P1-07: Use the existing receipt's currency instead of hardcoded "EUR"
                val existingCurrency = existingMatch.currency.takeIf { it.isNotBlank() } ?: homeCurrency()
                return@withContext ProcessReceiptResult(existingMatch, ReceiptParser.ParsedReceipt(null, null, null, null, timeProvider.now(), existingCurrency, emptyList(), 0f), ephemeralRawOcrText = null, duplicateOfReceiptId = existingMatch.id, isPreExistingDuplicate = true)
            }
        }

            // 1. Run OCR (Separate Try-Catch to distinguish OCR failure vs Parse failure)
            val ocrResult = try {
                if (resolvedMimeType != null) {
                    ocrService.processUriWithMime(imageUri, resolvedMimeType)
                } else {
                    ocrService.processUri(imageUri)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "OCR Failed for $imageUri")
                // Fallback: Try to save the image using manual record logic
                return@withContext saveManualReceiptRecord(imageUri).let { result ->
                    val failedReceipt = result.receipt.copy(
                        rawOcrText = sanitizeOcrBeforeInsert("Scan failed"),
                        confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK,
                        updatedAt = timeProvider.now()
                    )
                    scannedReceiptDao.update(failedReceipt)
                    ProcessReceiptResult(failedReceipt, result.parsed)
                }
            }

            // 2. Parse the OCR text with the user's home currency as fallback
            // P3-CURRENT-018: Narrow try/catch to ONLY the parse call so that
            // DB/normalizer/classifier errors propagate as infrastructure failures
            // rather than being misclassified as PARSE_FAILED.
            val homeCur = homeCurrency()
            val parsed = try {
                receiptParser.parse(ocrResult.fullText, homeCurrency = homeCur)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Parsing Logic Failed, but we HAVE the OCR text!
                // Save it so user can manually edit without losing the text.
                Timber.e(e, "Parsing Failed for $imageUri")
                
                val safeReason = safeFailureReason(e)
                val now = timeProvider.now()
                val failedReceipt = ReceiptTimestampPolicy.forInsert(ScannedReceipt(
                    imagePath = ocrResult.savedImagePath,
                    rawOcrText = sanitizeOcrBeforeInsert(ocrResult.fullText), // PRESERVED!
                    parsedTotal = null,
                    parsedMerchant = null,
                    parsedDate = null, 
                    parsedItems = null,
                    parsedTaxAmount = null, // Explicitly null for failed parse
                    currency = homeCur,
                    confidence = 0f,
                    processingStatus = ReceiptProcessingStatus.PARSE_FAILED.name,
                    parseFailureReason = safeReason,
                    sourceType = ReceiptSourceType.CAMERA.name,
                    documentType = ReceiptDocumentType.RETAIL_RECEIPT.name
                ), now)
                val receiptId = scannedReceiptDao.insert(failedReceipt)
                require(receiptId > 0) { "Receipt insert failed (conflict): imagePath=${failedReceipt.imagePath}" }

                // P3-BLOCKER-01: Write PARSE_FAILED event atomically with receipt
                // insert to close the crash window. Previously only the coordinator
                // wrote this event, leaving a gap if crash happened in between.
                writeReceiptEvent(receiptId, "PARSE_FAILED", now,
                    "OCR succeeded but receipt parsing failed",
                    ReceiptProcessingStatus.PARSE_FAILED.name,
                    errorDetails = safeReason)

                return@withContext ProcessReceiptResult(
                    failedReceipt.copy(id = receiptId),
                    ReceiptParser.ParsedReceipt(null, null, null, null, now, homeCur, emptyList(), 0f),
                    pagesProcessed = ocrResult.pagesProcessed,
                    totalPages = ocrResult.totalPages,
                    ephemeralRawOcrText = ocrResult.fullText
                )
            }

            // 3. Normalize merchant if found
            val lookupResult = parsed.merchantName?.let {
                merchantNormalizer.normalize(it, autoCreate = true)
            }
            val normalizedMerchant = lookupResult?.canonical?.normalizedName

            // 4. Save scanned receipt record
            val now = timeProvider.now()
            val receipt = ReceiptTimestampPolicy.forInsert(ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = sanitizeOcrBeforeInsert(ocrResult.fullText),
                parsedTotal = parsed.total,
                parsedMerchant = normalizedMerchant ?: parsed.merchantName,
                parsedDate = parsed.date,
                parsedItems = if (parsed.lineItems.isNotEmpty())
                    receiptParser.lineItemsToJson(parsed.lineItems) else null,
                parsedTaxAmount = parsed.tax,
                currency = parsed.currency,
                confidence = parsed.confidence
            ), now)

            val receiptId = database.withTransaction {
                val insertedReceiptId = scannedReceiptDao.insert(receipt)
                require(insertedReceiptId > 0) { "Receipt insert failed (conflict): imagePath=${receipt.imagePath}" }

                // P3-BLOCKER-01: Write RECEIPT_SAVED event atomically with receipt
                // insert to close the crash window between repository and coordinator.
                writeReceiptEvent(insertedReceiptId, "RECEIPT_SAVED", now,
                    "Receipt saved (scan flow)", receipt.processingStatus,
                    sourceType = "CAMERA", documentType = "RETAIL_RECEIPT")

                insertedReceiptId
            }

            return@withContext ProcessReceiptResult(
                receipt.copy(id = receiptId),
                parsed,
                pagesProcessed = ocrResult.pagesProcessed,
                totalPages = ocrResult.totalPages,
                ephemeralRawOcrText = ocrResult.fullText
            )
        }
    }

    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): ProcessReceiptResult {
        // 1. Persist a display copy without re-running OCR recognition.
        val path = try {
            ocrService.persistImageCopy(imageUri)
        } catch (e: Exception) {
            imageUri.toString()
        }

        val homeCur = homeCurrency()
        val now = timeProvider.now()
        val receipt = ReceiptTimestampPolicy.forInsert(ScannedReceipt(
            imagePath = path,
            rawOcrText = "[OCR Failed or Skipped]",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = now,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = homeCur,
            confidence = 0f
        ), now)
        val receiptId = scannedReceiptDao.insert(receipt)
        require(receiptId > 0) { "Receipt insert failed (conflict): imagePath=${receipt.imagePath}" }
        
        return ProcessReceiptResult(
            receipt.copy(id = receiptId),
            ReceiptParser.ParsedReceipt(
                merchantName = null,
                total = null,
                subtotal = null,
                tax = null,
                date = now,
                currency = homeCur,
                lineItems = emptyList(),
                confidence = 0f
            )
        )
    }

    /**
     * RCP-14: Detects whether a receipt's total and line items already include
     * the tax amount (tax-inclusive). Returns `true` when:
     * - The receipt has a [ScannedReceipt.parsedTotal]
     * - The receipt has a [ScannedReceipt.parsedTaxAmount]
     * - The receipt has line items (non-empty [ScannedReceipt.parsedItems])
     * - The sum of line item totals is within 5% of the receipt total
     *
     * This mirrors the detection logic in [ReceiptParser.parse] for cases
     * where the transient [ScannedReceipt.taxInclusive] flag was not carried
     * through (e.g. receipts loaded from the database before the flag was added).
     */
    private fun detectTaxInclusive(receipt: ScannedReceipt): Boolean {
        val lineItems = receipt.parsedItems?.let { receiptParser.lineItemsFromJson(it) }
        return ReceiptParser.isTaxInclusive(receipt.parsedTotal, receipt.parsedTaxAmount, lineItems)
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
     * @param currency The expense currency. **Legacy default:** `"EUR"` is the
     *   original hardcoded fallback; callers should explicitly pass the user's
     *   home currency via [homeCurrency] instead.
     * @Deprecated Prefer using [TransactionLifecycleCoordinator.createExpense]
     * directly with [ReceiptLinkService.linkReceiptToExpense] for the linking
     * step. This method remains for backward compatibility but will be removed
     * in a future release.
     */
    /**
     * ## WRN-N2: Full legacy linking deprecation (timeline)
     * This method is scheduled for removal in **v2.0**. All callers have been
     * migrated to [TransactionLifecycleCoordinator.createExpense] directly:
     *
     * | Caller | Migrated to | Status |
     * |--------|-------------|--------|
     * | [ReceiptScanViewModel] (via [ReceiptLifecycleCoordinator]) | `coordinator.createExpense()` | ✅ Done |
     * | [BankStatementLifecycleProcessor] | `coordinator.createExpense()` | ✅ Done |
     * | [EmailReceiptIngestionService] | `coordinator.createExpense()` (via [ReceiptLifecycleCoordinator]) | ✅ Done |
     * | [ReviewQueueRepository] | `coordinator.createExpense()` | ✅ Done |
     * | [NotificationProcessingPipeline] | `coordinator.createExpense()` | ✅ Done |
     * | [ManualExpenseRepository] | `coordinator.createExpense()` | ✅ Done |
     *
     * After removal, receipt-to-expense creation flows exclusively through
     * [TransactionLifecycleCoordinator], which provides consistent validation,
     * normalisation, deduplication, event emission, and warranty auto-creation.
     */
    @Deprecated(
        message = "Use ReceiptLifecycleCoordinator.processEmailReceipt for atomic save+link. " +
            "This convenience path will be removed.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith(
            expression = "coordinator.createExpense(request)",
            imports = ["com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator"]
        )
    )
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "XXX",
        categoryId: Long?,
        date: Long = timeProvider.now(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationSource: String? = null
    ): com.yourname.expensetracker.domain.model.Result<Long> {
        // P2-NEW-16: Legacy receipt create path removed.
        // No production callers remain. Use ReceiptLifecycleCoordinator.processReceiptInput()
        // or ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt() for atomic save+link.
        // This method body is intentionally replaced with an error to prevent accidental use.
        return com.yourname.expensetracker.domain.model.Result.Error(
            message = "createExpenseFromReceipt is permanently disabled. " +
                "Use ReceiptLifecycleCoordinator.processReceiptInput() or " +
                "createExpenseAndLinkReceipt() for atomic receipt expense creation."
        )
    }

    /** 
     * Returns the user's home currency, falling back to "XXX" (ISO 4217 unknown)
     * only as last resort.  Never silently defaults to "EUR".
     */
    private suspend fun homeCurrency(): String {
        val homeResolution = currencySettingsRepository.resolveHomeCurrency()
        return homeResolution.currencyOrNull?.code ?: "XXX"
    }

    /**
     * Creates a safe, truncated failure reason string from a [Throwable].
     *
     * - Includes the exception class simple name and message (if present).
     * - Truncates to at most 500 characters.
     * - Never includes the stack trace or raw OCR text.
     */
    private fun safeFailureReason(e: Throwable): String {
        val sb = StringBuilder()
        sb.append(e.javaClass.simpleName)
        if (!e.message.isNullOrBlank()) {
            sb.append(": ")
            sb.append(e.message!!.take(400))
        }
        return sb.toString().take(500)
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

    suspend fun getExpenseById(id: Long): Expense? {
        return expenseDao.getById(id)
    }

    /**
     * Persists a [ScannedReceipt] directly to the database.
     *
     * **Internal use only** — prefer [ReceiptLifecycleCoordinator] for the full
     * lifecycle (validation, deduplication, event logging, side effects).
     *
     * This method should only be used by code paths that manage their own lifecycle
     * events and side effects (e.g., [BankStatementLifecycleProcessor],
     * [WarrantyTrackerRepository.createManualPlaceholderReceipt]).
     */
    suspend fun insertReceipt(receipt: ScannedReceipt): Long {
        writeBarrier.checkWritesAllowed("ReceiptRepository.insertReceipt")
        val now = timeProvider.now()
        val normalized = ReceiptTimestampPolicy.forInsert(receipt, now)
        val receiptId = scannedReceiptDao.insert(normalized)
        require(receiptId > 0) { "Receipt insert failed (conflict): imageHash=${receipt.imageHash}" }
        return receiptId
    }

    suspend fun updateCategorizationStatus(receiptId: Long, status: CategorizationStatus) {
        writeBarrier.checkWritesAllowed("ReceiptRepository.updateCategorizationStatus")
        scannedReceiptDao.updateCategorizationStatus(receiptId, status.name)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        writeBarrier.checkWritesAllowed("ReceiptRepository.deleteReceipt")
        receipt.imagePath?.let { ocrService.deleteImage(it) }
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getRecentReceipts(since: Long, limit: Int = Int.MAX_VALUE): List<ScannedReceipt> {
        return scannedReceiptDao.getRecentReceipts(since, limit)
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
     *
     * ## RCP-N3-FIXED: Routing through [ReceiptLifecycleCoordinator]
     * Each batch item is now processed via [ReceiptLifecycleCoordinator.processReceiptInput]
     * which provides the full lifecycle:
     * - Input validation via [ReceiptInputValidator]
     * - OCR + parsing (delegated to [processReceipt])
     * - File hash computation and duplicate detection (hash, text fingerprint, semantic fingerprint)
     * - Lifecycle event audit trail (RECEIPT_SAVED, OCR_FAILED, DUPLICATE_DETECTED)
     * - Post-save side effects via [ReceiptSideEffectDispatcher] (warranty extraction,
     *   item categorization, price protection checks)
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
                                // Route through the lifecycle coordinator for full lifecycle coverage
                                val outcome = receiptLifecycleCoordinator.get().processReceiptInput(
                                    uri,
                                    options = ReceiptLifecycleCoordinator.ReceiptProcessingOptions(
                                        createReview = true
                                    )
                                )
                                BatchItemResult(
                                    success = outcome.isSuccess,
                                    error = outcome.exceptionOrNull()?.message
                                )
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
        // P3-BLOCKER-02: This legacy path is permanently disabled. All bank
        // statement imports must go through BankStatementLifecycleProcessor which
        // writes the durable run/item ledger.
        return BatchResult(
            successCount = 0,
            failureCount = 1,
            errors = listOf("Legacy processStatement is permanently disabled. " +
                "Use BankStatementLifecycleProcessor.processBankStatement() instead.")
        )
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
        writeBarrier.checkWritesAllowed("ReceiptRepository.clearAllScannedReceipts")
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
        // P3-BLOCKER-07: Debug export gated behind DEBUG build + storage mode.
        if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
            return "[EXPORT BLOCKED] Debug export is only available in debug builds."
        }
        val storageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
        if (storageMode == RawStorageMode.STORE_REDACTED || storageMode == RawStorageMode.DO_NOT_STORE) {
            return "[EXPORT BLOCKED] Raw OCR export is not available in ${storageMode.name} mode. " +
                "Switch to STORE_RAW to enable full debug export."
        }

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
        // P3-BLOCKER-004: Same gate as exportParserDebugData.
        if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
            return "[BLOCKED] Debug export is only available in debug builds."
        }
        val storageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
        if (storageMode == RawStorageMode.STORE_REDACTED || storageMode == RawStorageMode.DO_NOT_STORE) {
            return "[BLOCKED] Raw receipt debug is not available in ${storageMode.name} mode."
        }
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
            • Date:      ${receipt.parsedDate?.let { Instant.ofEpochMilli(it).toString() } ?: "NULL"}
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

    @Deprecated(
        "Permanently disabled. Use ReceiptLinkService.linkReceiptToExpense().",
        level = DeprecationLevel.WARNING
    )
    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        confidence: Double
    ) {
        error("Disabled: use ReceiptLinkService.linkReceiptToExpense()")
    }

    @Deprecated(
        "Permanently disabled. Use ReceiptLifecycleCoordinator for match lifecycle.",
        level = DeprecationLevel.WARNING
    )
    suspend fun saveMatchSuggestion(
        receiptId: Long,
        suggestedExpenseId: Long,
        confidence: Double
    ) {
        // No-op: match mutations are lifecycle-owned
    }

    suspend fun getReceiptsWithSuggestions(): List<com.yourname.expensetracker.data.database.entity.ScannedReceipt> {
        return scannedReceiptDao.getReceiptsWithSuggestions()
    }

    @Deprecated(
        "Permanently disabled. Use ReceiptLinkService.linkReceiptToExpense().",
        level = DeprecationLevel.WARNING
    )
    suspend fun approveMatchSuggestion(receiptId: Long) {
        error("Disabled: use ReceiptLinkService.linkReceiptToExpense()")
    }

    @Deprecated(
        "Permanently disabled. Use ReceiptLifecycleCoordinator for match lifecycle.",
        level = DeprecationLevel.WARNING
    )
    suspend fun rejectAllSuggestions(receiptId: Long) {
        error("Disabled: use ReceiptLifecycleCoordinator for match operations")
    }

    // ── Pipeline 3 event helper ──────────────────────────────────────────────

    private suspend fun writeReceiptEvent(
        receiptId: Long, eventType: String, occurredAt: Long,
        message: String, newStatus: String,
        sourceType: String = "CAMERA", documentType: String = "RETAIL_RECEIPT",
        errorDetails: String? = null
    ) {
        try {
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = sourceType,
                documentType = documentType, eventType = eventType,
                occurredAt = occurredAt, oldStatus = null,
                newStatus = newStatus, actor = "system:repository",
                message = message.take(500), metadata = null,
                errorDetails = errorDetails?.take(500)
            ))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Timber.w(e, "Failed to write receipt event %s", eventType) }
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
                val amountPenalty = receiptAmount?.let { kotlin.math.abs(it - expense.amount) } ?: 0.0
                val datePenalty = kotlin.math.abs(anchorDate - expense.date) / dayMs.toDouble()
                amountPenalty + datePenalty
            }
            .take(limit)
            .toList()
    }
}
