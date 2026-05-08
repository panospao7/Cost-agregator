package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionInput
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
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
    private val receiptRepository: Lazy<ReceiptRepository>,
    private val cloudExtractionService: CloudWarrantyExtractionService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiPolicy: AiPolicy,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    private companion object {
        private const val ACTIVE_ITEMS_REFRESH_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        /** WRN-16: Minimum confidence threshold for cloud-extracted warranties (0.0 - 1.0). */
        private const val MIN_CLOUD_CONFIDENCE = 0.5f
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
    suspend fun addWarranty(warranty: Warranty): Long = database.withTransaction {
        // W18: Ensure createdAt and updatedAt are set to timeProvider.now()
        val now = timeProvider.now()
        val warrantyWithTimestamps = warranty.copy(
            createdAt = if (warranty.createdAt == 0L) now else warranty.createdAt,
            updatedAt = if (warranty.updatedAt == 0L) now else warranty.updatedAt
        )
        warrantyDao.insertWarranty(warrantyWithTimestamps)
    }

    suspend fun addWarrantyIgnoreConflicts(warranty: Warranty): Long = database.withTransaction {
        val id = warrantyDao.insertWarrantyIgnore(warranty)
        // AID-9 Gap 2: Audit trail for AI-driven warranty creation
        if (id > 0L && warranty.autoDetected) {
            val auditMessage = JSONObject().apply {
                put("confidence", warranty.extractionConfidence)
                put("extractionSource", warranty.extractionSource)
            }.toString()
            val auditMetadata = JSONObject().apply {
                put("productName", warranty.productName)
                put("warrantyDurationMonths", warranty.warrantyDurationMonths)
                put("warrantyType", warranty.warrantyType.name)
            }.toString()
            runCatching {
                database.receiptEventDao().insert(
                    ReceiptEvent(
                        receiptId = warranty.receiptId,
                        sourceType = warranty.extractionSource,
                        documentType = "WARRANTY_EXTRACTION",
                        eventType = "AI_WARRANTY_CREATED",
                        occurredAt = timeProvider.now(),
                        oldStatus = null,
                        newStatus = null,
                        actor = "system:ai_warranty_extraction",
                        message = auditMessage,
                        metadata = auditMetadata,
                        errorDetails = null
                    )
                )
            }.onFailure { error ->
                Timber.w(error, "AID-9: Failed to write AI_WARRANTY_CREATED audit event for warrantyId=$id")
            }
        }
        id
    }
    
    suspend fun updateWarranty(warranty: Warranty) = warrantyDao.updateWarranty(warranty)
    
    suspend fun deleteWarranty(warranty: Warranty) = warrantyDao.deleteWarranty(warranty)
    
    suspend fun markWarrantyAsClaimed(warrantyId: Long) = 
        warrantyDao.updateWarrantyStatus(
            warrantyId = warrantyId,
            status = WarrantyStatus.CLAIMED,
            claimedAt = timeProvider.now(),
            updatedAt = timeProvider.now()
        )

    suspend fun getActiveWarrantyCount(): Int = warrantyDao.getActiveWarrantyCount(timeProvider.now())
    
    @Deprecated("Use getTotalProtectedValueAggregate() for multi-currency safety",
        ReplaceWith("getTotalProtectedValueAggregate().displayAmount"))
    suspend fun getTotalProtectedValue(): Double =
        warrantyDao.getTotalProtectedValue(timeProvider.now()) ?: 0.0

    /**
     * Returns protected value aggregated by currency with conversion awareness.
     * W01: Replaces raw Double sum with MoneyAggregate for multi-currency safety.
     * Now uses CurrencyConverter + CurrencySettingsRepository to convert each
     * bucket to the user's home currency.
     */
    suspend fun getTotalProtectedValueAggregate(): MoneyAggregate {
        val currencyTotals = warrantyDao.getTotalProtectedValueByCurrency(timeProvider.now())
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val buckets = currencyTotals.map { Pair(it.total, it.currency) }
        val counts = currencyTotals.map { it.txCount }
        return MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)
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
    
    suspend fun addReturnWindow(returnWindow: ReturnWindow): Long =
        createReturnWindowTimestamps().let { timestamps ->
            returnWindowDao.insertReturnWindow(
                returnWindow.withTimestamps(
                    createdAt = timestamps.createdAt,
                    updatedAt = timestamps.updatedAt
                )
            )
        }

    suspend fun getReturnWindowByReceiptId(receiptId: Long): ReturnWindow? =
        returnWindowDao.getReturnWindowByReceiptId(receiptId)
    
    suspend fun updateReturnWindow(returnWindow: ReturnWindow) =
        returnWindowDao.updateReturnWindow(
            returnWindow.copy(updatedAt = timeProvider.now())
        )
    
    suspend fun deleteReturnWindow(returnWindow: ReturnWindow) =
        returnWindowDao.deleteReturnWindow(returnWindow)

    /**
     * W02: Marks a return window as RETURNED with the given refund amount and currency.
     * If [refundCurrency] is null, the currency is inferred from the linked Expense,
     * falling back to "EUR".
     */
    suspend fun markAsReturned(
        returnWindowId: Long,
        refundAmount: Double,
        refundCurrency: String? = null
    ): ReturnWindow? {
        val existing = returnWindowDao.getReturnWindowById(returnWindowId) ?: return null
        val linkedExpense = existing.expenseId?.let { database.expenseDao().getById(it) }
        val currency = refundCurrency ?: linkedExpense?.currency ?: "EUR"
        val updated = existing.copy(
            status = ReturnStatus.RETURNED,
            returnedAt = timeProvider.now(),
            refundAmount = refundAmount,
            refundCurrency = currency,
            updatedAt = timeProvider.now()
        )
        returnWindowDao.updateReturnWindow(updated)
        return updated
    }

    suspend fun reconcileExpiredItems(now: Long = timeProvider.now()): ExpiryReconciliationResult {
        val expiredWarranties = warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now)
        val expiredReturnWindows = returnWindowDao.markExpiredReturnWindows(currentTime = now, updatedAt = now)
        return ExpiryReconciliationResult(
            expiredWarrantyCount = expiredWarranties,
            expiredReturnWindowCount = expiredReturnWindows
        )
    }

    suspend fun upsertReturnWindowForReceipt(receiptId: Long, fallbackWarranty: Warranty? = null): Long? {
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

        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.WARRANTY_EXTRACTION)
        val input = WarrantyExtractionInput(
            receiptText = receipt.rawOcrText,
            merchant = receipt.parsedMerchant,
            totalAmount = receipt.parsedTotal,
            purchaseDate = receipt.parsedDate,
            currency = receipt.currency
        )

        return cloudExtractionService.extractWarranty(input, shouldRedact)
    }

    /**
     * WRN-16: Cloud extraction confidence threshold enforcement.
     * If confidence is below MIN_CLOUD_CONFIDENCE (0.5), the extraction
     * result is discarded entirely (returns null) rather than creating
     * a low-confidence warranty or a needs-review draft. This prevents
     * unreliable cloud predictions from polluting the warranty list.
     */
    private fun WarrantyExtractionResult.toWarrantyEntityOrNull(receipt: ScannedReceipt): Warranty? {
        // WRN-16: Block extraction below confidence threshold
        if (confidence < MIN_CLOUD_CONFIDENCE) {
            Timber.w(
                "WRN-16: Cloud extraction confidence %.2f below threshold %.2f for receipt %d, discarding",
                confidence, MIN_CLOUD_CONFIDENCE, receipt.id
            )
            return null
        }

        val durationMonths = warrantyMonths ?: return null
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val warrantyEndDate = purchaseDate.toCalendarMonthEndDate(durationMonths)

        // WRN-3: Only auto-accept warranty if confidence > 0.3.
        // Below that threshold the warranty is flagged for human review.
        val lowConfidence = confidence <= 0.3f

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
            supportPhone = supportPhone,
            supportEmail = supportEmail,
            notes = returnConditions,
            extractionConfidence = confidence.toDouble(),
            needsReview = lowConfidence
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

    // TODO (W20): Use half-open warranty dates: startInclusive, endExclusive.
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
     */
    // W21: Use homeCurrency from CurrencySettingsRepository instead of hardcoded EUR.
    suspend fun createManualPlaceholderReceipt(
        merchantName: String,
        purchaseDate: Long,
        productName: String
    ): Long {
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val receipt = ScannedReceipt(
            imagePath = null,
            rawOcrText = "Manual warranty entry: $productName",
            parsedTotal = null,
            parsedMerchant = merchantName,
            parsedDate = purchaseDate,
            parsedItems = null,
            parsedTaxAmount = null,
            // W21: Use homeCurrency from CurrencySettingsRepository instead of hardcoded EUR.
            currency = homeCurrency,
            confidence = 1f
        )
        return receiptRepository.get().insertReceipt(receipt)
    }
}
