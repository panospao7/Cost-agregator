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
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator

@Singleton
class ReviewQueueRepository @Inject constructor(
    private val database: AppDatabase,
    private val pendingReviewDao: PendingReviewDao,
    private val rawNotificationDao: RawNotificationDao,
    private val expenseDao: ExpenseDao,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val parserRegistry: AppParserRegistry,
    private val timeProvider: TimeProvider,
    private val confidenceRouter: ConfidenceRouter
) {
    private companion object {
        private const val ERROR_REVIEW_NOT_FOUND = "REVIEW_NOT_FOUND"
        private const val ERROR_AMOUNT_EXCEEDS_LIMIT = "AMOUNT_EXCEEDS_LIMIT"
        private const val ERROR_REVIEW_ALREADY_PROCESSED = "REVIEW_ALREADY_PROCESSED"

        // Minimum positive sentinel for suggestedAmount when the parser cannot
        // extract a total.  Must satisfy the v76 CHECK(suggestedAmount > 0) invariant.
        private const val FALLBACK_SUGGESTED_AMOUNT = 0.01
    }


    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> =
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

    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null,
        finalDate: Long? = null,
        finalType: TransactionType? = null,
        finalLatitude: Double? = null,
        finalLongitude: Double? = null,
        finalAddress: String? = null
    ): Result<Long> {
        val review = pendingReviewDao.getById(reviewId)
            ?: return Result.Error(message = ERROR_REVIEW_NOT_FOUND)

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
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

        val expense = Expense(
            amount = amount,
            currency = review.suggestedCurrency,
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
            // Use the type-aware key so that PURCHASE vs DEPOSIT/TRANSFER rows
            // never collide on the persisted unique dedupeKey index (ISSUE-1 fix).
            // The range-based isDuplicateCurrencyAware pre-check remains the
            // canonical policy gate; insertAtomic is now purely a race guard.
            dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount, merchant, transactionDate, review.suggestedCurrency, type
            ),
            // Prefer user-provided location, fall back to review-captured GPS
            latitude = finalLatitude ?: review.suggestedLatitude,
            longitude = finalLongitude ?: review.suggestedLongitude,
            locationSource = when {
                finalLatitude != null -> AppConfig.Location.SOURCE_USER_MANUAL
                review.suggestedLatitude != null -> AppConfig.Location.SOURCE_DEVICE_GPS
                else -> null
            },
            resolvedAddress = finalAddress
        )

        val txAlreadyProcessed = -2L
        val txDuplicate = -1L

        val txnResult = database.withTransaction {
            val rowsUpdated = pendingReviewDao.transitionStatus(
                id = reviewId,
                expectedStatus = PendingReviewStatus.PENDING,
                newStatus = PendingReviewStatus.PROCESSING
            )
            if (rowsUpdated == 0) {
                return@withTransaction txAlreadyProcessed
            }

            // Pre-insert canonical duplicate check: uses currency + transaction-type aware
            // policy so that (a) PURCHASE vs DEPOSIT/TRANSFER are never conflated, and
            // (b) legacy rows with the old 3-part dedupe key are still caught by the
            // merchant/amount/date/currency/type window query (not just by key collision).
            // insertAtomic() below is kept only as a final race-condition guard.
            val isDuplicate = hasCanonicalApprovalDuplicate(expense)
            if (isDuplicate) {
                sourceStatsDao.incrementDuplicate(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
                pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
                return@withTransaction txDuplicate
            }

            // insertAtomic() is IGNORE-on-conflict — kept as a last-line race guard
            // in case a concurrent transaction commits the same expense between the
            // check above and this insert.
            val id = expenseDao.insertAtomic(expense)
            if (id > 0) {
                review.rawNotificationId?.let { rawNotificationDao.markRelevance(it, true) }
                sourceStatsDao.incrementAccepted(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
                review.scannedReceiptId?.let { receiptId -> scannedReceiptDao.linkToExpense(receiptId, id) }
                pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED)

                val correction = UserCorrection(
                    packageName = review.packageName,
                    originalMerchant = review.suggestedMerchant,
                    correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                        finalMerchant else null,
                    originalAmount = review.suggestedAmount,
                    correctedAmount = if (finalAmount != null && finalAmount != review.suggestedAmount)
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
                id
            } else {
                check(hasCanonicalApprovalDuplicate(expense)) {
                    "Expense insert conflicted without a canonical duplicate for reviewId=$reviewId"
                }
                sourceStatsDao.incrementDuplicate(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
                pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
                txDuplicate
            }
        }

        if (txnResult == txAlreadyProcessed) {
            return Result.Error(message = ERROR_REVIEW_ALREADY_PROCESSED)
        }

        if (txnResult == txDuplicate) {
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
                confidenceRouter.invalidateSourceStatsCache(review.packageName)
            }
            return Result.Duplicate
        }

        // External operations outside DB transaction (best-effort)
        runPostCommitSafely(
            action = "budget check after review approval (reviewId=$reviewId, expenseId=$txnResult)"
        ) {
            budgetMonitor.checkBudgets()
        }

        // Check for anomalies and alert (best-effort)
        runPostCommitSafely(
            action = "anomaly alert check after review approval (reviewId=$reviewId, expenseId=$txnResult)"
        ) {
            val enrichedExpense = expense.copy(id = txnResult)
            val expenseWithCategory = com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                expense = enrichedExpense,
                category = categoryId?.let { database.categoryDao().getById(it) }
            )
            anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)
        }

        runPostCommitSafely(
            action = "classifier retraining after review approval (reviewId=$reviewId)"
        ) {
            classifier.retrainFromCorrections()
        }

        if (categoryId != null) {
            runPostCommitSafely(
                action = "merchant-category pattern learning after review approval (reviewId=$reviewId, merchant=$merchant, categoryId=$categoryId)"
            ) {
                merchantCategoryRepository.learnPattern(merchant, categoryId)
            }
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
            confidenceRouter.invalidateSourceStatsCache(review.packageName)
        }
        return Result.Success(txnResult)
    }

    suspend fun rejectReview(reviewId: Long) {
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
                originalAmount = review.suggestedAmount,
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
            confidenceRouter.invalidateSourceStatsCache(review.packageName)
        }
    }

    suspend fun approveAllReview() {
        val pendingReviews = pendingReviewDao.getPending()
        for (review in pendingReviews) {
            try {
                approveReview(review.review.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to approve review ${review.review.id}")
            }
        }
    }

    suspend fun rejectAllReviews() {
        val pendingReviews = pendingReviewDao.getPending()
        for (review in pendingReviews) {
            try {
                rejectReview(review.review.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to reject review ${review.review.id}")
            }
        }
    }

    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) {
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

        val pendingReview = if (isRelevant && parsed == null) {
            PendingReview(
                rawNotificationId = id,
                suggestedAmount = FALLBACK_SUGGESTED_AMOUNT,
                suggestedCurrency = "EUR",
                suggestedMerchant = "Unknown",
                suggestedMerchantKey = MerchantKeyGenerator.generate("Unknown"),
                suggestedType = TransactionType.PURCHASE.name,
                suggestedCategoryId = null,
                confidence = 1.0f,
                packageName = notification.packageName,
                notificationTitle = notification.title,
                notificationText = notification.text ?: notification.bigText,
                suggestedDate = notification.timestamp
            )
        } else {
            null
        }

        data class MarkAsRelevantOutcome(
            val shouldTrainAsTransaction: Boolean,
            val shouldCheckBudgets: Boolean
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
                    val expenseId = expenseDao.insertAtomic(expense)
                    if (expenseId > 0) {
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        userCorrectionDao.insert(correction)
                        MarkAsRelevantOutcome(
                            shouldTrainAsTransaction = true,
                            shouldCheckBudgets = true
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
                    pendingReviewDao.insert(pendingReview)
                    sourceStatsDao.incrementPending(notification.packageName)
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

        if (outcome.shouldCheckBudgets) {
            runPostCommitSafely(
                action = "budget check after markAsRelevant (notificationId=$id, package=${notification.packageName})"
            ) {
                budgetMonitor.checkBudgets()
            }
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
            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
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
        val merchantKey = MerchantKeyGenerator.generate(merchantName)
        pendingReviewDao.bulkUpdateCategoryByMerchant(merchantKey, merchantName, categoryId)
    }

    suspend fun updatePendingReviewMerchantBulk(oldMerchant: String, newMerchant: String) {
        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        pendingReviewDao.bulkRenameMerchant(oldMerchantKey, oldMerchant, newMerchant, newMerchantKey)
    }

    suspend fun getPendingReviewsByMerchant(merchantName: String): List<PendingReview> {
        val merchantKey = MerchantKeyGenerator.generate(merchantName)
        return pendingReviewDao.getPendingByMerchant(merchantKey, merchantName)
    }
}
