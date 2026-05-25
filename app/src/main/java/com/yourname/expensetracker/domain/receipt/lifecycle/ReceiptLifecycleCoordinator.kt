package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.ExtractionState
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.domain.model.Result as DomainResult
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.runBestEffortAfterCommit
import com.yourname.expensetracker.domain.sideeffect.runBestEffortPostCommit
import kotlinx.coroutines.CancellationException
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.provenance.ReceiptSourceLinkPayloadFactory
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceContext
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.TargetEntityType
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
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
    private val pendingReviewDao: PendingReviewDao,
    private val pendingReviewSourceLinkService: PendingReviewSourceLinkService,
    private val timeProvider: TimeProvider,
    private val bankStatementLifecycleProcessor: BankStatementLifecycleProcessor,
    private val sideEffectDispatcher: ReceiptSideEffectDispatcher,
    private val duplicateDetector: ReceiptDuplicateDetector,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val postCommitActionRunner: PostCommitActionRunner,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val sourceLinkWriter: SourceLinkWriter,
    private val receiptSideEffectPlanner: ReceiptSideEffectPlanner,
    private val receiptInsertResolver: ReceiptInsertResolver
) {

    companion object {
        /** Last-resort fallback currency when no home currency can be resolved. */
        private const val FALLBACK_CURRENCY = "XXX"  // ISO 4217 unknown currency — no longer "EUR"

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
     * Options that control receipt processing behaviour.
     *
     * @param createReview When true, a [PendingReview] is created so the receipt
     *                     appears in the review queue for user confirmation.
     * @param autoMatchExistingExpense When true, the matching side effect may
     *                                 auto-link to existing expenses.
     */
    data class ReceiptProcessingOptions(
        val createReview: Boolean = false,
        val autoMatchExistingExpense: Boolean = true
    )

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
     * @param options Processing options controlling review creation and auto-matching.
     * @return [Result.success] with the saved [ScannedReceipt] on success,
     *         [Result.failure] on validation or processing error.
     */
    suspend fun processReceiptInput(
        uri: Uri,
        options: ReceiptProcessingOptions = ReceiptProcessingOptions()
    ): Result<ScannedReceipt> {
        // Guard: block writes during restore maintenance mode
        try {
            writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.processReceiptInput")
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // P3-NEW-08: Write INPUT_RECEIVED diagnostic before validation
        val correlationId = java.util.UUID.randomUUID().toString()
        emitIntakeDiagnostic("input", com.yourname.expensetracker.domain.diagnostics.EventOutcome.RECEIVED,
            correlationId, "INPUT_RECEIVED", mimeType = null, fileSizeBytes = null)

        // 1. Validate input
        val validation = inputValidator.validate(uri)
        if (!validation.isValid) {
            // P3-BLOCKER-006: Use sanitized message — never embed raw URI
            val message = "Receipt input validation failed"
            Timber.w("$message: %s", validation.errors.firstOrNull() ?: "unknown reason")
            // P3-NEW-08 / P3-BLOCKER-04: Write VALIDATION_FAILED diagnostic
            // Use reason codes extracted from validation errors — never raw URI
            val reasonCode = when {
                validation.errors.any { it.contains("not readable") } -> "URI_NOT_READABLE"
                validation.errors.any { it.contains("MIME type") || it.contains("determine MIME") } -> "MIME_UNKNOWN"
                validation.errors.any { it.contains("Unsupported MIME") } -> "MIME_UNSUPPORTED"
                validation.errors.any { it.contains("too large") || it.contains("exceeds") } -> "FILE_TOO_LARGE"
                validation.errors.any { it.contains("decode") } -> "IMAGE_DECODE_FAILED"
                else -> "VALIDATION_FAILED"
            }
            emitIntakeDiagnostic("validation", com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                correlationId, "VALIDATION_FAILED",
                mimeType = validation.mimeType, fileSizeBytes = validation.fileSizeBytes,
                message = "Validation failed",
                metadata = mapOf("reasonCode" to reasonCode))
            return Result.failure(IllegalArgumentException(message))
        }

        // P3-NEW-08: Write VALIDATION_PASSED diagnostic
        emitIntakeDiagnostic("validation", com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
            correlationId, "VALIDATION_PASSED",
            mimeType = validation.mimeType, fileSizeBytes = validation.fileSizeBytes)

        // 2. OCR + Parse via ReceiptRepository
        //    Ownership boundary: ReceiptRepository/OCR owns file persistence.
        //    The coordinator works with the imagePath from the response, not its own copy.
        return try {
            val processResult = receiptRepository.processReceipt(
                imageUri = uri,
                autoCreateReview = options.createReview,
                resolvedMimeType = validation.mimeType
            )

            // P3-REG-01: Pre-OCR duplicate — repository returned an already-existing
            // receipt. Do NOT run lifecycle update, review creation, or duplicate
            // cleanup against a pre-existing receipt.
            if (processResult.isPreExistingDuplicate) {
                Timber.d("Pre-OCR duplicate detected: existingId=%d", processResult.receipt.id)
                return Result.success(processResult.receipt)
            }

            val receipt = processResult.receipt
            val parsed = processResult.parsed

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
                        // P3-CUR-01 / P3-CUR-02: Proper exact-duplicate cleanup:
                        // 1. Delete pending reviews referencing the duplicate receipt
                        // 2. Write DUPLICATE_DETECTED event
                        // 3. Delete the duplicate receipt row
                        // 4. Delete the persisted asset file post-commit
                        val now = timeProvider.now()
                        database.withTransaction {
                            pendingReviewDao.deleteByScannedReceiptId(receipt.id)
                            receiptEventDao.insert(
                                ReceiptEvent(
                                    receiptId = receipt.id,
                                    sourceType = receipt.sourceType,
                                    documentType = receipt.documentType,
                                    eventType = "DUPLICATE_DETECTED",
                                    occurredAt = now,
                                    oldStatus = receipt.processingStatus,
                                    newStatus = ReceiptProcessingStatus.DUPLICATE_DETECTED.name,
                                    actor = "system:coordinator",
                                    message = "Exact-hash duplicate removed (existingId=${existing.id})",
                                    metadata = "{\"existingReceiptId\":${existing.id},\"matchType\":\"EXACT_HASH\"}",
                                    errorDetails = null
                                )
                            )
                            scannedReceiptDao.delete(receipt)
                        }
                        // Post-commit: delete the persisted asset for the duplicate
                        receipt.imagePath?.let { assetStore.deleteAsset(it) }
                        Timber.i("Duplicate receipt detected by exact hash: existingId=${existing.id}")
                        return Result.success(existing)
                    }
                }
            }

            // Determine processing status based on receipt content.
            // P3-P1-09: Respect PARSE_FAILED status set by the repository when
            // OCR succeeds but the parser throws an exception. Previously the
            // coordinator would reclassify these as OCR_COMPLETED.
            val isOcrFailure = receipt.rawOcrText == "[OCR Failed or Skipped]" ||
                receipt.rawOcrText.startsWith("Scan Failed:")
            val processingStatus = when {
                isOcrFailure -> ReceiptProcessingStatus.OCR_FAILED.name
                receipt.processingStatus == ReceiptProcessingStatus.PARSE_FAILED.name -> ReceiptProcessingStatus.PARSE_FAILED.name
                receipt.parsedMerchant != null -> ReceiptProcessingStatus.PARSED.name
                else -> ReceiptProcessingStatus.OCR_COMPLETED.name
            }

            // ── Post-OCR text + semantic fingerprints ─────────────────────
            // P3-CURRENT-003: Compute textFingerprint from the ephemeral raw OCR
            // text BEFORE sanitization to avoid false duplicates in STORE_REDACTED mode.
            val rawOcrForFingerprint = processResult.ephemeralRawOcrText ?: receipt.rawOcrText
            val textFingerprint = if (rawOcrForFingerprint.isNotBlank() &&
                !rawOcrForFingerprint.startsWith("Scan Failed") &&
                !rawOcrForFingerprint.startsWith("[OCR Failed")
            ) {
                duplicateDetector.computeTextFingerprintPublic(rawOcrForFingerprint)
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
                // Update current receipt with fingerprints and mark as duplicate.
                // P3-CUR-02: Wrap update + event + pending-review cleanup in a single
                // database transaction so a crash cannot leave them inconsistent.
                val now = timeProvider.now()
                val withFingerprints = ReceiptTimestampPolicy.forUpdate(receipt.copy(
                    imagePath = receipt.imagePath,
                    imageHash = fileHash ?: receipt.imageHash,
                    sourceType = ReceiptSourceType.CAMERA.name,
                    documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                    processingStatus = ReceiptProcessingStatus.DUPLICATE_DETECTED.name,
                    textFingerprint = textFingerprint,
                    semanticFingerprint = semanticFingerprint
                ), now).also { it.taxInclusive = taxInclusive }

                val existing = scannedReceiptDao.getById(postOcrDup.existingReceiptId!!)
                if (existing != null) {
                    database.withTransaction {
                        scannedReceiptDao.update(withFingerprints)
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
                    }
                    existing.taxInclusive = taxInclusive
                    return Result.success(existing)
                }
            }

            // 5. Update receipt with lifecycle metadata and fingerprints,
            //    and carry the taxInclusive flag for downstream consumers.
            val now = timeProvider.now()
            val ocrStorageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
            val sanitizedOcrText = RawContentSanitizer.sanitizeRawOcr(receipt.rawOcrText, ocrStorageMode)
            val updated = ReceiptTimestampPolicy.forUpdate(receipt.copy(
                imagePath = receipt.imagePath,
                imageHash = fileHash ?: receipt.imageHash,
                sourceType = ReceiptSourceType.CAMERA.name,
                documentType = ReceiptDocumentType.RETAIL_RECEIPT.name,
                processingStatus = processingStatus,
                textFingerprint = textFingerprint,
                semanticFingerprint = semanticFingerprint,
                rawOcrText = sanitizedOcrText
            ), now).also { it.taxInclusive = taxInclusive }

            // 5+6. Atomically persist the receipt update, all lifecycle events,
            // and optionally the pending review. P3-P1-01: wrap update+events+review
            // in a single transaction so a partial write cannot leave the database
            // in an inconsistent state.
            var createdReviewId: Long = 0
            database.withTransaction {
                scannedReceiptDao.update(updated)

                // 6. Write receipt lifecycle event
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

                // P3-P1-08: Write PARSE_FAILED event when parsing failed
                if (processingStatus == ReceiptProcessingStatus.PARSE_FAILED.name) {
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = updated.id,
                            sourceType = updated.sourceType,
                            documentType = updated.documentType,
                            eventType = "PARSE_FAILED",
                            occurredAt = now,
                            oldStatus = null,
                            newStatus = ReceiptProcessingStatus.PARSE_FAILED.name,
                            actor = "system:coordinator",
                            message = "OCR succeeded but receipt parsing failed",
                            metadata = null,
                            errorDetails = updated.parseFailureReason
                        )
                    )
                }

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

                // P2-15: Write PDF_PARTIAL event when only a subset of pages were processed
                val pagesProcessed = processResult.pagesProcessed
                val totalPages = processResult.totalPages
                if (pagesProcessed != null && totalPages != null && pagesProcessed < totalPages) {
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = updated.id,
                            sourceType = updated.sourceType,
                            documentType = updated.documentType,
                            eventType = "PDF_PARTIAL",
                            occurredAt = now,
                            oldStatus = null,
                            newStatus = updated.processingStatus,
                            actor = "system:coordinator",
                            message = "PDF partially processed: $pagesProcessed of $totalPages pages",
                            metadata = "{\"pagesProcessed\":$pagesProcessed,\"totalPages\":$totalPages}",
                            errorDetails = null
                        )
                    )
                }

                // P3-P1-09 / P3-NEW-01: Create PendingReview only AFTER dedupe
                // passes and the receipt is confirmed non-duplicate. This prevents
                // ghost/actionable reviews for duplicate receipt scans.
                if (options.createReview) {
                    val suggestedMerchant = receipt.parsedMerchant
                        ?: parsed.merchantName
                        ?: if (processingStatus == ReceiptProcessingStatus.PARSE_FAILED.name) "Parsing Failed"
                          else "Unknown Merchant"
                    val safeReviewSnippet = RawContentSanitizer.sanitizedOcrReviewSnippet(
                        processResult.ephemeralRawOcrText ?: sanitizedOcrText,
                        ocrStorageMode
                    )
                    val review = PendingReview(
                        rawNotificationId = null,
                        scannedReceiptId = updated.id,
                        suggestedAmount = parsed.total,
                        suggestedCurrency = updated.currency,
                        suggestedMerchant = suggestedMerchant,
                        suggestedMerchantKey = MerchantKeyGenerator.generate(suggestedMerchant),
                        suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                        suggestedDate = parsed.date,
                        confidence = parsed.confidence,
                        packageName = when (processingStatus) {
                            ReceiptProcessingStatus.PARSE_FAILED.name -> "receipt.scan.error"
                            ReceiptProcessingStatus.OCR_FAILED.name -> "receipt.scan.ocr_failed"
                            else -> "receipt.scan"
                        },
                        notificationTitle = "Scanned Receipt",
                        notificationText = safeReviewSnippet,
                        suggestedCategoryId = receipt.parsedMerchant?.let {
                            hybridClassifier.classify(it, parsed.total ?: 0.0).categoryId.takeIf { id -> id > 0 }
                        }
                    )
                    val reviewId = pendingReviewDao.insert(review)
                    require(reviewId > 0) { "PendingReview insert failed for receiptId=${updated.id}" }

                    val persistedReview = review.copy(id = reviewId)

                    // P3-CUR-01: Use the generated reviewId, NOT review.id (which is 0 after insert)
                    pendingReviewSourceLinkService.linkSourcesForReview(
                        review = persistedReview,
                        reviewId = reviewId,
                        sourceType = ExpenseSource.REVIEW_APPROVAL,
                        correlationId = null,
                        context = PendingReviewSourceContext(
                            stage = "receipt_scan_review",
                            reason = "Receipt scan needs review (after dedupe)",
                            confidence = review.confidence,
                            extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER.name
                        )
                    )

                    createdReviewId = reviewId
                }
            }

            // 7. Plan and run post-save side effects (warranty, categorization, matching, etc.)
            val receiptActions = receiptSideEffectPlanner.planAfterReceiptSaved(
                receipt = updated,
                correlationId = null
            )
            postCommitActionRunner.runBestEffortAfterCommit(
                batch = receiptActions,
                logMessage = "Post-save side effects failed for receipt",
                targetId = updated.id
            )

            Timber.d("Receipt processed via coordinator: id=%d, imagePath=%s", updated.id, updated.imagePath)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "processReceipt failed for %s, falling back to manual record", uri)

            // OCR/parse failed catastrophically — save manual record
            // Use the original URI string as a fallback image reference
            val fallbackCurrency = runCatching {
                currencySettingsRepository.homeCurrency().first()
            }.getOrDefault(FALLBACK_CURRENCY)

            val now = timeProvider.now()
            val manualReceipt = ReceiptTimestampPolicy.forInsert(ScannedReceipt(
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
                imageHash = null
            ), now)

            // P3-CUR-07: Wrap fallback insert + event in a single transaction
            // so that a crash cannot leave a receipt without its lifecycle event.
            val savedId = database.withTransaction {
                val id = scannedReceiptDao.insert(manualReceipt)
                require(id > 0) { "Manual receipt insert failed during fallback: uri=$uri" }

                receiptEventDao.insert(
                    ReceiptEvent(
                        receiptId = id,
                        sourceType = manualReceipt.sourceType,
                        documentType = manualReceipt.documentType,
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
                id
            }

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
        writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.saveEmailReceipt")
        val now = timeProvider.now()
        val ocrStorageMode = privacySettingsRepository.getSettings().rawOcrStorageMode
        val sanitizedOcrText = RawContentSanitizer.sanitizeRawOcr(receipt.rawOcrText, ocrStorageMode)
        val updated = ReceiptTimestampPolicy.forInsert(receipt.copy(
            sourceType = ReceiptSourceType.EMAIL.name,
            documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
            processingStatus = ReceiptProcessingStatus.PARSED.name,
            rawOcrText = sanitizedOcrText
        ), now)
        var id = 0L
        database.withTransaction {
            id = scannedReceiptDao.insert(updated)
            require(id > 0) { "Email receipt insert failed (conflict)" }

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
        }

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
        provider: String,
        correlationId: String? = null
    ): EmailReceiptProcessResult {
        try {
            writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.processEmailReceipt")
        } catch (e: Exception) {
            emitEmailReceiptDiagnostic("validate", "ERROR", "writes_blocked", null, null, correlationId)
            return EmailReceiptProcessResult.Error("Database writes blocked: ${e.message}")
        }

        // Check messageId dedup
        // PRIV-441-09: Use the hash from emailData.messageId (already hashed by ingestion service)
        // for dedup in all modes — never use raw messageId for persistence lookup
        val messageIdHash = emailData.messageId  // already HMAC hash from EmailReceiptIngestionService
        val settings = privacySettingsRepository.getSettings()
        val emailStorageMode = settings.emailReceiptStorageMode
        if (messageIdHash.isNotBlank()) {
            // Look up by hash in all modes — hash is always stored
            val existing = scannedReceiptDao.getBySourceFingerprint(messageIdHash)
            if (existing != null) {
                emitEmailReceiptDiagnostic("dedup", "DUPLICATE", "messageId_hash_duplicate", "ScannedReceipt", existing.id, correlationId)
                return EmailReceiptProcessResult.Duplicate(existing.id)
            }
        }
        // Check fingerprint dedup
        if (fingerprint.isNotBlank()) {
            val existing = emailReceiptDao.getByFingerprint(fingerprint)
            if (existing != null) {
                emitEmailReceiptDiagnostic("dedup", "DUPLICATE", "fingerprint_duplicate", "EmailReceiptSource", existing.receiptId, correlationId)
                return EmailReceiptProcessResult.Duplicate(existing.receiptId)
            }
        }

        // P2-19: Also check via ReceiptDuplicateDetector for text/semantic fingerprint match
        val emailTextFingerprint = if (rawEmailBody.isNotBlank()) {
            duplicateDetector.computeTextFingerprintPublic(rawEmailBody)
        } else null
        val emailSemanticFingerprint = if (emailData.merchant != null && emailData.amount != null && emailData.date != null) {
            duplicateDetector.computeSemanticFingerprintPublic(
                emailData.merchant,
                emailData.amount,
                emailData.date,
                emailData.currency
            )
        } else null
        // PRIV-43B-10: Use messageIdHash for duplicate detector — never raw messageId
        val duplicateResult = duplicateDetector.checkDuplicate(
            imageHash = null,
            textFingerprint = emailTextFingerprint,
            semanticFingerprint = emailSemanticFingerprint,
            externalSourceId = messageIdHash.ifBlank { null }
        )
        if (duplicateResult.isDuplicate && duplicateResult.existingReceiptId != null) {
            emitEmailReceiptDiagnostic("dedup", "DUPLICATE", "duplicate_detector_${duplicateResult.matchType}", "ScannedReceipt", duplicateResult.existingReceiptId, correlationId)
            return EmailReceiptProcessResult.Duplicate(duplicateResult.existingReceiptId)
        }

        val now = timeProvider.now()
        val effectiveOcrText = RawContentSanitizer.sanitizeRawOcr(rawEmailBody, emailStorageMode)
        var savedId = 0L
        val createdExpenseIds = mutableListOf<Long>()
        val linkedExistingExpenseIds = mutableListOf<Long>()
        var receiptPlan: PostCommitActionBatch? = null
        val transactionActionBatches = mutableListOf<PostCommitActionBatch>()

        try {
        database.withTransaction {
            val homeResolution = currencySettingsRepository.resolveHomeCurrency()
            val homeCurrency = homeResolution.currencyOrNull?.code ?: "XXX" // explicit unknown currency as last resort

            val receipt = ReceiptTimestampPolicy.forInsert(ScannedReceipt(
                imagePath = null,
                rawOcrText = effectiveOcrText,
                parsedTotal = emailData.amount,
                parsedMerchant = emailData.merchant,
                parsedDate = emailData.date,
                // PRIV-441-10: Sanitize parsed items by storage mode
                parsedItems = when (emailStorageMode) {
                    RawStorageMode.STORE_RAW -> emailData.items
                    RawStorageMode.STORE_REDACTED -> emailData.items?.let { "[REDACTED_ITEMS]" }
                    RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
                },
                parsedTaxAmount = null,
                currency = emailData.currency ?: homeCurrency,
                confidence = 0.7f,
                sourceType = ReceiptSourceType.EMAIL.name,
                documentType = ReceiptDocumentType.EMAIL_RECEIPT.name,
                processingStatus = if (emailData.merchant != null) ReceiptProcessingStatus.PARSED.name
                                   else ReceiptProcessingStatus.CAPTURED.name,
                // PRIV-441-09: Use messageIdHash as sourceFingerprint — never empty when hash available
                sourceFingerprint = messageIdHash.ifBlank { fingerprint },
                textFingerprint = emailTextFingerprint,
                semanticFingerprint = emailSemanticFingerprint
            ), now)
            savedId = scannedReceiptDao.insert(receipt)
            require(savedId > 0) { "Email receipt insert failed (conflict): sender=$sender" }

            val sanitizedSender = RawContentSanitizer.sanitizeEmailSender(sender, emailStorageMode)
            val sanitizedSubject = RawContentSanitizer.sanitizeEmailSubject(subject, emailStorageMode)

            val emailSource = EmailReceiptSource(
                receiptId = savedId,
                // PRIV-6825-02/03: nullable fields — null in restricted modes
                emailSender = sanitizedSender,
                emailSubject = sanitizedSubject,
                // PRIV-6825-03: raw messageId only in STORE_RAW; hash in all other modes
                emailMessageId = when (emailStorageMode) {
                    RawStorageMode.STORE_RAW -> messageId
                    else -> null
                },
                emailMessageIdHash = messageIdHash.ifBlank { null },
                contentFingerprintHash = fingerprint.ifBlank { null },
                parsedAt = now,
                provider = provider,
                confidence = 1.0,
                fingerprint = fingerprint
            )
            val sourceId = emailReceiptDao.insertOrIgnore(emailSource)
            if (sourceId == -1L) {
                // P3-CUR-03 / P3-NEW-03: Email source insert conflicted.
                // Resolve by fingerprint, raw messageId, and messageIdHash
                // in descending order of reliability. If nothing matches,
                // throw to rollback the transaction — we must never continue
                // with sourceId <= 0.
                val existing = when {
                    fingerprint.isNotBlank() -> emailReceiptDao.getByFingerprint(fingerprint)
                    else -> null
                } ?: emailReceiptDao.getByMessageId(messageId)
                  ?: messageIdHash.takeIf { it.isNotBlank() }?.let { emailReceiptDao.getByMessageIdHash(it) }
                  ?: fingerprint.takeIf { it.isNotBlank() }?.let { emailReceiptDao.getByContentFingerprintHash(it) }

                if (existing != null) {
                    throw DuplicateEmailReceiptException(
                        existingReceiptId = existing.receiptId,
                        reason = "email_source_conflict"
                    )
                }

                // Unresolved conflict — throw to rollback everything
                throw IllegalStateException(
                    "EmailReceiptSource insert conflict could not be resolved " +
                    "(fingerprint=$fingerprint, messageIdHash=$messageIdHash)"
                )
            }

            // PR4: Write source link for email receipt provenance
            if (sourceId > 0) {
                val emailToReceiptPayload = ReceiptSourceLinkPayloadFactory.forEmailReceiptToScannedReceipt(
                    emailReceiptSourceId = sourceId,
                    scannedReceiptId = savedId,
                    provider = provider,
                    messageIdHash = messageIdHash.ifBlank { null },
                    contentFingerprintHash = fingerprint.ifBlank { null }
                )
                sourceLinkWriter.linkTarget(
                    targetType = TargetEntityType.SCANNED_RECEIPT,
                    targetId = savedId,
                    payload = emailToReceiptPayload,
                    correlationId = correlationId
                )
            }

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

            // Create expense (and link) first so the planner below can see the linked state.
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
                    notes = "Email receipt from ${provider.ifBlank { "unknown" }}",
                    scannedReceiptId = savedId,
                    emailReceiptSourceId = sourceId,
                    deduplicationMode = DeduplicationMode.STANDARD,
                    correlationId = correlationId  // PRIV-441-11: propagate email correlation
                )
                val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
                when (mutation.value) {
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created -> {
                        val created = mutation.value as com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created
                        createdExpenseIds.add(created.expenseId)
                        transactionActionBatches.add(mutation.postCommitActions)
                        val linkResult = receiptLinkService.linkReceiptToExpense(
                            savedId, created.expenseId, "EMAIL_RECEIPT",
                            source = ExpenseSource.EMAIL_RECEIPT.name,
                            writeSourceLink = false
                        )
                        if (linkResult.isFailure) {
                            throw IllegalStateException("Link failed: ${linkResult.exceptionOrNull()?.message}", linkResult.exceptionOrNull())
                        }
                    }
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.DuplicateSkipped -> {
                        val dupSkipped = mutation.value as com.yourname.expensetracker.domain.transaction.CreateExpenseResult.DuplicateSkipped
                        Timber.d("Email receipt %d matched existing expense %d", savedId, dupSkipped.existingExpenseId)
                        linkedExistingExpenseIds.add(dupSkipped.existingExpenseId)
                        // No transaction action batch collected for duplicates — only reporting/linking
                        val linkResult = receiptLinkService.linkReceiptToExpense(
                            savedId, dupSkipped.existingExpenseId, "EMAIL_RECEIPT",
                            source = ExpenseSource.EMAIL_RECEIPT.name,
                            writeSourceLink = false
                        )
                        if (linkResult.isFailure) {
                            throw IllegalStateException("Link failed: ${linkResult.exceptionOrNull()?.message}", linkResult.exceptionOrNull())
                        }
                    }
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.ValidationFailed -> {
                        val vf = mutation.value as com.yourname.expensetracker.domain.transaction.CreateExpenseResult.ValidationFailed
                        Timber.w("Email receipt %d validation failed: %s", savedId, vf.errors)
                    }
                    else -> {}
                }
            }

            // Plan receipt side-effects AFTER expense creation and linking so the
            // planner knows which expenses are already linked (prevents double-dispatch).
            val allLinkedExpenseIds = (createdExpenseIds + linkedExistingExpenseIds).toSet()
            val freshReceipt = scannedReceiptDao.getById(savedId)
            if (freshReceipt != null) {
                receiptPlan = receiptSideEffectPlanner.planAfterReceiptSaved(
                    receipt = freshReceipt,
                    correlationId = correlationId,
                    linkedExpenseIds = allLinkedExpenseIds
                )
            }
        }

        } catch (e: DuplicateEmailReceiptException) {
            // P3-REG-02: Exception-based rollback — the scanned receipt insert
            // was correctly rolled back by the transaction. Return Duplicate.
            emitEmailReceiptDiagnostic("dedup", "DUPLICATE", "in_transaction_duplicate_${e.reason}", "EmailReceiptSource", e.existingReceiptId, correlationId)
            return EmailReceiptProcessResult.Duplicate(e.existingReceiptId)
        }

        // Single post-commit dispatch: combine receipt actions + transaction actions for created expenses only
        val receiptPlanToRun = receiptPlan ?: PostCommitActionBatch.empty(correlationId ?: "")
        val combinedBatch = if (transactionActionBatches.isNotEmpty()) {
            transactionActionBatches.fold(receiptPlanToRun) { acc, batch -> acc + batch }
        } else {
            receiptPlanToRun
        }

        postCommitActionRunner.runBestEffortAfterCommit(
            batch = combinedBatch,
            logMessage = "Email receipt post-commit side effects failed",
            targetId = savedId
        )

        return EmailReceiptProcessResult.Success(
            receiptId = savedId,
            createdExpenseIds = createdExpenseIds,
            linkedExistingExpenseIds = linkedExistingExpenseIds,
            expenseIds = createdExpenseIds + linkedExistingExpenseIds
        )
    }

    private suspend fun emitEmailReceiptDiagnostic(
        stage: String,
        outcome: String,
        reason: String,
        entityType: String? = null,
        entityId: Long? = null,
        correlationId: String? = null
    ) {
        try {
            diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                stage = stage,
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.valueOf(
                    outcome.uppercase().replace(" ", "_").let { o ->
                        com.yourname.expensetracker.domain.diagnostics.EventOutcome.entries
                            .firstOrNull { it.name == o }?.name ?: "FAILED_FINAL"
                    }
                ),
                correlationId = correlationId ?: "",
                entityType = entityType,
                entityId = entityId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("reason", reason.take(128))
                    .build()
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to write email receipt diagnostic event")
        }
    }

    /**
     * Writes a receipt intake diagnostic event (INPUT_RECEIVED, VALIDATION_PASSED,
     * VALIDATION_FAILED). Uses the existing diagnostic event infrastructure
     * so validation failures can be recorded without requiring a receipt row.
     * P3-NEW-08 / P3-REG-002: Correct EventOutcome mapping, no raw URI.
     */
    private suspend fun emitIntakeDiagnostic(
        stage: String,
        outcome: com.yourname.expensetracker.domain.diagnostics.EventOutcome,
        correlationId: String,
        eventType: String,
        message: String? = null,
        mimeType: String? = null,
        fileSizeBytes: Long? = null,
        metadata: Map<String, String>? = null
    ) {
        try {
            val md = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .put("stage", stage.take(64))
                .put("eventType", eventType.take(64))
            mimeType?.let { md.put("mimeType", it.take(128)) }
            fileSizeBytes?.let { md.put("fileSizeBytes", it.toString()) }
            message?.let { md.put("message", it.take(256)) }
            metadata?.forEach { (k, v) -> md.put(k, v.take(256)) }
            diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.RECEIPT,
                stage = stage,
                outcome = outcome,
                correlationId = correlationId,
                entityType = null,
                entityId = null,
                metadata = md.build()
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to write receipt intake diagnostic: %s", eventType)
        }
    }

    suspend fun deleteReceipt(receiptId: Long): Result<Unit> {
        try {
            writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.deleteReceipt")
        } catch (e: Exception) {
            return Result.failure(e)
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
            receipt.imagePath?.let { path ->
                try {
                    assetStore.deleteAsset(path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // P3-CUR-08: Write durable event when asset deletion fails
                    Timber.e(e, "Failed to delete asset for receipt %d: %s", receiptId, path)
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = receiptId,
                            sourceType = receipt.sourceType,
                            documentType = receipt.documentType,
                            eventType = "ASSET_DELETE_FAILED",
                            occurredAt = timeProvider.now(),
                            oldStatus = "DELETED",
                            newStatus = null,
                            actor = "system:coordinator",
                            message = "Failed to delete asset file: $path",
                            metadata = null,
                            errorDetails = e.message?.take(500)
                        )
                    )
                }
            }

            Timber.d("Receipt deleted: id=%d, assetPath=%s", receiptId, receipt.imagePath)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
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
        message = "Permanently disabled. Use createExpenseAndLinkReceipt(request).",
        level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith(
            expression = "createExpenseAndLinkReceipt(request)",
            imports = []
        )
    )
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "XXX",
        categoryId: Long?,
        date: Long,
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): DomainResult<Long> {
        return DomainResult.Error(
            message = "createExpenseFromReceipt is permanently disabled. Use createExpenseAndLinkReceipt()."
        )
    }

    /**
     * S7-F583-001: Atomic create-expense-and-link-receipt operation.
     *
     * Both the expense creation and the receipt link are performed inside a single
     * database transaction. If the link fails, the expense is rolled back — no orphan
     * expenses can be created.
     *
     * @return [Result.success] with the created expense ID, or [Result.failure] with
     *         a descriptive exception. Callers should NOT fall back to a two-step path.
     */
    suspend fun createExpenseAndLinkReceipt(
        request: com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
    ): Result<Long> {
        try {
            writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt")
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val receiptId = request.scannedReceiptId
            ?: return Result.failure(IllegalArgumentException("scannedReceiptId is required for atomic create+link"))

        // S7-66F-001: Use createExpenseDbOnlyV2 so side effects are NOT dispatched inside the transaction.
        // If linking fails and the transaction rolls back, no side effects will have run.
        val txResult: Pair<Result<Long>, PostCommitActionBatch> = try {
            database.withTransaction {
                val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
                when (val result = mutation.value) {
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created -> {
                        val linkResult = receiptLinkService.linkReceiptToExpense(
                            receiptId = receiptId,
                            expenseId = result.expenseId,
                            linkType = "DIRECT_SAVE",
                            source = com.yourname.expensetracker.domain.transaction.ExpenseSource.RECEIPT_SCAN.name,
                            writeSourceLink = false
                        )
                        if (linkResult.isFailure) {
                            throw IllegalStateException(
                                "Receipt link failed — rolling back expense: ${linkResult.exceptionOrNull()?.message}",
                                linkResult.exceptionOrNull()
                            )
                        }
                        Pair(Result.success(result.expenseId), mutation.postCommitActions)
                    }
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.DuplicateSkipped ->
                        Pair(Result.failure(IllegalStateException("Duplicate transaction detected")), PostCommitActionBatch.empty("receipt_link_dup"))
                    is com.yourname.expensetracker.domain.transaction.CreateExpenseResult.ValidationFailed ->
                        Pair(Result.failure(IllegalArgumentException("Validation failed: ${result.errors.joinToString()}")), PostCommitActionBatch.empty("receipt_link_vf"))
                    else ->
                        Pair(Result.failure(IllegalStateException("Expense creation failed")), PostCommitActionBatch.empty("receipt_link_err"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S7-66F-005: never swallow cancellation
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val (expenseIdResult, transactionActions) = txResult

        // S7-66F-001: Dispatch side effects only after successful commit
        expenseIdResult.onSuccess { expenseId ->
            val receiptActions = receiptSideEffectPlanner.planAfterReceiptLinked(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "DIRECT_SAVE",
                correlationId = null
            )
            val combined = transactionActions + receiptActions
            postCommitActionRunner.runBestEffortAfterCommit(
                batch = combined,
                logMessage = "Post-creation side effects failed for receipt expense",
                targetId = expenseId
            )
        }

        return expenseIdResult
    }

    /**
     * P3-REG-02: Thrown inside [processEmailReceipt]'s transaction to force
     * rollback when the email source insert conflicts with an existing row.
     * Using an exception ensures the newly inserted [ScannedReceipt] is rolled
     * back — unlike `return@withTransaction`, which would commit the orphan row.
     */
    private class DuplicateEmailReceiptException(
        val existingReceiptId: Long,
        val reason: String
    ) : RuntimeException("Duplicate email receipt: $reason (existingId=$existingReceiptId)")
}
