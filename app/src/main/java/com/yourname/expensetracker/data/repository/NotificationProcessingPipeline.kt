package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
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
 */
@Singleton
class NotificationProcessingPipeline @Inject constructor(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val sourceStatsDao: SourceStatsDao,
    private val parserRegistry: AppParserRegistry,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val timeProvider: TimeProvider,
    private val directionDetector: TransferDirectionDetector,
    private val analytics: TransferDirectionAnalytics,
    private val locationProvider: ForegroundLocationProvider
) {

    suspend fun process(notification: RawNotification) {
        try {
            processInternal(notification)
        } catch (e: Exception) {
            Timber.e(e, "Error processing notification: ${notification.packageName}")
        }
    }

    suspend fun processBatch(notifications: List<RawNotification>) {
        if (notifications.isEmpty()) return
        classifier.initialize()
        notifications.forEach { process(it) }
    }

    private suspend fun processInternal(notification: RawNotification) {
        classifier.initialize()

        val parsed = parserRegistry.parse(
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

        val expense = Expense(
            amount = parsed.amount,
            currency = parsed.currency,
            merchant = correctedMerchant,
            merchantKey = MerchantKeyGenerator.generate(correctedMerchant),
            transactionType = parsed.type,
            date = notification.timestamp,
            rawNotificationId = rawId,
            categoryId = categoryId,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            dedupeKey = Expense.generateDedupeKey(parsed.amount, correctedMerchant, notification.timestamp),
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
            classifier.train(fullText, isTransaction = true)
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
}
