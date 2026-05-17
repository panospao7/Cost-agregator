package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.toDbTransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.ai.usecase.CleanTransaction
import com.yourname.expensetracker.domain.ai.usecase.DebugTransaction
import com.yourname.expensetracker.domain.ai.usecase.ValidateBankStatementTransactionsUseCase
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured result returned by [BankStatementLifecycleProcessor.processBankStatement].
 *
 * @property receiptId The ID of the saved bank statement receipt record.
 * @property transactionsFound Number of transactions parsed from the statement.
 * @property reviewsCreated  Number of [PendingReview] entries created.
 * @property duplicatesSkipped  Number of transactions skipped as duplicates.
 * @property debugData Optional debug data for the debug viewer (OCR text, parsed transactions, logs).
 */
data class BankStatementResult(
    val receiptId: Long,
    val transactionsFound: Int,
    val reviewsCreated: Int,
    val duplicatesSkipped: Int,
    val debugData: DebugData? = null
)

/**
 * Lifecycle-aware processor for bank statement images / PDFs.
 *
 * Handles the complete lifecycle:
 *   1. Pre-OCR duplicate detection via SHA-256 file hash (skip OCR if known).
 *   2. Run OCR on the bank statement image/PDF.
 *   3. Parse transactions via [BankStatementParser].
 *   4. Save statement receipt with BANK_STATEMENT document type.
 *   5. Write statement-level lifecycle events.
 *   6. For each parsed transaction: create a [PendingReview].
 *   7. Do NOT trigger warranty, return window, price protection, or item
 *      categorization — these are inapplicable for statement-level imports.
 *   8. Return a structured [BankStatementResult].
 *
 * ## Dedupe strategy
 *
 * Each parsed transaction is checked against the database in three layers:
 * 1. **Existing approved expenses** — checked via
 *    [ExpenseDao.existsByMerchantKeyInRangeCurrencyAware] and
 *    [ExpenseDao.existsByMerchantInRangeCurrencyAware] with a configurable
 *    date window, amount tolerance, currency, and transaction type. If a
 *    matching expense already exists, the transaction is skipped.
 * 2. **Existing pending reviews** — checked via
 *    [PendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware] using
 *    the same criteria (merchantKey + amount + currency + date + type).
 *    If a matching PendingReview is found, the transaction is skipped.
 * 3. **Pre-OCR hash dedup** — before any OCR runs, the file SHA-256 hash
 *    is checked via [ReceiptDuplicateDetector.checkDuplicate]. If the same
 *    file was already processed, the entire statement is rejected.
 *
 * Note: A shared `BankTransactionDeduper` is planned to extract this
 * three-layer dedupe logic and reuse it across all statement import paths
 * (bank screenshots, PDF statements, CSV imports, and direct bank API sync).
 */
@Singleton
class BankStatementLifecycleProcessor @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val receiptLinkService: ReceiptLinkService,
    private val timeProvider: TimeProvider,
    private val bankStatementParser: BankStatementParser,
    private val pendingReviewDao: PendingReviewDao,
    private val expenseDao: ExpenseDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val assetStore: ReceiptAssetStore,
    private val transactionValidator: ValidateBankStatementTransactionsUseCase,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val restoreMaintenanceMode: DatabaseWriteBarrier,
    private val privacySettingsRepository: PrivacySettingsRepository
) {

    /**
     * Processes a bank statement image/PDF at [uri] through the lifecycle.
     *
     * 1. Checks for an existing receipt by SHA-256 file hash (pre-OCR dedup).
     * 2. Runs OCR on the bank statement image/PDF.
     * 3. Parses transactions from the OCR output.
     * 4. Creates a BANK_STATEMENT receipt record with lifecycle metadata.
     * 5. Creates a [PendingReview] for each non-duplicate transaction.
     * 6. Writes lifecycle events and returns the result.
     */
    suspend fun processBankStatement(uri: Uri): Result<BankStatementResult> {
        try {
            restoreMaintenanceMode.checkWritesAllowed("BankStatementLifecycleProcessor.processBankStatement")
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val startTime = timeProvider.now()
        val parsingLogs = mutableListOf<String>()

        return try {
            // ── Step 1: Pre-OCR duplicate detection via file hash ──────────────
            // Compute the SHA-256 hash of the URI content BEFORE running OCR.
            // If this file was already processed we can skip the expensive OCR
            // and parsing steps entirely.
            val preOcrHash = try {
                assetStore.computeUriHash(uri).getOrNull()
            } catch (_: Exception) {
                null
            }

            if (preOcrHash != null) {
                val dupResult = duplicateDetector.checkDuplicate(
                    imageHash = preOcrHash,
                    textFingerprint = null,
                    semanticFingerprint = null,
                    externalSourceId = null
                )
                if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
                    val existingReceipt = scannedReceiptDao.getById(dupResult.existingReceiptId!!)
                    if (existingReceipt != null) {
                        Timber.i("Duplicate bank statement detected by exact hash (pre-OCR): existingId=${existingReceipt.id}")
                        return Result.failure(IllegalStateException("Duplicate bank statement: already processed (receiptId=${existingReceipt.id})"))
                    }
                }
            }

            // ── Step 2: Run OCR via ReceiptRepository ──────────────────────────
            val ocrResult = receiptRepository.runStatementOcr(uri)

            // ── Step 3: Parse transactions ─────────────────────────────────────
            val homeCurrency = bankStatementParser.resolveHomeCurrencySuspend()
            val parsedTransactions = bankStatementParser.parse(ocrResult.blocks, homeCurrency)

            val transactionsFound = parsedTransactions.size
            Timber.d("BankStatementLifecycleProcessor: %d transactions found", transactionsFound)

            if (parsedTransactions.isEmpty()) {
                parsingLogs.add("No transactions found in bank statement")
                return Result.failure(
                    IllegalStateException("No transactions found in bank statement")
                )
            }

            // ── Step 3b: AI validation ─────────────────────────────────────────
            // Use ValidateBankStatementTransactionsUseCase to validate/correct
            // parser candidates with on-device (or cloud) AI.
            val debugTransactions = parsedTransactions.map { DebugTransaction.fromParsedTransaction(it) }
            val validatedTransactions = transactionValidator.validateTransactions(
                rawOcrText = ocrResult.fullText,
                candidateTransactions = debugTransactions,
                homeCurrency = homeCurrency
            )
            val validationSources: Map<Int, String> = validatedTransactions
                .mapIndexed { i, tx -> i to tx.source }
                .toMap()

            // Log the AI validation split
            val aiValidatedCount = validationSources.count { it.value.startsWith("AI_") }
            parsingLogs.add("AI validation: $aiValidatedCount/${validatedTransactions.size} transactions AI-validated or corrected")
            if (aiValidatedCount > 0) {
                val correctedCount = validationSources.count { it.value == "AI_CORRECTED" }
                parsingLogs.add("  AI_CORRECTED: $correctedCount, AI_VALIDATED: ${aiValidatedCount - correctedCount}")
            }

            // Build a merged list: use AI-validated values when confidence > 0.5,
            // fall back to parser-only values otherwise.
            data class MergedTransaction(
                val merchant: String,
                val amount: Double,
                val currency: String,
                val date: Long?,
                val confidence: Float,
                val type: ParsedTransactionType
            )
            val mergedTransactions = validatedTransactions.mapIndexed { i, cleanTx ->
                val originalTx = parsedTransactions[i]
                if (cleanTx.source.startsWith("AI_") && cleanTx.confidence > 0.5f) {
                    MergedTransaction(
                        merchant = cleanTx.merchant,
                        amount = cleanTx.amount,
                        currency = cleanTx.currency,
                        date = if (cleanTx.date > 0L) cleanTx.date else originalTx.date,
                        confidence = cleanTx.confidence,
                        type = originalTx.type
                    )
                } else {
                    MergedTransaction(
                        merchant = originalTx.merchant,
                        amount = originalTx.amount,
                        currency = originalTx.currency,
                        date = originalTx.date,
                        confidence = originalTx.confidence,
                        type = originalTx.type
                    )
                }
            }

            // ── Step 4: Save statement receipt with lifecycle metadata ─────────
            // Compute the image hash from the saved file path as a fallback
            // (the pre-OCR hash from computeUriHash is stored when available;
            // this fallback ensures the hash is ALWAYS stored so future re-imports
            // can be deduplicated).
            val fallbackHash = try {
                assetStore.computeFileHash(ocrResult.savedImagePath).getOrNull()
            } catch (_: Exception) { null }
            val storedHash = preOcrHash ?: fallbackHash

            val statementReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = RawContentSanitizer.sanitizeRawOcr(
                    ocrResult.fullText,
                    privacySettingsRepository.getSettings().rawOcrStorageMode
                ),
                parsedTotal = null, // varies per transaction
                parsedMerchant = "Bank Statement",
                parsedDate = timeProvider.now(),
                parsedItems = null,
                parsedTaxAmount = null,
                currency = parsedTransactions.firstOrNull()?.currency ?: "EUR",
                confidence = 0.8f,
                sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                processingStatus = ReceiptProcessingStatus.PARSED.name,
                imageHash = storedHash,
                createdAt = timeProvider.now(),
                updatedAt = timeProvider.now()
            )

            // NOTE: Directly inserting via receiptRepository.insertReceipt() instead of
            // going through ReceiptLifecycleCoordinator because this processor already
            // writes its own lifecycle events (RECEIPT_SAVED, PROCESSING_COMPLETE) and
            // bank statements don't need OCR/dedup/warranty side effects that the
            // coordinator provides.
            val receiptId = receiptRepository.insertReceipt(statementReceipt)
            if (receiptId <= 0) {
                return Result.failure(
                    IllegalStateException("Failed to save bank statement receipt record")
                )
            }

            // ── Step 5: Write RECEIPT_SAVED lifecycle event ────────────────────
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = receiptId,
                    sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                    documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                    eventType = "RECEIPT_SAVED",
                    occurredAt = timeProvider.now(),
                    oldStatus = null,
                    newStatus = ReceiptProcessingStatus.PARSED.name,
                    actor = "system:bank_statement_processor",
                    message = "Bank statement processed with $transactionsFound transactions",
                    metadata = null,
                    errorDetails = null
                )
            )

            // ── Step 6: Create a PendingReview for each transaction ────────────
            var reviewsCreated = 0
            var duplicatesSkipped = 0

            for (tx in mergedTransactions) {
                try {
                    // Normalize merchant
                    val lookupResult = merchantNormalizer.normalize(tx.merchant, autoCreate = true)
                    val normalizedMerchant = lookupResult.canonical.normalizedName

                    // Auto-categorize
                    val classification = hybridClassifier.classify(
                        merchantName = normalizedMerchant,
                        amount = tx.amount
                    )

                    val transactionDate = tx.date ?: timeProvider.now()
                    val merchantKey = MerchantKeyGenerator.generate(normalizedMerchant)

                    // ── Recurring rule active check ────────────────────────────
                    // Log whether an active recurring rule exists for this merchant
                    // so users can decide whether to merge with existing subscriptions.
                    val existingRecurring = runCatching {
                        recurringExpenseRepository.getByMerchantFuzzy(normalizedMerchant)
                    }.getOrNull()
                    if (existingRecurring != null) {
                        if (existingRecurring.isActive) {
                            parsingLogs.add("RECURRING: Active recurring rule exists for '${tx.merchant}' (id=${existingRecurring.id})")
                        } else {
                            parsingLogs.add("RECURRING: Inactive recurring rule found for '${tx.merchant}' (id=${existingRecurring.id}) — subscription may be paused or cancelled")
                        }
                    }

                    // P3-P1-11: Strengthened deduplication — check both existing
                    // expenses AND pending reviews with date window, amount tolerance,
                    // currency, and transaction type.  Previously only checked pending
                    // reviews by merchant with a tight 0.01 amount diff, missing
                    // existing approved expenses entirely.
                    val txType = tx.type.toDbTransactionType()
                    val dedupWindow = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
                    val amountTolerance = DuplicateDetectionPolicy.AMOUNT_TOLERANCE
                    val startDate = transactionDate - dedupWindow
                    val endDate = DuplicateDetectionPolicy.windowEndExclusive(transactionDate)
                    val minAmount = tx.amount - amountTolerance
                    val maxAmount = tx.amount + amountTolerance

                    // Check existing approved expenses
                    val hasExpenseDuplicate = expenseDao.existsByMerchantKeyInRangeCurrencyAware(
                        merchantKey = merchantKey,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        currency = tx.currency,
                        transactionType = txType.name
                    ) || expenseDao.existsByMerchantInRangeCurrencyAware(
                        merchant = normalizedMerchant,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        currency = tx.currency,
                        transactionType = txType.name
                    )

                    if (hasExpenseDuplicate) {
                        duplicatesSkipped++
                        parsingLogs.add("SKIP: Existing expense duplicate for ${tx.merchant} ${"%.2f".format(tx.amount)} $tx.currency")
                        continue
                    }

                    // Check existing pending reviews with proper tolerance window
                    val duplicateReview = pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                        merchantKey = merchantKey,
                        merchantName = normalizedMerchant,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        currency = tx.currency,
                        transactionType = txType.name
                    )

                    if (duplicateReview != null) {
                        duplicatesSkipped++
                        parsingLogs.add("SKIP: Pending review already exists for ${tx.merchant} ${"%.2f".format(tx.amount)} $tx.currency (reviewId=${duplicateReview.id})")
                        continue
                    }

                    // ── Create PendingReview ───────────────────────────────────
                    // NOTE: Do NOT trigger warranty, return window, price
                    // protection, or item categorization per lifecycle policy.
                    val review = PendingReview(
                        rawNotificationId = null,
                        scannedReceiptId = receiptId,
                        suggestedAmount = tx.amount,
                        suggestedCurrency = tx.currency,
                        suggestedMerchant = normalizedMerchant,
                        suggestedMerchantKey = merchantKey,
                        suggestedType = tx.type.toDbTransactionType().name,
                        suggestedCategoryId = classification.categoryId.takeIf { it > 0 },
                        suggestedDate = transactionDate,
                        confidence = tx.confidence,
                        packageName = "statement.import",
                        notificationTitle = "Bank Statement Transaction",
                        notificationText = "Imported from statement: ${tx.merchant}",
                        createdAt = timeProvider.now()
                    )

                    pendingReviewDao.insert(review)
                    reviewsCreated++
                    parsingLogs.add("INSERT: Pending review created for ${tx.merchant} €${tx.amount}")

                } catch (e: Exception) {
                    parsingLogs.add("ERROR: Failed to process transaction ${tx.merchant}: ${e.message}")
                    Timber.e(e, "Failed to create PendingReview for bank statement transaction: %s", tx.merchant)
                }
            }

            // ── Step 7: Update receipt processingStatus and write PROCESSING_COMPLETE event ──
            val receiptToUpdate = scannedReceiptDao.getById(receiptId)
            if (receiptToUpdate != null) {
                scannedReceiptDao.update(receiptToUpdate.copy(
                    processingStatus = ReceiptProcessingStatus.REVIEW_CREATED.name,
                    updatedAt = timeProvider.now()
                ))
            }
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = receiptId,
                    sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                    documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                    eventType = "PROCESSING_COMPLETE",
                    occurredAt = timeProvider.now(),
                    oldStatus = ReceiptProcessingStatus.PARSED.name,
                    newStatus = ReceiptProcessingStatus.REVIEW_CREATED.name,
                    actor = "system:bank_statement_processor",
                    message = "Bank statement processing complete: $transactionsFound transactions, $reviewsCreated reviews, $duplicatesSkipped duplicates skipped",
                    metadata = """{"transactionsFound":$transactionsFound,"reviewsCreated":$reviewsCreated,"duplicatesSkipped":$duplicatesSkipped}""",
                    errorDetails = null
                )
            )

            val debugData = DebugData(
                rawText = ocrResult.fullText,
                parsedTransactions = parsedTransactions,
                parsingLogs = parsingLogs,
                processingTimeMs = timeProvider.now() - startTime,
                parserUsed = "BankStatementParser",
                validationSources = validationSources
            )

            val result = BankStatementResult(
                receiptId = receiptId,
                transactionsFound = transactionsFound,
                reviewsCreated = reviewsCreated,
                duplicatesSkipped = duplicatesSkipped,
                debugData = debugData
            )

            Timber.d("BankStatementLifecycleProcessor: result=%s", result)
            Result.success(result)

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "BankStatementLifecycleProcessor failed for URI: %s", uri)
            Result.failure(e)
        }
    }
}
