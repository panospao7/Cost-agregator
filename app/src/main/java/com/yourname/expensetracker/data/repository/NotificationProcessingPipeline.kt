package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.subscription.NotificationSubscriptionDetector
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates the notification processing pipeline, extracted from [NotificationRepository]
 * to reduce its constructor dependency count.
 *
 * Responsibilities:
 *  - Parse raw notifications into structured transactions
 *  - Route through the confidence system
 *  - Classify merchants and transfer directions
 *  - Write the result (expense / pending review / rejected) inside a DB transaction
 *  - Trigger subscription detection after successful transaction processing
 */

@Singleton
class NotificationProcessingPipeline @Inject constructor(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val sourceStatsDao: SourceStatsDao,
    private val subscriptionCandidateDao: SubscriptionCandidateDao,
    private val parserRegistry: AppParserRegistry,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val timeProvider: TimeProvider,
    private val directionDetector: TransferDirectionDetector,
    private val analytics: TransferDirectionAnalytics,
    private val locationProvider: ForegroundLocationProvider,
    private val aiSettingsRepository: AiSettingsRepository,
    private val generateTransactionInsightUseCase: GenerateTransactionInsightUseCase,
    private val dashboardFollowThroughEngine: DashboardFollowThroughEngine,
    private val recommendationRepository: RecommendationRepository,
    private val subscriptionDetector: NotificationSubscriptionDetector,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    sealed interface ProcessingResult {
        data class Success(val packageName: String) : ProcessingResult
        data class Rejected(val packageName: String, val reason: String) : ProcessingResult
        data class Error(val packageName: String, val error: Throwable) : ProcessingResult
    }

    private val processMutex = Mutex()
    /**
     * App-scoped background enrichment for recommendations.
     * Ownership is the application lifecycle (not per-screen/request).
     */
    private val asyncScope: CoroutineScope = applicationScope
    private val recommendationSemaphore = Semaphore(MAX_CONCURRENT_RECOMMENDATION_JOBS)

    suspend fun process(notification: RawNotification): ProcessingResult {
        processMutex.withLock {
            try {
                processInternal(notification, initializeClassifier = true)
                return ProcessingResult.Success(notification.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error processing notification: ${notification.packageName}")
                return ProcessingResult.Error(notification.packageName, e)
            }
        }
    }

    suspend fun processBatch(notifications: List<RawNotification>): List<ProcessingResult> {
        if (notifications.isEmpty()) return emptyList()
        val results = mutableListOf<ProcessingResult>()
        processMutex.withLock {
            classifier.initialize()
            notifications.forEach { notification ->
                try {
                    processInternal(notification, initializeClassifier = false)
                    results += ProcessingResult.Success(notification.packageName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing notification in batch: ${notification.packageName}")
                    results += ProcessingResult.Error(notification.packageName, e)
                }
            }
        }
        return results
    }

    private suspend fun processInternal(notification: RawNotification, initializeClassifier: Boolean) {
        if (initializeClassifier) {
            classifier.initialize()
        }

        val parsed = parserRegistry.parseWithAiFallback(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            val oversizedCandidate = detectOversizedAmountCandidate(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText
            )
            database.withTransaction {
                val rawId = dao.insertOrIgnore(notification)
                if (rawId == -1L) return@withTransaction
                if (oversizedCandidate != null) {
                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = oversizedCandidate.amount,
                        suggestedCurrency = oversizedCandidate.currency,
                        suggestedMerchant = oversizedCandidate.merchantHint ?: "Unknown",
                        suggestedType = TransactionType.UNKNOWN.name,
                        suggestedCategoryId = null,
                        suggestedDate = notification.timestamp,
                        confidence = 0.5f,
                        explanation = "Oversized amount needs manual confirmation",
                        packageName = notification.packageName,
                        notificationTitle = notification.title,
                        notificationText = notification.text ?: notification.bigText
                    )
                    pendingReviewDao.insert(review)
                    sourceStatsDao.incrementTotalAndPending(notification.packageName, timeProvider.now())
                    dao.markRelevance(rawId, true)
                } else {
                    sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())
                    dao.markRelevance(rawId, false)
                }
            }
            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            return
        }

        val fullNotificationText = listOfNotNull(
            notification.title, notification.text, notification.bigText
        ).joinToString(" ")

        var routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )

        if (parsed.amount > 1_000_000.0 && routingResult.decision == RoutingDecision.AUTO_ACCEPT) {
            Timber.w("Auto-accept suppressed: amount exceeds validation limit")
            routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
        }

        val correctedMerchant = merchantNormalizer.normalize(parsed.merchant).canonical.normalizedName

        database.withTransaction {
            val rawId = dao.insertOrIgnore(notification)
            if (rawId == -1L) return@withTransaction

            confidenceRouter.ensureSourceStats(notification.packageName)

            when (routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> handleAutoAccept(
                    notification, rawId, parsed, correctedMerchant, fullNotificationText, routingResult
                )
                RoutingDecision.NEEDS_REVIEW -> handleNeedsReview(
                    notification, rawId, parsed, correctedMerchant, fullNotificationText, routingResult
                )
                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())
                }
            }

            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            confidenceRouter.invalidateMerchantCache(correctedMerchant)
        }
    }

    internal data class OversizedAmountCandidate(
        val amount: Double,
        val currency: String,
        val merchantHint: String?
    )

    internal companion object {
        private const val DEFAULT_RECOMMENDATION_USER_ID = "default_user"
        private const val RECOMMENDATION_JOB_TIMEOUT_MS = 3_000L
        private const val SUBSCRIPTION_DETECTION_TIMEOUT_MS = 5_000L
        private const val MAX_CONCURRENT_RECOMMENDATION_JOBS = 2
        private const val SUBSCRIPTION_LOOKBACK_DAYS = 120
        private val CURRENCY_HINT_REGEX = Regex("""(€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b)""", RegexOption.IGNORE_CASE)
        private val TRANSACTION_HINT_REGEX = Regex(
            """(paid|payment|purchase|transaction|transfer|sent|received|χρεωση|πληρωμη|αγορα|καταθεση|πιστωση)""",
            RegexOption.IGNORE_CASE
        )
        private val AMOUNT_TOKEN_REGEX = Regex(
            """(?:€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b)?\s*[+-]?\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?""",
            RegexOption.IGNORE_CASE
        )

        internal fun detectOversizedAmountCandidate(
            title: String?,
            text: String?,
            bigText: String?
        ): OversizedAmountCandidate? {
            val fullText = listOfNotNull(title, text, bigText)
                .joinToString(" ")
                .trim()
            if (fullText.isBlank()) return null

            // Avoid promoting random large numbers (order IDs, phone numbers) into review.
            if (!CURRENCY_HINT_REGEX.containsMatchIn(fullText) || !TRANSACTION_HINT_REGEX.containsMatchIn(fullText)) {
                return null
            }

            val oversized = AMOUNT_TOKEN_REGEX.findAll(fullText)
                .mapNotNull { AmountUtils.parseAmount(it.value) }
                .firstOrNull { it > AppConfig.MAX_TRANSACTION_AMOUNT }
                ?: return null

            val currency = when {
                fullText.contains("USD", ignoreCase = true) || fullText.contains("$") -> "USD"
                fullText.contains("GBP", ignoreCase = true) || fullText.contains("£") -> "GBP"
                else -> "EUR"
            }

            val merchantHint = extractMerchantHint(title ?: text ?: bigText)
            return OversizedAmountCandidate(
                amount = oversized,
                currency = currency,
                merchantHint = merchantHint
            )
        }

        private fun extractMerchantHint(input: String?): String? {
            val raw = input ?: return null
            val fromAt = Regex("""\bat\s+([A-Za-zΑ-Ωα-ω0-9 .,'&-]{2,40})""", RegexOption.IGNORE_CASE)
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            return fromAt?.takeIf { it.isNotBlank() }
        }

        internal fun hasNearDuplicatePendingReview(
            existing: List<PendingReview>,
            amount: Double,
            currency: String
        ): Boolean {
            return existing.any { review ->
                review.status == com.yourname.expensetracker.data.database.entity.PendingReviewStatus.PENDING &&
                    review.suggestedCurrency.equals(currency, ignoreCase = true) &&
                    abs(review.suggestedAmount - amount) < 0.01
            }
        }
    }

    private suspend fun handleAutoAccept(
        notification: RawNotification,
        rawId: Long,
        parsed: com.yourname.expensetracker.domain.parser.ParsedTransaction,
        correctedMerchant: String,
        fullText: String,
        routingResult: com.yourname.expensetracker.domain.intelligence.RoutingResult
    ) {
        val isDuplicate = expenseDao.isDuplicate(
            amount = parsed.amount,
            merchant = correctedMerchant,
            date = notification.timestamp,
            windowMs = 300_000
        )
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            classifier.train(fullText, isTransaction = true)
            return
        }

        val classification = hybridClassifier.classify(
            merchantName = correctedMerchant,
            amount = parsed.amount,
            notificationTitle = notification.title,
            notificationText = notification.text,
            packageName = notification.packageName
        )
        val categoryId = classification.categoryId.takeIf { it > 0 }

        val direction = parsed.transferDirection
            ?: directionDetector.detectDirection(
                notification.title, notification.text, notification.bigText, parsed.type
            )
        val accountName = parsed.transferAccountName
            ?: directionDetector.extractAccountName(
                notification.title, notification.text, notification.bigText
            )

        // Capture device GPS at notification time so the expense gets an immediate
        // location instead of waiting for the 6-hour background backfill worker.
        // We use "best-effort" — if GPS is unavailable the expense is created without
        // coordinates and the backfill worker will try later via geocoding.
        val deviceGps = try {
            locationProvider.getLastKnownLocation()
        } catch (e: Exception) {
            Timber.w(e, "GPS unavailable at notification time for merchant: $correctedMerchant")
            null
        }

        val expenseDate = parsed.date ?: notification.timestamp
        val expense = Expense(
            amount = parsed.amount,
            currency = parsed.currency,
            merchant = correctedMerchant,
            merchantKey = MerchantKeyGenerator.generate(correctedMerchant),
            transactionType = parsed.type,
            date = expenseDate,
            rawNotificationId = rawId,
            categoryId = categoryId,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            dedupeKey = Expense.generateDedupeKey(parsed.amount, correctedMerchant, expenseDate),
            transferDirection = direction,
            transferAccountName = accountName,
            latitude = deviceGps?.first,
            longitude = deviceGps?.second,
            locationSource = if (deviceGps != null) "DEVICE_GPS" else null
        )

        val expenseId = expenseDao.insertAtomic(expense)
        if (expenseId > 0) {
            dao.markRelevance(rawId, true)
            sourceStatsDao.incrementTotalAndAccepted(notification.packageName, timeProvider.now())
            if (expense.transactionType == TransactionType.TRANSFER || expense.transactionType == TransactionType.DEPOSIT) {
                if (direction != null) analytics.recordAutoDetection(direction, accountName, wasCorrect = true)
                else analytics.recordUnknownDirection()
            }
            budgetMonitor.checkBudgets()

            // Check for anomalies and alert
            val enrichedExpense = expense.copy(id = expenseId)
            val expenseWithCategory = com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                expense = enrichedExpense,
                category = categoryId?.let { database.categoryDao().getById(it) }
            )
            anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)

            classifier.train(fullText, isTransaction = true)

            // Non-blocking recommendation generation (fire-and-forget)
            asyncScope.launch {
                recommendationSemaphore.withPermit {
                    try {
                        withTimeoutOrNull(RECOMMENDATION_JOB_TIMEOUT_MS) {
                            val aiSettings = aiSettingsRepository.settings().first()
                            if (aiSettings.aiEnabled) {
                                val enrichedExpense = expense.copy(id = expenseId)
                                val aiArtifact = generateTransactionInsightUseCase(enrichedExpense)

                                val recommendations = dashboardFollowThroughEngine.generateRecommendations(
                                    transaction = enrichedExpense,
                                    aiArtifact = aiArtifact,
                                    userId = DEFAULT_RECOMMENDATION_USER_ID
                                )
                                recommendationRepository.saveAll(recommendations)
                            }
                        } ?: Timber.w("Recommendation generation timed out after ${RECOMMENDATION_JOB_TIMEOUT_MS}ms")
                    } catch (_: Exception) {
                        // Best-effort only.
                    }
                }
            }

            // Non-blocking subscription detection (fire-and-forget)
            asyncScope.launch {
                try {
                    withTimeoutOrNull(SUBSCRIPTION_DETECTION_TIMEOUT_MS) {
                        detectAndSaveSubscriptionCandidate(correctedMerchant, categoryId)
                    } ?: Timber.w("Subscription detection timed out after ${SUBSCRIPTION_DETECTION_TIMEOUT_MS}ms")
                } catch (e: Exception) {
                    Timber.w(e, "Subscription detection failed for merchant: $correctedMerchant")
                }
            }
        } else {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            classifier.train(fullText, isTransaction = true)
        }
    }

    private suspend fun handleNeedsReview(
        notification: RawNotification,
        rawId: Long,
        parsed: com.yourname.expensetracker.domain.parser.ParsedTransaction,
        correctedMerchant: String,
        fullText: String,
        routingResult: com.yourname.expensetracker.domain.intelligence.RoutingResult
    ) {
        val isDuplicate = expenseDao.isDuplicate(
            amount = parsed.amount,
            merchant = correctedMerchant,
            date = notification.timestamp,
            windowMs = 300_000
        )
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            classifier.train(fullText, isTransaction = true)
            return
        }
        val nearbyPending = pendingReviewDao.getPendingReviewsByMerchantAndDateRange(
            merchantPattern = correctedMerchant,
            startDate = notification.timestamp - 300_000,
            endDate = notification.timestamp + 300_000
        )
        if (hasNearDuplicatePendingReview(nearbyPending, parsed.amount, parsed.currency)) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            classifier.train(fullText, isTransaction = true)
            return
        }

        val classification = hybridClassifier.classify(
            merchantName = correctedMerchant,
            amount = parsed.amount,
            notificationTitle = notification.title,
            notificationText = notification.text,
            packageName = notification.packageName
        )
        val suggestedCategoryId = classification.categoryId.takeIf { it > 0 }

        val direction = parsed.transferDirection
            ?: directionDetector.detectDirection(
                notification.title, notification.text, notification.bigText, parsed.type
            )
        val accountName = parsed.transferAccountName
            ?: directionDetector.extractAccountName(
                notification.title, notification.text, notification.bigText
            )

        // Capture device GPS at review time so the reviewer sees a suggested location
        // pre-populated in the ReviewScreen. Best-effort — null is fine, the user can
        // set it manually or the backfill worker will geocode after approval.
        val deviceGpsForReview = try {
            locationProvider.getLastKnownLocation()
        } catch (e: Exception) {
            Timber.w(e, "GPS unavailable at review time for merchant: $correctedMerchant")
            null
        }

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
            suggestedDate = parsed.date,
            suggestedDirection = direction?.name,
            suggestedAccountName = accountName,
            suggestedLatitude = deviceGpsForReview?.first,
            suggestedLongitude = deviceGpsForReview?.second
        )
        pendingReviewDao.insert(review)
        sourceStatsDao.incrementTotalAndPending(notification.packageName, timeProvider.now())

        if (parsed.type == TransactionType.TRANSFER || parsed.type == TransactionType.DEPOSIT) {
            if (direction != null) analytics.recordAutoDetection(direction, accountName, wasCorrect = true)
            else analytics.recordUnknownDirection()
        }
    }

    /**
     * Detects subscription candidates for a merchant based on recent transactions.
     * Fetches the last SUBSCRIPTION_LOOKBACK_DAYS of transaction history for the merchant
     * and runs pattern detection. If a subscription is detected, saves it to the database.
     */
    private suspend fun detectAndSaveSubscriptionCandidate(merchant: String, categoryId: Long?) {
        try {
            // Skip if this merchant already has a pending candidate
            if (subscriptionCandidateDao.hasPendingCandidate(merchant)) {
                Timber.d("Skipping subscription detection for $merchant: already has pending candidate")
                return
            }

            // Fetch recent transactions for this merchant
            val since = timeProvider.now() - (SUBSCRIPTION_LOOKBACK_DAYS * TimePeriodUtils.DAY_IN_MILLIS)
            val recentExpenses = expenseDao.getRecentExpensesForMerchant(merchant, since)

            if (recentExpenses.size < NotificationSubscriptionDetector.MIN_TRANSACTIONS) {
                Timber.d("Not enough transactions for $merchant: ${recentExpenses.size}")
                return
            }

            // Convert to ExpenseWithCategory for the detector
            val expensesWithCategory = recentExpenses.map { expense ->
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                    expense = expense,
                    category = categoryId?.let { database.categoryDao().getById(it) }
                )
            }

            // Run subscription detection
            val candidates = subscriptionDetector.detectSubscriptions(expensesWithCategory)

            // Save high-confidence candidates (avoid duplicates for same merchant)
            candidates.firstOrNull()?.let { candidate ->
                // Double-check no pending candidate exists for this canonical merchant
                if (!subscriptionCandidateDao.hasPendingCandidate(candidate.canonicalMerchant)) {
                    val entity = subscriptionDetector.toEntity(candidate)
                    subscriptionCandidateDao.insert(entity)
                    Timber.i("Saved subscription candidate for ${candidate.canonicalMerchant} " +
                            "(${candidate.detectedInterval}, confidence=${"%.2f".format(candidate.confidence)})")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error detecting subscription candidate for: $merchant")
        }
    }

}
