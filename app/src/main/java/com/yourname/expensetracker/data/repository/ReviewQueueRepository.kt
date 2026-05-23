package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkPromoter
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import org.json.JSONObject

@Singleton
class ReviewQueueRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val pendingReviewDao: PendingReviewDao,
    private val rawNotificationDao: RawNotificationDao,
    private val expenseDao: ExpenseDao,
    private val sourceStatsDao: SourceStatsDao,
    private val receiptLinkService: ReceiptLinkService,
    private val userCorrectionDao: UserCorrectionDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val parserRegistry: AppParserRegistry,
    private val timeProvider: TimeProvider,
    private val confidenceRouter: ConfidenceRouter,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val pendingReviewSourceLinkService: PendingReviewSourceLinkService,
    private val pendingReviewSourceLinkPromoter: PendingReviewSourceLinkPromoter,
    private val transactionEventDao: TransactionEventDao,
    private val postCommitActionRunner: PostCommitActionRunner
) {
    private companion object {
        private const val ERROR_REVIEW_NOT_FOUND = "REVIEW_NOT_FOUND"
        private const val ERROR_AMOUNT_EXCEEDS_LIMIT = "AMOUNT_EXCEEDS_LIMIT"
        private const val ERROR_REVIEW_ALREADY_PROCESSED = "REVIEW_ALREADY_PROCESSED"
    }


    fun getAllPendingReviews(): Flow<List<PendingReviewWithReceipt>> =
        pendingReviewDao.getPendingUncappedFlow()

    fun getPendingReviewsBatch(limit: Int): Flow<List<PendingReviewWithReceipt>> =
        pendingReviewDao.getPendingFlow(limit)

    fun getPendingReviewCount(): Flow<Int> =
        pendingReviewDao.getPendingCountFlow()

    private suspend fun hasCanonicalApprovalDuplicate(expense: Expense): Boolean {
        return expenseDao.isDuplicateCurrencyAware(
            amount = expense.amount,
            merchant = expense.merchant,
            date = expense.date,
            currency = expense.currency,
            transactionType = expense.transactionType.name,
            merchantKey = expense.merchantKey,
            dedupeKey = expense.dedupeKey
        )
    }

    suspend fun getReviewById(reviewId: Long): PendingReview? =
        pendingReviewDao.getById(reviewId)

    suspend fun getPendingReviewWithReceiptById(reviewId: Long): PendingReviewWithReceipt? =
        pendingReviewDao.getPendingWithReceiptById(reviewId)

    /** Recover reviews stuck in PROCESSING state after process death mid-approval. */
    suspend fun recoverStuckReviews(): Int = pendingReviewDao.recoverStuckProcessing()

    private data class ReviewApprovalTxOutcome(
        val type: ReviewApprovalTxType,
        val expenseId: Long? = null,
        val transactionActions: PostCommitActionBatch = PostCommitActionBatch.empty("")
    )

    private enum class ReviewApprovalTxType { CREATED, DUPLICATE, ALREADY_PROCESSED }

    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalCurrency: String? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null,
        finalDate: Long? = null,
        finalType: TransactionType? = null,
        finalTransferDirection: TransferDirection? = null,
        finalTransferAccountName: String? = null,
        locationCleared: Boolean = false,
        finalLatitude: Double? = null,
        finalLongitude: Double? = null,
        finalAddress: String? = null,
        finalPlaceId: String? = null
    ): Result<Long> {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.approveReview")
        val review = pendingReviewDao.getById(reviewId)
            ?: return Result.Error(message = ERROR_REVIEW_NOT_FOUND)

    // ── Synthetic placeholder blocking ──────────────────────────────────
    // Block approval when the parser could not extract an amount (suggestedAmount is null)
    // and the user has not yet provided a real override.
    if (review.suggestedAmount == null && finalAmount == null) {
        return Result.Error(message = "Cannot approve review with synthetic fallback amount. Please edit the amount first.")
    }
    val amount: Double = finalAmount ?: review.suggestedAmount!!
    val merchant: String = finalMerchant ?: review.suggestedMerchant
    if (review.suggestedMerchant == "Unknown" && finalMerchant == null) {
        return Result.Error(message = "Cannot approve review with unknown merchant. Please edit the merchant first.")
    }

    // The coordinator owns merchantKey and dedupeKey generation.
    // Review approval passes the resolved merchant display value directly
    // and the resolved currency. No double-normalization before create.
    val resolvedCurrency: String = run {
        val resolved = finalCurrency?.takeIf { it.isNotBlank() }
            ?: review.suggestedCurrency?.takeIf { it.isNotBlank() }
        if (resolved.isNullOrBlank()) {
            return Result.Error(message = "Currency is required. Please edit the review and select a currency.")
        }
        resolved
    }
    val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId

    if (amount > 1000000.0) {
        return Result.Error(message = ERROR_AMOUNT_EXCEEDS_LIMIT)
    }

        val type: TransactionType = finalType ?: try {
            TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            TransactionType.PURCHASE
        }

        val notification = review.rawNotificationId?.let { rawNotificationDao.getById(it) }
        val transactionDate: Long = finalDate ?: review.suggestedDate ?: notification?.timestamp ?: review.createdAt
        val transferMetadataAllowed =
            type == TransactionType.TRANSFER || type == TransactionType.DEPOSIT
        val transferDirection = if (transferMetadataAllowed) {
            // S6-004: Prefer user-edited direction, fall back to review suggestion
            // S6-D5-010: Case-insensitive lookup — valueOf is exact/case-sensitive
            finalTransferDirection ?: review.suggestedDirection?.let { raw ->
                TransferDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            }
        } else {
            null
        }
        val transferAccountName = if (transferMetadataAllowed) {
            finalTransferAccountName ?: review.suggestedAccountName
        } else {
            null
        }

        val expense = Expense(
            amount = amount,
            currency = resolvedCurrency,
            merchant = merchant,
            merchantKey = MerchantKeyGenerator.generate(merchant),
            transactionType = type,
            date = transactionDate,
            rawNotificationId = review.rawNotificationId,
            categoryId = categoryId,
            createdAt = timeProvider.now(),
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = review.scannedReceiptId != null,
            notes = if (review.scannedReceiptId != null) "Scanned from receipt" else null,
            dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount, merchant, transactionDate, resolvedCurrency, type
            ),
            transferDirection = transferDirection,
            transferAccountName = transferAccountName,
            // S6-005: locationCleared=true means explicit clear — null lat/lon means CLEAR, not unchanged
            latitude = if (locationCleared) null else finalLatitude ?: review.suggestedLatitude,
            longitude = if (locationCleared) null else finalLongitude ?: review.suggestedLongitude,
            locationSource = when {
                locationCleared -> AppConfig.Location.SOURCE_UNKNOWN
                finalLatitude != null && finalLongitude != null -> AppConfig.Location.SOURCE_USER_MANUAL
                review.suggestedLatitude != null && review.suggestedLongitude != null -> AppConfig.Location.SOURCE_DEVICE_GPS
                else -> AppConfig.Location.SOURCE_UNKNOWN
            },
            placeId = if (locationCleared) null else finalPlaceId,
            resolvedAddress = if (locationCleared) null else finalAddress
        )

        val txOutcome = try {
            database.withTransaction {
                val rowsUpdated = pendingReviewDao.transitionStatus(
                    id = reviewId,
                    expectedStatus = PendingReviewStatus.PENDING,
                    newStatus = PendingReviewStatus.PROCESSING
                )
                if (rowsUpdated == 0) {
                    return@withTransaction ReviewApprovalTxOutcome(ReviewApprovalTxType.ALREADY_PROCESSED)
                }

                // ── Delegate to TransactionLifecycleCoordinator ─────────────────────────
                // The coordinator handles key generation, dedup check, atomic insert,
                // lifecycle event logging, and source-link writing.
                val request = CreateExpenseRequest(
                    merchant = expense.merchant,
                    amount = expense.amount,
                    currency = expense.currency,
                    date = expense.date,
                    transactionType = expense.transactionType,
                    source = ExpenseSource.REVIEW_APPROVAL,
                    categoryId = expense.categoryId,
                    notes = expense.notes,
                    paymentMethod = expense.paymentMethod,
                    isManualEntry = expense.isManualEntry,
                    transferDirection = expense.transferDirection,
                    transferAccountName = expense.transferAccountName,
                    latitude = expense.latitude,
                    longitude = expense.longitude,
                    locationSource = expense.locationSource,
                    placeId = expense.placeId,
                    resolvedAddress = expense.resolvedAddress,
                    rawNotificationId = review.rawNotificationId,
                    pendingReviewId = reviewId,
                    scannedReceiptId = review.scannedReceiptId,
                    skipDeduplication = false
                )

                val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
                when (val result = mutation.value) {
                    is CreateExpenseResult.Created -> {
                        val id = result.expenseId

                        // PR3: Promote pending-review source links to expense
                        val promotion = pendingReviewSourceLinkPromoter.promotePendingReviewLinksToExpense(
                            pendingReviewId = reviewId,
                            expenseId = id,
                            correlationId = null,
                            source = ExpenseSource.REVIEW_APPROVAL
                        )
                        if (promotion.hasFatalFailure) {
                            throw IllegalStateException(
                                "Fatal failure promoting source links for reviewId=$reviewId: ${promotion.failures.joinToString(", ")}"
                            )
                        }

                        // Write SOURCE_LINKED event for promotion
                        if (promotion.inserted > 0) {
                            transactionEventDao.insert(
                                com.yourname.expensetracker.data.database.entity.TransactionEvent(
                                    expenseId = id,
                                    eventType = LifecycleEventType.SOURCE_LINKED.name,
                                    source = ExpenseSource.REVIEW_APPROVAL.name,
                                    actor = null,
                                    occurredAt = timeProvider.now(),
                                    dedupeKey = null,
                                    duplicateExpenseId = null,
                                    beforeSnapshot = null,
                                    afterSnapshot = null,
                                    metadata = JSONObject().apply {
                                        put("operation", "PENDING_REVIEW_SOURCE_PROMOTION")
                                        put("pendingReviewId", reviewId)
                                        put("attempted", promotion.attempted)
                                        put("inserted", promotion.inserted)
                                        put("alreadyExists", promotion.alreadyExists)
                                    }.toString(),
                                    reason = "Source links promoted from pending review",
                                    correlationId = null
                                )
                            )
                        }

                        review.rawNotificationId?.let { rawNotificationDao.markRelevance(it, true) }
                        sourceStatsDao.incrementAccepted(review.packageName)
                        sourceStatsDao.decrementPending(review.packageName)
                        review.scannedReceiptId?.let { receiptId ->
                            val linkResult = receiptLinkService.linkReceiptToExpense(
                                receiptId = receiptId,
                                expenseId = id,
                                linkType = "REVIEW_APPROVAL",
                                source = ExpenseSource.REVIEW_APPROVAL.name
                            )
                            if (linkResult.isFailure) {
                                throw IllegalStateException(
                                    "Failed to link receipt $receiptId to expense $id during review approval",
                                    linkResult.exceptionOrNull()
                                )
                            }
                        }
                        pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED)

                        val correction = UserCorrection(
                            packageName = review.packageName,
                            originalMerchant = review.suggestedMerchant,
                            correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                                finalMerchant else null,
                            originalAmount = review.suggestedAmount ?: 0.0,
                            correctedAmount = if (finalAmount != null && finalAmount != (review.suggestedAmount ?: 0.0))
                                finalAmount else null,
                            originalCategoryId = review.suggestedCategoryId,
                            correctedCategoryId = if (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)
                                finalCategoryId else null,
                            originalType = review.suggestedType,
                            correctedType = if (finalType != null && finalType.name != review.suggestedType)
                                finalType.name else null,
                            wasRejected = false,
                            wasApproved = true,
                            notificationTitle = review.notificationTitle,
                            notificationText = review.notificationText
                        )
                        userCorrectionDao.insert(correction)
                        ReviewApprovalTxOutcome(ReviewApprovalTxType.CREATED, id, mutation.postCommitActions)
                    }
                    is CreateExpenseResult.DuplicateSkipped -> {
                        sourceStatsDao.incrementDuplicate(review.packageName)
                        sourceStatsDao.decrementPending(review.packageName)
                        pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
                        ReviewApprovalTxOutcome(ReviewApprovalTxType.DUPLICATE)
                    }
                    is CreateExpenseResult.InsertConflict -> {
                        sourceStatsDao.incrementDuplicate(review.packageName)
                        sourceStatsDao.decrementPending(review.packageName)
                        pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
                        ReviewApprovalTxOutcome(ReviewApprovalTxType.DUPLICATE)
                    }
                    is CreateExpenseResult.ValidationFailed -> {
                        // S6-001: Throw inside transaction — rolls back PROCESSING status to PENDING
                        throw IllegalArgumentException("Validation failed: ${result.errors.joinToString(", ")}")
                    }
                    is CreateExpenseResult.Error -> throw result.exception
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S6-D5-001: never swallow cancellation
        } catch (e: Exception) {
            // S6-D5-001/002: Thrown exceptions (ValidationFailed, link failure) roll back the
            // transaction and surface as Result.Error — review stays PENDING
            Timber.e(e, "Review approval failed for reviewId=$reviewId")
            return Result.Error(message = e.message ?: "Approval failed")
        }

        when (txOutcome.type) {
            ReviewApprovalTxType.ALREADY_PROCESSED ->
                return Result.Error(message = ERROR_REVIEW_ALREADY_PROCESSED)

            ReviewApprovalTxType.DUPLICATE -> {
                val fullText = listOfNotNull(
                    review.notificationTitle,
                    review.notificationText
                ).joinToString(" ")
                runPostCommitSafely(
                    action = "classifier training after duplicate review approval (reviewId=$reviewId, package=${review.packageName})"
                ) {
                    classifier.train(fullText, isTransaction = true)
                }

                runPostCommitSafely(
                    action = "source stats cache invalidation after duplicate review approval (reviewId=$reviewId, package=${review.packageName})"
                ) {
                    // AIML-13: Use comprehensive invalidation after user action
                    confidenceRouter.invalidateAfterUserAction(review.packageName, review.suggestedMerchant)
                }
                return Result.Duplicate
            }

            ReviewApprovalTxType.CREATED -> {
                // ── Deferred lifecycle side effects (now safely post-commit) ──────────
                postCommitActionRunner.run(txOutcome.transactionActions)

                // ── Source-specific post-commit side effects ─────────────────────────
                runPostCommitSafely(
                    action = "classifier retraining after review approval (reviewId=$reviewId)"
                ) {
                    classifier.retrainFromCorrections()
                }

                if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
                    runPostCommitSafely(
                        action = "merchant alias learning after review approval (reviewId=$reviewId, from=${review.suggestedMerchant}, to=$finalMerchant)"
                    ) {
                        merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
                    }
                }

                runPostCommitSafely(
                    action = "source stats cache invalidation after review approval (reviewId=$reviewId, package=${review.packageName})"
                ) {
                    // AIML-13: Use comprehensive invalidation after user action
                    confidenceRouter.invalidateAfterUserAction(review.packageName, review.suggestedMerchant)
                }
                return Result.Success(txOutcome.expenseId!!)
            }
        }
    }

    suspend fun rejectReview(reviewId: Long) {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.rejectReview")
        val review = pendingReviewDao.getById(reviewId) ?: return

        val rejected = database.withTransaction {
            val rowsUpdated = pendingReviewDao.transitionStatus(
                id = reviewId,
                expectedStatus = PendingReviewStatus.PENDING,
                newStatus = PendingReviewStatus.REJECTED
            )
            if (rowsUpdated == 0) {
                return@withTransaction false
            }

            review.rawNotificationId?.let { id -> rawNotificationDao.markRelevance(id, false) }
            sourceStatsDao.incrementRejected(review.packageName)
            sourceStatsDao.decrementPending(review.packageName)

            val correction = UserCorrection(
                packageName = review.packageName,
                originalMerchant = review.suggestedMerchant,
                correctedMerchant = null,
                originalAmount = review.suggestedAmount ?: 0.0,
                correctedAmount = null,
                originalCategoryId = review.suggestedCategoryId,
                correctedCategoryId = null,
                originalType = review.suggestedType,
                correctedType = null,
                wasRejected = true,
                wasApproved = false,
                notificationTitle = review.notificationTitle,
                notificationText = review.notificationText
            )
            userCorrectionDao.insert(correction)
            true
        }

        if (!rejected) return

        runPostCommitSafely(
            action = "classifier retraining after review rejection (reviewId=$reviewId)"
        ) {
            classifier.retrainFromCorrections()
        }

        runPostCommitSafely(
            action = "source stats cache invalidation after review rejection (reviewId=$reviewId, package=${review.packageName})"
        ) {
            // AIML-13: Use comprehensive invalidation after user action
            confidenceRouter.invalidateAfterUserAction(review.packageName, review.suggestedMerchant)
        }
    }

    /**
     * Approves all pending reviews and returns per-review results.
     *
     * @return List of (reviewId, Result) pairs indicating success or failure for each review.
     */
    suspend fun approveAllReview(): List<Pair<Long, Result<Long>>> {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.approveAllReview")
        val pendingReviews = pendingReviewDao.getPendingUncapped()
        return pendingReviews.map { review ->
            val result = try {
                approveReview(review.review.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to approve review ${review.review.id}")
                Result.Error(exception = e)
            }
            review.review.id to result
        }
    }

    suspend fun rejectAllReviews() {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.rejectAllReviews")
        val pendingReviews = pendingReviewDao.getPendingUncapped()
        for (review in pendingReviews) {
            try {
                rejectReview(review.review.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to reject review ${review.review.id}")
            }
        }
    }

    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.markAsRelevant")
        val notification = rawNotificationDao.getById(id) ?: return

        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        val parsed = if (isRelevant) {
            parserRegistry.parse(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                subText = notification.subText,
                packageName = notification.packageName
            )
        } else {
            null
        }

        val expense = parsed?.let {
            val lookupResult = merchantNormalizer.normalize(it.merchant)
            val correctedMerchant = lookupResult.canonical.normalizedName

            val classification = hybridClassifier.classify(
                merchantName = correctedMerchant,
                amount = it.amount,
                notificationTitle = notification.title,
                notificationText = notification.text,
                packageName = notification.packageName
            )
            val categoryId = classification.categoryId.takeIf { category -> category > 0 }

            val recoveredType = it.type.toDbTransactionType()
            Expense(
                amount = it.amount,
                currency = it.currency,
                merchant = correctedMerchant,
                merchantKey = MerchantKeyGenerator.generate(correctedMerchant),
                transactionType = recoveredType,
                date = notification.timestamp,
                rawNotificationId = id,
                categoryId = categoryId,
                createdAt = timeProvider.now(),
                paymentMethod = PaymentMethod.CARD,
                isManualEntry = false,
                notes = "Manually recovered from debug log",
                // Use type-aware key so that PURCHASE vs DEPOSIT/TRANSFER rows
                // never collide on the persisted unique dedupeKey index (ISSUE-8 fix).
                dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                    it.amount, correctedMerchant, notification.timestamp, it.currency, recoveredType
                )
            )
        }

        // ── Placeholder PendingReview (UI-only, never becomes a real expense) ──
        // When the parser succeeded partially but no structured Expense could be
        // built (e.g. merchant/amount missing), we create a review with sentinel
        // values.  These are placeholders that must be edited by the user before
        // approval — approveReview() blocks approval unless overrides are given.
        // The coordinator also rejects any create request that carries these
        // sentinel values.
        val pendingReview = if (isRelevant && parsed == null) {
            PendingReview(
                rawNotificationId = id,
                suggestedAmount = null,
                suggestedCurrency = "",  // S6-D5-003: empty — user must select currency before approval
                suggestedMerchant = "Unknown",
                suggestedMerchantKey = MerchantKeyGenerator.generate("Unknown"),
                suggestedType = TransactionType.PURCHASE.name,
                suggestedCategoryId = null,
                confidence = 0.0f,
                packageName = notification.packageName,
                notificationTitle = notification.title,
                notificationText = notification.text ?: notification.bigText,
                suggestedDate = notification.timestamp,
                extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER
            )
        } else {
            null
        }

        data class MarkAsRelevantOutcome(
            val shouldTrainAsTransaction: Boolean,
            val shouldCheckBudgets: Boolean,
            val createdExpenseId: Long? = null,
            val transactionActions: PostCommitActionBatch = PostCommitActionBatch.empty("")
        )

        val outcome = database.withTransaction {
            rawNotificationDao.markRelevance(id, isRelevant)

            val correction = UserCorrection(
                packageName = notification.packageName,
                originalMerchant = "Manual",
                correctedMerchant = null,
                originalAmount = 0.0,
                correctedAmount = null,
                originalCategoryId = null,
                correctedCategoryId = null,
                originalType = null,
                correctedType = null,
                wasRejected = !isRelevant,
                wasApproved = isRelevant,
                notificationTitle = notification.title,
                notificationText = notification.text ?: notification.bigText
            )

            when {
                expense != null -> {
                    val request = CreateExpenseRequest(
                        merchant = expense.merchant,
                        amount = expense.amount,
                        currency = expense.currency,
                        date = expense.date,
                        transactionType = expense.transactionType,
                        source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT,
                        categoryId = expense.categoryId,
                        rawNotificationId = expense.rawNotificationId,
                        paymentMethod = expense.paymentMethod,
                        notes = expense.notes,
                        isManualEntry = false
                    )
                    val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
                    if (mutation.value is CreateExpenseResult.Created) {
                        val expenseId = (mutation.value as CreateExpenseResult.Created).expenseId
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        userCorrectionDao.insert(correction)
                        MarkAsRelevantOutcome(
                            shouldTrainAsTransaction = true,
                            shouldCheckBudgets = true,
                            createdExpenseId = expenseId,
                            transactionActions = mutation.postCommitActions
                        )
                    } else {
                        sourceStatsDao.incrementDuplicate(notification.packageName)
                        userCorrectionDao.insert(correction)
                        MarkAsRelevantOutcome(
                            shouldTrainAsTransaction = true,
                            shouldCheckBudgets = false
                        )
                    }
                }

                pendingReview != null -> {
                    val existing = pendingReview.rawNotificationId?.let { pendingReviewDao.getByRawId(it) }
                    val placeholderReviewId = pendingReviewDao.upsertByRawNotificationId(pendingReview)
                    // PR3: Write source links for placeholder review provenance
                    pendingReviewSourceLinkService.linkSourcesForReview(
                        review = pendingReview,
                        reviewId = placeholderReviewId,
                        sourceType = ExpenseSource.REVIEW_APPROVAL,
                        correlationId = null,
                        context = com.yourname.expensetracker.domain.provenance.PendingReviewSourceContext(
                            stage = "manual_mark_relevant_pending_review",
                            reason = "manual_recovery_placeholder",
                            extractionState = pendingReview.extractionState.name
                        )
                    )
                    if (existing == null) {
                        sourceStatsDao.incrementPending(notification.packageName)
                    }
                    userCorrectionDao.insert(correction)
                    MarkAsRelevantOutcome(
                        shouldTrainAsTransaction = false,
                        shouldCheckBudgets = false
                    )
                }

                else -> {
                    userCorrectionDao.insert(correction)
                    MarkAsRelevantOutcome(
                        shouldTrainAsTransaction = false,
                        shouldCheckBudgets = false
                    )
                }
            }
        }

        if (outcome.createdExpenseId != null) {
            runPostCommitSafely(
                action = "transaction side effects after markAsRelevant (notificationId=$id, expenseId=${outcome.createdExpenseId})"
            ) {
                postCommitActionRunner.run(outcome.transactionActions)
            }
        }

        if (outcome.shouldCheckBudgets) {
            // Transaction side effects (via postCommitActionRunner) already
            // handle budget monitoring via budgetMonitor.checkBudgets().
            // Only check here if lifecycle dispatch was skipped (e.g. duplicate
            // or pendingReview paths). Currently shouldCheckBudgets is only true
            // when createdExpenseId is non-null, so the lifecycle dispatch
            // already covers it. This guard is kept for future scenarios where
            // lifecycle dispatch may be skipped but budget checks are still needed.
        }

        if (outcome.shouldTrainAsTransaction) {
            runPostCommitSafely(
                action = "classifier training after markAsRelevant (notificationId=$id, package=${notification.packageName})"
            ) {
                classifier.train(fullNotificationText, isTransaction = true)
            }
        }

        runPostCommitSafely(
            action = "source stats cache invalidation after markAsRelevant (notificationId=$id, package=${notification.packageName})"
        ) {
            // AIML-13: Use comprehensive invalidation after user action
            // Use the parsed merchant (falling back to packageName), not notification.title
            val merchantForInvalidation = expense?.merchant
                ?: pendingReview?.suggestedMerchant
                ?: notification.packageName
            confidenceRouter.invalidateAfterUserAction(
                notification.packageName,
                merchantForInvalidation
            )
        }

        runPostCommitSafely(
            action = "classifier retraining after markAsRelevant (notificationId=$id)"
        ) {
            classifier.retrainFromCorrections()
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

    suspend fun updatePendingReviewCategoryBulk(merchantName: String, categoryId: Long) {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.updatePendingReviewCategoryBulk")
        val merchantKey = MerchantKeyGenerator.generate(merchantName)
        pendingReviewDao.bulkUpdateCategoryByMerchant(merchantKey, merchantName, categoryId)
    }

    suspend fun updatePendingReviewMerchantBulk(oldMerchant: String, newMerchant: String) {
        writeBarrier.checkWritesAllowed("ReviewQueueRepository.updatePendingReviewMerchantBulk")
        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        pendingReviewDao.bulkRenameMerchant(oldMerchantKey, oldMerchant, newMerchant, newMerchantKey)
    }

    suspend fun getPendingReviewsByMerchant(merchantName: String): List<PendingReview> {
        val merchantKey = MerchantKeyGenerator.generate(merchantName)
        return pendingReviewDao.getPendingByMerchant(merchantKey, merchantName)
    }
}
