package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.model.Result as DomainResult
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SideEffectMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for the receipt lifecycle.
 *
 * This coordinator is the single entry point for processing receipt input
 * through any path (camera scan, gallery pick, email ingestion, bank statement
 * import, etc.).  It orchestrates the full lifecycle:
 *
 *   validate → OCR / parse → dedupe → save → event logging
 *   → post-save side effects (via [ReceiptSideEffectDispatcher])
 *
 * Full lifecycle phases:
 * 1. **Input Validation** — [ReceiptInputValidator] checks the URI is readable
 *    and has an acceptable MIME type.
 * 2. **OCR / Parse** — [ReceiptRepository.processReceipt] runs OCR and
 *    parses the extracted text for merchant, total, date, and line items.
 *    File persistence is owned by ReceiptRepository/OCR — the coordinator does NOT
 *    create its own copy of the asset.
 * 4. **Deduplication** — [ReceiptDuplicateDetector] checks for existing
 *    receipts by hash, text fingerprint, semantic fingerprint, or external ID.
 * 5. **Database Save** — The [ScannedReceipt] is persisted via
 *    [ScannedReceiptDao] with lifecycle metadata (sourceType, documentType,
 *    processingStatus, asset info).
 * 6. **Lifecycle Event** — A `RECEIPT_SAVED` (and optionally `OCR_FAILED`)
 *    event is written to the [ReceiptEventDao] audit trail.
 * 7. **Post-Save Side Effects** — [ReceiptSideEffectDispatcher] dispatches
 *    downstream operations based on document type: warranty extraction,
 *    item categorization, transaction matching, and price protection checks.
 *
 * As each existing processing path is migrated (PRs 4-8), its logic is moved
 * into this class and the old path is thinned or removed.
 *
 * @constructor Inject dependencies needed for database transactions, validation,
 *              asset storage, processing, link management, event logging, and side effects.
 */
@Singleton
class ReceiptLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val receiptRepository: ReceiptRepository,
    private val receiptLinkService: ReceiptLinkService,
    private val assetStore: ReceiptAssetStore,
    private val inputValidator: ReceiptInputValidator,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val receiptEventDao: ReceiptEventDao,
    private val emailReceiptDao: EmailReceiptDao,
    private val timeProvider: TimeProvider,
    private val bankStatementLifecycleProcessor: BankStatementLifecycleProcessor,
    private val sideEffectDispatcher: ReceiptSideEffectDispatcher,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val privacySettingsRepository: PrivacySettingsRepository
) {

    companion object {
        /** Last-resort fallback currency when no home currency can be resolved. */
        private const val FALLBACK_CURRENCY = "EUR"

        /**
         * Resolves an email sender address to a known provider name for
         * [EmailReceiptSource.provider]. Returns "unknown" if no provider
         * can be identified.
         */
        private fun resolveEmailProvider(from: String): String {
            val domain = from.substringAfterLast("@").substringBefore(">").lowercase().trim()
            return when {
                domain.contains("amazon") -> "amazon"
                domain.contains("uber") -> "uber"
                domain.contains("apple") -> "apple"
                domain.contains("paypal") -> "paypal"
                domain.contains("stripe") -> "stripe"
                domain.contains("doordash") || domain.contains("ubereats") -> "food_delivery"
                else -> "unknown"
            }
        }
    }

    /**
     * Processes a receipt input URI through the full lifecycle.
     *
     * Full implementation (PR 4):
     * 1. Validates the input URI.
     * 2. Delegates to [ReceiptRepository.processReceipt] for OCR, parsing, and file persistence.
     *    File persistence is owned by ReceiptRepository/OCR — the coordinator does NOT
     *    create its own copy of the asset.
     * 3. Computes a file hash from the repository-managed image path for deduplication (best-effort).
     * 4. Checks for duplicates (hash, text fingerprint, semantic fingerprint).
     * 5. Updates the receipt with lifecycle metadata (sourceType, documentType,
     *    processingStatus, fingerprints) and writes a lifecycle event.
     * 6. If processing fails catastrophically, saves a manual record and
     *    writes an OCR_FAILED event.
     *
     * @param uri The content URI of the receipt image / PDF.
     * @return [Result.success] with the saved [ScannedReceipt] on success,
     *         [Result.failure] on validation or processing error.
     */
    suspend fun processReceiptInput(uri: Uri): Result<ScannedReceipt> {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }

        // 1. Validate input
        val validation = inputValidator.validate(uri)
        if (!validation.isValid) {
            val message = "Receipt input validation failed: ${validation.errors.joinToString("; ")}"
            Timber.w(message)
            return Result.failure(IllegalArgumentException(message))
        }

        // 2. OCR + Parse via ReceiptRepository
        //    Ownership boundary: ReceiptRepository/OCR owns file persistence.
        //    The coordinator works with the imagePath from the response, not its own copy.
        return try {
            val (receipt, parsed) = receiptRepository.processReceipt(
                imageUri = uri,
                autoCreateReview = false
            )

            // RCP-14 / RCP-6: Capture taxInclusive from the parser result for
            // downstream propagation. The flag is not stored in the DB entity
            // (ScannedReceipt lacks a taxInclusive column) so we carry it on
            // the updated receipt copy for the caller's benefit. When
            // taxInclusive == true, receipt.parsedTotal already contains the
            // tax and must not be incremented further.
            val taxInclusive = parsed.taxInclusive

            // 3. Compute file hash from repository-managed image path (best-effort, non-fatal)
            val fileHash = receipt.imagePath?.let { path ->
                try {
                    assetStore.computeFileHash(path).getOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            // TODO RCP-5: Add image perceptual hash check for pre-OCR duplicate
            // detection.  The file hash above only catches byte-identical images.
            // A perceptual hash (e.g. pHash or dHash) would catch near-duplicate
            // images (resized, re-compressed, minor crops) without running OCR.
            // This check should be done BEFORE the OCR call in ReceiptRepository,
            // using a lightweight Bitmap decode + hash computation.

            // 3b. Check for duplicate receipt via file hash
            if (fileHash != null) {
                val dupResult = duplicateDetector.checkDuplicate(
                    imageHash = fileHash,
                    textFingerprint = null,
                    semanticFingerprint = null,
                    externalSourceId = null
                )
                if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
                    val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {
                        it.taxInclusive = taxInclusive
                    }
                    if (existing != null) {
                        Timber.i("Duplicate receipt detected by exact hash: existingId=${existing.id}")
                        return Result.success(existing)
                    }
                }
            }

            // Determine processing status based on receipt content
            val isOcrFailure = receipt.rawOcrText == "[OCR Failed or Skipped]" ||
                receipt.rawOcrText.startsWith("Scan Failed:")
            val processingStatus = when {
                isOcrFailure -> ReceiptProcessingStatus.OCR_FAILED.name
                receipt.parsedMerchant != null -> ReceiptProcessingStatus.PARSED.name
                else -> ReceiptProcessingStatus.OCR_COMPLETED.name
            }

            // ── Post-OCR text + semantic fingerprints ─────────────────────
            val textFingerprint = if (receipt.rawOcrText.isNotBlank() &&
                !receipt.rawOcrText.startsWith("Scan Failed") &&
                !receipt.rawOcrText.startsWith("[OCR Failed")
            ) {
                duplicateDetector.computeTextFingerprintPublic(receipt.rawOcrText)
            } else null

            val semanticFingerprint = if (receipt.parsedMerchant != null && receipt.parsedTotal != null && receipt.parsedDate != null) {
                duplicateDetector.computeSemanticFingerprintPublic(
                    receipt.parsedMerchant,
                    receipt.parsedTotal,
                    receipt.parsedDate,
                    receipt.currency
                )
            } else null

            // ── Post-OCR dedup check (text + semantic) ────────────────────
            val postOcrDup = duplicateDetector.checkDuplicate(
                imageHash = null,  // already checked above
                textFingerprint = textFingerprint,
                semanticFingerprint = semanticFingerprint,
                externalSourceId = null
            )

            if (postOcrDup.isDuplicate && postOcrDup.matchType != "EXACT_HASH") {
                // Update current receipt with fingerprints and mark as duplicate
                val now = timeProvider.now()
                val withFingerprints = receipt.copy(
                    imagePath = receipt.imagePath,
                    imageHash = fileHash ?: receipt.imageHash,
                    sourceType = ReceiptSourceType.CAMERA.name,
                    documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                    processingStatus = ReceiptProcessingStatus.DUPLICATE_DETECTED.name,
                    textFingerprint = textFingerprint,
                    semanticFingerprint = semanticFingerprint,
                    updatedAt = now
                ).also { it.taxInclusive = taxInclusive }
                scannedReceiptDao.update(withFingerprints)

                // Write DUPLICATE_DETECTED event with existing receipt ID in metadata
                val existing = scannedReceiptDao.getById(postOcrDup.existingReceiptId!!)
                if (existing != null) {
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = withFingerprints.id,
                            sourceType = withFingerprints.sourceType,
                            documentType = withFingerprints.documentType,
                            eventType = "DUPLICATE_DETECTED",
                            occurredAt = now,
                            oldStatus = receipt.processingStatus,
                            newStatus = ReceiptProcessingStatus.DUPLICATE_DETECTED.name,
                            actor = "system:coordinator",
                            message = "Duplicate receipt detected (match=${postOcrDup.matchType}, existingId=${existing.id})",
                            metadata = "{\"existingReceiptId\":${existing.id},\"matchType\":\"${postOcrDup.matchType}\"}",
                            errorDetails = null
                        )
                    )
                    existing.taxInclusive = taxInclusive
                    return Result.success(existing)
                }
            }

            // 5. Update receipt with lifecycle metadata and fingerprints,
            //    and carry the taxInclusive flag for downstream consumers.
            val now = timeProvider.now()
            // P3-P1-02: Repair createdAt=0L sentinel that arrives when the OCR
            // pipeline creates a ScannedReceipt without setting a timestamp.
            val repairedCreatedAt = if (receipt.createdAt == 0L) now else receipt.createdAt
            val updated = receipt.copy(
                imagePath = receipt.imagePath,
                imageHash = fileHash ?: receipt.imageHash,
                sourceType = ReceiptSourceType.CAMERA.name,
                documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                processingStatus = processingStatus,
                textFingerprint = textFingerprint,
                semanticFingerprint = semanticFingerprint,
                createdAt = repairedCreatedAt,
                updatedAt = now
            ).also { it.taxInclusive = taxInclusive }

            // 5+6. Atomically persist the receipt update and all lifecycle events.
            // P3-P1-01: wrap update+events in a single transaction so a partial
            // write (event inserted but receipt row not yet updated, or vice versa)
            // cannot leave the database in an inconsistent state.
            database.withTransaction {
                scannedReceiptDao.update(updated)

                // 6. Write lifecycle event
                receiptEventDao.insert(
                    ReceiptEvent(
                        receiptId = updated.id,
                        sourceType = updated.sourceType,
                        documentType = updated.documentType,
                        eventType = "RECEIPT_SAVED",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = updated.processingStatus,
                        actor = "system:coordinator",
                        message = "Receipt processed via lifecycle coordinator",
                        metadata = null,
                        errorDetails = null
                    )
                )

                if (isOcrFailure) {
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = updated.id,
                            sourceType = updated.sourceType,
                            documentType = updated.documentType,
                            eventType = "OCR_FAILED",
                            occurredAt = now,
                            oldStatus = null,
                            newStatus = ReceiptProcessingStatus.OCR_FAILED.name,
                            actor = "system:coordinator",
                            message = "OCR failed for receipt input",
                            metadata = null,
                            errorDetails = null
                        )
                    )
                }
            }

            // 7. Dispatch post-save side effects (warranty, categorization, matching, etc.)
            try {
                sideEffectDispatcher.dispatchAfterSave(updated)
            } catch (e: Exception) {
                Timber.e(e, "Post-save side effects failed for receipt %d", updated.id)
                // Non-fatal — receipt is already saved
            }

            Timber.d("Receipt processed via coordinator: id=%d, imagePath=%s", updated.id, updated.imagePath)
            Result.success(updated)
        } catch (e: Exception) {
            Timber.e(e, "processReceipt failed for %s, falling back to manual record", uri)

            // OCR/parse failed catastrophically — save manual record
            // Use the original URI string as a fallback image reference
            val fallbackCurrency = runCatching {
                currencySettingsRepository.homeCurrency().first()
            }.getOrDefault(FALLBACK_CURRENCY)

            val manualReceipt = ScannedReceipt(
                imagePath = uri.toString(),
                rawOcrText = "[OCR Failed or Skipped]",
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = fallbackCurrency,
                confidence = 0f,
                sourceType = ReceiptSourceType.CAMERA.name,
                documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                processingStatus = ReceiptProcessingStatus.OCR_FAILED.name,
                imageHash = null,
                createdAt = timeProvider.now(),
                updatedAt = timeProvider.now()
            )
            val savedId = scannedReceiptDao.insert(manualReceipt)

            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = savedId,
                    sourceType = manualReceipt.sourceType,
                    documentType = manualReceipt.documentType,
                    eventType = "OCR_FAILED",
                    occurredAt = timeProvider.now(),
                    oldStatus = null,
                    newStatus = ReceiptProcessingStatus.OCR_FAILED.name,
                    actor = "system:coordinator",
                    message = "OCR failed for receipt input",
                    metadata = null,
                    errorDetails = null
                )
            )

            Result.success(manualReceipt.copy(id = savedId))
        }
    }

    /**
     * Processes an email receipt from structured data.
     *
     * Will be fully implemented in PR 5.
     *
     * @param emailData Structured data extracted from the email.
     * @return [Result.success] with the saved [ScannedReceipt] on success,
     *         [Result.failure] on error.
     */
    /**
     * Processes a bank statement image and extracts individual transactions.
     *
     * Delegates to [BankStatementLifecycleProcessor] which handles the full
     * lifecycle: save with BANK_STATEMENT document type, parse transactions,
     * create PendingReview entries, and write lifecycle events.
     *
     * @param uri The content URI of the bank statement image / PDF.
     * @return [Result.success] with [BankStatementResult] on success,
     *         [Result.failure] on error.
     */
    suspend fun processBankStatement(uri: Uri): DomainResult<BankStatementResult> {
        val result = bankStatementLifecycleProcessor.processBankStatement(uri)
        return result.fold(
            onSuccess = { DomainResult.Success(it) },
            onFailure = { DomainResult.Error(exception = it, message = it.message) }
        )
    }

    /**
     * Saves an email receipt with proper lifecycle metadata.
     *
     * Sets [ReceiptSourceType.EMAIL], [ReceiptDocumentType.EMAIL_RECEIPT],
     * and [ReceiptProcessingStatus.PARSED] on the receipt before inserting,
     * then writes a RECEIPT_SAVED lifecycle event.
     *
     * @param receipt The [ScannedReceipt] to persist (sourceType/documentType
     *                will be overridden).
     * @return The auto-generated receipt ID.
     */
    suspend fun saveEmailReceipt(receipt: ScannedReceipt): Long {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        val now = timeProvider.now()
        val ocrStorageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
        val sanitizedOcrText = when (ocrStorageMode) {
            RawStorageMode.STORE_RAW -> receipt.rawOcrText
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY -> ""
            RawStorageMode.DO_NOT_STORE -> ""
        }
        val updated = receipt.copy(
            sourceType = ReceiptSourceType.EMAIL.name,
            documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
            processingStatus = ReceiptProcessingStatus.PARSED.name,
            rawOcrText = sanitizedOcrText,
            createdAt = if (receipt.createdAt == 0L) now else receipt.createdAt,
            updatedAt = now
        )
        val id = scannedReceiptDao.insert(updated)

        receiptEventDao.insert(
            ReceiptEvent(
                receiptId = id,
                sourceType = ReceiptSourceType.EMAIL.name,
                documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
                eventType = "RECEIPT_SAVED",
                occurredAt = now,
                oldStatus = null,
                newStatus = ReceiptProcessingStatus.PARSED.name,
                actor = "system:email_ingestion",
                message = "Email receipt saved via lifecycle coordinator",
                metadata = null,
                errorDetails = null
            )
        )

        Timber.d("Email receipt saved via coordinator: id=%d", id)
        return id
    }

    /**
     * Returns recent receipts created after [since].
     *
     * Delegates to [ScannedReceiptDao.getRecentReceipts] for deduplication
     * lookups during email receipt ingestion.
     */
    suspend fun getRecentReceipts(since: Long): List<ScannedReceipt> {
        return scannedReceiptDao.getRecentReceipts(since)
    }

    /**
     * Returns a single receipt by its ID, or null if not found.
     */
    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    /**
     * Full lifecycle owner for email receipt ingestion.
     *
     * Saves receipt → creates EmailReceiptSource → writes event →
     * creates Expense → links receipt → dispatches side effects.
     *
     * @param emailData       Parsed email data with amount/merchant/currency/date.
     * @param fingerprint     Deduplication fingerprint from the caller.
     * @param rawEmailBody    Full raw email body text (stored in rawOcrText).
     * @param sender          Email sender address.
     * @param subject         Email subject line.
     * @param messageId       Email message ID for source dedup.
     * @param provider        Provider name (amazon/uber/apple/unknown).
     * @return [EmailReceiptProcessResult] sealed result.
     */
    suspend fun processEmailReceipt(
        emailData: EmailReceiptData,
        fingerprint: String,
        rawEmailBody: String,
        sender: String,
        subject: String,
        messageId: String,
        provider: String
    ): EmailReceiptProcessResult {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return EmailReceiptProcessResult.Error("Database writes blocked during restore")
        }

        // Check messageId dedup
        if (messageId.isNotBlank()) {
            val existing = scannedReceiptDao.getBySourceFingerprint(messageId)
            if (existing != null) {
                return EmailReceiptProcessResult.Duplicate(existing.id)
            }
        }
        // Check fingerprint dedup
        if (fingerprint.isNotBlank()) {
            val existing = emailReceiptDao.getByFingerprint(fingerprint)
            if (existing != null) {
                return EmailReceiptProcessResult.Duplicate(existing.receiptId)
            }
        }

        val now = timeProvider.now()
        val ocrStorageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
        val effectiveOcrText = when (ocrStorageMode) {
            RawStorageMode.STORE_RAW -> rawEmailBody
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY -> ""
            RawStorageMode.DO_NOT_STORE -> ""
        }
        var savedId = 0L
        var expenseIds = mutableListOf<Long>()

        database.withTransaction {
            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
                .getOrDefault("EUR")

            val receipt = ScannedReceipt(
                imagePath = null,
                rawOcrText = effectiveOcrText,
                parsedTotal = emailData.amount,
                parsedMerchant = emailData.merchant,
                parsedDate = emailData.date,
                parsedItems = emailData.items,
                parsedTaxAmount = null,
                currency = emailData.currency ?: homeCurrency,
                confidence = 0.7f,
                sourceType = ReceiptSourceType.EMAIL.name,
                documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
                processingStatus = if (emailData.merchant != null) ReceiptProcessingStatus.PARSED.name
                                   else ReceiptProcessingStatus.CAPTURED.name,
                sourceFingerprint = messageId,
                createdAt = now,
                updatedAt = now
            )
            savedId = scannedReceiptDao.insert(receipt)

            val emailSource = EmailReceiptSource(
                receiptId = savedId,
                emailSender = sender,
                emailSubject = subject,
                emailMessageId = messageId,
                parsedAt = now,
                provider = provider,
                confidence = 1.0,
                fingerprint = fingerprint
            )
            emailReceiptDao.insertOrIgnore(emailSource)

            receiptEventDao.insert(ReceiptEvent(
                receiptId = savedId,
                sourceType = ReceiptSourceType.EMAIL.name,
                documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
                eventType = "RECEIPT_SAVED",
                occurredAt = now,
                oldStatus = null,
                newStatus = receipt.processingStatus,
                actor = "system:email_ingestion",
                message = "Email receipt saved via lifecycle coordinator",
                metadata = null,
                errorDetails = null
            ))

            // Create expense if we have enough data
            if (emailData.amount != null && emailData.amount > 0 &&
                !emailData.merchant.isNullOrBlank() &&
                emailData.date != null && emailData.date > 0
            ) {
                val request = CreateExpenseRequest(
                    merchant = emailData.merchant!!,
                    amount = emailData.amount,
                    currency = emailData.currency ?: homeCurrency,
                    date = emailData.date,
                    transactionType = TransactionType.PURCHASE,
                    source = ExpenseSource.EMAIL_RECEIPT,
                    notes = "Imported from email: $subject",
                    scannedReceiptId = savedId,
                    deduplicationMode = DeduplicationMode.STANDARD
                )
                when (val result = transactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)) {
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created -> {
                        expenseIds.add(result.expenseId)
                        receiptLinkService.linkReceiptToExpense(savedId, result.expenseId, "EMAIL_RECEIPT", source = ExpenseSource.EMAIL_RECEIPT.name)
                    }
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.DuplicateSkipped -> {
                        Timber.d("Email receipt %d matched existing expense %d", savedId, result.existingExpenseId)
                        expenseIds.add(result.existingExpenseId)
                        receiptLinkService.linkReceiptToExpense(savedId, result.existingExpenseId, "EMAIL_RECEIPT", source = ExpenseSource.EMAIL_RECEIPT.name)
                    }
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.ValidationFailed -> {
                        Timber.w("Email receipt %d validation failed: %s", savedId, result.errors)
                    }
                    else -> {}
                }
            }
        }

        // Post-commit side effects (best-effort)
        val saved = scannedReceiptDao.getById(savedId)
        if (saved != null) {
            runCatching { sideEffectDispatcher.dispatchAfterSave(saved) }
            for (expenseId in expenseIds) {
                runCatching {
                    transactionLifecycleCoordinator.dispatchPostCreationSideEffects(
                        expenseId, ExpenseSource.EMAIL_RECEIPT
                    )
                }
            }
        }

        return EmailReceiptProcessResult.Success(receiptId = savedId, expenseIds = expenseIds)
    }

    suspend fun deleteReceipt(receiptId: Long): Result<Unit> {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }

        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return Result.failure(IllegalArgumentException("Receipt not found: $receiptId"))

        return try {
            // Database operations inside a single transaction
            database.withTransaction {
                // 2. Write delete event for audit trail
                receiptEventDao.insert(
                    ReceiptEvent(
                        receiptId = receiptId,
                        sourceType = receipt.sourceType,
                        documentType = receipt.documentType,
                        eventType = "RECEIPT_DELETED",
                        occurredAt = timeProvider.now(),
                        oldStatus = receipt.processingStatus,
                        newStatus = "DELETED",
                        actor = "system:coordinator",
                        message = "Receipt deleted with asset cleanup",
                        metadata = null,
                        errorDetails = null
                    )
                )

                // 3. Delete all receipt-expense links
                receiptExpenseLinkDao.deleteAllLinksForReceipt(receiptId)

                // 4. Delete the database row
                scannedReceiptDao.delete(receipt)
            }

            // 5. POST-COMMIT: Delete the physical asset file (if one exists)
            receipt.imagePath?.let { assetStore.deleteAsset(it) }

            Timber.d("Receipt deleted: id=%d, assetPath=%s", receiptId, receipt.imagePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete receipt: id=%d", receiptId)
            Result.failure(e)
        }
    }

    /**
     * Creates an expense from a scanned receipt and links the receipt to the expense.
     *
     * This is a convenience wrapper around [TransactionLifecycleCoordinator.createExpense]
     * and [ReceiptLinkService.linkReceiptToExpense].
     *
     * @param receiptId The scanned receipt ID.
     * @param merchant Merchant/display name.
     * @param amount Transaction amount.
     * @param currency Currency code.
     * @param categoryId Optional category ID.
     * @param date Transaction date.
     * @param paymentMethod Payment method.
     * @param notes Optional notes.
     * @return [DomainResult] with the created expense ID on success.
     * @Deprecated Use [TransactionLifecycleCoordinator.createExpense] directly with
     *   [ReceiptLinkService.linkReceiptToExpense] for the linking step.
     */
    @Deprecated(
        message = "Migrate to explicit pipeline: 1) load receipt for tax-inclusive check, " +
            "2) MerchantNormalizer.normalize(merchant, autoCreate=true) for canonical name, " +
            "3) HybridExpenseClassifier.classify() when categoryId is null, " +
            "4) TransactionLifecycleCoordinator.createExpense() with all resolved fields, " +
            "5) ReceiptLinkService.linkReceiptToExpense() for receipt-expense linking, " +
            "6) HybridExpenseClassifier.learnFromCorrection() as best-effort side effect. " +
            "This method will be removed in a future release.",
        replaceWith = ReplaceWith(
            expression = "transactionLifecycleCoordinator.createExpense(request) /* see migration steps above */",
            imports = ["com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator"]
        )
    )
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long,
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): DomainResult<Long> {
        // ── Fix 1 (CRITICAL): Tax-inclusive amount override (RCP-14) ──────────
        // Load the receipt entity. taxInclusive is @Ignore (transient), so it
        // is always false when loaded from DB. Fall back to detecting
        // tax-inclusive from the persisted fields.
        val receiptRecord = scannedReceiptDao.getById(receiptId)
        val effectiveAmount = if (receiptRecord != null) {
            // RCP-14: taxInclusive is @Ignore (transient), recompute from persisted fields
            val parsedItems = receiptRecord.parsedItems
            val isTaxInclusive = receiptRecord.taxInclusive ||
                (receiptRecord.parsedTotal != null && receiptRecord.parsedTaxAmount != null && parsedItems != null && run {
                    try {
                        val items = org.json.JSONArray(parsedItems)
                        if (items.length() == 0) false
                        else {
                            var sum = 0.0
                            for (i in 0 until items.length()) {
                                sum += items.getJSONObject(i).getDouble("totalPrice")
                            }
                            kotlin.math.abs(sum - receiptRecord.parsedTotal) < receiptRecord.parsedTotal * 0.05
                        }
                    } catch (_: Exception) { false }
                })
            if (isTaxInclusive && receiptRecord.parsedTotal != null) {
                Timber.d(
                    "RCP-14: Overriding amount from %.2f to %.2f for tax-inclusive receipt %d",
                    amount, receiptRecord.parsedTotal, receiptId
                )
                receiptRecord.parsedTotal
            } else {
                amount
            }
        } else {
            amount
        }

        // ── Fix 2 (CRITICAL): Merchant normalization ──────────────────────────
        val normalizedMerchant = merchantNormalizer.normalize(
            rawName = merchant,
            autoCreate = true
        ).canonical.normalizedName

        // ── Fix 3 (CRITICAL): Auto-categorization when no category provided ───
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = effectiveAmount
        ).categoryId.takeIf { it > 0 }

        // ── Fix 4 (MAJOR): Notes fallback ─────────────────────────────────────
        val notesValue = notes ?: "Scanned from receipt"

        val request = CreateExpenseRequest(
            merchant = normalizedMerchant,
            amount = effectiveAmount,
            currency = currency,
            date = date,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.RECEIPT_SCAN,
            categoryId = finalCategoryId,
            notes = notesValue,
            paymentMethod = paymentMethod,
            isManualEntry = true,
            scannedReceiptId = receiptId
        )

        return when (val result = transactionLifecycleCoordinator.createExpense(request)) {
            is CreateExpenseResult.Created -> {
                val expenseId = result.expenseId
                // Link receipt to the newly created expense
                receiptLinkService.linkReceiptToExpense(
                    receiptId = receiptId,
                    expenseId = expenseId,
                    linkType = "DIRECT_SAVE",
                    source = ExpenseSource.RECEIPT_SCAN.name
                )

                // ── Fix 5 (MAJOR): Classifier learning (best-effort side effect) ──
                if (finalCategoryId != null) {
                    try {
                        hybridClassifier.learnFromCorrection(
                            merchantName = normalizedMerchant,
                            correctCategoryId = finalCategoryId,
                            amount = effectiveAmount
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "Classifier learning failed after expense creation from receipt %d", receiptId)
                    }
                }

                DomainResult.Success(expenseId)
            }
            is CreateExpenseResult.DuplicateSkipped -> {
                DomainResult.Duplicate
            }
            is CreateExpenseResult.ValidationFailed -> {
                DomainResult.Error(
                    message = "Validation failed: ${result.errors.joinToString(", ")}"
                )
            }
            is CreateExpenseResult.InsertConflict -> {
                DomainResult.Error(
                    message = "Insert conflict: ${result.dedupeKey}"
                )
            }
            is CreateExpenseResult.Error -> {
                DomainResult.Error(
                    exception = result.exception,
                    message = result.exception.message ?: "Unknown error"
                )
            }
        }
    }
}
