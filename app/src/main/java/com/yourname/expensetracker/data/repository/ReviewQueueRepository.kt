package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber

import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator

@Singleton
class ReviewQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
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

    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> =
        pendingReviewDao.getPendingFlow(limit)

    fun getPendingReviewCount(): Flow<Int> =
        pendingReviewDao.getPendingCountFlow()

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
        val review = pendingReviewDao.getById(reviewId) ?: return Result.Error(message = context.getString(R.string.debug_error_review_not_found))

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId

        if (amount > 1000000.0) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_exceeds_limit))
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
            dedupeKey = Expense.generateDedupeKey(amount, merchant, transactionDate),
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
                sourceStatsDao.incrementDuplicate(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
                pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
                txDuplicate
            }
        }

        if (txnResult == txAlreadyProcessed) {
            return Result.Error(message = context.getString(R.string.debug_error_review_already_processed))
        }

        if (txnResult == txDuplicate) {
            val fullText = listOfNotNull(
                review.notificationTitle,
                review.notificationText
            ).joinToString(" ")
            classifier.train(fullText, isTransaction = true)

            confidenceRouter.invalidateSourceStatsCache(review.packageName)
            return Result.Duplicate
        }

        // External operations outside DB transaction
        budgetMonitor.checkBudgets()

        // Check for anomalies and alert
        val enrichedExpense = expense.copy(id = txnResult)
        val expenseWithCategory = com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
            expense = enrichedExpense,
            category = categoryId?.let { database.categoryDao().getById(it) }
        )
        anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)

        try { classifier.retrainFromCorrections() } catch (e: Exception) {
            Timber.e(e, "Failed to retrain classifier")
        }

        if (categoryId != null) {
            merchantCategoryRepository.learnPattern(merchant, categoryId)
        }

        if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
            merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
        }

        confidenceRouter.invalidateSourceStatsCache(review.packageName)
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

        try { classifier.retrainFromCorrections() } catch (e: Exception) {
            Timber.e(e, "Failed to retrain classifier")
        }

        confidenceRouter.invalidateSourceStatsCache(review.packageName)
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
        rawNotificationDao.markRelevance(id, isRelevant)

        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        if (isRelevant) {
            val parsed = parserRegistry.parse(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                subText = notification.subText,
                packageName = notification.packageName
            )

            if (parsed != null) {
                val lookupResult = merchantNormalizer.normalize(parsed.merchant)
                val correctedMerchant = lookupResult.canonical.normalizedName

                val classification = hybridClassifier.classify(
                    merchantName = correctedMerchant,
                    amount = parsed.amount,
                    notificationTitle = notification.title,
                    notificationText = notification.text,
                    packageName = notification.packageName
                )
                val categoryId = classification.categoryId.takeIf { it > 0 }

                val expense = Expense(
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = correctedMerchant,
                    merchantKey = MerchantKeyGenerator.generate(correctedMerchant),
                    transactionType = parsed.type,
                    date = notification.timestamp,
                    rawNotificationId = id,
                    categoryId = categoryId,
                    createdAt = timeProvider.now(),
                    paymentMethod = PaymentMethod.CARD,
                    isManualEntry = false,
                    notes = "Manually recovered from debug log",
                    dedupeKey = Expense.generateDedupeKey(parsed.amount, correctedMerchant, notification.timestamp)
                )

                val expenseId = database.withTransaction {
                    expenseDao.insertAtomic(expense)
                }

                if (expenseId > 0) {
                    sourceStatsDao.incrementAccepted(notification.packageName)
                    budgetMonitor.checkBudgets()
                    classifier.train(fullNotificationText, isTransaction = true)
                } else {
                    sourceStatsDao.incrementDuplicate(notification.packageName)
                    classifier.train(fullNotificationText, isTransaction = true)
                }
            } else {
                val review = PendingReview(
                    rawNotificationId = id,
                    suggestedAmount = 0.0,
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
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }
        }

        confidenceRouter.invalidateSourceStatsCache(notification.packageName)

        database.withTransaction {
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
            userCorrectionDao.insert(correction)
        }

        try { classifier.retrainFromCorrections() } catch (e: Exception) {
            Timber.e(e, "Failed to retrain classifier")
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
