package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.toDbTransactionType
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.ai.usecase.ValidateBankStatementTransactionsUseCase
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
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
 *   1. Save statement receipt with BANK_STATEMENT document type.
 *   2. Parse transactions via [BankStatementParser].
 *   3. For each parsed transaction: create a [PendingReview].
 *   4. Do NOT trigger warranty, return window, price protection, or item
 *      categorization — these are inapplicable for statement-level imports.
 *   5. Write statement-level lifecycle events.
 *   6. Return a structured [BankStatementResult].
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
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val assetStore: ReceiptAssetStore
    // TODO: Inject ValidateBankStatementTransactionsUseCase for AI validation
    // TODO: The use case will validate parser candidates with on-device/cloud AI:
    // TODO:   private val transactionValidator: ValidateBankStatementTransactionsUseCase
) {

    /**
     * Processes a bank statement image/PDF at [uri] through the lifecycle.
     *
     * 1. Validates the input, persists the asset, and runs OCR.
     * 2. Parses transactions from the OCR output.
     * 3. Creates a BANK_STATEMENT receipt record with lifecycle metadata.
     * 4. Creates a [PendingReview] for each non-duplicate transaction.
     * 5. Writes lifecycle events and returns the result.
     */
    suspend fun processBankStatement(uri: Uri): Result<BankStatementResult> {
        val startTime = timeProvider.now()
        val parsingLogs = mutableListOf<String>()

        return try {
            // ── Step 1: Run OCR via ReceiptRepository ──────────────────────────
            val ocrResult = receiptRepository.runStatementOcr(uri)

            // ── Step 1b: Duplicate detection via file hash ─────────────────────
            val fileHash = ocrResult.savedImagePath.let { path ->
                try {
                    assetStore.computeFileHash(path).getOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            if (fileHash != null) {
                val dupResult = duplicateDetector.checkDuplicate(
                    imageHash = fileHash,
                    textFingerprint = null,
                    semanticFingerprint = null,
                    externalSourceId = null
                )
                if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
                    val existingReceipt = scannedReceiptDao.getById(dupResult.existingReceiptId!!)
                    if (existingReceipt != null) {
                        Timber.i("Duplicate bank statement detected by exact hash: existingId=${existingReceipt.id}")
                        return Result.failure(IllegalStateException("Duplicate bank statement: already processed (receiptId=${existingReceipt.id})"))
                    }
                }
            }

            // ── Step 2: Parse transactions ─────────────────────────────────────
            val parsedTransactions = bankStatementParser.parse(ocrResult.blocks)

            val transactionsFound = parsedTransactions.size
            Timber.d("BankStatementLifecycleProcessor: %d transactions found", transactionsFound)

            if (parsedTransactions.isEmpty()) {
                parsingLogs.add("No transactions found in bank statement")
                return Result.failure(
                    IllegalStateException("No transactions found in bank statement")
                )
            }

            // ── Step 2b: AI validation (TODO) ─────────────────────────────────
            // TODO: Wire in ValidateBankStatementTransactionsUseCase:
            // TODO:   1. Convert parsedTransactions to DebugTransaction list
            // TODO:   2. Call transactionValidator.validateTransactions(
            // TODO:        rawOcrText = ocrResult.fullText,
            // TODO:        candidateTransactions = debugTransactions,
            // TODO:        homeCurrency = "..."
            // TODO:      )
            // TODO:   3. Use AI-validated CleanTransaction list (with "AI_VALIDATED" / "AI_CORRECTED" source)
            // TODO:      for high-confidence items; fall back to parser-only ("PARSER_ONLY") for low-confidence ones
            // TODO:   4. Log the split (AI-validated vs parser-only) to parsingLogs
            // TODO:   5. Build validationSources map for DebugData
            //
            // For now, all transactions are treated as parser-only.
            val validationSources: Map<Int, String> = parsedTransactions.indices.associateWith { "PARSER_ONLY" }

            // ── Step 3: Save statement receipt with lifecycle metadata ─────────
            val statementReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText,
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
                createdAt = timeProvider.now(),
                updatedAt = timeProvider.now()
            )

            val receiptId = scannedReceiptDao.insert(statementReceipt)
            if (receiptId <= 0) {
                return Result.failure(
                    IllegalStateException("Failed to save bank statement receipt record")
                )
            }

            // ── Step 4: Write RECEIPT_SAVED lifecycle event ────────────────────
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

            // ── Step 5: Create a PendingReview for each transaction ────────────
            var reviewsCreated = 0
            var duplicatesSkipped = 0

            for (tx in parsedTransactions) {
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

                    // ── Deduplication check ────────────────────────────────────
                    val existingPending = pendingReviewDao.getPendingByMerchant(
                        merchantKey = merchantKey,
                        merchantName = normalizedMerchant
                    ).firstOrNull { review ->
                        val amountDiff = kotlin.math.abs((review.suggestedAmount ?: 0.0) - tx.amount)
                        amountDiff < 0.01 && review.suggestedCurrency == tx.currency
                    }

                    if (existingPending != null) {
                        duplicatesSkipped++
                        parsingLogs.add("SKIP: Pending review already exists for ${tx.merchant} €${tx.amount}")
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
                        notificationText = "Imported from statement: ${tx.merchant}"
                    )

                    pendingReviewDao.insert(review)
                    reviewsCreated++
                    parsingLogs.add("INSERT: Pending review created for ${tx.merchant} €${tx.amount}")

                } catch (e: Exception) {
                    parsingLogs.add("ERROR: Failed to process transaction ${tx.merchant}: ${e.message}")
                    Timber.e(e, "Failed to create PendingReview for bank statement transaction: %s", tx.merchant)
                }
            }

            // ── Step 6: Write PROCESSING_COMPLETE lifecycle event ──────────────
            val totalEvents = 1 + 1 // RECEIPT_SAVED + PROCESSING_COMPLETE
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
            Timber.e(e, "BankStatementLifecycleProcessor failed for URI: %s", uri)
            Result.failure(e)
        }
    }
}
