package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExtractionState
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.notification.NotificationPersistenceContext
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.subscription.NotificationSubscriptionDetector
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.provenance.NotificationSourceLinkPayloadFactory
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceContext
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.TargetEntityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject
import com.yourname.expensetracker.domain.notification.RawNotificationInsertResult
import com.yourname.expensetracker.domain.notification.DuplicateBasis
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import javax.inject.Singleton

/**
 * Encapsulates the notification processing pipeline, extracted from [NotificationRepository]
 * to reduce its constructor dependency count.
 *
 * ## Pipeline Flow
 *
 * Every raw notification entering this pipeline traverses the following stages:
 *
 *  1. **Fast fingerprint dedup** — checks [RawNotificationDao.exists] before any
 *     expensive parse. Returns [NotificationPipelineOutcome.Duplicate] on hit.
 *  2. **Parse** — calls [AppParserRegistry.parseWithAiFallback] to extract
 *     amount / merchant / currency / date from the notification text.
 *  3. **Parse-fallback branch** — if parsing fails, detects oversized amounts
 *     and transaction signals to decide between NeedsReview / AutoRejected / Duplicate.
 *  4. **Routing** — [ConfidenceRouter.route] decides the disposition:
 *     AUTO_ACCEPT, NEEDS_REVIEW, or AUTO_REJECT.
 *  5. **DB transaction** — writes RawNotification, SourceStats, and the
 *     resolved outcome (Expense via coordinator, PendingReview, or rejection)
 *     atomically inside a Room transaction.
 *  6. **Post-commit actions** — best-effort side effects: analytics training,
 *     recommendation enrichment, subscription detection, transfer analytics.
 *
 * ## Outcomes Produced
 *
 * The pipeline returns one of the following [NotificationPipelineOutcome] sealed types:
 *  - [NotificationPipelineOutcome.AutoAccepted] — successfully created an Expense
 *  - [NotificationPipelineOutcome.NeedsReview] — created a PendingReview for manual approval
 *  - [NotificationPipelineOutcome.Duplicate] — rejected as a duplicate (fingerprint or canonical)
 *  - [NotificationPipelineOutcome.ParserFailed] — parsing failed with no signal
 *  - [NotificationPipelineOutcome.AutoRejected] — routing decision was AUTO_REJECT
 *  - [NotificationPipelineOutcome.Dropped] — dropped before processing (e.g. maintenance mode)
 *  - [NotificationPipelineOutcome.Error] — processing threw an exception
 *
 * Every outcome is recorded as a [PipelineDiagnosticEvent] for observability.
 *
 * ## TRN-8: Fingerprint pre-check before parse/AI fallback (FIXED)
 * A fast [RawNotificationDao.exists] fingerprint check now runs **before** the
 * expensive parse + AI fallback call. See [processInternal].
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

// DONE (P2-1): processInternal() now returns NotificationPipelineOutcome with packageName+correlationId.

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
    private val aiSettingsRepository: AiSettingsRepository,
    private val generateTransactionInsightUseCase: GenerateTransactionInsightUseCase,
    private val dashboardFollowThroughEngine: DashboardFollowThroughEngine,
    private val recommendationRepository: RecommendationRepository,
    private val subscriptionDetector: NotificationSubscriptionDetector,
    private val coordinator: TransactionLifecycleCoordinator,
    private val postCommitActionRunner: PostCommitActionRunner,
    private val pendingReviewSourceLinkService: PendingReviewSourceLinkService,
    private val sourceLinkWriter: SourceLinkWriter,
    private val transactionLifecycleEventWriter: com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter,
    private val diagnosticEmitter: com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter,
    private val writeBarrier: DatabaseWriteBarrier,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val userCurrencyProvider: UserCurrencyProvider,
    private val moneySignalDetector: com.yourname.expensetracker.domain.notification.money.NotificationMoneySignalDetector,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    // P1-PR5 (NEW-P1-008): Semaphore allows bounded concurrency instead of full serialization.
    // classifier.initialize() is idempotent and thread-safe (uses internal synchronization).
    private val processSemaphore = Semaphore(4)
    /**
     * App-scoped background enrichment for recommendations.
     * Ownership is the application lifecycle (not per-screen/request).
     */
    private val asyncScope: CoroutineScope = applicationScope
    private val recommendationSemaphore = Semaphore(MAX_CONCURRENT_RECOMMENDATION_JOBS)

    suspend fun process(notification: RawNotification,
                        persistenceContext: NotificationPersistenceContext? = null): NotificationPipelineOutcome {
        return process(notification, notification, persistenceContext = persistenceContext)
    }

    suspend fun process(notification: RawNotification, storageNotification: RawNotification,
                        correlationId: String? = null,
                        persistenceContext: NotificationPersistenceContext? = null): NotificationPipelineOutcome {
        writeBarrier.checkWritesAllowed("NotificationProcessingPipeline.process")
        processSemaphore.withPermit {
            // DDL-F876-08: generate cid OUTSIDE try so the exception catch path reuses it
            val cid = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
            return try {
                val outcome = processInternal(notification, storageNotification, initializeClassifier = true, correlationId = cid, persistenceContext = persistenceContext)
                writePipelineDiagnosticEvent(outcome, notification.packageName, correlationId = cid)
                outcome
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorOutcome = NotificationPipelineOutcome.Error(notification.packageName, cid, e)
                // DDL-F876-08: use the same cid so exceptions are traceable from listener
                writePipelineDiagnosticEvent(errorOutcome, notification.packageName, correlationId = cid)
                errorOutcome
            }
        }
    }

    /**
     * Processes a batch of raw notifications sequentially under a shared mutex.
     * Each notification produces a [NotificationPipelineOutcome] and, critically,
     * a [PipelineDiagnosticEvent] is written for every outcome — success, rejection,
     * or error — so that batch processing is fully observable in the diagnostic ledger.
     */
    suspend fun processBatch(notifications: List<RawNotification>): List<NotificationPipelineOutcome> {
        writeBarrier.checkWritesAllowed("NotificationProcessingPipeline.processBatch")
        if (notifications.isEmpty()) return emptyList()
        val results = mutableListOf<NotificationPipelineOutcome>()
        processSemaphore.withPermit {
            classifier.initialize()
            notifications.forEach { notification ->
                try {
                    val outcome = processInternal(notification, initializeClassifier = false)
                    writePipelineDiagnosticEvent(outcome, notification.packageName)
                    results += outcome
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing notification in batch: ${notification.packageName}")
                    val errorOutcome = NotificationPipelineOutcome.Error(notification.packageName, null, e)
                    writePipelineDiagnosticEvent(errorOutcome, notification.packageName)
                    results += errorOutcome
                }
            }
        }
        return results
    }

    private suspend fun processInternal(notification: RawNotification, storageNotification: RawNotification = notification, initializeClassifier: Boolean, correlationId: String? = null, persistenceContext: NotificationPersistenceContext? = null): NotificationPipelineOutcome {
        if (initializeClassifier) {
            classifier.initialize()
        }
        val sourceStatsTimestamp = timeProvider.now()

        // ── TRN-8: Fast fingerprint dedup check before expensive parse ──────────
        // Uses dedupeFingerprint (canonical content hash) instead of raw fields
        // so duplicate detection works under all RawStorageMode values.
        val dedupeFingerprint = notification.dedupeFingerprint
            ?: com.yourname.expensetracker.domain.notification.RawNotificationFingerprint.compute(
                packageName = notification.packageName,
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                timestamp = notification.timestamp
            )
        if (dao.existsByDedupeFingerprint(dedupeFingerprint)) {
            Timber.d("TRN-8: Duplicate notification detected by fingerprint before parse: ${notification.packageName}")
            Timber.d("Pipeline outcome: DUPLICATE for package=%s", notification.packageName)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            return NotificationPipelineOutcome.Duplicate(notification.packageName, correlationId, "Fingerprint duplicate before parse")
        }

        // Phase 1: Pre-DB work (no transaction held)
        val parseOutcome = parserRegistry.parseWithProvenance(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )
        val parsed = parseOutcome.parsed
        val provenance = parseOutcome.provenance

        if (parsed != null) {
            diagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "parse",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                correlationId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("packageName", notification.packageName)
                    .put("parserSource", provenance.source.name)
                    .put("parserConfidence", provenance.confidence?.toString())
                    .build()
            ))
        }

        if (parsed == null) {
            Timber.d("Pipeline outcome: PARSER_FAILED for package=%s", notification.packageName)
            val fullText = listOfNotNull(notification.title, notification.text, notification.bigText).joinToString(" ").trim()
            val homeCurrency = userCurrencyProvider.getHomeCurrency()
            val oversizedCandidate = detectOversizedAmountCandidate(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                defaultCurrency = resolveCurrency(fullText, homeCurrency)
            )

            // Phase 2: DB transaction (DB-only mutations)
            // P1-PR2: Collect source-link failures for post-commit diagnostic emission
            val deferredLinkDiagnostics = mutableListOf<DeferredSourceLinkDiagnostic>()
            var parserFailedOutcome: NotificationPipelineOutcome? = null
            database.withTransaction {
                when (val insertResult = insertRawNotificationIfNotDuplicate(notification, storageNotification)) {
                    is RawNotificationInsertResult.Duplicate -> {
                        parserFailedOutcome = NotificationPipelineOutcome.Duplicate(notification.packageName, correlationId, "Insert ${insertResult.basis.name}")
                        return@withTransaction
                    }
                    is RawNotificationInsertResult.Inserted -> {
                        val rawId = insertResult.rawId

                sourceStatsDao.insertIfNotExists(
                    SourceStats(
                        packageName = notification.packageName,
                        lastSeen = sourceStatsTimestamp
                    )
                )

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
                        // PR5: Write dedupe source link
                        val linkMatchType = if (hasExpenseDuplicate) "expense_duplicate" else "pending_review_duplicate"
                        val linkResult = writeNotificationDedupeSourceLink(
                            rawId = rawId,
                            matchType = linkMatchType,
                            correlationId = correlationId
                        )
                        if (linkResult is SourceLinkWriteResult.Failed) {
                            deferredLinkDiagnostics += DeferredSourceLinkDiagnostic(rawId, linkMatchType, correlationId, linkResult)
                        }
                        parserFailedOutcome = NotificationPipelineOutcome.Duplicate(notification.packageName, correlationId, "Oversized duplicate")
                        // P1-SLICE-D: markProcessed atomically inside transaction
                        dao.markProcessed(rawId)
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
                        notificationTitle = sanitizePendingReviewText(notification.title, persistenceContext?.rawStorageMode),
                        notificationText = sanitizePendingReviewText(notification.text ?: notification.bigText, persistenceContext?.rawStorageMode),
                        extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER
                    )
                    val reviewId = pendingReviewDao.upsertByRawNotificationId(review)
                    // PR3: Write source links for review provenance
                    val linkResult = pendingReviewSourceLinkService.linkSourcesForReview(
                        review = review,
                        reviewId = reviewId,
                        sourceType = ExpenseSource.REVIEW_APPROVAL,
                        correlationId = correlationId,
                        context = PendingReviewSourceContext(
                            stage = "notification_oversized_parse_failed",
                            reason = "Oversized amount needs manual confirmation",
                            confidence = review.confidence,
                            extractionState = review.extractionState.name
                        )
                    )
                    if (linkResult.hasFatalFailure) {
                        // P1-PR2 (NEW-P1-015): Do NOT throw inside transaction — review is still valid
                        // even if provenance links failed. Collect for post-commit diagnostic.
                        Timber.w("Source link fatal failure for oversized review reviewId=%d: %s", reviewId, linkResult.failures.joinToString(", "))
                        deferredLinkDiagnostics += DeferredSourceLinkDiagnostic(
                            rawId = rawId,
                            matchType = "review_source_link_fatal",
                            correlationId = correlationId,
                            result = SourceLinkWriteResult.Failed(
                                errorClass = "PendingReviewSourceLinkFatalFailure",
                                errorMessageHash = linkResult.failures.joinToString(", ").let { Integer.toHexString(it.hashCode()).take(8) },
                                retryable = false
                            )
                        )
                    }
                    Timber.d("Pipeline outcome: NEEDS_REVIEW reviewId=%d", reviewId)
                    sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
                    dao.markRelevance(rawId, true)
                    parserFailedOutcome = NotificationPipelineOutcome.NeedsReview(notification.packageName, correlationId, rawId, reviewId)
                    // P1-SLICE-D: markProcessed atomically inside transaction
                    dao.markProcessed(rawId)
                } else {
                    val transactionSignalCandidate = detectTransactionSignalCandidate(
                        title = notification.title,
                        text = notification.text,
                        bigText = notification.bigText,
                        defaultCurrency = resolveCurrency(fullText, homeCurrency)
                    )

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
                            // PR5: Write dedupe source link
                            val linkMatchType = if (hasExpenseDuplicate) "expense_duplicate" else "pending_review_duplicate"
                            val linkResult = writeNotificationDedupeSourceLink(
                                rawId = rawId,
                                matchType = linkMatchType,
                                correlationId = correlationId
                            )
                            if (linkResult is SourceLinkWriteResult.Failed) {
                                deferredLinkDiagnostics += DeferredSourceLinkDiagnostic(rawId, linkMatchType, correlationId, linkResult)
                            }
                        parserFailedOutcome = NotificationPipelineOutcome.Duplicate(notification.packageName, correlationId, "Signal duplicate")
                        // P1-SLICE-D: markProcessed atomically inside transaction
                        dao.markProcessed(rawId)
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
                            notificationTitle = sanitizePendingReviewText(notification.title, persistenceContext?.rawStorageMode),
                            notificationText = sanitizePendingReviewText(notification.text ?: notification.bigText, persistenceContext?.rawStorageMode),
                            extractionState = ExtractionState.SYNTHETIC_PLACEHOLDER
                        )
                        val reviewId = pendingReviewDao.upsertByRawNotificationId(review)
                        // PR3: Write source links for review provenance
                        val linkResult = pendingReviewSourceLinkService.linkSourcesForReview(
                            review = review,
                            reviewId = reviewId,
                            sourceType = ExpenseSource.REVIEW_APPROVAL,
                correlationId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
                            context = PendingReviewSourceContext(
                                stage = "notification_transaction_signal_parse_failed",
                                reason = "Transaction signal detected but parser failed",
                                confidence = review.confidence,
                                extractionState = review.extractionState.name
                            )
                        )
                        if (linkResult.hasFatalFailure) {
                            // P1-PR2 (NEW-P1-015): Do NOT throw inside transaction — review is still valid
                            Timber.w("Source link fatal failure for signal review reviewId=%d: %s", reviewId, linkResult.failures.joinToString(", "))
                            deferredLinkDiagnostics += DeferredSourceLinkDiagnostic(
                                rawId = rawId,
                                matchType = "review_source_link_fatal",
                                correlationId = correlationId,
                                result = SourceLinkWriteResult.Failed(
                                    errorClass = "PendingReviewSourceLinkFatalFailure",
                                    errorMessageHash = linkResult.failures.joinToString(", ").let { Integer.toHexString(it.hashCode()).take(8) },
                                    retryable = false
                                )
                            )
                        }
                        Timber.d("Pipeline outcome: NEEDS_REVIEW reviewId=%d", reviewId)
                        sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
                        dao.markRelevance(rawId, true)
                        parserFailedOutcome = NotificationPipelineOutcome.NeedsReview(notification.packageName, correlationId, rawId, reviewId)
                    // P1-SLICE-D: markProcessed atomically inside transaction
                    dao.markProcessed(rawId)
                    } else {
                        Timber.d("Pipeline outcome: AUTO_REJECTED reason=%s", "Unparseable notification with no transaction signal")
                        sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, sourceStatsTimestamp)
                        dao.markRelevance(rawId, false)
                        parserFailedOutcome = NotificationPipelineOutcome.AutoRejected(notification.packageName, correlationId, rawId, "Unparseable notification with no transaction signal")
                        // P1-SLICE-D: markProcessed atomically inside transaction
                        dao.markProcessed(rawId)
                    }
                }
                    } // close Inserted
                } // close when
            } // close withTransaction

            // Phase 3: Post-commit best-effort actions
            // P1-PR2: Emit deferred source-link diagnostics now that transaction is committed
            for (diag in deferredLinkDiagnostics) {
                emitDeferredSourceLinkDiagnostic(diag.rawId, diag.matchType, diag.correlationId, diag.result)
            }
            runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
                confidenceRouter.invalidateSourceStatsCache(notification.packageName)
            }
            return parserFailedOutcome ?: NotificationPipelineOutcome.ParserFailed(notification.packageName, correlationId, null, "No outcome tracked")
        }

        val preDbContext = buildPreDbContext(notification, parsed, persistenceContext)

        // Phase 2: DB transaction (DB-only mutations)
        // P1-PR2: Collect source-link failures for post-commit diagnostic emission
        val deferredLinkDiagnosticsParsed = mutableListOf<DeferredSourceLinkDiagnostic>()
        val dbOutcome = database.withTransaction {
            val rawId = when (val insertResult = insertRawNotificationIfNotDuplicate(notification, storageNotification)) {
                is RawNotificationInsertResult.Duplicate -> return@withTransaction ParsedDbOutcome.RawDuplicate
                is RawNotificationInsertResult.Inserted -> insertResult.rawId
            }

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
                    sourceStatsTimestamp = sourceStatsTimestamp,
                    correlationId = correlationId,
                    deferredDiagnostics = deferredLinkDiagnosticsParsed
                )
                RoutingDecision.NEEDS_REVIEW -> handleNeedsReviewInTransaction(
                    notification = notification,
                    rawId = rawId,
                    preDb = preDbContext,
                    sourceStatsTimestamp = sourceStatsTimestamp,
                    correlationId = correlationId,
                    deferredDiagnostics = deferredLinkDiagnosticsParsed
                )
                RoutingDecision.AUTO_REJECT -> {
                    if (notification.packageName in FINANCIAL_PACKAGES) {
                        Timber.w("Auto-reject overridden to NEEDS_REVIEW for financial package: ${notification.packageName}")
                        handleNeedsReviewInTransaction(
                            notification = notification,
                            rawId = rawId,
                            preDb = preDbContext,
                            sourceStatsTimestamp = sourceStatsTimestamp,
                            correlationId = correlationId,
                            deferredDiagnostics = deferredLinkDiagnosticsParsed
                        )
                    } else {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, sourceStatsTimestamp)
                        // P1-SLICE-D: markProcessed atomically inside transaction
                        dao.markProcessed(rawId)
                        ParsedDbOutcome.AutoRejected
                    }
                }
            }
        }

        val outcome = when (dbOutcome) {
            is ParsedDbOutcome.AutoAccepted -> NotificationPipelineOutcome.AutoAccepted(
                packageName = notification.packageName,
                correlationId = correlationId,
                rawId = dbOutcome.rawId,
                expenseId = dbOutcome.expenseId
            )
            is ParsedDbOutcome.NeedsReviewCreated -> NotificationPipelineOutcome.NeedsReview(
                packageName = notification.packageName,
                correlationId = correlationId,
                rawId = dbOutcome.rawId,
                reviewId = dbOutcome.reviewId
            )
            ParsedDbOutcome.AutoRejected -> NotificationPipelineOutcome.AutoRejected(
                packageName = notification.packageName,
                correlationId = correlationId,
                rawId = null,
                reason = "Routing decision rejected"
            )
            ParsedDbOutcome.RawDuplicate -> NotificationPipelineOutcome.Duplicate(
                notification.packageName,
                correlationId,
                "Raw duplicate in transaction"
            )
            ParsedDbOutcome.Duplicate -> NotificationPipelineOutcome.Duplicate(
                notification.packageName,
                correlationId,
                "Canonical duplicate"
            )
        }

        if (dbOutcome == ParsedDbOutcome.RawDuplicate) return outcome

        // Phase 3: Post-commit best-effort actions
        // P1-PR2: Emit deferred source-link diagnostics now that transaction is committed
        for (diag in deferredLinkDiagnosticsParsed) {
            emitDeferredSourceLinkDiagnostic(diag.rawId, diag.matchType, diag.correlationId, diag.result)
        }
        runPostCommitSafely("invalidate source stats cache for ${notification.packageName}") {
            confidenceRouter.invalidateAfterUserAction(
                notification.packageName,
                preDbContext.correctedMerchant
            )
        }

        runParsedPostCommitActions(notification, preDbContext, dbOutcome, correlationId)

        return outcome
    }

    /**
     * PR5 + P1-NEW-18: Writes a dedupe source link for a notification that was identified as a duplicate.
     * The link targets the notification itself, with metadata describing the match type.
     * Returns a typed [SourceLinkWriteResult] instead of silently swallowing errors.
     *
     * ## P1-PR2 (NEW-P1-002): Transaction-safe
     * This method is called inside [database.withTransaction] blocks. The [sourceLinkWriter]
     * call is a pure DAO write (safe). On failure, diagnostic data is collected but NOT emitted
     * here — the caller must emit diagnostics post-commit via [emitDeferredSourceLinkDiagnostic].
     * This avoids the dispatcher switch ([diagnosticEmitter.emit] uses [withContext(ioDispatcher)])
     * inside a Room transaction, which can cause deadlocks.
     */
    private suspend fun writeNotificationDedupeSourceLink(
        rawId: Long,
        matchType: String,
        correlationId: String?,
        confidence: Double? = null
    ): SourceLinkWriteResult {
        return try {
            val payload = NotificationSourceLinkPayloadFactory.forDedupeMatch(
                sourceNotificationId = rawId,
                matchedNotificationId = rawId,
                matchType = matchType,
                confidence = confidence
            )
            sourceLinkWriter.linkTarget(
                targetType = TargetEntityType.RAW_NOTIFICATION,
                targetId = rawId,
                payload = payload,
                correlationId = correlationId
            )
        } catch (t: Throwable) {
            Timber.w(t, "Failed to write notification dedupe source link: rawId=%d matchType=%s", rawId, matchType)
            // P1-PR2: Do NOT call diagnosticEmitter.emit() here — we are inside a Room transaction.
            // Diagnostic emission is deferred to post-commit by the caller.
            SourceLinkWriteResult.Failed(
                errorClass = t.javaClass.name,
                errorMessageHash = t.message?.let { Integer.toHexString(it.hashCode()) },
                retryable = true
            )
        }
    }

    /**
     * P1-PR2: Emit a SOURCE_LINK_FAILED diagnostic event post-commit.
     * Called after the transaction completes to avoid dispatcher switch inside Room transaction.
     */
    private suspend fun emitDeferredSourceLinkDiagnostic(
        rawId: Long,
        matchType: String,
        correlationId: String?,
        result: SourceLinkWriteResult.Failed
    ) {
        runPostCommitSafely("emit source link failed diagnostic rawId=$rawId") {
            diagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "source_link",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.SOURCE_LINK_FAILED,
                correlationId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
                entityType = "RawNotification",
                entityId = rawId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("matchType", matchType)
                    .put("errorClass", result.errorClass ?: "unknown")
                    .build(),
                isTerminal = false
            ))
        }
    }

    private suspend fun writePipelineDiagnosticEvent(outcome: NotificationPipelineOutcome, packageName: String, correlationId: String? = null) {
        val (stage, outcomeStr) = when (outcome) {
            is NotificationPipelineOutcome.AutoAccepted -> "create" to "AUTO_ACCEPTED"
            is NotificationPipelineOutcome.NeedsReview -> "review" to "NEEDS_REVIEW"
            is NotificationPipelineOutcome.Duplicate -> "dedup" to "DUPLICATE"
            is NotificationPipelineOutcome.ParserFailed -> "parse" to "PARSER_FAILED"
            is NotificationPipelineOutcome.AutoRejected -> "reject" to "AUTO_REJECTED"
            is NotificationPipelineOutcome.Dropped -> "drop" to "DROPPED"
            is NotificationPipelineOutcome.Error -> "error" to "ERROR"
        }
        diagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
            stage = stage,
            outcome = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.CREATED
                is NotificationPipelineOutcome.NeedsReview -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.NEEDS_REVIEW
                is NotificationPipelineOutcome.Duplicate -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE
                is NotificationPipelineOutcome.ParserFailed -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL
                is NotificationPipelineOutcome.AutoRejected -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED
                is NotificationPipelineOutcome.Dropped -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED
                is NotificationPipelineOutcome.Error -> com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL
            },
            reasonCode = when (outcome) {
                is NotificationPipelineOutcome.Duplicate -> com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE
                is NotificationPipelineOutcome.ParserFailed -> com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PARSER_FAILED
                is NotificationPipelineOutcome.Dropped -> com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.FILTER_REJECTED
                else -> null
            },
            correlationId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
            entityType = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> "Expense"
                is NotificationPipelineOutcome.NeedsReview -> "PendingReview"
                else -> null
            },
            entityId = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> outcome.expenseId
                is NotificationPipelineOutcome.NeedsReview -> outcome.reviewId
                else -> null
            },
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .putHashed("packageName", packageName)
                .build(),
            isTerminal = true
        ))
    }

    private suspend fun insertRawNotificationIfNotDuplicate(
        notification: RawNotification,
        storageNotification: RawNotification = notification
    ): RawNotificationInsertResult {
        // Always resolve the canonical fingerprint from the processing notification
        val fingerprint = notification.dedupeFingerprint
            ?: com.yourname.expensetracker.domain.notification.RawNotificationFingerprint.compute(
                packageName = notification.packageName,
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                timestamp = notification.timestamp
            )

        // Fast pre-check using fingerprint (works under all RawStorageMode values)
        val existingId = dao.findIdByDedupeFingerprint(fingerprint)
        if (existingId != null) {
            return RawNotificationInsertResult.Duplicate(
                existingRawId = existingId,
                dedupeFingerprint = fingerprint,
                basis = DuplicateBasis.DEDUPE_FINGERPRINT_PRECHECK
            )
        }

        // Always persist the resolved fingerprint in the storage row
        val insertId = dao.insertOrIgnore(
            storageNotification.copy(dedupeFingerprint = fingerprint)
        )

        return if (insertId == -1L) {
            RawNotificationInsertResult.Duplicate(
                existingRawId = dao.findIdByDedupeFingerprint(fingerprint),
                dedupeFingerprint = fingerprint,
                basis = DuplicateBasis.DEDUPE_FINGERPRINT_INSERT_CONFLICT
            )
        } else {
            RawNotificationInsertResult.Inserted(
                rawId = insertId,
                dedupeFingerprint = fingerprint
            )
        }
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
     * PR3: Sanitize notification text before storing in PendingReview.
     * Uses the provided rawStorageMode (from PersistenceContext when available,
     * falls back to current PrivacySettings).
     * Falls back to STORE_REDACTED on read failure (fail-closed).
     */
    private suspend fun sanitizePendingReviewText(text: String?, mode: com.yourname.expensetracker.domain.privacy.RawStorageMode? = null): String? {
        val resolvedMode = mode ?: try {
            privacySettingsRepository.getSettings().rawNotificationStorageMode
        } catch (_: Exception) {
            com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_REDACTED
        }
        return RawContentSanitizer.sanitizeNotificationText(text, resolvedMode)
    }

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

    /**
     * Resolve the best-guess currency from notification text using the money signal detector.
     * Falls back to home currency or "EUR" only when no signal is detected.
     */
    private suspend fun resolveCurrency(fullText: String, homeCurrency: String?): String {
        if (fullText.isBlank()) return homeCurrency ?: "EUR"
        val signal = moneySignalDetector.bestTransactionAmount(fullText, homeCurrency)
        return signal?.currencyCode ?: homeCurrency ?: "EUR"
    }

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
        // PR 9: Expanded currency support beyond EUR/USD/GBP.
        // Symbols: € $ £ ¥ ₺; ISO codes: EUR USD GBP CHF PLN RON TRY CAD AUD JPY SEK NOK DKK HUF CZK
        private val CURRENCY_HINT_REGEX = Regex(
            """(€|\$|£|¥|₺|\bEUR\b|\bUSD\b|\bGBP\b|\bCHF\b|\bPLN\b|\bRON\b|\bTRY\b|\bCAD\b|\bAUD\b|\bJPY\b|\bSEK\b|\bNOK\b|\bDKK\b|\bHUF\b|\bCZK\b|\bTL\b|\bkr\b|\bFt\b|\bKč\b|\bKc\b)""",
            RegexOption.IGNORE_CASE
        )
        private val TRANSACTION_HINT_REGEX = Regex(
            """(paid|payment|purchase|transaction|transfer|sent|received|χρεωση|πληρωμη|αγορα|καταθεση|πιστωση)""",
            RegexOption.IGNORE_CASE
        )
private val CURRENCY_SUFFIX_REGEX = Regex("""\s*(€|\$|£|¥|₺|\bEUR\b|\bUSD\b|\bGBP\b|\bCHF\b|\bPLN\b|\bRON\b|\bTRY\b|\bCAD\b|\bAUD\b|\bJPY\b|\bSEK\b|\bNOK\b|\bDKK\b|\bHUF\b|\bCZK\b|\bTL\b|\bkr\b|\bFt\b|\bKč\b|\bKc\b)""", RegexOption.IGNORE_CASE)
private val CARD_TAIL_REGEX = Regex("""\*+\d{4}\b""")

private val AMOUNT_TOKEN_REGEX = Regex(
    """(?:€|\$|£|¥|₺|\bEUR\b|\bUSD\b|\bGBP\b|\bCHF\b|\bPLN\b|\bRON\b|\bTRY\b|\bCAD\b|\bAUD\b|\bJPY\b|\bSEK\b|\bNOK\b|\bDKK\b|\bHUF\b|\bCZK\b|\bTL\b|\bkr\b|\bFt\b|\bKč\b|\bKc\b)?\s*[+-]?\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?(?:\s*(?:€|\$|£|¥|₺|\bEUR\b|\bUSD\b|\bGBP\b|\bCHF\b|\bPLN\b|\bRON\b|\bTRY\b|\bCAD\b|\bAUD\b|\bJPY\b|\bSEK\b|\bNOK\b|\bDKK\b|\bHUF\b|\bCZK\b|\bTL\b|\bkr\b|\bFt\b|\bKč\b|\bKc\b))?""",
    RegexOption.IGNORE_CASE
)

        internal fun detectOversizedAmountCandidate(
            title: String?,
            text: String?,
            bigText: String?,
            defaultCurrency: String = "EUR"
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

            // P2-10 FIXED: Currency resolved by NotificationMoneySignalDetector via resolveCurrency().
            // The defaultCurrency parameter is the detector-derived currency or home-currency fallback.
            // No hardcoded $->USD or else->EUR remains.
            val currency = defaultCurrency

            val merchantHint = extractMerchantHint(title ?: text ?: bigText)
            return OversizedAmountCandidate(
                amount = oversized,
                currency = currency,
                merchantHint = merchantHint
            )
        }

    internal fun detectTransactionSignalCandidate(
        title: String?, text: String?, bigText: String?,
        defaultCurrency: String = "EUR"
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

            // P2-10 FIXED: Currency resolved by NotificationMoneySignalDetector via resolveCurrency().
            val currency = defaultCurrency

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

    /**
     * P1-PR2: Holds source-link failure data collected inside a transaction for
     * post-commit diagnostic emission. Avoids calling [diagnosticEmitter.emit]
     * (which switches to [ioDispatcher]) inside a Room transaction.
     */
    private data class DeferredSourceLinkDiagnostic(
        val rawId: Long,
        val matchType: String,
        val correlationId: String?,
        val result: SourceLinkWriteResult.Failed
    )

    private sealed class ParsedDbOutcome {
        object RawDuplicate : ParsedDbOutcome()
        object AutoRejected : ParsedDbOutcome()
        object Duplicate : ParsedDbOutcome()
        data class NeedsReviewCreated(val rawId: Long, val reviewId: Long) : ParsedDbOutcome()
        data class AutoAccepted(
            val rawId: Long,
            val expenseId: Long,
            val insertedExpense: Expense,
            val transactionActions: PostCommitActionBatch
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
        val deviceGps: Pair<Double, Double>?,
        // P2-11: pending review sanitization should use this field instead of reading
        // current privacy settings, to prevent settings-change-after-capture leaks.
        val rawStorageMode: com.yourname.expensetracker.domain.privacy.RawStorageMode? = null,
        val persistenceContext: NotificationPersistenceContext? = null
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
        parsed: com.yourname.expensetracker.domain.parser.ParsedTransaction,
        persistenceContext: NotificationPersistenceContext? = null
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
            // C12/E3-NOW-003: If classification is ambiguous, force review regardless of routing
            if (classification.requiresReview && routingResult.decision == RoutingDecision.AUTO_ACCEPT) {
                Timber.d("C12: Ambiguous classification for merchant='%s' — routing downgraded from AUTO_ACCEPT to NEEDS_REVIEW", correctedMerchant)
                routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
            }
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

        // PR 3 (P1-NEW-16): Location enrichment removed from notification pipeline.
        // Notification capture uses FOREGROUND_SERVICE_TYPE_DATA_SYNC only and does not
        // read GPS by default. If location enrichment is desired, it must be explicitly
        // gated behind: user feature toggle + PrivacyGate.DEVICE_GPS_LOCATION + runtime
        // permission + foreground-service type declaration.
        val deviceGps: Pair<Double, Double>? = null

        val eventDate = parsed.date ?: notification.timestamp
        val merchantKey = MerchantKeyGenerator.generate(correctedMerchant)
        val transactionType = parsed.type.toDbTransactionType()
        // TODO P1-CURRENT-011: parsed.currency is the parser's best guess. When the parser
        // falls back to a default currency (e.g. "EUR"), the isDuplicateCurrencyAware check
        // uses that narrow currency, but existing expenses may be in a different currency.
        // This can cause false negatives in dedup when the fallback currency doesn't match
        // the user's actual home currency.
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
            deviceGps = deviceGps,
            persistenceContext = persistenceContext
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
        sourceStatsTimestamp: Long,
        correlationId: String? = null,
        deferredDiagnostics: MutableList<DeferredSourceLinkDiagnostic> = mutableListOf()
    ): ParsedDbOutcome {
        val isDuplicate = hasCanonicalExpenseDuplicate(preDb)
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            // PR5: Write dedupe source link
            val linkResult1 = writeNotificationDedupeSourceLink(
                rawId = rawId,
                matchType = "canonical_expense_duplicate",
                correlationId = correlationId,
                confidence = preDb.routingResult.adjustedConfidence.toDouble()
            )
            if (linkResult1 is SourceLinkWriteResult.Failed) {
                deferredDiagnostics += DeferredSourceLinkDiagnostic(rawId, "canonical_expense_duplicate", correlationId, linkResult1)
            }
            // P1-SLICE-D: markProcessed atomically inside transaction
            dao.markProcessed(rawId)
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
            // PR5: Write dedupe source link
            val linkResult2 = writeNotificationDedupeSourceLink(
                rawId = rawId,
                matchType = "pending_review_duplicate",
                correlationId = correlationId,
                confidence = preDb.routingResult.adjustedConfidence.toDouble()
            )
            if (linkResult2 is SourceLinkWriteResult.Failed) {
                deferredDiagnostics += DeferredSourceLinkDiagnostic(rawId, "pending_review_duplicate", correlationId, linkResult2)
            }
            // P1-SLICE-D: markProcessed atomically inside transaction
            dao.markProcessed(rawId)
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
            skipDeduplication = true,
            correlationId = correlationId  // DDL-512-05: propagate notification listener correlation
        )

        val mutation = coordinator.createExpenseDbOnlyV2(request)
        return when (val result = mutation.value) {
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
                // NEW-09: Do not include raw notification title/text in audit payload.
                // Store safe metadata only — package name is already hashed in the event metadata.
                val auditReason = JSONObject().apply {
                    put("packageName", notification.packageName)
                    put("amount", preDb.parsed.amount)
                    put("merchant", preDb.correctedMerchant)
                }.toString()
                runCatching {
                    transactionLifecycleEventWriter.write(
                        TransactionContext(
                            correlationId = java.util.UUID.randomUUID().toString(),
                            occurredAt = System.currentTimeMillis()
                        ),
                        com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent(
                            expenseId = expenseId,
                            eventType = "AI_AUTO_ACCEPT",
                            source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT.name,
                            actor = "system:ai_auto_accept",
                            correlationId = correlationId,  // DDL-512-05
                            dedupeKey = preDb.dedupeKey,
                            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                .putHashed("packageName", notification.packageName)
                                .build()
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

                // P1-SLICE-D: markProcessed atomically inside transaction
                dao.markProcessed(rawId)
                ParsedDbOutcome.AutoAccepted(
                    rawId = rawId,
                    expenseId = expenseId,
                    insertedExpense = expense,
                    transactionActions = mutation.postCommitActions
                )
            }

            is CreateExpenseResult.DuplicateSkipped -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                // PR5: Write dedupe source link
                writeNotificationDedupeSourceLink(
                    rawId = rawId,
                    matchType = "coordinator_duplicate_skipped",
                    correlationId = correlationId,
                    confidence = preDb.routingResult.adjustedConfidence.toDouble()
                )
                // P1-SLICE-D: markProcessed atomically inside transaction
                dao.markProcessed(rawId)
                ParsedDbOutcome.Duplicate
            }

            is CreateExpenseResult.InsertConflict -> {
                check(hasCanonicalExpenseDuplicate(preDb)) {
                    "Expense insert conflicted without a canonical duplicate for rawId=$rawId"
                }
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                // PR5: Write dedupe source link
                writeNotificationDedupeSourceLink(
                    rawId = rawId,
                    matchType = "coordinator_insert_conflict",
                    correlationId = correlationId,
                    confidence = preDb.routingResult.adjustedConfidence.toDouble()
                )
                // P1-SLICE-D: markProcessed atomically inside transaction
                dao.markProcessed(rawId)
                ParsedDbOutcome.Duplicate
            }

            is CreateExpenseResult.ValidationFailed -> {
                Timber.w("Auto-accept validation failed: ${result.errors}")
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
                // PR5: Write dedupe source link
                writeNotificationDedupeSourceLink(
                    rawId = rawId,
                    matchType = "coordinator_validation_failed",
                    correlationId = correlationId,
                    confidence = preDb.routingResult.adjustedConfidence.toDouble()
                )
                // P1-SLICE-D: markProcessed atomically inside transaction
                dao.markProcessed(rawId)
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
        sourceStatsTimestamp: Long,
        correlationId: String? = null,
        deferredDiagnostics: MutableList<DeferredSourceLinkDiagnostic> = mutableListOf()
    ): ParsedDbOutcome {
        val isDuplicate = hasCanonicalExpenseDuplicate(preDb)
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, sourceStatsTimestamp)
            // PR5: Write dedupe source link
            val linkResult1 = writeNotificationDedupeSourceLink(
                rawId = rawId,
                matchType = "canonical_expense_duplicate",
                correlationId = correlationId,
                confidence = preDb.routingResult.adjustedConfidence.toDouble()
            )
            if (linkResult1 is SourceLinkWriteResult.Failed) {
                deferredDiagnostics += DeferredSourceLinkDiagnostic(rawId, "canonical_expense_duplicate", correlationId, linkResult1)
            }
            // P1-SLICE-D: markProcessed atomically inside transaction
            dao.markProcessed(rawId)
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
            // PR5: Write dedupe source link
            val linkResult2 = writeNotificationDedupeSourceLink(
                rawId = rawId,
                matchType = "pending_review_duplicate",
                correlationId = correlationId,
                confidence = preDb.routingResult.adjustedConfidence.toDouble()
            )
            if (linkResult2 is SourceLinkWriteResult.Failed) {
                deferredDiagnostics += DeferredSourceLinkDiagnostic(rawId, "pending_review_duplicate", correlationId, linkResult2)
            }
            // P1-SLICE-D: markProcessed atomically inside transaction
            dao.markProcessed(rawId)
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
            notificationTitle = sanitizePendingReviewText(notification.title, preDb.persistenceContext?.rawStorageMode),
            // P1-CURRENT-013 FIX: Preserve combined text for review context
            notificationText = sanitizePendingReviewText(preDb.fullNotificationText, preDb.persistenceContext?.rawStorageMode),
            suggestedDate = preDb.eventDate,
            suggestedDirection = preDb.direction?.name,
            suggestedAccountName = preDb.accountName,
            suggestedLatitude = preDb.deviceGps?.first,
            suggestedLongitude = preDb.deviceGps?.second
        )
        val reviewId = pendingReviewDao.upsertByRawNotificationId(review)
        // PR3: Write source links for review provenance
        val linkResult = pendingReviewSourceLinkService.linkSourcesForReview(
            review = review,
            reviewId = reviewId,
            sourceType = ExpenseSource.REVIEW_APPROVAL,
            correlationId = correlationId,
            context = PendingReviewSourceContext(
                stage = "notification_needs_review",
                reason = preDb.routingResult.decision.name,
                confidence = review.confidence,
                extractionState = review.extractionState.name,
                routingDecision = preDb.routingResult.decision.name
            )
        )
        if (linkResult.hasFatalFailure) {
            // P1-PR2 (NEW-P1-015): Do NOT throw inside transaction — review is still valid
            // even if provenance links failed. Log warning; diagnostic emitted post-commit.
            Timber.w("Source link fatal failure for needs-review reviewId=%d: %s", reviewId, linkResult.failures.joinToString(", "))
            deferredDiagnostics += DeferredSourceLinkDiagnostic(
                rawId = rawId,
                matchType = "review_source_link_fatal",
                correlationId = correlationId,
                result = SourceLinkWriteResult.Failed(
                    errorClass = "PendingReviewSourceLinkFatalFailure",
                    errorMessageHash = linkResult.failures.joinToString(", ").let { Integer.toHexString(it.hashCode()).take(8) },
                    retryable = false
                )
            )
        }
        Timber.d("Pipeline outcome: NEEDS_REVIEW reviewId=%d", reviewId)
        sourceStatsDao.incrementTotalAndPending(notification.packageName, sourceStatsTimestamp)
        // P1-SLICE-D: markProcessed atomically inside transaction
        dao.markProcessed(rawId)
        return ParsedDbOutcome.NeedsReviewCreated(rawId = rawId, reviewId = reviewId)
    }

    private suspend fun runParsedPostCommitActions(
        notification: RawNotification,
        preDb: PreDbContext,
        dbOutcome: ParsedDbOutcome,
        correlationId: String? = null
    ) {
        when (dbOutcome) {
            ParsedDbOutcome.RawDuplicate,
            ParsedDbOutcome.AutoRejected -> Unit

            ParsedDbOutcome.Duplicate -> {
                runPostCommitSafely("classifier training after duplicate (${notification.packageName})") {
                    classifier.train(preDb.fullNotificationText, isTransaction = true)
                }
            }

            is ParsedDbOutcome.NeedsReviewCreated -> {
                runTransferAnalyticsPostCommit(
                    transactionType = preDb.transactionType,
                    direction = preDb.direction,
                    accountName = preDb.accountName,
                    transferId = null
                )
            }

            is ParsedDbOutcome.AutoAccepted -> {
                runPostCommitSafely("transaction side effects after auto-accept (expenseId=${dbOutcome.expenseId})") {
                    postCommitActionRunner.run(dbOutcome.transactionActions)
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
