package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

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
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    // Shared expenses flow to prevent redundant DB queries (shared by multiple ViewModels)
    private val sharedExpenses = expenseDao.getAllFlow()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(30000),
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
    suspend fun getClassifierStats() = classifier.getStats()

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
        date: Long = timeProvider.now(),
        notes: String? = null
    ): OperationResult<Long> {
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Manual expense amount too large: $amount")
            return OperationResult.Error("Amount exceeds limit")
        }

        return database.withTransaction {
            // 1. Normalize merchant name
            val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
            val normalizedMerchant = lookupResult.canonical.normalizedName

            // 2. Auto-categorize if no category provided
            val finalCategoryId = categoryId ?: hybridClassifier.classify(
                merchantName = normalizedMerchant,
                amount = amount
            ).categoryId.takeIf { it > 0 }

            // 3. Dedup check with tighter window for manual entries (1 minute)
            // For manual entries, we trust the user but want to avoid accidental double-taps.
            val isDuplicate = expenseDao.isDuplicate(
                amount = amount,
                merchant = normalizedMerchant,
                date = date,
                windowMs = 60000 // 1 minute window for manual double-entry prevention
            )
            if (isDuplicate) return@withTransaction OperationResult.Duplicate

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
                merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
            }

            OperationResult.Success(id)
        }
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }

    // === Analytics Helpers ===

    suspend fun getExpenseCountForPeriod(startMs: Long, endMs: Long): Int =
        expenseDao.getCountForPeriod(startMs, endMs)

    // === Core Processing Pipeline ===

    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        // 1. Initial existence check (fast, non-transactional)
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return

        // 2. Heavy CPU/IO Work - MOVE OUTSIDE TRANSACTION
        // Initialize classifier if needed
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
                // Secondary check inside transaction to prevent race conditions
                if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction
                
                val rawId = try { dao.insert(notification) } catch (e: Exception) { return@withTransaction }
                sourceStatsDao.incrementTotal(notification.packageName, timeProvider.now())
                sourceStatsDao.incrementAutoRejected(notification.packageName)
                dao.markRelevance(rawId, false)
            }
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
            android.util.Log.w("NotificationRepo", "Auto-accept suppressed due to large amount: ${parsed.amount}")
            routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
        }

        // Apply merchant normalization & user corrections
        val lookupResult = merchantNormalizer.normalize(parsed.merchant)
        val correctedMerchant = lookupResult.canonical.normalizedName

        // 3. Database Transaction - ONLY MINIMAL DB WRITES
        database.withTransaction {
            // Secondary check inside transaction
            if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction

            // Save raw notification
            val rawId = try {
                dao.insert(notification)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                return@withTransaction
            }

            // Update stats
            confidenceRouter.ensureSourceStats(notification.packageName)
            sourceStatsDao.incrementTotal(notification.packageName, timeProvider.now())

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
                        sourceStatsDao.incrementDuplicate(notification.packageName)
                        
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
                        isManualEntry = false
                    )
                    try {
                        expenseDao.insert(expense)
                        dao.markRelevance(rawId, true)
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        
                        // Check budgets (Note: potentially heavy, but standard for accept flow)
                        budgetMonitor.checkBudgets()
        
                        // Train classifier: auto-accepted = positive example
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        dao.markRelevance(rawId, false)
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
                        sourceStatsDao.incrementDuplicate(notification.packageName)
                        
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
                    sourceStatsDao.incrementPending(notification.packageName)
                }

                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementAutoRejected(notification.packageName)
                }
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
    ): OperationResult<Long> {
        val review = pendingReviewDao.getById(reviewId) ?: return OperationResult.Error("Review not found")
        
        // Critical Fix: Atomically check and update status to prevent double-processing
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING")
        if (rowsUpdated == 0) return OperationResult.Error("Review already processed")

        // If we fail later, we should ideally revert this, but for now we secure the lock.
        // We will update to APPROVED at the end.

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Approval suppressed due to large amount: $amount")
            pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
            return OperationResult.Error("Amount exceeds limit")
        }

        val type: com.yourname.expensetracker.data.database.entity.TransactionType = try {
            com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            android.util.Log.w("NotificationRepo", "Unknown transaction type: ${review.suggestedType}, falling back to PURCHASE")
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
        }

        val notification = review.rawNotificationId?.let { dao.getById(it) }
        val transactionDate: Long = review.suggestedDate ?: notification?.timestamp ?: review.createdAt

        // Check for duplicates
        // Increased window to 5 minutes to catch delayed bank notifications
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate,
            windowMs = 300000
        )
        
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
                timeProvider.now(),
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

                    // Fix 1.19 status update: We update it to APPROVED
                    pendingReviewDao.updateStatus(reviewId, "APPROVED")
                    
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
                        merchantCategoryRepository.learnPattern(merchant, categoryId)
                    }

                    // Learn alias if merchant name was changed (BUG-MERC-001)
                    if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
                        merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
                    }
                    
                    return OperationResult.Success(expenseId)
                } else {
                    pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
                    return OperationResult.Error("Insertion failed")
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Unexpected constraint error, fail the operation
                pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
                return OperationResult.Error("Database constraint error: ${e.message}")
            }
        } else {
             // It's a duplicate, we treat this as "processed" to clear the review
             sourceStatsDao.incrementDuplicate(review.packageName)
             sourceStatsDao.decrementPending(review.packageName)
             pendingReviewDao.updateStatus(reviewId, "DUPLICATE")
             
             // Train classifier: user approved this as an expense (even if duplicate)
             val fullText = listOfNotNull(
                 review.notificationTitle,
                 review.notificationText
             ).joinToString(" ")
             classifier.train(fullText, isTransaction = true)

             return OperationResult.Duplicate
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

        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        if (isRelevant) {
            // CRITICAL FIX: When user explicitly marks as relevant (Expense ✓),
            // we must actually CREATE the expense or review item.
            
            // 1. Try to parse again
            val parsed = parserRegistry.parse(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                subText = notification.subText,
                packageName = notification.packageName
            )

            if (parsed != null) {
                // We have valid data, so we can create an Expense directly (User Override)
                // We assume if they clicked "Expense", they validated it looks correct-ish,
                // or at least we should create it so they can see it.
                
                // Normalization
                val lookupResult = merchantNormalizer.normalize(parsed.merchant)
                val correctedMerchant = lookupResult.canonical.normalizedName
                
                // Categorization
                val classification = hybridClassifier.classify(
                    merchantName = correctedMerchant,
                    amount = parsed.amount,
                    notificationTitle = notification.title,
                    notificationText = notification.text,
                    packageName = notification.packageName
                )
                val categoryId = classification.categoryId.takeIf { it > 0 }

                // Check for duplicates before inserting
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp,
                    windowMs = 300000
                )

                if (isDuplicate) {
                    sourceStatsDao.incrementDuplicate(notification.packageName)
                    
                    // Train classifier: user manually marked this as an expense
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
                        // Decrease auto-rejected count since we reversed the decision
                        // (Optional, but keeps stats cleaner)
                        
                        budgetMonitor.checkBudgets()
                        
                        // Train classifier
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "Failed to insert recovered expense", e)
                    }
                }
            } else {
                // Parsing failed, but user says it's an expense.
                // Create a PendingReview with blank values so they can fill it in.
                val review = PendingReview(
                    rawNotificationId = id,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Unknown",
                    suggestedType = TransactionType.PURCHASE.name,
                    suggestedCategoryId = null,
                    confidence = 1.0f, // Manual override = 100% confidence
                    packageName = notification.packageName,
                    notificationTitle = notification.title,
                    notificationText = notification.text ?: notification.bigText,
                    suggestedDate = notification.timestamp
                )
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }
        }

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
        // merchantCategoryDao.deleteAll() // Removed as part of refactoring
        sourceStatsDao.resetAllPendingCounts()
    }

    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        merchantCategoryRepository.learnPattern(expense.merchant, newCategoryId)

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

    suspend fun updateExpenseMerchant(expense: Expense, newMerchant: String) {
        if (expense.merchant == newMerchant) return
        
        expenseDao.updateMerchant(expense.id, newMerchant)
        
        // Catch the rename for future auto-correction (BUG-MERC-001)
        // We link whatever the current normalized merchant name is to the new brand name
        merchantNormalizer.learnMerchantAlias(expense.merchant, newMerchant)
        
        // Also learn the category for this brand name
        expense.categoryId?.let { 
            merchantCategoryRepository.learnPattern(newMerchant, it)
        }
    }

    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == "PENDING") {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        pendingReviewDao.deleteByRawId(notification.id) // Fix 1.20: Clean up orphaned reviews
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

    fun getExpensesWithCategoryInPeriod(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryInPeriodFlow(startMs, endMs)

    fun getExpensesWithCategoryFiltered(
        startMs: Long, 
        endMs: Long, 
        type: TransactionType?,
        categoryId: Long?, 
        merchant: String?
    ): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryFilteredFlow(
            startMs = startMs,
            endMs = endMs,
            type = type?.name,
            categoryId = categoryId,
            merchant = merchant
        )

    suspend fun getExpensesPaged(limit: Int, offset: Int): List<ExpenseWithCategory> =
        expenseDao.getExpensesWithCategoryPaged(limit, offset)

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
