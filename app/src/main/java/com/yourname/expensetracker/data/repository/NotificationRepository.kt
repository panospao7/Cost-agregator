package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.model.Result
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class NotificationRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,

    private val parserRegistry: AppParserRegistry,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {

    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean =
        dao.exists(packageName, timestamp, title, text)

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    suspend fun getClassifierStats() = classifier.getStats()


    // === Core Processing Pipeline ===

    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        try {
            processAndSaveInternal(notification)
        } catch (e: Exception) {
            Timber.e(e, "Error processing notification: ${notification.packageName}")
        }
    }
    
    private suspend fun processAndSaveInternal(notification: RawNotification) {
        // Heavy CPU/IO Work - done before transaction
        classifier.initialize()

        // Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            database.withTransaction {
                // Use INSERT OR IGNORE for atomic insert
                val rawId = dao.insertOrIgnore(notification)
                if (rawId == -1L) {
                    // Already exists
                    return@withTransaction
                }
                sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())
                dao.markRelevance(rawId, false)
            }
            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            return
        }

        // Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")
        
        // Route through confidence system (includes source stats + ML)
        var routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )

        // Fix 4.12: Large amount validation -> Force Needs Review
        if (parsed.amount > 1000000.0 && routingResult.decision == RoutingDecision.AUTO_ACCEPT) {
            Timber.w("Auto-accept suppressed due to large amount (validation limit)")
            routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
        }

        // Apply merchant normalization & user corrections
        val lookupResult = merchantNormalizer.normalize(parsed.merchant)
        val correctedMerchant = lookupResult.canonical.normalizedName

        // Database Transaction - ONLY MINIMAL DB WRITES
        database.withTransaction {
            // Use INSERT OR IGNORE for atomic insert
            val rawId = dao.insertOrIgnore(notification)
            if (rawId == -1L) {
                // Already exists - duplicate notification
                return@withTransaction
            }

            // Update stats - use atomic methods in each branch
            confidenceRouter.ensureSourceStats(notification.packageName)

            when (routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> {
                    // Check for duplicates
                    val isDuplicate = expenseDao.isDuplicate(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000 
                    )
                    
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
                        
                        // Train ML classifier: duplicates are still valid transactions
                        classifier.train(fullNotificationText, isTransaction = true)
                        
                        return@withTransaction
                    }

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
                        transactionType = parsed.type,
                        date = notification.timestamp,
                        rawNotificationId = rawId,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.CARD,
                        isManualEntry = false,
                        dedupeKey = Expense.generateDedupeKey(parsed.amount, correctedMerchant, notification.timestamp)
                    )

                    val expenseId = expenseDao.insertAtomic(expense)

                    if (expenseId > 0) {
                        dao.markRelevance(rawId, true)
                        sourceStatsDao.incrementTotalAndAccepted(notification.packageName, timeProvider.now())
                        
                        budgetMonitor.checkBudgets()
                        classifier.train(fullNotificationText, isTransaction = true)
                    } else {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
                        classifier.train(fullNotificationText, isTransaction = true)
                    }
                }

                RoutingDecision.NEEDS_REVIEW -> {
                    // Check for duplicates before adding to review
                    val isDuplicate = expenseDao.isDuplicate(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000
                    )
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
                        
                        // Train ML classifier: duplicates are still valid transactions
                        classifier.train(fullNotificationText, isTransaction = true)
                        
                        return@withTransaction
                    }

                    val classification = hybridClassifier.classify(
                        merchantName = correctedMerchant,
                        amount = parsed.amount,
                        notificationTitle = notification.title,
                        notificationText = notification.text,
                        packageName = notification.packageName
                    )
                    val suggestedCategoryId = classification.categoryId.takeIf { it > 0 }

                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = parsed.amount,
                        suggestedCurrency = parsed.currency,
                        suggestedMerchant = correctedMerchant,
                        suggestedType = parsed.type.name,
                        suggestedCategoryId = suggestedCategoryId,
                        confidence = routingResult.adjustedConfidence,
                        packageName = notification.packageName,
                        notificationTitle = notification.title,
                        notificationText = notification.text ?: notification.bigText,
                        suggestedDate = parsed.date
                    )
                    pendingReviewDao.insert(review)
                    sourceStatsDao.incrementTotalAndPending(notification.packageName, timeProvider.now())
                }

                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())
                }
            }

            // Invalidate all related caches to ensure fresh data for subsequent notifications
            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            confidenceRouter.invalidateMerchantCache(correctedMerchant)
        }
    }

    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))

    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)

    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)

    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()

    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == PendingReviewStatus.PENDING) {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        pendingReviewDao.deleteByRawId(notification.id)
        dao.delete(notification)
    }

    // === Classifier Management ===

    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Existing methods (updated) ===


    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        // merchantCategoryDao.deleteAll() // Removed as part of refactoring
        sourceStatsDao.resetAllPendingCounts()
    }

    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }


    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        if (notifications.isEmpty()) return
        
        // Initialize once for the batch
        classifier.initialize()
        
        // Process in parallel chunks
        notifications.chunked(20).forEach { chunk ->
            coroutineScope {
                chunk.map { notification -> 
                    async { processAndSave(notification) } 
                }.awaitAll()
            }
        }
    }
}
