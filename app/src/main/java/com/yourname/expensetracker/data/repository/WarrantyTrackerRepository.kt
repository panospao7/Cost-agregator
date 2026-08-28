package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionInput
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for warranty and return-window tracking backed by [receiptRepository].
 *
 * Database access to receipt data goes through [ReceiptRepository] — NOT through
 * [com.yourname.expensetracker.data.database.dao.ScannedReceiptDao] directly.
 * This ensures all receipt lifecycle invariants are respected.
 *
 * Warranty and return-window DAOs ([WarrantyDao], [ReturnWindowDao]) are accessed
 * directly because they have no lifecycle abstraction layer above them.
 */
@Singleton
class WarrantyTrackerRepository @Inject constructor(
    private val database: AppDatabase,
    private val warrantyDao: WarrantyDao,
    private val returnWindowDao: ReturnWindowDao,
    private val warrantyLifecycleEventDao: WarrantyLifecycleEventDao,
    private val receiptRepository: Lazy<ReceiptRepository>,
    private val cloudExtractionService: CloudWarrantyExtractionService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiPolicy: AiPolicy,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val receiptLifecycleEventWriter: com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter,
    private val transactionRunner: DomainTransactionRunner
) {
    private companion object {
        private const val ACTIVE_ITEMS_REFRESH_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        /** Confidence threshold above which warranties are auto-accepted without review. */
        private const val AUTO_ACCEPT_CLOUD_CONFIDENCE = 0.75f
        /** Confidence threshold below which cloud-extracted warranties are discarded entirely. */
        private const val REVIEW_CLOUD_CONFIDENCE = 0.30f
    }

    private fun activeItemsTickerFlow(intervalMs: Long = ACTIVE_ITEMS_REFRESH_INTERVAL_MS): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(intervalMs)
        }
    }

    // Warranty operations
    fun getAllWarranties(): Flow<List<Warranty>> = warrantyDao.getAllWarranties()
    
    fun getActiveWarranties(): Flow<List<Warranty>> =
        activeItemsTickerFlow().flatMapLatest {
            warrantyDao.getActiveWarranties(timeProvider.now())
        }
    
    fun getWarrantiesByStatus(status: WarrantyStatus): Flow<List<Warranty>> = 
        warrantyDao.getWarrantiesByStatus(status)
    
    suspend fun getWarrantiesExpiringSoon(days: Int): List<Warranty> {
        val currentStart = TimePeriodUtils.getStartOfDay(timeProvider.now())
        val futureExclusive = TimePeriodUtils.addDays(currentStart, days.coerceAtLeast(0))
        return warrantyDao.getWarrantiesExpiringSoon(
            futureTime = futureExclusive,
            currentTime = currentStart
        )
    }
    
    suspend fun getWarrantyByReceiptId(receiptId: Long): Warranty? = 
        warrantyDao.getWarrantyByReceiptId(receiptId)
    
    /**
     * WRN-20: Manual warranty path is now transactional.
     * The insert is wrapped in [database.withTransaction] to ensure ACID semantics
     * when this method is part of a larger multi-table write (future-proofing).
     */
    suspend fun addWarranty(warranty: Warranty): Long {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.addWarranty")
        return database.withTransaction {
        // W18: Ensure createdAt and updatedAt are set to timeProvider.now()
        val now = timeProvider.now()
        val warrantyWithTimestamps = warranty.copy(
            createdAt = if (warranty.createdAt == 0L) now else warranty.createdAt,
            updatedAt = if (warranty.updatedAt == 0L) now else warranty.updatedAt
        )
        val warrantyId = warrantyDao.insertWarranty(warrantyWithTimestamps)

        // PR-W1: Record CREATED lifecycle event
        try {
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = warrantyId,
                    eventType = WarrantyLifecycleEventTypes.CREATED,
                    occurredAt = now,
                        description = "WARRANTY_CREATED"
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Failed to write CREATED lifecycle event for warrantyId=$warrantyId")
        }

        warrantyId
    }
    }

    suspend fun addWarrantyIgnoreConflicts(warranty: Warranty): Long {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.addWarrantyIgnoreConflicts")
        return database.withTransaction {
        val now = timeProvider.now()
        val warrantyWithTimestamps = warranty.copy(
            createdAt = if (warranty.createdAt == 0L) now else warranty.createdAt,
            updatedAt = if (warranty.updatedAt == 0L) now else warranty.updatedAt
        )
        val id = warrantyDao.insertWarrantyIgnore(warrantyWithTimestamps)
        if (id > 0L) {
            // PR-W1: Record CREATED lifecycle event
            try {
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = id,
                        eventType = WarrantyLifecycleEventTypes.CREATED,
                        occurredAt = now,
                        description = "WARRANTY_CREATED"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "PR-W1: Failed to write CREATED lifecycle event for warrantyId=$id")
            }

            // AID-9 Gap 2: Audit trail for AI-driven warranty creation
            if (warrantyWithTimestamps.autoDetected) {
                val auditMessage = JSONObject().apply {
                    put("confidence", warrantyWithTimestamps.extractionConfidence)
                    put("extractionSource", warrantyWithTimestamps.extractionSource)
                }.toString()
                val auditMetadata = JSONObject().apply {
                    put("warrantyDurationMonths", warrantyWithTimestamps.warrantyDurationMonths)
                    put("warrantyType", warrantyWithTimestamps.warrantyType.name)
                }.toString()
                try {
                    transactionRunner.runInTransaction(
                        operationId = "warranty.ai_warranty_created",
                        correlationId = java.util.UUID.randomUUID().toString(),
                        source = "WarrantyTrackerRepository"
                    ) { ctx ->
                        receiptLifecycleEventWriter.write(
                            ctx,
                            com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent(
                                receiptId = warrantyWithTimestamps.receiptId,
                                sourceType = warrantyWithTimestamps.extractionSource,
                                documentType = "WARRANTY_EXTRACTION",
                                eventType = "AI_WARRANTY_CREATED",
                                actor = "system:ai_warranty_extraction",
                                message = auditMessage,
                                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                    .put("warrantyDurationMonths", warrantyWithTimestamps.warrantyDurationMonths)
                                    .put("warrantyType", warrantyWithTimestamps.warrantyType.name)
                                    .build()
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.w(e, "AID-9: Failed to write AI_WARRANTY_CREATED audit event for warrantyId=$id")
                }
            }
        }
        id
    }
    }
    
    suspend fun updateWarranty(warranty: Warranty) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")
        warrantyDao.updateWarranty(warranty)
        try {
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = warranty.id,
                    eventType = WarrantyLifecycleEventTypes.UPDATED,
                    occurredAt = timeProvider.now(),
                    description = "WARRANTY_UPDATED"
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Failed to write UPDATED lifecycle event for warrantyId=${warranty.id}")
        }
    }

    suspend fun deleteWarranty(warranty: Warranty) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.deleteWarranty")
        warrantyDao.deleteWarranty(warranty)
        try {
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = warranty.id,
                    eventType = WarrantyLifecycleEventTypes.DELETED,
                    occurredAt = timeProvider.now(),
                    description = "WARRANTY_DELETED"
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Failed to write DELETED lifecycle event for warrantyId=${warranty.id}")
        }
    }

    /**
     * S12-009: Atomically reject an auto-detected warranty and its linked return window.
     * Both deletions happen in one transaction — if either fails, both are rolled back.
     */
    suspend fun rejectAutoDetectedWarranty(warranty: Warranty) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.rejectAutoDetectedWarranty")
        database.withTransaction {
            if (warranty.receiptId != null) {
                val returnWindow = getReturnWindowByReceiptId(warranty.receiptId)
                if (returnWindow != null) {
                    returnWindowDao.deleteReturnWindow(returnWindow)
                }
            }
            warrantyDao.deleteWarranty(warranty)
            try {
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = warranty.id,
                        eventType = WarrantyLifecycleEventTypes.AI_EXTRACTION_DISCARDED,
                        occurredAt = timeProvider.now(),
                        description = "WARRANTY_AI_EXTRACTION_DISCARDED"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Failed to write AI_EXTRACTION_DISCARDED event for warrantyId=${warranty.id}")
            }
        }
    }

    suspend fun markWarrantyAsClaimed(warrantyId: Long) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.markWarrantyAsClaimed")
        database.withTransaction {
        val now = timeProvider.now()
        warrantyDao.updateWarrantyStatus(
            warrantyId = warrantyId,
            status = WarrantyStatus.CLAIMED,
            claimedAt = now,
            updatedAt = now
        )

        // PR-W1: Record CLAIMED lifecycle event
        try {
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = warrantyId,
                    eventType = WarrantyLifecycleEventTypes.CLAIMED,
                    occurredAt = now,
                    description = "Warranty claimed"
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "PR-W1: Failed to write CLAIMED lifecycle event for warrantyId=$warrantyId")
        }
        }
    }

    suspend fun getActiveWarrantyCount(): Int = warrantyDao.getActiveWarrantyCount(timeProvider.now())
    
    @Deprecated(
        message = "Use getTotalProtectedValueAggregate() for multi-currency safety",
        replaceWith = ReplaceWith("getTotalProtectedValueAggregate().displayAmount"),
        level = DeprecationLevel.WARNING
    )
    suspend fun getTotalProtectedValue(): Double =
        warrantyDao.getTotalProtectedValue(timeProvider.now()) ?: 0.0

    /**
     * Returns protected value aggregated by currency with conversion awareness.
     * W01: Replaces raw Double sum with MoneyAggregate for multi-currency safety.
     * CURR-C62-10: Returns partial aggregate if home currency unavailable.
     */
    suspend fun getTotalProtectedValueAggregate(): MoneyAggregate {
        val currencyTotals = warrantyDao.getTotalProtectedValueByCurrency(timeProvider.now())
        val homeResolution = currencySettingsRepository.resolveHomeCurrency()
        
        return when (homeResolution) {
            is HomeCurrencyResolution.Resolved, is HomeCurrencyResolution.FirstRunDefault -> {
                val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR"
                val buckets = currencyTotals.map { Pair(it.total, it.currency) }
                val counts = currencyTotals.map { it.txCount }
                MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)
            }
            is HomeCurrencyResolution.Failed -> {
                MoneyAggregate.empty(CurrencyCode("EUR")).copy(
                    isPartial = true,
                    warningMessage = "Home currency unavailable: ${homeResolution.reason}",
                    conversionQuality = ConversionQuality.UNAVAILABLE
                )
            }
        }
    }
    
    // Return window operations
    fun getAllReturnWindows(): Flow<List<ReturnWindow>> = returnWindowDao.getAllReturnWindows()

    private data class ReturnWindowTimestamps(
        val createdAt: Long,
        val updatedAt: Long
    )
    
    fun getActiveReturnWindows(): Flow<List<ReturnWindow>> =
        activeItemsTickerFlow().flatMapLatest {
            returnWindowDao.getActiveReturnWindows(timeProvider.now())
        }
    
    suspend fun getReturnWindowsExpiringSoon(days: Int): List<ReturnWindow> {
        val currentStart = TimePeriodUtils.getStartOfDay(timeProvider.now())
        val futureExclusive = TimePeriodUtils.addDays(currentStart, days.coerceAtLeast(0))
        return returnWindowDao.getReturnWindowsExpiringSoon(
            futureTime = futureExclusive,
            currentTime = currentStart
        )
    }
    
    suspend fun addReturnWindow(returnWindow: ReturnWindow): Long {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.addReturnWindow")
        return createReturnWindowTimestamps().let { timestamps ->
            returnWindowDao.insertReturnWindow(
                returnWindow.withTimestamps(
                    createdAt = timestamps.createdAt,
                    updatedAt = timestamps.updatedAt
                )
            )
        }
    }

    suspend fun getReturnWindowByReceiptId(receiptId: Long): ReturnWindow? =
        returnWindowDao.getReturnWindowByReceiptId(receiptId)
    
    suspend fun updateReturnWindow(returnWindow: ReturnWindow) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.updateReturnWindow")
        returnWindowDao.updateReturnWindow(
            returnWindow.copy(updatedAt = timeProvider.now())
        )
    }
    
    suspend fun deleteReturnWindow(returnWindow: ReturnWindow) {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.deleteReturnWindow")
        returnWindowDao.deleteReturnWindow(returnWindow)
    }

    /**
     * W02: Marks a return window as RETURNED with the given refund amount and currency.
     * If [refundAmount] is null, refund-related fields are left unchanged.
     * If [refundCurrency] is null, it falls back to the linked Expense's currency,
     * then to the user's home currency setting.
     * CURR-C62-10: Falls back to EUR only as last resort if home currency unavailable.
     */
    suspend fun markAsReturned(
        returnWindowId: Long,
        refundAmount: Double? = null,
        refundCurrency: String? = null
    ): ReturnWindow? {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.markAsReturned")
        val existing = returnWindowDao.getReturnWindowById(returnWindowId) ?: return null
        val linkedExpense = existing.expenseId?.let { database.expenseDao().getById(it) }
        val homeResolution = currencySettingsRepository.resolveHomeCurrency()
        val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR" // last resort for refund currency
        val currency = refundCurrency ?: linkedExpense?.currency ?: homeCurrency
        val updated = existing.copy(
            status = ReturnStatus.RETURNED,
            returnedAt = timeProvider.now(),
            refundAmount = refundAmount ?: existing.refundAmount,
            refundCurrency = if (refundAmount != null) currency else existing.refundCurrency,
            updatedAt = timeProvider.now()
        )
        returnWindowDao.updateReturnWindow(updated)
        // PR3-FINALGATE: Do not write a WarrantyLifecycleEvent for return-window actions
        // because warrantyId expects a warranty ID, not a receiptId or returnWindowId.
        // TODO: Add a dedicated ReturnWindowLifecycleEvent table or general diagnostic
        // event infrastructure when schema evolution is planned.
        Timber.d("Return window $returnWindowId marked as RETURNED")
        return updated
    }

    suspend fun reconcileExpiredItems(now: Long = timeProvider.now()): ExpiryReconciliationResult {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.reconcileExpiredItems")
        val expiredWarranties = warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now)
        val expiredReturnWindows = returnWindowDao.markExpiredReturnWindows(currentTime = now, updatedAt = now)
        val resultNow = now
        if (expiredWarranties > 0 || expiredReturnWindows > 0) {
            try {
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = -1L,
                        eventType = WarrantyLifecycleEventTypes.EXPIRED,
                        occurredAt = resultNow,
                        description = "Batch expired: $expiredWarranties warranties, $expiredReturnWindows return windows"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Failed to write EXPIRED batch lifecycle event")
            }
        }
        return ExpiryReconciliationResult(
            expiredWarrantyCount = expiredWarranties,
            expiredReturnWindowCount = expiredReturnWindows
        )
    }

    suspend fun upsertReturnWindowForReceipt(receiptId: Long, fallbackWarranty: Warranty? = null): Long? {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.upsertReturnWindowForReceipt")
        val receipt = receiptRepository.get().getReceiptById(receiptId) ?: return null
        // Skip return window creation for bank statements, manual placeholders, and failed OCR receipts
        if (receipt.documentType == "BANK_STATEMENT" || receipt.documentType == "MANUAL_PLACEHOLDER" ||
            receipt.processingStatus == "OCR_FAILED") {
            return null
        }
        val extractionResult = extractWarrantyResult(receipt)
        val extractedWarranty = extractionResult?.toWarrantyEntityOrNull(receipt)
        val resolvedWarranty = fallbackWarranty ?: extractedWarranty
        val returnWindow = extractReturnWindow(receipt, resolvedWarranty, extractionResult) ?: return null
        val existingReturnWindow = returnWindowDao.getReturnWindowByReceiptId(receiptId)

        return if (existingReturnWindow == null) {
            val timestamps = createReturnWindowTimestamps()
            returnWindowDao.insertReturnWindow(
                returnWindow.withTimestamps(
                    createdAt = timestamps.createdAt,
                    updatedAt = timestamps.updatedAt
                )
            )
        } else {
            returnWindowDao.updateReturnWindow(
                returnWindow.copy(
                    id = existingReturnWindow.id,
                    returnPolicyUrl = returnWindow.returnPolicyUrl ?: existingReturnWindow.returnPolicyUrl,
                    returnConditions = returnWindow.returnConditions ?: existingReturnWindow.returnConditions,
                    status = existingReturnWindow.status,
                    returnedAt = existingReturnWindow.returnedAt,
                    refundAmount = existingReturnWindow.refundAmount,
                    createdAt = existingReturnWindow.createdAt,
                    updatedAt = timeProvider.now()
                )
            )
            existingReturnWindow.id
        }
    }
    
    // AI extraction
    suspend fun extractWarrantyFromReceipt(receipt: ScannedReceipt): Warranty? {
        val extractionResult = extractWarrantyResult(receipt) ?: return null
        return extractionResult.toWarrantyEntityOrNull(receipt)
    }

    private suspend fun extractWarrantyResult(receipt: ScannedReceipt): WarrantyExtractionResult? {
        val settings = aiSettingsRepository.settings().first()
        val routeDecision = aiCapabilityRouter.decide(AiCapability.WARRANTY_EXTRACTION, settings)
        Timber.d(
            "WarrantyTrackerRepository: warranty extraction route=%s reason=%s provider=%s model=%s",
            routeDecision.route,
            routeDecision.reason,
            routeDecision.providerName,
            routeDecision.modelName
        )

        if (routeDecision.route != AiRoute.CLOUD) {
            Timber.d(
                "WarrantyTrackerRepository: skipping cloud warranty extraction route=%s receiptId=%d",
                routeDecision.route,
                receipt.id
            )
            return null
        }

        val input = WarrantyExtractionInput(
            receiptText = receipt.rawOcrText,
            merchant = receipt.parsedMerchant,
            totalAmount = receipt.parsedTotal,
            purchaseDate = receipt.parsedDate,
            currency = receipt.currency
        )

        return cloudExtractionService.extractWarranty(input)
    }

    /**
     * Three-band cloud extraction confidence threshold enforcement.
     *
     * - confidence >= AUTO_ACCEPT_CLOUD_CONFIDENCE (0.75): auto-accepted, status = ACTIVE
     * - REVIEW_CLOUD_CONFIDENCE (0.30) <= confidence < AUTO_ACCEPT_CLOUD_CONFIDENCE (0.75):
     *     created as a needs-review draft, status = PENDING_REVIEW
     * - confidence < REVIEW_CLOUD_CONFIDENCE (0.30): discarded entirely (returns null)
     */
    private suspend fun WarrantyExtractionResult.toWarrantyEntityOrNull(receipt: ScannedReceipt): Warranty? {
        if (confidence < REVIEW_CLOUD_CONFIDENCE) {
            Timber.d("Cloud warranty extraction discarded: confidence $confidence below review threshold $REVIEW_CLOUD_CONFIDENCE for receiptId=${receipt.id}")
            // PR4-FINALGATE: Write discard diagnostic to warranty lifecycle events.
            // We use warrantyId = -1 as a sentinel to indicate this is not tied to a specific warranty.
            try {
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = -1L,
                        eventType = WarrantyLifecycleEventTypes.AI_EXTRACTION_DISCARDED,
                        occurredAt = timeProvider.now(),
                        description = "Cloud warranty extraction discarded: confidence=$confidence below threshold=$REVIEW_CLOUD_CONFIDENCE receiptId=${receipt.id}"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Failed to write AI_EXTRACTION_DISCARDED diagnostic for receiptId=${receipt.id}")
            }
            return null
        }

        val needsReview = confidence < AUTO_ACCEPT_CLOUD_CONFIDENCE
        val status = if (needsReview) WarrantyStatus.PENDING_REVIEW else WarrantyStatus.ACTIVE

        val durationMonths = warrantyMonths ?: return null
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val warrantyEndDate = purchaseDate.toCalendarMonthEndDate(durationMonths)

        return Warranty(
            receiptId = receipt.id,
            expenseId = receipt.expenseId,
            productName = productName,
            // WRN-18: Use actual merchant from receipt; fallback includes context.
            merchantName = receipt.parsedMerchant ?: receipt.sourceType?.let { "Receipt ($it)" } ?: "Unknown merchant",
            purchaseDate = purchaseDate,
            warrantyDurationMonths = durationMonths,
            warrantyEndDate = warrantyEndDate,
            warrantyType = parseWarrantyType(warrantyType),
            autoDetected = true,
            extractionSource = "cloud",
            supportPhone = supportPhone,
            supportEmail = supportEmail,
            notes = returnConditions,
            extractionConfidence = confidence.toDouble(),
            needsReview = needsReview,
            status = status
        )
    }

    private fun parseWarrantyType(type: String): WarrantyType {
        return try {
            WarrantyType.valueOf(type.uppercase())
        } catch (_: IllegalArgumentException) {
            WarrantyType.MANUFACTURER
        }
    }
    
    // Extract return window from warranty extraction result
    suspend fun extractReturnWindow(
        receipt: ScannedReceipt,
        warranty: Warranty?,
        extractionResult: WarrantyExtractionResult? = null
    ): ReturnWindow? {
        val returnDays = extractionResult?.returnDays ?: defaultReturnDaysForMerchant(receipt.parsedMerchant)
        if (returnDays <= 0) return null
        
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val purchaseStart = TimePeriodUtils.getStartOfDay(purchaseDate)
        // Use half-open end-of-day semantics so the return window survives
        // through its entire deadline day (matches warranty end-date fix).
        val deadlineMidnight = TimePeriodUtils.addDays(purchaseStart, returnDays)
        val returnDeadline = TimePeriodUtils.getEndOfDay(deadlineMidnight)
        val timestamps = createReturnWindowTimestamps()
        
        // WRN-18: Use descriptive fallback for unknown merchant/product in return window.
        val resolvedMerchant = receipt.parsedMerchant
            ?: receipt.sourceType?.let { "Receipt ($it)" }
            ?: "Unknown merchant"
        return ReturnWindow(
            receiptId = receipt.id,
            expenseId = receipt.expenseId,
            productName = warranty?.productName ?: "Purchase from $resolvedMerchant",
            merchantName = resolvedMerchant,
            purchaseDate = purchaseDate,
            returnDays = returnDays,
            returnDeadline = returnDeadline,
            returnConditions = extractionResult?.returnConditions,
            createdAt = timestamps.createdAt,
            updatedAt = timestamps.updatedAt
        )
    }
    
    // Batch operations for receipt scanning
    suspend fun processReceiptForWarranty(receipt: ScannedReceipt): Pair<Warranty?, ReturnWindow?> {
        val extractionResult = extractWarrantyResult(receipt)
        val warranty = extractionResult?.toWarrantyEntityOrNull(receipt)
        val returnWindow = extractReturnWindow(receipt, warranty, extractionResult)
        
        return Pair(warranty, returnWindow)
    }

    private fun defaultReturnDaysForMerchant(merchant: String?): Int {
        return when {
            merchant?.contains("Amazon", ignoreCase = true) == true -> 30
            merchant?.contains("Best Buy", ignoreCase = true) == true -> 15
            merchant?.contains("Apple", ignoreCase = true) == true -> 14
            else -> 30
        }
    }

    // W20-FIXED: Half-open warranty dates used for new paths (exclusive end boundary).
    /**
     * Returns the exclusive end-boundary of the calendar date [durationMonths]
     * after the date represented by this timestamp.
     *
     * The result is the **start of the next day** after the last valid day,
     * maintaining half-open interval semantics throughout the system.
     *
     * Before this fix, `atStartOfDay` was used, which caused warranties to
     * be marked expired at 00:00:00 of their last valid day — losing a full
     * day of coverage. The half-open fix means:
     *   - A warranty expiring May 5 has endDate = May 6 00:00:00
     *   - `warrantyEndDate < now()` is false all day on May 5, true on May 6
     *
     * See also: `createReturnWindowDeadline` which uses the same half-open pattern.
     */
    private fun Long.toCalendarMonthEndDate(durationMonths: Int): Long {
        val zoneId = ZoneId.systemDefault()
        val endDate = Instant.ofEpochMilli(this)
            .atZone(zoneId)
            .toLocalDate()
            .plusMonths(durationMonths.toLong())

        // Use end-of-day (exclusive next-day start) for half-open correctness
        val dayStart = endDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return TimePeriodUtils.getEndOfDay(dayStart)
    }

    data class ExpiryReconciliationResult(
        val expiredWarrantyCount: Int,
        val expiredReturnWindowCount: Int
    )

    private fun createReturnWindowTimestamps(): ReturnWindowTimestamps {
        val now = timeProvider.now()
        return ReturnWindowTimestamps(
            createdAt = now,
            updatedAt = now
        )
    }

    private fun ReturnWindow.withTimestamps(createdAt: Long, updatedAt: Long): ReturnWindow {
        return copy(
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * Creates a synthetic placeholder receipt for manual warranty/return window entries.
     *
     * NOTE: This bypasses [ReceiptLifecycleCoordinator] intentionally — this is NOT a
     * real receipt scan. It creates a lightweight [ScannedReceipt] record solely to
     * satisfy the foreign-key constraint on [Warranty.receiptId] / [ReturnWindow.receiptId].
     * No OCR, deduplication, or warranty side effects are needed since the user is
     * manually entering warranty data.
     * CURR-C62-10: Falls back to EUR only as last resort if home currency unavailable.
     */
    suspend fun createManualPlaceholderReceipt(
        merchantName: String,
        purchaseDate: Long,
        productName: String
    ): Long {
        val now = timeProvider.now()
        val homeResolution = currencySettingsRepository.resolveHomeCurrency()
        val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR" // last resort for placeholder
        val receipt = ScannedReceipt(
            imagePath = null,
            rawOcrText = "Manual warranty entry",
            parsedTotal = null,
            parsedMerchant = merchantName,
            parsedDate = purchaseDate,
            parsedItems = null,
            parsedTaxAmount = null,
            // W21: Use homeCurrency from CurrencySettingsRepository instead of hardcoded EUR.
            currency = homeCurrency,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            sourceType = "MANUAL_RECORD",
            documentType = "MANUAL_PLACEHOLDER",
            processingStatus = "PARSED"
        )
        return receiptRepository.get().insertReceipt(receipt)
    }
}
