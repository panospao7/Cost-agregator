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
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.database.entity.TransferDirection
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

        // Phase 1: Pre-DB work (no transaction held)
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

            // Phase 2: DB transaction (DB-only mutations)
            database.withTransaction {
                val rawId = dao.insertOrIgnore(notification)
                if (rawId == -1L) return@withTransaction

                // Ensure source_stats row exists before any increment updates.
                sourceStatsDao.insertIfNotExists(SourceStats(packageName = notification.packageName))

                if (oversizedCandidate != null) {
                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = oversizedCandidate.amount,
                        suggestedCurrency = oversizedCandidate.currency,
                        suggestedMerchant = oversizedCandidate.merchantHint ?: "Unknown",
                        suggestedMerchantKey = MerchantKeyGenerator.generate(oversizedCandidate.merchantHint ?: "Unknown"),
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

            // Phase 3: Post-commit best-effort actions
            runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
                confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            }
            return
        }

        val preDbContext = buildPreDbContext(notification, parsed)

        // Phase 2: DB transaction (DB-only mutations)
        val dbOutcome = database.withTransaction {
            val rawId = dao.insertOrIgnore(notification)
            if (rawId == -1L) return@withTransaction ParsedDbOutcome.RawDuplicate

            // Keep source stats row creation transactional while avoiding non-DB work.
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = notification.packageName))

            when (preDbContext.routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> handleAutoAcceptInTransaction(notification, rawId, preDbContext)
                RoutingDecision.NEEDS_REVIEW -> handleNeedsReviewInTransaction(notification, rawId, preDbContext)
                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, timeProvider.now())
                    ParsedDbOutcome.AutoRejected
                }
            }
        }

        if (dbOutcome == ParsedDbOutcome.RawDuplicate) return

        // Phase 3: Post-commit best-effort actions
        runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
            confidenceRouter.invalidateSourceStatsCache(notification.packageName)
        }
        runPostCommitSafely("invalidate merchant cache for ${preDbContext.correctedMerchant}") {
            confidenceRouter.invalidateMerchantCache(preDbContext.correctedMerchant)
        }

        runParsedPostCommitActions(notification, preDbContext, dbOutcome)
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

        // Kept for deterministic unit tests.
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

    private sealed class ParsedDbOutcome {
        object RawDuplicate : ParsedDbOutcome()
        object AutoRejected : ParsedDbOutcome()
        object Duplicate : ParsedDbOutcome()
        object NeedsReviewCreated : ParsedDbOutcome()
        data class AutoAccepted(
            val expenseId: Long,
            val insertedExpense: Expense
        ) : ParsedDbOutcome()
    }

    private data class PreDbContext(
        val parsed: com.yourname.expensetracker.domain.parser.ParsedTransaction,
        val transactionType: TransactionType,
        val fullNotificationText: String,
        val routingResult: com.yourname.expensetracker.domain.intelligence.RoutingResult,
        val correctedMerchant: String,
        val eventDate: Long,
        val merchantKey: String,
        val dedupeKey: String,
        val categoryId: Long?,
        val direction: TransferDirection?,
        val accountName: String?,
        val deviceGps: Pair<Double, Double>?
    )

    private suspend fun buildPreDbContext(
        notification: RawNotification,
        parsed: com.yourname.expensetracker.domain.parser.ParsedTransaction
    ): PreDbContext {
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
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

        val shouldPrepareAcceptedOrReviewData = routingResult.decision != RoutingDecision.AUTO_REJECT

        val categoryId = if (shouldPrepareAcceptedOrReviewData) {
            val classification = hybridClassifier.classify(
                merchantName = correctedMerchant,
                amount = parsed.amount,
                notificationTitle = notification.title,
                notificationText = notification.text,
                packageName = notification.packageName
            )
            classification.categoryId.takeIf { it > 0 }
        } else {
            null
        }

        val direction = if (shouldPrepareAcceptedOrReviewData) {
            (parsed.transferDirection
                ?: directionDetector.detectDirection(
                    notification.title,
                    notification.text,
                    notification.bigText,
                    parsed.type
                ))?.toDbTransferDirection()
        } else {
            null
        }

        val accountName = if (shouldPrepareAcceptedOrReviewData) {
            parsed.transferAccountName
                ?: directionDetector.extractAccountName(
                    notification.title,
                    notification.text,
                    notification.bigText
                )
        } else {
            null
        }

        // Best-effort location capture outside DB transaction.
        val deviceGps = if (shouldPrepareAcceptedOrReviewData) {
            try {
                locationProvider.getLastKnownLocation()
            } catch (e: Exception) {
                Timber.w(e, "GPS unavailable at notification time for merchant: $correctedMerchant")
                null
            }
        } else {
            null
        }

        val eventDate = parsed.date ?: notification.timestamp
        val merchantKey = MerchantKeyGenerator.generate(correctedMerchant)
        val dedupeKey = Expense.generateDedupeKey(parsed.amount, correctedMerchant, eventDate)

        return PreDbContext(
            parsed = parsed,
            transactionType = parsed.type.toDbTransactionType(),
            fullNotificationText = fullNotificationText,
            routingResult = routingResult,
            correctedMerchant = correctedMerchant,
            eventDate = eventDate,
            merchantKey = merchantKey,
            dedupeKey = dedupeKey,
            categoryId = categoryId,
            direction = direction,
            accountName = accountName,
            deviceGps = deviceGps
        )
    }

    private suspend fun handleAutoAcceptInTransaction(
        notification: RawNotification,
        rawId: Long,
        preDb: PreDbContext
    ): ParsedDbOutcome {
        val isDuplicate = expenseDao.isDuplicate(
            amount = preDb.parsed.amount,
            merchant = preDb.correctedMerchant,
            date = preDb.eventDate,
            windowMs = 300_000,
            merchantKey = preDb.merchantKey,
            dedupeKey = preDb.dedupeKey
        )
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            return ParsedDbOutcome.Duplicate
        }

        val expense = Expense(
            amount = preDb.parsed.amount,
            currency = preDb.parsed.currency,
            merchant = preDb.correctedMerchant,
            merchantKey = preDb.merchantKey,
            transactionType = preDb.transactionType,
            date = preDb.eventDate,
            rawNotificationId = rawId,
            categoryId = preDb.categoryId,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            dedupeKey = preDb.dedupeKey,
            transferDirection = preDb.direction,
            transferAccountName = preDb.accountName,
            latitude = preDb.deviceGps?.first,
            longitude = preDb.deviceGps?.second,
            locationSource = if (preDb.deviceGps != null) "DEVICE_GPS" else null
        )

        val expenseId = expenseDao.insertAtomic(expense)
        return if (expenseId > 0) {
            dao.markRelevance(rawId, true)
            sourceStatsDao.incrementTotalAndAccepted(notification.packageName, timeProvider.now())
            ParsedDbOutcome.AutoAccepted(
                expenseId = expenseId,
                insertedExpense = expense
            )
        } else {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            ParsedDbOutcome.Duplicate
        }
    }

    private suspend fun handleNeedsReviewInTransaction(
        notification: RawNotification,
        rawId: Long,
        preDb: PreDbContext
    ): ParsedDbOutcome {
        val isDuplicate = expenseDao.isDuplicate(
            amount = preDb.parsed.amount,
            merchant = preDb.correctedMerchant,
            date = preDb.eventDate,
            windowMs = 300_000,
            merchantKey = preDb.merchantKey,
            dedupeKey = preDb.dedupeKey
        )
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            return ParsedDbOutcome.Duplicate
        }

        val hasPendingDuplicate = pendingReviewDao.hasPendingDuplicateInRange(
            merchantKey = preDb.merchantKey,
            merchantName = preDb.correctedMerchant,
            startDate = preDb.eventDate - 300_000,
            endDate = preDb.eventDate + 300_000,
            minAmount = preDb.parsed.amount - 0.01,
            maxAmount = preDb.parsed.amount + 0.01,
            currency = preDb.parsed.currency
        )
        if (hasPendingDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, timeProvider.now())
            return ParsedDbOutcome.Duplicate
        }

        val review = PendingReview(
            rawNotificationId = rawId,
            suggestedAmount = preDb.parsed.amount,
            suggestedCurrency = preDb.parsed.currency,
            suggestedMerchant = preDb.correctedMerchant,
            suggestedMerchantKey = preDb.merchantKey,
            suggestedType = preDb.transactionType.name,
            suggestedCategoryId = preDb.categoryId,
            confidence = preDb.routingResult.adjustedConfidence,
            packageName = notification.packageName,
            notificationTitle = notification.title,
            notificationText = notification.text ?: notification.bigText,
            suggestedDate = preDb.eventDate,
            suggestedDirection = preDb.direction?.name,
            suggestedAccountName = preDb.accountName,
            suggestedLatitude = preDb.deviceGps?.first,
            suggestedLongitude = preDb.deviceGps?.second
        )
        pendingReviewDao.insert(review)
        sourceStatsDao.incrementTotalAndPending(notification.packageName, timeProvider.now())
        return ParsedDbOutcome.NeedsReviewCreated
    }

    private suspend fun runParsedPostCommitActions(
        notification: RawNotification,
        preDb: PreDbContext,
        dbOutcome: ParsedDbOutcome
    ) {
        when (dbOutcome) {
            ParsedDbOutcome.RawDuplicate,
            ParsedDbOutcome.AutoRejected -> Unit

            ParsedDbOutcome.Duplicate -> {
                runPostCommitSafely("classifier training after duplicate (${notification.packageName})") {
                    classifier.train(preDb.fullNotificationText, isTransaction = true)
                }
            }

            ParsedDbOutcome.NeedsReviewCreated -> {
                runTransferAnalyticsPostCommit(
                    transactionType = preDb.transactionType,
                    direction = preDb.direction,
                    accountName = preDb.accountName,
                    transferId = null
                )
            }

            is ParsedDbOutcome.AutoAccepted -> {
                runTransferAnalyticsPostCommit(
                    transactionType = preDb.transactionType,
                    direction = preDb.direction,
                    accountName = preDb.accountName,
                    transferId = dbOutcome.expenseId
                )

                runPostCommitSafely("budget check after notification auto-accept (expenseId=${dbOutcome.expenseId})") {
                    budgetMonitor.checkBudgets()
                }

                val enrichedExpense = dbOutcome.insertedExpense.copy(id = dbOutcome.expenseId)

                runPostCommitSafely("anomaly alert check after notification auto-accept (expenseId=${dbOutcome.expenseId})") {
                    val expenseWithCategory = com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                        expense = enrichedExpense,
                        category = preDb.categoryId?.let { database.categoryDao().getById(it) }
                    )
                    anomalyAlertOrchestrator.checkAndAlert(expenseWithCategory)
                }

                runPostCommitSafely("classifier training after notification auto-accept (${notification.packageName})") {
                    classifier.train(preDb.fullNotificationText, isTransaction = true)
                }

                runPostCommitSafely("launch recommendation enrichment (expenseId=${dbOutcome.expenseId})") {
                    launchRecommendationEnrichment(enrichedExpense)
                }

                runPostCommitSafely("launch subscription detection (${preDb.correctedMerchant})") {
                    launchSubscriptionDetection(preDb.correctedMerchant, preDb.categoryId)
                }
            }
        }
    }

    private suspend fun runTransferAnalyticsPostCommit(
        transactionType: TransactionType,
        direction: TransferDirection?,
        accountName: String?,
        transferId: Long?
    ) {
        if (transactionType != TransactionType.TRANSFER && transactionType != TransactionType.DEPOSIT) return

        runPostCommitSafely("transfer analytics update") {
            if (direction != null) {
                analytics.recordAutoDetection(
                    direction = direction,
                    accountName = accountName,
                    wasCorrect = true,
                    transferId = transferId
                )
            } else {
                analytics.recordUnknownDirection()
            }
        }
    }

    private fun launchRecommendationEnrichment(expense: Expense) {
        // Non-blocking recommendation generation (fire-and-forget)
        asyncScope.launch {
            recommendationSemaphore.withPermit {
                try {
                    withTimeoutOrNull(RECOMMENDATION_JOB_TIMEOUT_MS) {
                        val aiSettings = aiSettingsRepository.settings().first()
                        if (aiSettings.aiEnabled) {
                            val aiArtifact = generateTransactionInsightUseCase(expense)

                            val recommendations = dashboardFollowThroughEngine.generateRecommendations(
                                transaction = expense,
                                aiArtifact = aiArtifact,
                                userId = DEFAULT_RECOMMENDATION_USER_ID
                            )
                            recommendationRepository.saveAll(recommendations)
                        }
                    } ?: Timber.w("Recommendation generation timed out after ${RECOMMENDATION_JOB_TIMEOUT_MS}ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Recommendation generation failed for expenseId=${expense.id}")
                }
            }
        }
    }

    private fun launchSubscriptionDetection(merchant: String, categoryId: Long?) {
        // Non-blocking subscription detection (fire-and-forget)
        asyncScope.launch {
            try {
                withTimeoutOrNull(SUBSCRIPTION_DETECTION_TIMEOUT_MS) {
                    detectAndSaveSubscriptionCandidate(merchant, categoryId)
                } ?: Timber.w("Subscription detection timed out after ${SUBSCRIPTION_DETECTION_TIMEOUT_MS}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Subscription detection failed for merchant: $merchant")
            }
        }
    }

    private suspend fun runPostCommitSafely(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Post-commit action failed: $action")
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
            val merchantPendingSet = subscriptionCandidateDao
                .getPendingCanonicalMerchants(listOf(merchant))
                .toSet()
            if (merchant in merchantPendingSet) {
                Timber.d("Skipping subscription detection for $merchant: already has pending candidate")
                return
            }

            // Fetch recent transactions for this merchant
            val since = timeProvider.now() - (SUBSCRIPTION_LOOKBACK_DAYS * TimePeriodUtils.DAY_IN_MILLIS)
            val recentExpenses = expenseDao.getRecentExpensesWithCategoryForMerchant(merchant, since)

            if (recentExpenses.size < NotificationSubscriptionDetector.MIN_TRANSACTIONS) {
                Timber.d("Not enough transactions for $merchant: ${recentExpenses.size}")
                return
            }

            val fallbackCategory = categoryId
                ?.takeIf { it > 0 }
                ?.let { database.categoryDao().getById(it) }

            val expensesWithCategory = if (fallbackCategory == null) {
                recentExpenses
            } else {
                recentExpenses.map { expenseWithCategory ->
                    if (expenseWithCategory.category != null || expenseWithCategory.expense.categoryId != null) {
                        expenseWithCategory
                    } else {
                        expenseWithCategory.copy(category = fallbackCategory)
                    }
                }
            }

            // Run subscription detection
            val candidates = subscriptionDetector.detectSubscriptions(expensesWithCategory)

            if (candidates.isEmpty()) return

            // Save high-confidence candidates while avoiding per-candidate EXISTS queries.
            val candidateMerchants = candidates.map { it.canonicalMerchant }.distinct()
            val existingPending = subscriptionCandidateDao
                .getPendingCanonicalMerchants(candidateMerchants)
                .toHashSet()

            candidates.firstOrNull { it.canonicalMerchant !in existingPending }?.let { candidate ->
                val entity = subscriptionDetector.toEntity(candidate)
                subscriptionCandidateDao.insert(entity)
                Timber.i(
                    "Saved subscription candidate for ${candidate.canonicalMerchant} " +
                        "(${candidate.detectedInterval}, confidence=${"%.2f".format(candidate.confidence)})"
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Error detecting subscription candidate for: $merchant")
        }
    }

}
