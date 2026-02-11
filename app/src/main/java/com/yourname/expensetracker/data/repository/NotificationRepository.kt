package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val parserRegistry: AppParserRegistry,
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: MerchantNormalizer,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor // <-- NEW
) {
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    // Shared expenses flow to prevent redundant DB queries (shared by multiple ViewModels)
    private val sharedExpenses = expenseDao.getAllFlow()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

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

    // === Review Queue ===
    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> = pendingReviewDao.getPendingFlow(limit)
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    fun getClassifierStats() = classifier.getStats()

    // === Manual Expense Entry ===

    /**
     * Search merchants from existing expenses for autocomplete
     */
    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList()
        return expenseDao.searchMerchants(query)
    }

    /**
     * Get recent distinct merchant names for suggestions
     */
    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }

    /**
     * Add a manually entered expense
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        transactionType: TransactionType = TransactionType.PURCHASE,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: Long = System.currentTimeMillis(),
        notes: String? = null
    ): Long {
        // 1. Normalize merchant name
        val normalizedMerchant = merchantNormalizer.applyUserCorrections(merchant)

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: categorizationEngine.categorize(normalizedMerchant)

                // 3. Dedup check with tighter window for manual entries (1 minute)
                // For manual entries, we trust the user but want to avoid accidental double-taps.
                val isDuplicate = expenseDao.isDuplicate(
                    amount = amount,
                    merchant = normalizedMerchant,
                    date = date,
                    windowMs = 60000 // 1 minute window for manual double-entry prevention
                )
        if (isDuplicate) return -1L

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = transactionType,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true,
            notes = notes
        )

        val id = expenseDao.insert(expense)

        // 5. Check budgets
        budgetMonitor.checkBudgets()

        // 6. Learn the merchant→category mapping for future auto-categorization
        if (finalCategoryId != null && id > 0) {
            val pattern = categorizationEngine.normalize(normalizedMerchant)
            if (pattern.isNotEmpty()) {
                merchantCategoryDao.insert(
                    MerchantCategory(
                        merchantPattern = pattern,
                        categoryId = finalCategoryId,
                        confidence = 1.0f
                    )
                )
            }
        }

        return id
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }

    // === Core Processing Pipeline ===
    @Transaction
    suspend fun processAndSave(notification: RawNotification) {
        // Optimized check: return early if already exists
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return

        // 1. Save raw notification
        val rawId = try {
            dao.insert(notification)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Race condition: another thread inserted it after our exists() check
            return
        }

        // 2. Ensure source stats exist, then increment total
        confidenceRouter.ensureSourceStats(notification.packageName)
        sourceStatsDao.incrementTotal(notification.packageName)

        // 3. Initialize classifier if needed
        classifier.initialize()

        // 4. Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            sourceStatsDao.incrementAutoRejected(notification.packageName)
            dao.markRelevance(rawId, false)
            return
        }

        // 5. Apply merchant normalization & user corrections
        val correctedMerchant = merchantNormalizer.applyUserCorrections(parsed.merchant)

        // 6. Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        // 7. Route through confidence system (now includes ML)
        val routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )

        when (routingResult.decision) {
            RoutingDecision.AUTO_ACCEPT -> {
                // 3. Check for duplicates
                // Use a short window (e.g. 10s) for auto-detected transactions to avoid flagging
                // legitimate consecutive purchases (e.g. buying rounds of drinks) as duplicates,
                // while still catching immediate double-processing of the same notification.
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp,
                    windowMs = 10000 
                )
                if (isDuplicate) {
                    dao.markRelevance(rawId, false)
                    return
                }

                val categoryId = categorizationEngine.categorize(correctedMerchant)

                val expense = Expense(
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = correctedMerchant,
                    transactionType = parsed.type,
                    date = notification.timestamp,
                    rawNotificationId = rawId,
                    categoryId = categoryId,
                    paymentMethod = PaymentMethod.CARD,
                    isManualEntry = false
                )
                try {
                    expenseDao.insert(expense)
                    dao.markRelevance(rawId, true)
                    sourceStatsDao.incrementAccepted(notification.packageName)
                    
                    // Check budgets
                    budgetMonitor.checkBudgets()
    
                    // Train classifier: auto-accepted = positive example
                    classifier.train(fullNotificationText, isTransaction = true)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    // Ignore duplicate expenses
                    dao.markRelevance(rawId, false)
                }
            }

            RoutingDecision.NEEDS_REVIEW -> {
                val suggestedCategoryId = categorizationEngine.categorize(correctedMerchant)

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
                    notificationText = notification.text ?: notification.bigText
                )
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }

            RoutingDecision.AUTO_REJECT -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementAutoRejected(notification.packageName)

                // Only train negative if it's truly NOT a transaction (parser was null)
                // If it reached here, it means parser WAS not null but confidence was low.
                // We DON'T train it as negative yet, let the user decide if they mark it manually.
            }
        }
    }

    // === Review Actions ===

    /**
     * User approves a pending review (possibly with modifications)
     */
    /**
     * User approves a pending review (possibly with modifications)
     */
    @Transaction
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        
        // Critical Fix: Check status specifically to avoid double-processing
        // We do this instead of updateStatusIfPending at the start to ensure we don't
        // mark it as APPROVED if the subsequent DB operations fail.
        if (review.status != "PENDING") return

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId
        val type: com.yourname.expensetracker.data.database.entity.TransactionType = try {
            com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            android.util.Log.w("NotificationRepo", "Unknown transaction type: ${review.suggestedType}, falling back to PURCHASE")
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
        }

        val notification = review.rawNotificationId?.let { dao.getById(it) }
        val transactionDate: Long = review.suggestedDate ?: notification?.timestamp ?: review.createdAt

        // Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate
        )
        
        var operationSuccessful = true
        
        if (!isDuplicate) {
            // Create the expense
            val expense = com.yourname.expensetracker.data.database.entity.Expense(
                0L,
                amount,
                review.suggestedCurrency,
                merchant,
                type,
                transactionDate,
                review.rawNotificationId,
                categoryId,
                System.currentTimeMillis(),
                com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD,
                review.scannedReceiptId != null,
                if (review.scannedReceiptId != null) "Scanned from receipt" else null
            )
            
            try {
                val expenseId = expenseDao.insert(expense)
                
                if (expenseId > 0) {
                    review.rawNotificationId?.let { dao.markRelevance(it, true) }
                    sourceStatsDao.incrementAccepted(review.packageName)
                    sourceStatsDao.decrementPending(review.packageName)

                    // Link to scanned receipt if this was a scan
                    review.scannedReceiptId?.let { receiptId ->
                        scannedReceiptDao.linkToExpense(receiptId, expenseId)
                    }

                    // Check budgets
                    budgetMonitor.checkBudgets()
                } else {
                    // Insertion failed (likely IGNORE strategy due to same ID, which shouldn't happen here as ID is 0L)
                    operationSuccessful = false
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Unexpected constraint error, fail the operation
                operationSuccessful = false
            }
        } else {
             // It's a duplicate, we treat this as "processed" to clear the review
             sourceStatsDao.decrementPending(review.packageName)
        }

        if (operationSuccessful) {
            // ONLY set status to APPROVED if all core steps succeeded OR it was a duplicate we decided to skip
            pendingReviewDao.updateStatusIfPending(reviewId, "APPROVED")
            
            // Record user correction for learning
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

            // Retrain classifier
            try {
                classifier.retrainFromCorrections()
            } catch (e: Exception) {
                android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
            }

            // Learn mapping
            if (categoryId != null) {
                val pattern = categorizationEngine.normalize(merchant)
                if (pattern.isNotEmpty()) {
                    merchantCategoryDao.insert(
                        MerchantCategory(
                            merchantPattern = pattern,
                            categoryId = categoryId,
                            confidence = 1.0f
                        )
                    )
                }
            }
        }
    }

    /**
     * User rejects a pending review
     */
    @Transaction
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        // Atomic update check
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED")
        if (rowsUpdated == 0) return

        review.rawNotificationId?.let { id -> dao.markRelevance(id, false) }
        sourceStatsDao.incrementRejected(review.packageName)
        sourceStatsDao.decrementPending(review.packageName)

        // Record rejection for learning
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

        // Train classifier: user rejected = negative
        // LOG-003 Fix: Use retrainFrom corrections
        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }

    /**
     * Approves all currently pending reviews
     */
    @Transaction
    suspend fun approveAllReview() {
        val pending = pendingReviewDao.getPending()
        pending.forEach { item ->
            approveReview(item.review.id)
        }
    }

    // === Classifier Management ===

    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Existing methods (updated) ===

    @Transaction
    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) {
        val notification = dao.getById(id) ?: return
        dao.markRelevance(id, isRelevant)

        // Train classifier directly from this manual action
        // Also record a correction for future retraining (LOG-003)
        // We record correction AND retrain immediately to ensure consistency
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
        
        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        merchantCategoryDao.deleteAll()
        sourceStatsDao.resetAllPendingCounts()
    }

    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        val pattern = categorizationEngine.normalize(expense.merchant)
        if (pattern.isNotEmpty()) {
            merchantCategoryDao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = newCategoryId,
                    confidence = 1.0f
                )
            )
        }

        // Also record as a correction for learning
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }

    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == "PENDING") {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        dao.delete(notification)
    }

    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))

    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)

    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)

    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()

    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()

    fun getAllExpenses(): Flow<List<Expense>> = sharedExpenses

    fun getExpensesWithCategory(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)

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
