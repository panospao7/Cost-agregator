package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
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
 *   validate → persist asset → OCR / parse → dedupe → save → event logging
 *   → post-save side effects (via [ReceiptSideEffectDispatcher])
 *
 * Full lifecycle phases:
 * 1. **Input Validation** — [ReceiptInputValidator] checks the URI is readable
 *    and has an acceptable MIME type.
 * 2. **Asset Persistence** — [ReceiptAssetStore] copies the file to app-local
 *    storage and computes an SHA-256 hash for deduplication.
 * 3. **OCR / Parse** — [ReceiptRepository.processReceipt] runs OCR and
 *    parses the extracted text for merchant, total, date, and line items.
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
 * @constructor Inject dependencies needed for validation, asset storage,
 *              processing, link management, event logging, and side effects.
 */
@Singleton
class ReceiptLifecycleCoordinator @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val receiptLinkService: ReceiptLinkService,
    private val assetStore: ReceiptAssetStore,
    private val inputValidator: ReceiptInputValidator,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val receiptEventDao: ReceiptEventDao,
    private val timeProvider: TimeProvider,
    private val bankStatementLifecycleProcessor: BankStatementLifecycleProcessor,
    private val sideEffectDispatcher: ReceiptSideEffectDispatcher,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val currencySettingsRepository: CurrencySettingsRepository
) {

    companion object {
        /** Last-resort fallback currency when no home currency can be resolved. */
        private const val FALLBACK_CURRENCY = "EUR"
    }

    /**
     * Processes a receipt input URI through the full lifecycle.
     *
     * Full implementation (PR 4):
     * 1. Validates the input URI.
     * 2. Persists the receipt asset to app-local storage.
     * 3. Computes a file hash for deduplication (best-effort).
     * 4. Delegates to [ReceiptRepository.processReceipt] for OCR and parsing.
     * 5. Updates the receipt with lifecycle metadata (sourceType, documentType,
     *    processingStatus, asset info) and writes a lifecycle event.
     * 6. If processing fails catastrophically, saves a manual record and
     *    writes an OCR_FAILED event.
     *
     * @param uri The content URI of the receipt image / PDF.
     * @return [Result.success] with the saved [ScannedReceipt] on success,
     *         [Result.failure] on validation or processing error.
     */
    suspend fun processReceiptInput(uri: Uri): Result<ScannedReceipt> {
        // 1. Validate input
        val validation = inputValidator.validate(uri)
        if (!validation.isValid) {
            val message = "Receipt input validation failed: ${validation.errors.joinToString("; ")}"
            Timber.w(message)
            return Result.failure(IllegalArgumentException(message))
        }

        // 2. Persist asset to app-local storage
        val assetPath = assetStore.persistReceiptAsset(uri).getOrElse { error ->
            return Result.failure(IllegalStateException("Failed to persist receipt asset", error))
        }

        // 3. Compute file hash for deduplication (best-effort, non-fatal)
        val fileHash = try {
            assetStore.computeFileHash(assetPath).getOrNull()
        } catch (_: Exception) {
            null
        }

        // 3b. Check for duplicate receipt via file hash
        if (fileHash != null) {
            val dupResult = duplicateDetector.checkDuplicate(
                imageHash = fileHash,
                textFingerprint = null,
                semanticFingerprint = null,
                externalSourceId = null
            )
            if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
                val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)
                if (existing != null) {
                    Timber.i("Duplicate receipt detected by exact hash: existingId=${existing.id}")
                    return Result.success(existing)
                }
            }
        }

        // 4. OCR + Parse via ReceiptRepository
        return try {
            val (receipt, _) = receiptRepository.processReceipt(
                imageUri = uri,
                autoCreateReview = false
            )

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

            // ── Post-OCR dedup check ──────────────────────────────────────
            val postOcrDup = duplicateDetector.checkDuplicate(
                imageHash = null,  // already checked pre-OCR
                textFingerprint = textFingerprint,
                semanticFingerprint = semanticFingerprint,
                externalSourceId = null
            )

            if (postOcrDup.isDuplicate && postOcrDup.matchType != "EXACT_HASH") {
                // Update current receipt with fingerprints for future dedup
                val withFingerprints = receipt.copy(
                    imagePath = receipt.imagePath ?: assetPath,
                    imageHash = fileHash ?: receipt.imageHash,
                    sourceType = ReceiptSourceType.CAMERA.name,
                    documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                    processingStatus = processingStatus,
                    textFingerprint = textFingerprint,
                    semanticFingerprint = semanticFingerprint,
                    updatedAt = timeProvider.now()
                )
                scannedReceiptDao.update(withFingerprints)
                // Return existing receipt as duplicate
                val existing = scannedReceiptDao.getById(postOcrDup.existingReceiptId!!)
                if (existing != null) return Result.success(existing)
            }

            // 5. Update receipt with asset info, lifecycle metadata, and fingerprints
            val updated = receipt.copy(
                imagePath = receipt.imagePath ?: assetPath,
                imageHash = fileHash ?: receipt.imageHash,
                sourceType = ReceiptSourceType.CAMERA.name,
                documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                processingStatus = processingStatus,
                textFingerprint = textFingerprint,
                semanticFingerprint = semanticFingerprint,
                updatedAt = timeProvider.now()
            )
            scannedReceiptDao.update(updated)

            // 6. Write lifecycle event
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = updated.id,
                    sourceType = updated.sourceType,
                    documentType = updated.documentType,
                    eventType = "RECEIPT_SAVED",
                    occurredAt = timeProvider.now(),
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
                        occurredAt = timeProvider.now(),
                        oldStatus = null,
                        newStatus = ReceiptProcessingStatus.OCR_FAILED.name,
                        actor = "system:coordinator",
                        message = "OCR failed for receipt input",
                        metadata = null,
                        errorDetails = null
                    )
                )
            }

            // 7. Dispatch post-save side effects (warranty, categorization, matching, etc.)
            try {
                sideEffectDispatcher.dispatchAfterSave(updated)
            } catch (e: Exception) {
                Timber.e(e, "Post-save side effects failed for receipt %d", updated.id)
                // Non-fatal — receipt is already saved
            }

            Timber.d("Receipt processed via coordinator: id=%d, assetPath=%s", updated.id, assetPath)
            Result.success(updated)
        } catch (e: Exception) {
            Timber.e(e, "processReceipt failed for %s, falling back to manual record", uri)

            // OCR/parse failed catastrophically — save manual record
            val fallbackCurrency = runCatching {
                currencySettingsRepository.homeCurrency().first()
            }.getOrDefault(FALLBACK_CURRENCY)

            val manualReceipt = ScannedReceipt(
                imagePath = assetPath,
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
                imageHash = fileHash,
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
    suspend fun processEmailReceipt(emailData: EmailReceiptData): Result<ScannedReceipt> {
        // 1. Check message ID dedup via sourceFingerprint
        if (emailData.messageId.isNotBlank()) {
            val existing = scannedReceiptDao.getBySourceFingerprint(emailData.messageId)
            if (existing != null) {
                return Result.success(existing)
            }
        }

        // 2. Build receipt
        val now = timeProvider.now()
        val receipt = ScannedReceipt(
            imagePath = null,  // email receipts have no image
            rawOcrText = emailData.body,
            parsedTotal = emailData.amount,
            parsedMerchant = emailData.merchant,
            parsedDate = emailData.date,
            parsedItems = emailData.items,
            parsedTaxAmount = null,
            currency = emailData.currency ?: FALLBACK_CURRENCY,
            confidence = 0.7f,
            sourceType = ReceiptSourceType.EMAIL.name,
            documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
            processingStatus = if (emailData.merchant != null) ReceiptProcessingStatus.PARSED.name
                               else ReceiptProcessingStatus.CAPTURED.name,
            sourceFingerprint = emailData.messageId,
            createdAt = now,
            updatedAt = now
        )

        // 3. Save receipt
        val savedId = scannedReceiptDao.insert(receipt)
        val saved = receipt.copy(id = savedId)

        // 4. Write event
        receiptEventDao.insert(ReceiptEvent(
            receiptId = savedId,
            sourceType = ReceiptSourceType.EMAIL.name,
            documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
            eventType = "RECEIPT_SAVED",
            occurredAt = now,
            oldStatus = null,
            newStatus = saved.processingStatus,
            actor = "system:email_ingestion",
            message = "Email receipt saved via lifecycle coordinator",
            metadata = null,
            errorDetails = null
        ))

        // 5. Dispatch side effects
        sideEffectDispatcher.dispatchAfterSave(saved)

        return Result.success(saved)
    }

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
    suspend fun processBankStatement(uri: Uri): Result<BankStatementResult> {
        return bankStatementLifecycleProcessor.processBankStatement(uri)
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
        val now = timeProvider.now()
        val updated = receipt.copy(
            sourceType = ReceiptSourceType.EMAIL.name,
            documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
            processingStatus = ReceiptProcessingStatus.PARSED.name,
            createdAt = now,
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
     * Deletes a receipt and its associated assets.
     *
     * Performs a complete teardown in this order:
     * 1. Looks up the receipt by ID — returns failure if not found.
     * 2. Writes a [ReceiptEvent] with eventType `RECEIPT_DELETED` for the audit trail.
     * 3. Deletes all [ReceiptExpenseLink] rows referencing this receipt.
     * 4. Deletes the physical asset file from app-local storage (if [ScannedReceipt.imagePath] is set).
     * 5. Deletes the database row.
     *
     * @param receiptId The ID of the receipt to delete.
     * @return [Result.success] on completion, [Result.failure] on error.
     */
    suspend fun deleteReceipt(receiptId: Long): Result<Unit> {
        // 1. Look up receipt — fail fast if not found
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return Result.failure(IllegalArgumentException("Receipt not found: $receiptId"))

        return try {
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

            // 4. Delete the physical asset file (if one exists)
            receipt.imagePath?.let { assetStore.deleteAsset(it) }

            // 5. Delete the database row
            scannedReceiptDao.delete(receipt)

            Timber.d("Receipt deleted: id=%d, assetPath=%s", receiptId, receipt.imagePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete receipt: id=%d", receiptId)
            Result.failure(e)
        }
    }
}
