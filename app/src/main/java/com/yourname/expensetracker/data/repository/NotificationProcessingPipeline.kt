package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExtractionState
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SideEffectMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
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
import org.json.JSONObject
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
 *
 * ## TRN-8: Fingerprint pre-check before parse/AI fallback
 * The current pipeline performs an expensive parse + optional AI fallback
 * ([parserRegistry.parseWithAiFallback]) at line 161 **before** checking for
 * duplicates. If the notification is a duplicate, the parse effort is wasted.
 * A future optimisation should move the raw-notification duplicate check (or
 * a lightweight fingerprint check) **before** the parse call, returning early
 * when a matching raw notification already exists. The `insertRawNotificationIfNotDuplicate`
 * guard at Phase 2 is too late — the expensive work has already been done.
 *
 * ## AID-9: AI auto-apply audit requirement
 * When [RoutingDecision.AUTO_ACCEPT] is chosen by the confidence router, the
 * pipeline creates an [Expense] directly without user review via
 * [handleAutoAcceptInTransaction]. This is an AI-driven decision and MUST be
 * auditable. Any future changes to the auto-accept path MUST:
 * 1. Log the routing decision, confidence score, and source notification
 * 2. Provide a mechanism to review and revert auto-accepted expenses
 * 3. Notify the user when an expense is auto-created from AI classification
 *
 * ### Implementation (AID-9):
 * - On auto-accept, a [TransactionEvent] with eventType `AI_AUTO_ACCEPT` is
 *   written to `transaction_events` containing the confidence score, routing
 *   decision, raw notification ID (in `metadata`), and a sanitised notification
 *   payload (package, title, amount, merchant) in `reason`.
 * - The expense source is set to [ExpenseSource.NOTIFICATION_AUTO_ACCEPT] so
 *   auto-created expenses are identifiable in queries and the debug viewer.
 * - A `Timber.i` log is emitted at creation time for visibility in the debug log.
 *
 * See also [TransactionEvent] KDoc for the proposed `ai_audit_log` table schema.
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
    private val timeProvider: TimeProvider,
    private val directionDetector: TransferDirectionDetector,
    private val analytics: TransferDirectionAnalytics,
    private val locationProvider: ForegroundLocationProvider,
    private val aiSettingsRepository: AiSettingsRepository,
    private val generateTransactionInsightUseCase: GenerateTransactionInsightUseCase,
    private val dashboardFollowThroughEngine: DashboardFollowThroughEngine,
    private val recommendationRepository: RecommendationRepository,
    private val subscriptionDetector: NotificationSubscriptionDetector,
    private val coordinator: TransactionLifecycleCoordinator,
    private val transactionEventDao: TransactionEventDao,
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
        val sourceStatsTimestamp = timeProvider.now()

        // ── TRN-8: Fast fingerprint dedup check before expensive parse ──────────
        // Check if an identical notification already exists in the DB. If it does,
        // skip the expensive parse + AI fallback entirely. The raw-notification
        // dedup in insertRawNotificationIfNotDuplicate (Phase 2) was too late —
        // the parse work had already been wasted.
        if (dao.exists(
                packageName = notification.packageName,
                timestamp = notification.timestamp,
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText
            )
        ) {
            Timber.d("TRN-8: Duplicate notification detected before parse, skipping: ${notification.packageName}")
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            return
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
                val rawId = insertRawNotificationIfNotDuplicate(notification)
                if (rawId == -1L) return@withTransaction

                // Ensure source_stats row exists before any increment updates.
                sourceStatsDao.insertIfNotExists(
                    SourceStats(
                        packageName = notification.packageName,
                        lastSeen = sourceStatsTimestamp
                    )
                )

                // ── Oversized-amount fallback ──────────────────────────────────
                // When a raw notification contains an amount exceeding the
                // MAX_TRANSACTION_AMOUNT threshold and the parser cannot extract
                // a structured transaction, we create a PendingReview with a
                // merchant hint.
                //
                // The "Unknown" merchant name here is a UI placeholder — the user
                // must provide a real merchant before approval.  approveReview()
                // blocks approval without a user override.  The coordinator also
                // rejects any creation request carrying this sentinel.
                if (oversizedCandidate != null) {
                    val oversizedMerchant = oversizedCandidate.merchantHint ?: "Unknown"
                    val oversizedMerchantKey = MerchantKeyGenerator.generate(oversizedMerchant)
                    val hasExpenseDuplicate = expenseDao.isDuplicateCurrencyAware(
                        amount = oversizedCandidate.amount,
                        merchant = oversizedMerchant,
                        date = notification.timestamp,
                        currency = oversizedCandidate.currency,
                        transactionType = TransactionType.UNKNOWN.name,
                        windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                        merchantKey = oversizedMerchantKey,
                        dedupeKey = null
                    )
                    val hasPendingDuplicate = pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
                        merchantKey = oversizedMerchantKey,
                        merchantName = oversizedMerchant,
                        startDate = notification.timestamp - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                        endDate = DuplicateDetectionPolicy.windowEndExclusive(notification.timestamp),
                        minAmount = oversizedCandidate.amount - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                        maxAmount = oversizedCandidate.amount + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                        currency = oversizedCandidate.currency,
                        transactionType = TransactionType.UNKNOWN.name
                    )

                    if (hasExpenseDuplicate || hasPendingDuplicate) {
                        sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                        dao.markRelevance(rawId, false)
                        return@withTransaction
                    }

                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = oversizedCandidate.amount,
                        suggestedCurrency = oversizedCandidate.currency,
                        suggestedMerchant = oversizedMerchant,
                        suggestedMerchantKey = oversizedMerchantKey,
                        suggestedType = TransactionType.UNKNOWN.name,
                        suggestedCategoryId = null,
                        suggestedDate = notification.timestamp,
                        confidence = 0.0f,
                        explanation = "Oversized amount needs manual confirmation",
                        packageName = notification.packageName,
                        notificationTitle = notification.title,
                        notificationText = notification.text ?: notification.bigText,
                        extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER
                    )
                    pendingReviewDao.upsertByRawNotificationId(review)
                    sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
                    dao.markRelevance(rawId, true)
                } else {
                    val transactionSignalCandidate = detectTransactionSignalCandidate(
                        title = notification.title,
                        text = notification.text,
                        bigText = notification.bigText
                    )

                    // ── Transaction-signal fallback ──────────────────────────────
                    // When a notification contains a transaction keyword and
                    // amount but the structured parser still failed (e.g. format
                    // not recognised), we create a PendingReview with a merchant
                    // hint.
                    //
                    // The "Unknown" merchant name is a UI placeholder — the user
                    // must provide a real merchant before approval.  Both
                    // approveReview() and the TransactionLifecycleCoordinator
                    // block creation of real expenses from this sentinel.
                    if (transactionSignalCandidate != null) {
                        val signalMerchant = transactionSignalCandidate.merchantHint ?: "Unknown"
                        val signalMerchantKey = MerchantKeyGenerator.generate(signalMerchant)
                        val hasExpenseDuplicate = expenseDao.isDuplicateCurrencyAware(
                            amount = transactionSignalCandidate.amount,
                            merchant = signalMerchant,
                            date = notification.timestamp,
                            currency = transactionSignalCandidate.currency,
                            transactionType = TransactionType.UNKNOWN.name,
                            windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                            merchantKey = signalMerchantKey,
                            dedupeKey = null
                        )
                        val hasPendingDuplicate = pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
                            merchantKey = signalMerchantKey,
                            merchantName = signalMerchant,
                            startDate = notification.timestamp - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                            endDate = DuplicateDetectionPolicy.windowEndExclusive(notification.timestamp),
                            minAmount = transactionSignalCandidate.amount - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                            maxAmount = transactionSignalCandidate.amount + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                            currency = transactionSignalCandidate.currency,
                            transactionType = TransactionType.UNKNOWN.name
                        )

                        if (hasExpenseDuplicate || hasPendingDuplicate) {
                            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                            dao.markRelevance(rawId, false)
                            return@withTransaction
                        }

                        val review = PendingReview(
                            rawNotificationId = rawId,
                            suggestedAmount = transactionSignalCandidate.amount,
                            suggestedCurrency = transactionSignalCandidate.currency,
                            suggestedMerchant = signalMerchant,
                            suggestedMerchantKey = signalMerchantKey,
                            suggestedType = TransactionType.UNKNOWN.name,
                            suggestedCategoryId = null,
                            suggestedDate = notification.timestamp,
                            confidence = 0.0f,
                            explanation = "Transaction signal detected but parser failed — needs manual confirmation",
                            packageName = notification.packageName,
                            notificationTitle = notification.title,
                            notificationText = notification.text ?: notification.bigText,
                            extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER
                        )
                        pendingReviewDao.upsertByRawNotificationId(review)
                        sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
                        dao.markRelevance(rawId, true)
                    } else {
                        sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, sourceStatsTimestamp)
                        dao.markRelevance(rawId, false)
                    }
                }
            }

            // Phase 3: Post-commit best-effort actions
            runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
                // AIML-13: Invalidate comprehensively after pipeline processing
                confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            }
            return
        }

        val preDbContext = buildPreDbContext(notification, parsed)

        // Phase 2: DB transaction (DB-only mutations)
        val dbOutcome = database.withTransaction {
            val rawId = insertRawNotificationIfNotDuplicate(notification)
            if (rawId == -1L) return@withTransaction ParsedDbOutcome.RawDuplicate

            // Keep source stats row creation transactional while avoiding non-DB work.
            sourceStatsDao.insertIfNotExists(
                SourceStats(
                    packageName = notification.packageName,
                    lastSeen = sourceStatsTimestamp
                )
            )

            when (preDbContext.routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> handleAutoAcceptInTransaction(
                    notification = notification,
                    rawId = rawId,
                    preDb = preDbContext,
                    sourceStatsTimestamp = sourceStatsTimestamp
                )
                RoutingDecision.NEEDS_REVIEW -> handleNeedsReviewInTransaction(
                    notification = notification,
                    rawId = rawId,
                    preDb = preDbContext,
                    sourceStatsTimestamp = sourceStatsTimestamp
                )
                RoutingDecision.AUTO_REJECT -> {
                    if (notification.packageName in FINANCIAL_PACKAGES) {
                        Timber.w("Auto-reject overridden to NEEDS_REVIEW for financial package: ${notification.packageName}")
                        handleNeedsReviewInTransaction(
                            notification = notification,
                            rawId = rawId,
                            preDb = preDbContext,
                            sourceStatsTimestamp = sourceStatsTimestamp
                        )
                    } else {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, sourceStatsTimestamp)
                        ParsedDbOutcome.AutoRejected
                    }
                }
            }
        }

        if (dbOutcome == ParsedDbOutcome.RawDuplicate) return

        // Phase 3: Post-commit best-effort actions
        runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
            // AIML-13: Invalidate comprehensively after pipeline processing
            confidenceRouter.invalidateAfterUserAction(
                notification.packageName,
                preDbContext.correctedMerchant
            )
        }

        runParsedPostCommitActions(notification, preDbContext, dbOutcome)
    }

    private suspend fun insertRawNotificationIfNotDuplicate(notification: RawNotification): Long {
        val alreadyExists = dao.exists(
            packageName = notification.packageName,
            timestamp = notification.timestamp,
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText
        )
        if (alreadyExists) {
            return -1L
        }
        return dao.insertOrIgnore(notification)
    }

    internal data class OversizedAmountCandidate(
        val amount: Double,
        val currency: String,
        val merchantHint: String?
    )

    internal data class TransactionSignalCandidate(
        val amount: Double,
        val currency: String,
        val merchantHint: String?
    )

    /**
     * Constants and utility methods for the notification processing pipeline.
     *
     * ## Fallback / Placeholder Values Policy
     *
     * When the pipeline cannot extract a structured transaction from a raw
     * notification it may create a [PendingReview] with sentinel / placeholder
     * values instead of a real [Expense]:
     *
     * - **Merchant:** `"Unknown"` — used when no merchant hint or parser
     *   result is available.  This is a **UI placeholder only**; it never
     *   becomes the merchant value of a real expense row.
     * - **Amount:** `null` — `suggestedAmount` is null when no amount could be
     *   parsed.  Blocked at approval time.
     *
     * Both guard gates (approveReview in ReviewQueueRepository and
     * TransactionLifecycleCoordinator.createExpense) reject any attempt to
     * promote these sentinel values into real expenses without explicit user
     * overrides.
     */
    internal companion object {
        private const val DEFAULT_RECOMMENDATION_USER_ID = "default_user"
        private const val RECOMMENDATION_JOB_TIMEOUT_MS = 3_000L
        private const val SUBSCRIPTION_DETECTION_TIMEOUT_MS = 5_000L
        private const val MAX_CONCURRENT_RECOMMENDATION_JOBS = 2
        private const val SUBSCRIPTION_LOOKBACK_DAYS = 120
        /**
         * Single source of truth for finance packages lives in [NotificationFilter.FINANCE_PACKAGES].
         * This alias prevents the two sets from drifting out of sync.
         */
        private val FINANCIAL_PACKAGES: Set<String>
            get() = com.yourname.expensetracker.service.NotificationFilter.FINANCE_PACKAGES
        private val CURRENCY_HINT_REGEX = Regex("""(€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b)""", RegexOption.IGNORE_CASE)
        private val TRANSACTION_HINT_REGEX = Regex(
            """(paid|payment|purchase|transaction|transfer|sent|received|χρεωση|πληρωμη|αγορα|καταθεση|πιστωση)""",
            RegexOption.IGNORE_CASE
        )
private val CURRENCY_SUFFIX_REGEX = Regex("""\s*(€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b)""", RegexOption.IGNORE_CASE)
private val CARD_TAIL_REGEX = Regex("""\*+\d{4}\b""")

private val AMOUNT_TOKEN_REGEX = Regex(
    """(?:€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b)?\s*[+-]?\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?(?:\s*(?:€|\$|£|\bEUR\b|\bUSD\b|\bGBP\b))?""",
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

    internal fun detectTransactionSignalCandidate(
        title: String?, text: String?, bigText: String?
    ): TransactionSignalCandidate? {
        val fullText = listOfNotNull(title, text, bigText)
            .joinToString(" ")
            .trim()
        if (fullText.isBlank()) return null

        if (!CURRENCY_HINT_REGEX.containsMatchIn(fullText) || !TRANSACTION_HINT_REGEX.containsMatchIn(fullText)) {
            return null
        }

        // Score amount candidates to prefer currency-attached amounts over bare
        // numbers (e.g. masked PAN fragments, order IDs). This mirrors the
        // disambiguation logic in GenericTransactionParser.extractAmount().
        data class ScoredAmount(val amount: Double, val score: Int)

        val candidates = mutableListOf<ScoredAmount>()
        for (match in AMOUNT_TOKEN_REGEX.findAll(fullText)) {
            val parsed = AmountUtils.parseAmount(match.value) ?: continue
            if (!parsed.isFinite() || parsed <= 0.01 || parsed > AppConfig.MAX_TRANSACTION_AMOUNT) continue

        var score = 0
        // Currency prefix/suffix in the matched token → strong signal
        if (CURRENCY_HINT_REGEX.containsMatchIn(match.value)) score += 4
        // Currency suffix immediately after the number (e.g. "4.08€", "4 EUR")
        if (CURRENCY_SUFFIX_REGEX.containsMatchIn(match.value)) score += 4
        // Decimal separator → likely an amount, not an ID
        if (parsed != parsed.toLong().toDouble()) score += 2

        // Proximity to a transaction keyword in the surrounding context
        val ctxStart = (match.range.first - 30).coerceAtLeast(0)
        val ctxEnd = (match.range.last + 30).coerceAtMost(fullText.length)
        val context = fullText.substring(ctxStart, ctxEnd)
        if (TRANSACTION_HINT_REGEX.containsMatchIn(context)) score += 2

        // Penalty: masked PAN tail (e.g. "Card *1234") → likely not an amount
        if (CARD_TAIL_REGEX.containsMatchIn(context)) score -= 3

        candidates.add(ScoredAmount(parsed, score))
        }
        if (candidates.isEmpty()) return null

        val best = candidates.maxWithOrNull(
            compareBy<ScoredAmount> { it.score }
                .thenByDescending { it.amount }
        ) ?: return null
        val amount = best.amount

        val currency = when {
            fullText.contains("USD", ignoreCase = true) || fullText.contains("$") -> "USD"
            fullText.contains("GBP", ignoreCase = true) || fullText.contains("£") -> "GBP"
            else -> "EUR"
        }

        val merchantHint = extractMerchantHint(title ?: text ?: bigText)
        return TransactionSignalCandidate(
            amount = amount, currency = currency, merchantHint = merchantHint
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
                    abs((review.suggestedAmount ?: 0.0) - amount) < DuplicateDetectionPolicy.AMOUNT_TOLERANCE
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

    private suspend fun hasCanonicalExpenseDuplicate(preDb: PreDbContext): Boolean {
        return expenseDao.isDuplicateCurrencyAware(
            amount = preDb.parsed.amount,
            merchant = preDb.correctedMerchant,
            date = preDb.eventDate,
            currency = preDb.parsed.currency,
            transactionType = preDb.transactionType.name,
            windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
            merchantKey = preDb.merchantKey,
            dedupeKey = preDb.dedupeKey
        )
    }

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
        val transactionType = parsed.type.toDbTransactionType()
        // Use type-aware key so that PURCHASE vs DEPOSIT/TRANSFER rows never collide on
        // the persisted unique dedupeKey index (ISSUE-8 fix). UNKNOWN type falls back to
        // the type-blind key for backward compatibility with legacy rows.
        val dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            parsed.amount, correctedMerchant, eventDate, parsed.currency, transactionType
        )

        return PreDbContext(
            parsed = parsed,
            transactionType = transactionType,
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

    /**
     * AID-9: Auto-accept creates an Expense directly from AI routing WITHOUT user review.
     * This path is auditable — a [TransactionEvent] with eventType `AI_AUTO_ACCEPT`
     * is written inside the DB transaction containing the routing decision, confidence
     * score, and sanitised notification payload. See class-level KDoc for details.
     */
    private suspend fun handleAutoAcceptInTransaction(
        notification: RawNotification,
        rawId: Long,
        preDb: PreDbContext,
        sourceStatsTimestamp: Long
    ): ParsedDbOutcome {
        val isDuplicate = hasCanonicalExpenseDuplicate(preDb)
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            return ParsedDbOutcome.Duplicate
        }

        // Check pending-review queue too — an identical transaction may already be
        // awaiting manual approval; inserting a second copy would create a real duplicate.
        val hasPendingDuplicate = pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
            merchantKey = preDb.merchantKey,
            merchantName = preDb.correctedMerchant,
            startDate = preDb.eventDate - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
            endDate = DuplicateDetectionPolicy.windowEndExclusive(preDb.eventDate),
            minAmount = preDb.parsed.amount - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
            maxAmount = preDb.parsed.amount + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
            currency = preDb.parsed.currency,
            transactionType = preDb.transactionType.name
        )
        if (hasPendingDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
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

        val request = CreateExpenseRequest(
            merchant = preDb.correctedMerchant,
            amount = preDb.parsed.amount,
            currency = preDb.parsed.currency,
            date = preDb.eventDate,
            transactionType = preDb.transactionType,
            source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT,
            categoryId = preDb.categoryId,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            transferDirection = preDb.direction,
            transferAccountName = preDb.accountName,
            latitude = preDb.deviceGps?.first,
            longitude = preDb.deviceGps?.second,
            locationSource = if (preDb.deviceGps != null) "DEVICE_GPS" else null,
            rawNotificationId = rawId,
            skipDeduplication = true
        )

        return when (val result = coordinator.createExpense(request, SideEffectMode.DEFER)) {
            is CreateExpenseResult.Created -> {
                val expenseId = result.expenseId
                dao.markRelevance(rawId, true)
                sourceStatsDao.incrementTotalAndAccepted(notification.packageName, sourceStatsTimestamp)

                // AID-9 Gap 1: Write audit event for AI auto-accept
                val auditMetadata = JSONObject().apply {
                    put("confidence", preDb.routingResult.adjustedConfidence)
                    put("routingDecision", preDb.routingResult.decision.name)
                    put("rawNotificationId", rawId)
                }.toString()
                val auditReason = JSONObject().apply {
                    put("packageName", notification.packageName)
                    put("title", notification.title)
                    put("amount", preDb.parsed.amount)
                    put("merchant", preDb.correctedMerchant)
                }.toString()
                runCatching {
                    transactionEventDao.insert(
                        TransactionEvent(
                            expenseId = expenseId,
                            eventType = "AI_AUTO_ACCEPT",
                            source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT.name,
                            actor = "system:ai_auto_accept",
                            occurredAt = timeProvider.now(),
                            dedupeKey = preDb.dedupeKey,
                            duplicateExpenseId = null,
                            beforeSnapshot = null,
                            afterSnapshot = null,
                            metadata = auditMetadata,
                            reason = auditReason
                        )
                    )
                }.onFailure { error ->
                    Timber.w(error, "AID-9: Failed to write AI_AUTO_ACCEPT audit event for expenseId=$expenseId")
                }

                // AID-9 Gap 3: Notify user via log (visible in debug viewer)
                Timber.i(
                    "AID-9: AI auto-created expense id=%d amount=%.2f %s merchant=%s",
                    expenseId,
                    preDb.parsed.amount,
                    preDb.parsed.currency,
                    preDb.correctedMerchant
                )

                ParsedDbOutcome.AutoAccepted(
                    expenseId = expenseId,
                    insertedExpense = expense
                )
            }

            is CreateExpenseResult.DuplicateSkipped -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                ParsedDbOutcome.Duplicate
            }

            is CreateExpenseResult.InsertConflict -> {
                check(hasCanonicalExpenseDuplicate(preDb)) {
                    "Expense insert conflicted without a canonical duplicate for rawId=$rawId"
                }
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                ParsedDbOutcome.Duplicate
            }

            is CreateExpenseResult.ValidationFailed -> {
                Timber.w("Auto-accept validation failed: ${result.errors}")
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                ParsedDbOutcome.Duplicate
            }

            is CreateExpenseResult.Error -> {
                throw result.exception
            }
        }
    }

    private suspend fun handleNeedsReviewInTransaction(
        notification: RawNotification,
        rawId: Long,
        preDb: PreDbContext,
        sourceStatsTimestamp: Long
    ): ParsedDbOutcome {
        val isDuplicate = hasCanonicalExpenseDuplicate(preDb)
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            return ParsedDbOutcome.Duplicate
        }

        // Use the shared windowEndExclusive helper so the pending-review boundary
        // matches the expense-duplicate boundary: both DAO queries use SQL `< :endDate`
        // (exclusive), so endDate must be date + windowMs + 1 to cover the full
        // inclusive range [date - windowMs, date + windowMs].
        val hasPendingDuplicate = pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
            merchantKey = preDb.merchantKey,
            merchantName = preDb.correctedMerchant,
            startDate = preDb.eventDate - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
            endDate = DuplicateDetectionPolicy.windowEndExclusive(preDb.eventDate),
            minAmount = preDb.parsed.amount - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
            maxAmount = preDb.parsed.amount + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
            currency = preDb.parsed.currency,
            transactionType = preDb.transactionType.name
        )
        if (hasPendingDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
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
        pendingReviewDao.upsertByRawNotificationId(review)
        sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
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
                runPostCommitSafely("lifecycle side effects after auto-accept (expenseId=${dbOutcome.expenseId})") {
                    coordinator.dispatchPostCreationSideEffects(
                        dbOutcome.expenseId,
                        ExpenseSource.NOTIFICATION_AUTO_ACCEPT
                    )
                }

                runTransferAnalyticsPostCommit(
                    transactionType = preDb.transactionType,
                    direction = preDb.direction,
                    accountName = preDb.accountName,
                    transferId = dbOutcome.expenseId
                )

                val enrichedExpense = dbOutcome.insertedExpense.copy(id = dbOutcome.expenseId)

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
                    direction = when (direction) {
                        TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                        TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                        null -> return@runPostCommitSafely
                    },
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
