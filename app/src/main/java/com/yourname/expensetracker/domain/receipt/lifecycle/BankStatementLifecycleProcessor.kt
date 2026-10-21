package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.data.database.dao.BankStatementImportItemDao
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.BankStatementImportItem
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.ReceiptRecordWriter
import com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.toDbTransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.ai.usecase.DebugTransaction
import com.yourname.expensetracker.domain.ai.usecase.StatementValidationContracts
import com.yourname.expensetracker.domain.ai.usecase.StatementValidationOutcome
import com.yourname.expensetracker.domain.ai.usecase.ValidateBankStatementTransactionsUseCase
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.receipt.statement.CounterpartyLabel
import com.yourname.expensetracker.domain.receipt.statement.CurrencyEvidence
import com.yourname.expensetracker.domain.receipt.statement.StatementImportGate
import com.yourname.expensetracker.domain.receipt.statement.StatementRow
import com.yourname.expensetracker.domain.receipt.statement.StatementSkipReason
import com.yourname.expensetracker.domain.receipt.statement.TextStatementParser
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.privacy.RawSourceType
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * @property skippedCount Number of rows recorded as SKIPPED ledger items (never merchant text).
 * @property skippedByReason Skipped rows per bounded reason code (e.g. NO_DATE, CANCELLED); counts only.
 * @property pagesProcessed Pages read successfully, when the source is a PDF.
 * @property totalPages Pages in the source PDF, when known.
 */
data class BankStatementResult(
    val receiptId: Long,
    val transactionsFound: Int,
    val reviewsCreated: Int,
    val duplicatesSkipped: Int,
    val debugData: DebugData? = null,
    val duplicateOfReceiptId: Long? = null,
    val isPartial: Boolean = false,
    val skippedCount: Int = 0,
    val skippedByReason: Map<String, Int> = emptyMap(),
    val pagesProcessed: Int? = null,
    val totalPages: Int? = null
)

/** Per-item amount ceiling for statement rows; one source of truth with [StatementImportGate]. */
internal const val STATEMENT_ITEM_AMOUNT_CEILING: Double = StatementImportGate.AMOUNT_CEILING

/**
 * Statement text head handed to AI validation (chars) before scrubbing; slightly above the use case's
 * own 4000-char prompt cap so an IBAN straddling the cap is masked whole.
 */
internal const val STATEMENT_AI_TEXT_SLICE: Int = 4_100

/** Persisted statement text cap (chars) before sanitising, so a huge text PDF cannot overflow a CursorWindow. */
internal const val STATEMENT_RAW_PERSIST_CAP: Int = 200_000

/**
 * Pure terminal policy; page failures do not invent failed transaction counts.
 *
 * O2: skipped rows alone no longer fail a run. A run FAILS when an item failed, or when rows were
 * skipped and nothing at all was created or recognised as a duplicate (no usable outcome).
 * Skips, duplicates or partial recognition next to at least one usable outcome end as
 * COMPLETED_WITH_SKIPS. [createdItemCount] defaults to 0 for callers that only classify failures.
 */
internal fun bankStatementCompletionStatus(
    failedItemCount: Int,
    skippedItemCount: Int,
    duplicateItemCount: Int,
    pdfPartial: Boolean,
    createdItemCount: Int = 0
): String = when {
    failedItemCount > 0 -> BankStatementImportRun.STATUS_FAILED
    skippedItemCount > 0 && createdItemCount == 0 && duplicateItemCount == 0 ->
        BankStatementImportRun.STATUS_FAILED
    pdfPartial || skippedItemCount > 0 || duplicateItemCount > 0 ->
        BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS
    else -> BankStatementImportRun.STATUS_COMPLETED
}

/**
 * Lifecycle-aware processor for bank statement images / PDFs.
 *
 * P3-994-06: Run scope — [BankStatementImportRun] covers only the post-parse
 * transaction import phase. OCR failure, parse failure, no-transactions-found,
 * and pre-OCR duplicate are covered by intake diagnostics, not the run ledger.
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
    private val database: AppDatabase,
    private val transactionRunner: DomainTransactionRunner,
    private val receiptRepository: ReceiptRepository,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptLifecycleEventWriter: ReceiptLifecycleEventWriter,
    private val receiptLinkService: ReceiptLinkService,
    private val timeProvider: TimeProvider,
    private val bankStatementParser: BankStatementParser,
    private val textStatementParser: TextStatementParser,
    private val pendingReviewDao: PendingReviewDao,
    private val expenseDao: ExpenseDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val assetStore: ReceiptAssetStore,
    private val transactionValidator: ValidateBankStatementTransactionsUseCase,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    // RP-15 (15-A): policy comes ONLY from the resolver — never an inline
    // `settings.rawBankStatementStorageMode` read. The resolver fails closed to
    // FAIL_CLOSED_DEFAULTS (DO_NOT_STORE) on corrupt/unavailable settings.
    private val rawPersistencePolicyResolver: RawPersistencePolicyResolver,
    private val bankStatementImportRunDao: BankStatementImportRunDao,
    private val bankStatementImportItemDao: BankStatementImportItemDao,
    private val receiptRecordWriter: ReceiptRecordWriter
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
            writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.processBankStatement")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        }
        val startTime = timeProvider.now()
        val parsingLogs = mutableListOf<String>()
        var runId: Long? = null
        var statementReceiptId: Long? = null

        return try {
            // ── Step 1: Pre-OCR duplicate detection via file hash ──────────────
            // Compute the SHA-256 hash of the URI content BEFORE running OCR.
            // If this file was already processed we can skip the expensive OCR
            // and parsing steps entirely.
            val preOcrHash = try {
                assetStore.computeUriHash(uri).getOrNull()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            // Home is informational for validation, not source-currency evidence.
            // Explicit source rows can proceed without settings; unknown rows are
            // represented by the parser and skipped at the existing item boundary.
            val homeCurrency = bankStatementParser.resolveHomeCurrencySuspend().orEmpty()
            val statementParse: StatementParse.Rows = when (val parsed = parseStatementRows(ocrResult, homeCurrency)) {
                is StatementParse.LayoutFailed -> {
                    // A recognised layout threw: fail cleanly, never feed it to the generic parser.
                    parsingLogs.add("Statement layout could not be read")
                    return Result.failure(IllegalStateException("STATEMENT_LAYOUT_FAILED"))
                }
                is StatementParse.Rows -> parsed
            }
            val parsedRows = statementParse.parsed
            val layoutSkippedRows = statementParse.skipped
            val parsedTransactions = parsedRows.map { it.tx }

            val transactionsFound = parsedTransactions.size
            Timber.d("BankStatementLifecycleProcessor: %d transactions found", transactionsFound)

            if (parsedTransactions.isEmpty()) {
                parsingLogs.add("No transactions found in bank statement")
                return Result.failure(
                    IllegalStateException("No transactions found in bank statement")
                )
            }

            // Source-currency evidence per row (O1): a currency printed on the row or declared once in
            // the statement header is source evidence; only CurrencyEvidence.NONE stays an assumption.
            // Text-path rows additionally pass the pure gate: a row without a date is never dated "now"
            // (O3, text path only: the OCR path keeps its null-date behaviour) and an amount above the
            // ceiling never reaches AI validation. Skipped rows keep their parsed index as ledger index.
            val sourceCurrencyEvidence = BooleanArray(parsedRows.size)
            val preAiSkipReasons = arrayOfNulls<StatementSkipReason>(parsedRows.size)
            parsedRows.forEachIndexed { rowIndex, row ->
                sourceCurrencyEvidence[rowIndex] = row.currencyEvidence != CurrencyEvidence.NONE
                if (statementParse.textPath) {
                    when (val decision = StatementImportGate.evaluate(row)) {
                        is StatementImportGate.Decision.Skip -> {
                            preAiSkipReasons[rowIndex] = decision.reason
                        }
                        is StatementImportGate.Decision.Accept -> {
                            sourceCurrencyEvidence[rowIndex] = decision.currencyIsSourceEvidence
                        }
                    }
                }
            }
            val aiEligibleIndexes = parsedRows.indices.filter { preAiSkipReasons[it] == null }
            val statementItemTotal = parsedRows.size + layoutSkippedRows.size

            // ── Step 3b: Create durable import run ledger row ─────────────────
            // P3-REG-010: Re-check write barrier before DB write after long OCR/parse.
            // R27-S4b: counted in-flight write (runWrite performs the same barrier check first).
            val correlationId = java.util.UUID.randomUUID().toString()
            val statementSourceFingerprint = preOcrHash
            val now = timeProvider.now()
            val importRunId = writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.createImportRun")) {
                bankStatementImportRunDao.insert(
                    BankStatementImportRun(
                        statementReceiptId = null,  // will update after receipt insert
                        sourceFingerprint = statementSourceFingerprint,
                        correlationId = correlationId,
                        status = BankStatementImportRun.STATUS_RUNNING,
                        startedAt = now,
                        totalItems = statementItemTotal
                    )
                )
            }
            require(importRunId > 0) { "Failed to create bank statement import run" }
            runId = importRunId

            // ── Step 3c: AI validation ─────────────────────────────────────────
            // Use ValidateBankStatementTransactionsUseCase to validate/correct
            // parser candidates with on-device (or cloud) AI.
            // RP-13 Gate A (P3-002 / D10): every candidate carries an immutable
            // candidateId (dense 0..n-1 over the rows sent to AI; mapped back to the
            // parser source-line index through aiEligibleIndexes); results merge back
            // BY candidateId — never by response position. The use case splits large
            // candidate lists into bounded AI calls itself.
            val debugTransactions = aiEligibleIndexes.mapIndexed { candidateId, rowIndex ->
                val tx = parsedTransactions[rowIndex]
                DebugTransaction.fromParsedTransaction(tx, candidateId = candidateId).copy(
                    currencyAssumption = if (sourceCurrencyEvidence[rowIndex]) {
                        CurrencyAssumption.PARSED_FROM_SOURCE
                    } else {
                        tx.currencyAssumption
                    }
                )
            }
            val validationOutcome = if (debugTransactions.isEmpty()) {
                StatementValidationOutcome.Validated(emptyList())
            } else {
                transactionValidator.validateTransactions(
                    // Privacy: the AI prompt gets only a bounded, scrubbed head of the statement text
                    // (IBANs incl. spaced ones and 12+ digit runs masked). The slice is a little wider
                    // than the use-case cap of 4000 so an identifier straddling the cap is masked whole
                    // before the use case truncates.
                    rawOcrText = scrubStatementText(ocrResult.fullText.take(STATEMENT_AI_TEXT_SLICE)),
                    candidateTransactions = debugTransactions,
                    homeCurrency = homeCurrency
                )
            }
            val validatedTransactions = validationOutcome.transactions

            when (validationOutcome) {
                is StatementValidationOutcome.IdentityMismatch ->
                    parsingLogs.add(
                        "AI_IDENTITY_MISMATCH (${validationOutcome.detailCode}): falling back to parser-only"
                    )
                is StatementValidationOutcome.ParserOnly ->
                    parsingLogs.add("AI validation unavailable (${validationOutcome.reasonCode}): parser-only")
                is StatementValidationOutcome.Validated -> Unit
            }

            // Log the AI validation split. AI_REJECTED rows are identity-proven
            // skips (candidateId owned), not validations.
            val aiValidatedCount = validatedTransactions.count {
                it.source == StatementValidationContracts.SOURCE_AI_VALIDATED ||
                    it.source == StatementValidationContracts.SOURCE_AI_CORRECTED
            }
            val aiRejectedCount = validatedTransactions.count {
                it.source == StatementValidationContracts.SOURCE_AI_REJECTED
            }
            parsingLogs.add("AI validation: $aiValidatedCount/${validatedTransactions.size} transactions AI-validated or corrected")
            if (aiRejectedCount > 0) {
                parsingLogs.add("  AI_REJECTED: $aiRejectedCount")
            }
            if (aiValidatedCount > 0) {
                val correctedCount = validatedTransactions.count {
                    it.source == StatementValidationContracts.SOURCE_AI_CORRECTED
                }
                parsingLogs.add("  AI_CORRECTED: $correctedCount, AI_VALIDATED: ${aiValidatedCount - correctedCount}")
            }

            // Build a merged list: use AI-validated values when confidence > 0.5,
            // fall back to parser-only values otherwise.
            // Gate A: ownership is proven by candidateId — never merge by position.
            // [candidateId] is the parser source-line index (the ledger itemIndex), not the dense AI id.
            // [direction] and [accountLabel] come ONLY from the parsed row, never from AI output.
            data class MergedTransaction(
                val candidateId: Int,
                val merchant: String,
                val amount: Double,
                val currency: String,
                val date: Long?,
                val confidence: Float,
                val type: ParsedTransactionType,
                val source: String,
                val direction: ParsedTransferDirection?,
                val accountLabel: String?
            )

            // Gate B currency policy for parser-derived values (P3-010 / D11):
            // an explicitly parsed source currency (row symbol or single statement-header
            // currency, O1) is kept; a home/unknown assumption is never treated as known —
            // the typed CURRENCY_UNKNOWN blank reaches the ledger skip instead of a
            // fabricated currency.
            fun parserPolicyCurrency(originalTx: ParsedTransaction, sourceEvidence: Boolean): String =
                if (sourceEvidence ||
                    originalTx.currencyAssumption == CurrencyAssumption.PARSED_FROM_SOURCE
                ) {
                    CurrencyCode.parse(originalTx.currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }?.code
                        ?: StatementValidationContracts.CURRENCY_UNKNOWN
                } else {
                    StatementValidationContracts.CURRENCY_UNKNOWN
                }

            val mergedTransactions = validatedTransactions.map { cleanTx ->
                val aiCandidateId = cleanTx.candidateId
                require(aiCandidateId in aiEligibleIndexes.indices) {
                    "AI candidateId outside proven candidate range"
                }
                val candidateId = aiEligibleIndexes[aiCandidateId]
                val originalTx = parsedTransactions[candidateId]
                val rowSourceEvidence = sourceCurrencyEvidence[candidateId]
                // Boundary scrub: no IBAN or long digit run reaches a stored merchant or label,
                // including a merchant an AI correction wrote.
                val parserMerchant = scrubStatementText(originalTx.merchant)
                val label = originalTx.transferAccountName?.let { scrubStatementText(it) }
                when {
                    statementParse.layoutRecognised -> {
                        // Money authority: a recognised layout read the row deterministically, so the
                        // parser's amount, currency, date, type, direction and label are final. The AI
                        // may only offer a scrubbed merchant correction, never for a TRANSFER (its
                        // merchant is a masked counterparty label), and its rejection never drops the row.
                        val aiMerchant = if (
                            originalTx.type != ParsedTransactionType.TRANSFER &&
                            cleanTx.source != StatementValidationContracts.SOURCE_AI_REJECTED &&
                            cleanTx.source.startsWith("AI_") &&
                            cleanTx.confidence > 0.5f
                        ) {
                            scrubStatementText(cleanTx.merchant).takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                        MergedTransaction(
                            candidateId = candidateId,
                            merchant = aiMerchant ?: parserMerchant,
                            amount = originalTx.amount,
                            currency = parserPolicyCurrency(originalTx, rowSourceEvidence),
                            date = originalTx.date,
                            confidence = originalTx.confidence,
                            type = originalTx.type,
                            source = if (cleanTx.source == StatementValidationContracts.SOURCE_AI_REJECTED) {
                                StatementValidationContracts.SOURCE_PARSER_ONLY
                            } else {
                                cleanTx.source
                            },
                            direction = originalTx.transferDirection,
                            accountLabel = label
                        )
                    }
                    cleanTx.source == StatementValidationContracts.SOURCE_AI_REJECTED ->
                        // Identity-proven AI rejection: parser fields remain the
                        // authoritative description of the skipped row.
                        MergedTransaction(
                            candidateId = candidateId,
                            merchant = parserMerchant,
                            amount = originalTx.amount,
                            currency = parserPolicyCurrency(originalTx, rowSourceEvidence),
                            date = originalTx.date,
                            confidence = originalTx.confidence,
                            type = originalTx.type,
                            source = cleanTx.source,
                            direction = originalTx.transferDirection,
                            accountLabel = label
                        )
                    cleanTx.source.startsWith("AI_") && cleanTx.confidence > 0.5f ->
                        MergedTransaction(
                            candidateId = candidateId,
                            merchant = scrubStatementText(cleanTx.merchant),
                            amount = cleanTx.amount,
                            currency = cleanTx.currency,
                            date = if (cleanTx.date > 0L) cleanTx.date else originalTx.date,
                            confidence = cleanTx.confidence,
                            type = originalTx.type,
                            source = cleanTx.source,
                            direction = originalTx.transferDirection,
                            accountLabel = label
                        )
                    else ->
                        MergedTransaction(
                            candidateId = candidateId,
                            merchant = parserMerchant,
                            amount = originalTx.amount,
                            currency = parserPolicyCurrency(originalTx, rowSourceEvidence),
                            date = originalTx.date,
                            confidence = originalTx.confidence,
                            type = originalTx.type,
                            source = cleanTx.source,
                            direction = originalTx.transferDirection,
                            accountLabel = label
                        )
                }
            }

            // ── Steps 4+5: Save receipt + events atomically (MIT-041) ──────────
            // MIT-041: Wrap receipt insert, run attachment, and initial RECEIPT_SAVED
            // event in a single database transaction so the receipt never commits
            // without its lifecycle event.
            // Compute the image hash from the saved file path as a fallback
            // (the pre-OCR hash from computeUriHash is stored when available;
            // this fallback ensures the hash is ALWAYS stored so future re-imports
            // can be deduplicated).
            val fallbackHash = try {
                assetStore.computeFileHash(ocrResult.savedImagePath).getOrNull()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            val storedHash = preOcrHash ?: fallbackHash

            val statementReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                // RP-15 (15-A): consume the complete policy from the resolver.
                // Fail-closed note: if settings are corrupt/unavailable the resolver
                // yields DO_NOT_STORE and the raw OCR text is not persisted at all
                // (previously a settings-read failure aborted the whole import).
                // The text is capped BEFORE sanitising: a text PDF can carry up to 2M chars and a
                // single stored row that large overflows the Room CursorWindow on read-back.
                rawOcrText = RawContentSanitizer.sanitizeRawOcr(
                    ocrResult.fullText.take(STATEMENT_RAW_PERSIST_CAP),
                    rawPersistencePolicyResolver.forSource(RawSourceType.BANK_STATEMENT).mode
                ),
                parsedTotal = null, // varies per transaction
                parsedMerchant = "Bank Statement",
                parsedDate = timeProvider.now(),
                parsedItems = null,
                parsedTaxAmount = null,
                currency = parsedTransactions.firstOrNull()?.currency ?: homeCurrency,
                confidence = 0.8f,
                sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                processingStatus = ReceiptProcessingStatus.PARSED.name,
                imageHash = storedHash,
                createdAt = timeProvider.now(),
                updatedAt = timeProvider.now()
            )

            // MIT-041: declared outside transaction so it's visible to Step 6
            var receiptId: Long = 0L
            var earlyReturn: Result<BankStatementResult>? = null

            // R27-S4b: receipt + events + ledger attach is a counted in-flight write.
            writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.writeResults")) {
            transactionRunner.runInTransaction(
                correlationId = java.util.UUID.randomUUID().toString(),
                operationId = "bank_statement.save_receipt",
                source = "BankStatementLifecycleProcessor"
            ) { context ->
            // NOTE: Directly inserting via receiptRepository.insertReceipt() instead of
            // going through ReceiptLifecycleCoordinator because this processor already
            // writes its own lifecycle events (RECEIPT_SAVED, PROCESSING_COMPLETE) and
            // bank statements don't need OCR/dedup/warranty side effects that the
            // coordinator provides.
            // P3-D4B-03: Use typed ReceiptRecordWriter.
            when (val write = receiptRecordWriter.insertOrResolve(statementReceipt)) {
                is ReceiptRecordWriteResult.Inserted -> {
                    receiptId = write.receipt.id
                    statementReceiptId = receiptId
                    bankStatementImportRunDao.attachReceipt(runId = importRunId, receiptId = receiptId)
                }
                is ReceiptRecordWriteResult.Duplicate -> {
                    bankStatementImportRunDao.attachReceipt(runId = importRunId, receiptId = write.existingReceipt.id)
                    bankStatementImportRunDao.finalize(
                        runId = importRunId, status = BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
                        completedAt = timeProvider.now(), totalItems = 0, processedItems = 0,
                        createdReviewCount = 0, duplicateExpenseCount = 0,
                        duplicatePendingCount = 0, failedItemCount = 0,
                        errorSummary = "Duplicate statement receipt"
                    )
                    earlyReturn = Result.success(BankStatementResult(
                        receiptId = write.existingReceipt.id,
                        transactionsFound = 0, reviewsCreated = 0, duplicatesSkipped = 0,
                        duplicateOfReceiptId = write.existingReceipt.id
                    ))
                    return@runInTransaction
                }
                is ReceiptRecordWriteResult.Failed -> {
                    earlyReturn = Result.failure(IllegalStateException(write.reason))
                    return@runInTransaction
                }
            }

            // ── Step 5: Write RECEIPT_SAVED lifecycle event ────────────────────
            receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                receiptId = receiptId,
                sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                eventType = "RECEIPT_SAVED",
                newStatus = ReceiptProcessingStatus.PARSED.name,
                actor = "system:bank_statement_processor",
                message = "Bank statement processed with $transactionsFound transactions"
            ))

            // Truncation and isolated recognition failures both make the import partial.
            val pagesProcessed = ocrResult.pagesProcessed
            val totalPages = ocrResult.totalPages
            if (ocrResult.isPartial) {
                receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                    receiptId = receiptId,
                    sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                    documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                    eventType = "PDF_PARTIAL",
                    newStatus = ReceiptProcessingStatus.PARSED.name,
                    actor = "system:bank_statement_processor",
                    message = "Bank statement PDF partially processed",
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .apply {
                            pagesProcessed?.let { put("pagesProcessed", it) }
                            totalPages?.let { put("totalPages", it) }
                        }
                        .build()
                ))
                // P3-03EA-05: Also update run fields for self-contained ledger
                bankStatementImportRunDao.updatePdfPartial(
                    runId = importRunId, pdfPartial = true,
                    pagesProcessed = pagesProcessed, totalPages = totalPages
                )
            }

            } // end MIT-041: receipt + events transaction
            } // end R27-S4b: runWrite(writeResults)

            // If receipt was a duplicate or failed, return early
            if (earlyReturn != null) return earlyReturn!!

            // ── Step 6: Create a PendingReview for each transaction ────────────
            var reviewsCreated = 0
            var duplicatesSkipped = 0
            var failedItemCount = 0
            // Skipped rows per bounded reason code (counts only; never row content).
            val skippedByReason = sortedMapOf<String, Int>()
            // Ids of the pending reviews THIS run created (O4: excluded from its own duplicate check).
            val sameRunReviewIds = HashSet<Long>()

            // ── Step 6a: ledger rows for rows that never reach AI ──────────────
            // Parsed rows gated out before AI keep their parsed index; rows the layout itself
            // could not turn into transactions (cancelled, unknown status, ...) follow the parsed
            // ones. Bounded reason code only; layout-skipped rows carry no merchant/amount/date.
            for (rowIndex in preAiSkipReasons.indices) {
                val reason = preAiSkipReasons[rowIndex] ?: continue
                val skippedTx = parsedTransactions[rowIndex]
                val reasonCode = skipReasonCode(reason)
                writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                    bankStatementImportItemDao.insert(
                        BankStatementImportItem(
                            runId = importRunId,
                            itemIndex = rowIndex,
                            transactionFingerprint = null,
                            status = BankStatementImportItem.STATUS_SKIPPED,
                            merchant = sanitizeMerchant(skippedTx.merchant),
                            amount = skippedTx.amount,
                            currency = skippedTx.currency.takeIf { sourceCurrencyEvidence[rowIndex] },
                            transactionDate = skippedTx.date,
                            errorReason = reasonCode,
                            duplicateReason = null,
                            expenseId = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                skippedByReason.merge(reasonCode, 1, Int::plus)
                parsingLogs.add("Skipped item $rowIndex: $reasonCode")
            }
            layoutSkippedRows.forEachIndexed { skipPosition, skippedRow ->
                val reasonCode = skipReasonCode(skippedRow.reason)
                val skipItemIndex = parsedRows.size + skipPosition
                writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                    bankStatementImportItemDao.insert(
                        BankStatementImportItem(
                            runId = importRunId,
                            itemIndex = skipItemIndex,
                            transactionFingerprint = null,
                            status = BankStatementImportItem.STATUS_SKIPPED,
                            merchant = null,
                            amount = null,
                            currency = null,
                            transactionDate = null,
                            errorReason = reasonCode,
                            duplicateReason = null,
                            expenseId = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                skippedByReason.merge(reasonCode, 1, Int::plus)
                parsingLogs.add("Skipped item $skipItemIndex: $reasonCode")
            }

            for (tx in mergedTransactions) {
                // RP-13 Gate A: the ledger index is the parser source-line
                // identity (candidateId), not the outcome list position.
                val index = tx.candidateId
                try {
                    // ── RP-13 Gate A (P3-002): identity-proven AI rejection ──
                    // The candidateId was contract-validated, so the rejection
                    // is recorded as a ledger skip without a review; the parser
                    // row stays the authoritative candidate description.
                    if (tx.source == StatementValidationContracts.SOURCE_AI_REJECTED) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = tx.amount,
                                    currency = tx.currency.ifBlank { null },
                                    transactionDate = tx.date ?: now,
                                    errorReason = "AI_REJECTED",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        skippedByReason.merge("AI_REJECTED", 1, Int::plus)
                        parsingLogs.add("Skipped item $index: AI_REJECTED")
                        continue
                    }

                    // ── PR15: Pre-mutation validation ──────────────────────────
                    if (tx.amount.isNaN() || tx.amount.isInfinite()) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = null,
                                    currency = tx.currency.ifBlank { null },
                                    transactionDate = tx.date ?: now,
                                    errorReason = "INVALID_AMOUNT",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        failedItemCount++
                        skippedByReason.merge("INVALID_AMOUNT", 1, Int::plus)
                        parsingLogs.add("Skipped item $index: invalid amount (${tx.amount})")
                        continue
                    }

                    if (tx.amount <= 0.0) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = null,
                                    currency = tx.currency.ifBlank { null },
                                    transactionDate = tx.date ?: now,
                                    errorReason = "NON_POSITIVE_AMOUNT",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        failedItemCount++
                        skippedByReason.merge("NON_POSITIVE_AMOUNT", 1, Int::plus)
                        parsingLogs.add("Skipped item $index: non-positive amount (${tx.amount})")
                        continue
                    }

                    // Per-item amount ceiling (O5): applies to the merged amount, so an AI-corrected
                    // amount cannot slip past the parser/layout ceiling.
                    if (tx.amount > STATEMENT_ITEM_AMOUNT_CEILING) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = tx.amount,
                                    currency = tx.currency.ifBlank { null },
                                    transactionDate = tx.date,
                                    errorReason = "AMOUNT_ABOVE_CEILING",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        skippedByReason.merge("AMOUNT_ABOVE_CEILING", 1, Int::plus)
                        parsingLogs.add("Skipped item $index: AMOUNT_ABOVE_CEILING")
                        continue
                    }

                    // ── RP-13 Gate B (P3-010 / D11): typed currency unknown ──
                    // The validator applies the explicit-source/validated policy;
                    // a blank currency here is CURRENCY_UNKNOWN — no home fallback,
                    // no guessed currency in a review or dedupe key.
                    if (CurrencyCode.parse(tx.currency)?.let { it in CurrencyCode.ALL_SUPPORTED } != true) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = tx.amount,
                                    currency = null,
                                    transactionDate = tx.date ?: now,
                                    errorReason = "CURRENCY_UNKNOWN",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        failedItemCount++
                        skippedByReason.merge("CURRENCY_UNKNOWN", 1, Int::plus)
                        parsingLogs.add("Skipped item $index: CURRENCY_UNKNOWN")
                        continue
                    }

                    // Date sanity: reject dates > 1 day in the future or < year 2000
                    val transactionDate = tx.date ?: now
                    if (transactionDate > now + 86_400_000L || transactionDate < 946_684_800_000L) {
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.skipItem")) {
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId,
                                    itemIndex = index,
                                    transactionFingerprint = null,
                                    status = BankStatementImportItem.STATUS_SKIPPED,
                                    merchant = sanitizeMerchant(tx.merchant),
                                    amount = tx.amount,
                                    currency = tx.currency,
                                    transactionDate = transactionDate,
                                    errorReason = "UNREASONABLE_DATE",
                                    duplicateReason = null,
                                    expenseId = null,
                                    createdAt = now,
                                    updatedAt = now
                                )
                            )
                        }
                        failedItemCount++
                        skippedByReason.merge("UNREASONABLE_DATE", 1, Int::plus)
                        parsingLogs += "Skipped item $index: unreasonable date (${transactionDate})"
                        continue
                    }

                    // Normalize merchant. A TRANSFER merchant is a masked counterparty label (a person or
                    // account), never a shop: it must not be auto-created as a merchant/alias, so it is
                    // used verbatim and the normalizer is not called.
                    val normalizedMerchant = if (tx.type == ParsedTransactionType.TRANSFER) {
                        tx.merchant
                    } else {
                        merchantNormalizer.normalize(tx.merchant, autoCreate = true).canonical.normalizedName
                    }

                    // Auto-categorize
                    val classification = hybridClassifier.classify(
                        merchantName = normalizedMerchant,
                        amount = tx.amount
                    )
                    val merchantKey = MerchantKeyGenerator.generate(normalizedMerchant)

                    // ── Recurring rule active check ────────────────────────────
                    // Log whether an active recurring rule exists for this merchant
                    // so users can decide whether to merge with existing subscriptions.
                    val existingRecurring = CancellationSafe.runCatchingCancellable {
                        recurringExpenseRepository.getByMerchantFuzzy(normalizedMerchant)
                    }.getOrNull()
                    if (existingRecurring != null) {
                        if (existingRecurring.isActive) {
                            parsingLogs.add("RECURRING: Active recurring rule exists [REDACTED]")
                        } else {
                            parsingLogs.add("RECURRING: Inactive recurring rule found [REDACTED]")
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
                        // PR15: Fetch the actual duplicate expense ID for ledger auditing.
                        // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup —
                        // the blocking precheck above already saw the row; the audit
                        // lookup must see the same row (including isNotMine) or the
                        // ledger would record a degraded null expenseId/reason.
                        val duplicateExpenseId = expenseDao.findBlockingDuplicateIdCurrencyAware(
                            amount = tx.amount,
                            merchant = normalizedMerchant,
                            date = transactionDate,
                            currency = tx.currency,
                            transactionType = txType.name,
                            merchantKey = merchantKey
                        )
                        // P3-BLOCKER-H2: Wrap duplicate decision + item insert in transaction.
                        // R27-S4b: counted in-flight write (same barrier check, now drain-visible).
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.duplicateExpense.tx")) {
                        transactionRunner.runInTransaction(
                            correlationId = java.util.UUID.randomUUID().toString(),
                            operationId = "bank_statement.skip_duplicate",
                            source = "BankStatementLifecycleProcessor"
                        ) { context ->
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId, itemIndex = index, transactionFingerprint = merchantKey,
                                    status = BankStatementImportItem.STATUS_DUPLICATE_EXPENSE,
                                    duplicateReason = duplicateExpenseId?.let { "Duplicate expense ID $it" },
                                    expenseId = duplicateExpenseId,
                                    merchant = sanitizeMerchant(tx.merchant), amount = tx.amount, currency = tx.currency,
                                    transactionDate = tx.date, createdAt = timeProvider.now(), updatedAt = timeProvider.now()
                                )
                            )
                        }
                        }
                        parsingLogs.add("SKIP: Existing expense duplicate [REDACTED]")
                        continue
                    }

                    // Check existing pending reviews with proper tolerance window.
                    // O4: reviews created by THIS import run are not duplicates of their own later rows:
                    // identical rows inside one statement are separate real transactions (repeated
                    // top-ups, repeated purchases). The exclusion is in memory by this run's review ids
                    // (no new DAO surface / schema change); cross-run duplicates are still detected.
                    val duplicateReview = pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                        merchantKey = merchantKey,
                        merchantName = normalizedMerchant,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        currency = tx.currency,
                        transactionType = txType.name
                    )?.takeUnless { it.id in sameRunReviewIds }

                    if (duplicateReview != null) {
                        duplicatesSkipped++
                        // P3-BLOCKER-H2: Wrap duplicate decision + item insert in transaction.
                        // R27-S4b: counted in-flight write (same barrier check, now drain-visible).
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.duplicatePending.tx")) {
                        transactionRunner.runInTransaction(
                            correlationId = java.util.UUID.randomUUID().toString(),
                            operationId = "bank_statement.skip_duplicate_review",
                            source = "BankStatementLifecycleProcessor"
                        ) { context ->
                            bankStatementImportItemDao.insert(
                                BankStatementImportItem(
                                    runId = importRunId, itemIndex = index, transactionFingerprint = merchantKey,
                                    status = BankStatementImportItem.STATUS_DUPLICATE_PENDING_REVIEW,
                                    duplicateReason = "Duplicate pending review ID ${duplicateReview.id}",
                                    expenseId = null,
                                    pendingReviewId = duplicateReview.id,
                                    merchant = sanitizeMerchant(tx.merchant), amount = tx.amount, currency = tx.currency,
                                    transactionDate = tx.date, createdAt = timeProvider.now(), updatedAt = timeProvider.now()
                                )
                            )
                        }
                        }
                        parsingLogs.add("SKIP: Pending review already exists [REDACTED]")
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
                        // PR15: PII fix — do not embed raw merchant in notification text
                        notificationText = "New transaction: ${tx.amount} ${tx.currency}",
                        createdAt = timeProvider.now(),
                        // Direction and counterparty label come from the parsed row (never AI output);
                        // the label is IBAN-masked and digit-run scrubbed at the merge boundary.
                        suggestedDirection = tx.direction?.name,
                        suggestedAccountName = tx.accountLabel
                    )

                    // P3-REG-005: Wrap review creation and item row in a single
                    // transaction so neither can exist without the other.
                    // R27-S4b: counted in-flight write (same barrier check, now drain-visible).
                    val reviewId = writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.processItem.tx")) {
                    transactionRunner.runInTransaction(
                        correlationId = java.util.UUID.randomUUID().toString(),
                        operationId = "bank_statement.create_review",
                        source = "BankStatementLifecycleProcessor"
                    ) { context ->
                        val revId = pendingReviewDao.insert(review)
                        require(revId > 0) { "PendingReview insert failed" }
                        bankStatementImportItemDao.insert(
                            BankStatementImportItem(
                                runId = importRunId,
                                itemIndex = index,
                                transactionFingerprint = merchantKey,
                                status = BankStatementImportItem.STATUS_CREATED_REVIEW,
                                duplicateReason = null,
                                expenseId = null,
                                pendingReviewId = revId,
                                merchant = sanitizeMerchant(tx.merchant),
                                amount = tx.amount,
                                currency = tx.currency,
                                transactionDate = tx.date,
                                createdAt = timeProvider.now(),
                                updatedAt = timeProvider.now()
                            )
                        )
                        // PR15: Per-item lifecycle event for audit trail
                        receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                            receiptId = receiptId,
                            sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                            documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                            eventType = "REVIEW_CREATED",
                            oldStatus = ReceiptProcessingStatus.PARSED.name,
                            newStatus = ReceiptProcessingStatus.REVIEW_CREATED.name,
                            actor = "system:bank_statement_processor",
                            message = "Review created for item $index: \$${tx.amount} ${tx.currency}",
                            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                .put("itemIndex", index)
                                .put("amount", tx.amount)
                                .put("currency", tx.currency)
                                .build()
                        ))
                        revId
                    }
                    }
                    sameRunReviewIds.add(reviewId)
                    reviewsCreated++
                    parsingLogs.add("INSERT: Pending review created [REDACTED]")

                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failedItemCount++
                    // PR15: Structured failure codes — no raw exception messages persisted
                    val failureCode = when (e) {
                        is java.sql.SQLException -> "DB_INSERT_FAILURE"
                        is IllegalStateException -> "PROCESSING_STATE_ERROR"
                        else -> "ITEM_PROCESSING_FAILURE"
                    }
                    val errorClass = e::class.simpleName ?: "Unknown"
                    // R27-S4b: counted in-flight write (runWrite performs the same barrier check first).
                    writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.processItem.errorRecovery")) {
                        bankStatementImportItemDao.insert(
                            BankStatementImportItem(
                                runId = importRunId,
                                itemIndex = index,
                                transactionFingerprint = null,
                                status = BankStatementImportItem.STATUS_FAILED,
                                merchant = sanitizeMerchant(tx.merchant),
                                amount = tx.amount,
                                currency = tx.currency,
                                errorReason = "$failureCode: error class=$errorClass, item=$index",
                                createdAt = timeProvider.now(),
                                updatedAt = timeProvider.now()
                            )
                        )
                    }
                    parsingLogs.add("ERROR: Failed to process transaction $index (code=$failureCode)")
                    Timber.e(e, "Failed to create PendingReview for bank statement transaction")
                }
            }

            // ── Step 7: Finalize the import run ledger ─────────────────────────
            // P3-P1-10 / P3-REG-008: Use actual DAO counts for correct ledger.
            val finalFailedItemCount = bankStatementImportItemDao.countByRunAndStatus(importRunId, BankStatementImportItem.STATUS_FAILED)
            val skippedItemCount = bankStatementImportItemDao.countByRunAndStatus(importRunId, BankStatementImportItem.STATUS_SKIPPED)
            val expDupCount = bankStatementImportItemDao.countByRunAndStatus(importRunId, BankStatementImportItem.STATUS_DUPLICATE_EXPENSE)
            val pendDupCount = bankStatementImportItemDao.countByRunAndStatus(importRunId, BankStatementImportItem.STATUS_DUPLICATE_PENDING_REVIEW)
            val createdCount = bankStatementImportItemDao.countByRunAndStatus(importRunId, BankStatementImportItem.STATUS_CREATED_REVIEW)
            val totalItems = finalFailedItemCount + skippedItemCount + expDupCount + pendDupCount + createdCount
            val finalStatus = bankStatementCompletionStatus(
                failedItemCount = finalFailedItemCount,
                skippedItemCount = skippedItemCount,
                duplicateItemCount = expDupCount + pendDupCount,
                pdfPartial = ocrResult.isPartial,
                createdItemCount = createdCount
            )
            val endTime = timeProvider.now()

            // ── Step 8: Finalize run + receipt status + events atomically ──────
            // P3-BLOCKER-05.3: Only write PROCESSING_COMPLETE / REVIEW_CREATED
            // status when the run is not in a failed state.
            // P3-P1-10 / P3-REG-008: Run finalization is inside the same
            // transaction as receipt-status update and event write so the ledger
            // never disagrees with the receipt state.
            val runSucceeded = finalStatus != BankStatementImportRun.STATUS_FAILED
            if (runSucceeded) {
                // R27-S4: terminal write is a counted in-flight write; blocked during maintenance.
                writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeSuccess")) {
                transactionRunner.runInTransaction(
                    correlationId = java.util.UUID.randomUUID().toString(),
                    operationId = "bank_statement.finalize_success",
                    source = "BankStatementLifecycleProcessor"
                ) { context ->
                    // P3-PR2 / P3-P1-05: Write barrier check before direct DAO mutation
                    writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.finalizeStatus")

                    // Finalize the import run atomically with receipt status update
                    bankStatementImportRunDao.finalize(
                        runId = importRunId,
                        status = finalStatus,
                        completedAt = endTime,
                        totalItems = statementItemTotal,
                        processedItems = totalItems,
                        createdReviewCount = createdCount,
                        duplicateExpenseCount = expDupCount,
                        duplicatePendingCount = pendDupCount,
                        failedItemCount = finalFailedItemCount + skippedItemCount,
                        errorSummary = if (ocrResult.isPartial) "PDF_PARTIAL" else null
                    )

                    val receiptToUpdate = scannedReceiptDao.getById(receiptId)
                    if (receiptToUpdate != null) {
                        scannedReceiptDao.update(receiptToUpdate.copy(
                            processingStatus = ReceiptProcessingStatus.REVIEW_CREATED.name,
                            updatedAt = timeProvider.now()
                        ))
                    }
                    receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                        receiptId = receiptId,
                        sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                        documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                        eventType = "PROCESSING_COMPLETE",
                        oldStatus = ReceiptProcessingStatus.PARSED.name,
                        newStatus = ReceiptProcessingStatus.REVIEW_CREATED.name,
                        actor = "system:bank_statement_processor",
                        message = "Bank statement processing complete: $transactionsFound transactions, $reviewsCreated reviews, $duplicatesSkipped duplicates skipped",
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("transactionsFound", transactionsFound)
                            .put("reviewsCreated", reviewsCreated)
                            .put("duplicatesSkipped", duplicatesSkipped)
                            .put("skippedCount", skippedItemCount)
                            .build()
                    ))
                }
                }
            } else {
                // R27-S4: terminal write is a counted in-flight write; blocked during maintenance.
                writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeFailure")) {
                transactionRunner.runInTransaction(
                    correlationId = java.util.UUID.randomUUID().toString(),
                    operationId = "bank_statement.finalize_failure",
                    source = "BankStatementLifecycleProcessor"
                ) { context ->
                    // Finalize the import run atomically with the failure event
                    bankStatementImportRunDao.finalize(
                        runId = importRunId,
                        status = BankStatementImportRun.STATUS_FAILED,
                        completedAt = endTime,
                        totalItems = statementItemTotal,
                        processedItems = totalItems,
                        createdReviewCount = createdCount,
                        duplicateExpenseCount = expDupCount,
                        duplicatePendingCount = pendDupCount,
                        failedItemCount = finalFailedItemCount + skippedItemCount,
                        errorSummary = "Bank statement processing had failures"
                    )

                    receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                        receiptId = receiptId,
                        sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                        documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                        eventType = "PROCESSING_FAILED",
                        oldStatus = ReceiptProcessingStatus.PARSED.name,
                        newStatus = null,
                        actor = "system:bank_statement_processor",
                        message = "Bank statement processing had failures: $finalFailedItemCount failed items",
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("transactionsFound", transactionsFound)
                            .put("failedItemCount", finalFailedItemCount)
                            .put("skippedCount", skippedItemCount)
                            .build()
                    ))
                }
                }
                return Result.failure(
                    Exception("Bank statement import had $finalFailedItemCount failed and $skippedItemCount skipped items")
                )
            }

            val debugData = null // P3-33D-05: DebugData redacted by default

            val result = BankStatementResult(
                receiptId = receiptId,
                transactionsFound = transactionsFound,
                reviewsCreated = reviewsCreated,
                duplicatesSkipped = duplicatesSkipped,
                debugData = debugData,
                isPartial = ocrResult.isPartial,
                skippedCount = skippedItemCount,
                skippedByReason = skippedByReason.toMap(),
                pagesProcessed = ocrResult.pagesProcessed,
                totalPages = ocrResult.totalPages
            )

            Timber.d("BankStatementLifecycleProcessor: receiptId=%d, txs=%d, reviews=%d, dups=%d",
                result.receiptId, result.transactionsFound, result.reviewsCreated, result.duplicatesSkipped)
            Result.success(result)

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                val cancellation = e
                runId?.let { rid ->
                    try {
                        withContext(NonCancellable) {
                            withTimeout(2000L) {
                                val processedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_CREATED_REVIEW)
                                val failedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_FAILED)
                                // R27-S4: counted in-flight write; blocked during maintenance (run stays RUNNING, recovered later).
                                writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeCancelled")) {
                                    bankStatementImportRunDao.finalize(
                                        runId = rid,
                                        status = BankStatementImportRun.STATUS_CANCELLED,
                                        completedAt = timeProvider.now(),
                                        totalItems = processedItems + failedItems,
                                        processedItems = processedItems,
                                        createdReviewCount = processedItems,
                                        duplicateExpenseCount = 0,
                                        duplicatePendingCount = 0,
                                        failedItemCount = failedItems,
                                        errorSummary = "WORKER_CANCELLED"
                                    )
                                }
                            }
                        }
                    } catch (cleanupError: Throwable) {
                        // NEVER rethrow from cancellation cleanup. Add as suppressed for diagnostics.
                        cancellation.addSuppressed(cleanupError)
                        // Bounded diagnostics: exception class only, no message/stack trace.
                        Timber.w("Failed to finalize cancelled bank statement import run %d: %s", rid, cleanupError::class.simpleName ?: "Unknown")
                    }
                }
                throw cancellation
            }
            // Bounded diagnostics: exception class only, no message/stack trace.
            Timber.e("BankStatementLifecycleProcessor failed: %s", e::class.simpleName ?: "Unknown")
            runId?.let { rid ->
                val receiptId = statementReceiptId
                if (receiptId != null) {
                    // Receipt was created — finalize run + write PROCESSING_FAILED event atomically
                    try {
                        // R27-S4: counted in-flight write; blocked during maintenance (run stays RUNNING, recovered later).
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeUnexpectedFailure")) {
                        transactionRunner.runInTransaction(
                            correlationId = java.util.UUID.randomUUID().toString(),
                            operationId = "bank_statement.finalize_unexpected_failure",
                            source = "BankStatementLifecycleProcessor"
                        ) { context ->
                            val processedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_CREATED_REVIEW)
                            val failedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_FAILED)
                            bankStatementImportRunDao.finalize(
                                runId = rid,
                                status = BankStatementImportRun.STATUS_FAILED,
                                completedAt = timeProvider.now(),
                                totalItems = processedItems + failedItems,
                                processedItems = processedItems,
                                createdReviewCount = processedItems,
                                duplicateExpenseCount = 0,
                                duplicatePendingCount = 0,
                                failedItemCount = failedItems,
                                errorSummary = "WORKER_UNHANDLED_EXCEPTION"
                            )
                            receiptLifecycleEventWriter.write(context, ReceiptLifecycleEvent(
                                receiptId = receiptId,
                                sourceType = ReceiptSourceType.BANK_STATEMENT.name,
                                documentType = ReceiptDocumentType.BANK_STATEMENT.name,
                                eventType = "PROCESSING_FAILED",
                                oldStatus = ReceiptProcessingStatus.PARSED.name,
                                newStatus = null,
                                actor = "system:bank_statement_processor",
                                message = "Bank statement processing failed unexpectedly",
                                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                    .put("reason", "WORKER_UNHANDLED_EXCEPTION")
                                    .put("errorClass", e::class.simpleName ?: "Unknown")
                                    .build()
                            ))
                        }
                        }
                    } catch (finalizeError: Exception) {
                        if (finalizeError is kotlinx.coroutines.CancellationException) throw finalizeError
                        // Bounded diagnostics: exception class only, no message/stack trace.
                        Timber.w("Failed to finalize bank statement import run %d: %s", rid, finalizeError::class.simpleName ?: "Unknown")
                    }
                } else {
                    // No receipt created — finalize run as ledger-only
                    try {
                        val processedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_CREATED_REVIEW)
                        val failedItems = bankStatementImportItemDao.countByRunAndStatus(rid, BankStatementImportItem.STATUS_FAILED)
                        // R27-S4: counted in-flight write; blocked during maintenance (run stays RUNNING, recovered later).
                        writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeLedgerOnlyFailure")) {
                            bankStatementImportRunDao.finalize(
                                runId = rid,
                                status = BankStatementImportRun.STATUS_FAILED,
                                completedAt = timeProvider.now(),
                                totalItems = processedItems + failedItems,
                                processedItems = processedItems,
                                createdReviewCount = processedItems,
                                duplicateExpenseCount = 0,
                                duplicatePendingCount = 0,
                                failedItemCount = failedItems,
                                errorSummary = "WORKER_UNHANDLED_EXCEPTION"
                            )
                        }
                    } catch (finalizeError: Exception) {
                        if (finalizeError is kotlinx.coroutines.CancellationException) throw finalizeError
                        // Bounded diagnostics: exception class only, no message/stack trace.
                        Timber.w("Failed to finalize bank statement import run %d: %s", rid, finalizeError::class.simpleName ?: "Unknown")
                    }
                }
            }
            Result.failure(e)
        }
    }

    /**
     * Sanitizes a merchant/description string for safe storage.
     * Removes control characters and truncates to a safe length.
     * PR15: Pre-mutation validation.
     */
    private fun sanitizeMerchant(raw: String?): String? {
        if (raw == null) return null
        // Remove control characters (except common whitespace), mask any IBAN / long digit run
        // (an AI-corrected merchant must not carry one into a ledger row) and truncate.
        val sanitized = scrubStatementText(raw.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), ""))
            .take(200)
        return sanitized.ifBlank { null }
    }

    /** Boundary scrub for any statement-derived text that is stored: IBANs, then long digit runs. */
    private fun scrubStatementText(text: String): String =
        CounterpartyLabel.scrubLongDigitRuns(CounterpartyLabel.maskIbans(text))

    /** Bounded ledger/summary code for a layout skip reason; exhaustive so a new reason cannot be dropped. */
    private fun skipReasonCode(reason: StatementSkipReason): String = when (reason) {
        StatementSkipReason.NO_DATE -> "NO_DATE"
        StatementSkipReason.CANCELLED -> "CANCELLED"
        StatementSkipReason.UNKNOWN_STATUS -> "UNKNOWN_STATUS"
        StatementSkipReason.NOT_SETTLED -> "NOT_SETTLED"
        StatementSkipReason.AMOUNT_ABOVE_CEILING -> "AMOUNT_ABOVE_CEILING"
        StatementSkipReason.INCOMPLETE_ROW -> "INCOMPLETE_ROW"
    }

    private sealed interface StatementParse {
        data class Rows(
            val parsed: List<StatementRow.Parsed>,
            val skipped: List<StatementRow.Skipped>,
            /** True when the PDF text path produced the rows (NO_DATE gating applies only there). */
            val textPath: Boolean,
            /**
             * True when a recognised text layout (not the generic fallback, not the OCR path) produced
             * the rows: the parser is then the authority for amount, currency, date, type, direction
             * and counterparty label, and the AI may only offer a scrubbed non-transfer merchant.
             */
            val layoutRecognised: Boolean = false
        ) : StatementParse

        /** A recognised text layout threw while parsing: no rows, and no generic fallback. */
        object LayoutFailed : StatementParse
    }

    /**
     * Routes one statement to its parser. The PDF text path uses the matched layout; an unknown
     * text layout falls back to the generic parser with a mandatory date; the OCR/image path keeps
     * the generic parser unchanged (null dates allowed, O3).
     */
    private suspend fun parseStatementRows(ocrResult: OcrResult, homeCurrency: String): StatementParse =
        // Text parsing and sanitising of a large statement is CPU work: never on the caller's (Main) thread.
        withContext(Dispatchers.Default) { parseStatementRowsBlocking(ocrResult, homeCurrency) }

    private fun parseStatementRowsBlocking(ocrResult: OcrResult, homeCurrency: String): StatementParse {
        val pages = ocrResult.pdfTextPages
        if (pages == null) {
            return StatementParse.Rows(
                parsed = genericRows(ocrResult, homeCurrency, requireDate = false),
                skipped = emptyList(),
                textPath = false
            )
        }
        val textResult = textStatementParser.parse(pages, homeCurrency)
        if (textResult.failed) return StatementParse.LayoutFailed
        if (textResult.layout == null) {
            return StatementParse.Rows(
                parsed = genericRows(ocrResult, homeCurrency, requireDate = true),
                skipped = emptyList(),
                textPath = true
            )
        }
        return StatementParse.Rows(
            parsed = textResult.parsedRows,
            skipped = textResult.skippedRows,
            textPath = true,
            layoutRecognised = textResult.layout != null
        )
    }

    private fun genericRows(
        ocrResult: OcrResult,
        homeCurrency: String,
        requireDate: Boolean
    ): List<StatementRow.Parsed> {
        val transactions = if (requireDate) {
            bankStatementParser.parse(ocrResult.blocks, homeCurrency, requireDate = true)
        } else {
            bankStatementParser.parse(ocrResult.blocks, homeCurrency)
        }
        return transactions.map { tx ->
            StatementRow.Parsed(
                tx = tx,
                currencyEvidence = if (tx.currencyAssumption == CurrencyAssumption.PARSED_FROM_SOURCE) {
                    CurrencyEvidence.ROW
                } else {
                    CurrencyEvidence.NONE
                }
            )
        }
    }
}
