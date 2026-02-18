package com.yourname.expensetracker.data.repository

import androidx.room.Transaction
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewQueueRepository @Inject constructor(
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
    private val parserRegistry: AppParserRegistry,
    private val timeProvider: TimeProvider
) {

    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> =
        pendingReviewDao.getPendingFlow(limit)

    fun getPendingReviewCount(): Flow<Int> =
        pendingReviewDao.getPendingCountFlow()

    @Transaction
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ): Result<Long> {
        val review = pendingReviewDao.getById(reviewId) ?: return Result.Error(message = "Review not found")

        // Atomically check and update status to prevent double-processing
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING")
        if (rowsUpdated == 0) return Result.Error(message = "Review already processed")

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId

        if (amount > 1000000.0) {
            pendingReviewDao.updateStatus(reviewId, "PENDING")
            return Result.Error(message = "Amount exceeds limit")
        }

        val type: TransactionType = try {
            TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            TransactionType.PURCHASE
        }

        val notification = review.rawNotificationId?.let { rawNotificationDao.getById(it) }
        val transactionDate: Long = review.suggestedDate ?: notification?.timestamp ?: review.createdAt

        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate,
            windowMs = 300000
        )

        if (!isDuplicate) {
            val expense = Expense(
                0L,
                amount,
                review.suggestedCurrency,
                merchant,
                type,
                transactionDate,
                review.rawNotificationId,
                categoryId,
                timeProvider.now(),
                PaymentMethod.CARD,
                review.scannedReceiptId != null,
                if (review.scannedReceiptId != null) "Scanned from receipt" else null
            )

            try {
                val expenseId = expenseDao.insert(expense)

                if (expenseId > 0) {
                    review.rawNotificationId?.let { rawNotificationDao.markRelevance(it, true) }
                    sourceStatsDao.incrementAccepted(review.packageName)
                    sourceStatsDao.decrementPending(review.packageName)

                    review.scannedReceiptId?.let { receiptId ->
                        scannedReceiptDao.linkToExpense(receiptId, expenseId)
                    }

                    budgetMonitor.checkBudgets()
                    pendingReviewDao.updateStatus(reviewId, "APPROVED")

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
                        wasRejected = false,
                        wasApproved = true,
                        notificationTitle = review.notificationTitle,
                        notificationText = review.notificationText
                    )
                    userCorrectionDao.insert(correction)

                    try { classifier.retrainFromCorrections() } catch (e: Exception) {
                        android.util.Log.e("ReviewQueueRepo", "Failed to retrain classifier", e)
                    }

                    if (categoryId != null) {
                        merchantCategoryRepository.learnPattern(merchant, categoryId)
                    }

                    if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
                        merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
                    }

                    return Result.Success(expenseId)
                } else {
                    pendingReviewDao.updateStatus(reviewId, "PENDING")
                    return Result.Error(message = "Insertion failed")
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                pendingReviewDao.updateStatus(reviewId, "PENDING")
                return Result.Error(message = "Database constraint error: ${e.message}", exception = e)
            }
        } else {
            sourceStatsDao.incrementDuplicate(review.packageName)
            sourceStatsDao.decrementPending(review.packageName)
            pendingReviewDao.updateStatus(reviewId, "DUPLICATE")

            val fullText = listOfNotNull(
                review.notificationTitle,
                review.notificationText
            ).joinToString(" ")
            classifier.train(fullText, isTransaction = true)

            return Result.Duplicate
        }
    }

    @Transaction
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED")
        if (rowsUpdated == 0) return

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
            wasRejected = true,
            wasApproved = false,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)

        try { classifier.retrainFromCorrections() } catch (e: Exception) {
            android.util.Log.e("ReviewQueueRepo", "Failed to retrain classifier", e)
        }
    }

    @Transaction
    suspend fun approveAllReview() {
        val pending = pendingReviewDao.getPending()
        pending.forEach { item ->
            approveReview(item.review.id)
        }
    }

    @Transaction
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

                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp,
                    windowMs = 300000
                )

                if (isDuplicate) {
                    sourceStatsDao.incrementDuplicate(notification.packageName)
                    classifier.train(fullNotificationText, isTransaction = true)
                } else {
                    val expense = Expense(
                        amount = parsed.amount,
                        currency = parsed.currency,
                        merchant = correctedMerchant,
                        transactionType = parsed.type,
                        date = notification.timestamp,
                        rawNotificationId = id,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.CARD,
                        isManualEntry = false,
                        notes = "Manually recovered from debug log"
                    )

                    try {
                        expenseDao.insert(expense)
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        budgetMonitor.checkBudgets()
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: Exception) {
                        android.util.Log.e("ReviewQueueRepo", "Failed to insert recovered expense", e)
                    }
                }
            } else {
                val review = PendingReview(
                    rawNotificationId = id,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Unknown",
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

        val correction = UserCorrection(
            packageName = notification.packageName,
            originalMerchant = "Manual",
            correctedMerchant = null,
            originalAmount = 0.0,
            correctedAmount = null,
            originalCategoryId = null,
            correctedCategoryId = null,
            wasRejected = !isRelevant,
            wasApproved = isRelevant,
            notificationTitle = notification.title,
            notificationText = notification.text ?: notification.bigText
        )
        userCorrectionDao.insert(correction)

        try { classifier.retrainFromCorrections() } catch (e: Exception) {
            android.util.Log.e("ReviewQueueRepo", "Failed to retrain classifier", e)
        }
    }
}
