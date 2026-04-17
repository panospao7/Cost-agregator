package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
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
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarrantyTrackerRepository @Inject constructor(
    private val warrantyDao: WarrantyDao,
    private val returnWindowDao: ReturnWindowDao,
    private val scannedReceiptDao: com.yourname.expensetracker.data.database.dao.ScannedReceiptDao,
    private val cloudExtractionService: CloudWarrantyExtractionService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiPolicy: AiPolicy,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val timeProvider: TimeProvider
) {
    private companion object {
        private const val ACTIVE_ITEMS_REFRESH_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
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
    
    suspend fun addWarranty(warranty: Warranty): Long = warrantyDao.insertWarranty(warranty)

    suspend fun addWarrantyIgnoreConflicts(warranty: Warranty): Long = warrantyDao.insertWarrantyIgnore(warranty)
    
    suspend fun updateWarranty(warranty: Warranty) = warrantyDao.updateWarranty(warranty)
    
    suspend fun deleteWarranty(warranty: Warranty) = warrantyDao.deleteWarranty(warranty)
    
    suspend fun markWarrantyAsClaimed(warrantyId: Long) = 
        warrantyDao.updateWarrantyStatus(
            warrantyId = warrantyId,
            status = WarrantyStatus.CLAIMED,
            updatedAt = timeProvider.now()
        )

    suspend fun getActiveWarrantyCount(): Int = warrantyDao.getActiveWarrantyCount(timeProvider.now())
    
    suspend fun getTotalProtectedValue(): Double =
        warrantyDao.getTotalProtectedValue(timeProvider.now()) ?: 0.0
    
    // Return window operations
    fun getAllReturnWindows(): Flow<List<ReturnWindow>> = returnWindowDao.getAllReturnWindows()
    
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
        returnWindowDao.insertReturnWindow(returnWindow)

    suspend fun getReturnWindowByReceiptId(receiptId: Long): ReturnWindow? =
        returnWindowDao.getReturnWindowByReceiptId(receiptId)
    
    suspend fun updateReturnWindow(returnWindow: ReturnWindow) = 
        returnWindowDao.updateReturnWindow(returnWindow)
    
    suspend fun markAsReturned(
        returnWindowId: Long, 
        refundAmount: Double? = null
    ) = returnWindowDao.markAsReturned(
        returnWindowId = returnWindowId,
        returnedAt = timeProvider.now(),
        refundAmount = refundAmount,
        updatedAt = timeProvider.now()
    )

    suspend fun reconcileExpiredItems(now: Long = timeProvider.now()): ExpiryReconciliationResult {
        val expiredWarranties = warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now)
        val expiredReturnWindows = returnWindowDao.markExpiredReturnWindows(currentTime = now, updatedAt = now)
        return ExpiryReconciliationResult(
            expiredWarrantyCount = expiredWarranties,
            expiredReturnWindowCount = expiredReturnWindows
        )
    }

    suspend fun upsertReturnWindowForReceipt(receiptId: Long, fallbackWarranty: Warranty? = null): Long? {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return null
        val extractionResult = extractWarrantyResult(receipt)
        val extractedWarranty = extractionResult?.toWarrantyEntityOrNull(receipt)
        val resolvedWarranty = fallbackWarranty ?: extractedWarranty
        val returnWindow = extractReturnWindow(receipt, resolvedWarranty, extractionResult) ?: return null
        val existingReturnWindow = returnWindowDao.getReturnWindowByReceiptId(receiptId)

        return if (existingReturnWindow == null) {
            returnWindowDao.insertReturnWindow(returnWindow)
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

    private fun WarrantyExtractionResult.toWarrantyEntityOrNull(receipt: ScannedReceipt): Warranty? {
        val durationMonths = warrantyMonths ?: return null
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val warrantyEndDate = purchaseDate.toCalendarMonthEndDate(durationMonths)

        return Warranty(
            receiptId = receipt.id,
            expenseId = receipt.expenseId,
            productName = productName,
            merchantName = receipt.parsedMerchant ?: "Unknown",
            purchaseDate = purchaseDate,
            warrantyDurationMonths = durationMonths,
            warrantyEndDate = warrantyEndDate,
            warrantyType = parseWarrantyType(warrantyType),
            supportPhone = supportPhone,
            supportEmail = supportEmail,
            notes = returnConditions
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
        val returnDeadline = TimePeriodUtils.addDays(purchaseStart, returnDays)
        
        return ReturnWindow(
            receiptId = receipt.id,
            expenseId = receipt.expenseId,
            productName = warranty?.productName ?: "Purchase from ${receipt.parsedMerchant ?: "Unknown"}",
            merchantName = receipt.parsedMerchant ?: "Unknown",
            purchaseDate = purchaseDate,
            returnDays = returnDays,
            returnDeadline = returnDeadline,
            returnConditions = extractionResult?.returnConditions
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

    private fun Long.toCalendarMonthEndDate(durationMonths: Int): Long {
        val zoneId = ZoneId.systemDefault()
        val endDate = Instant.ofEpochMilli(this)
            .atZone(zoneId)
            .toLocalDate()
            .plusMonths(durationMonths.toLong())

        return endDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    data class ExpiryReconciliationResult(
        val expiredWarrantyCount: Int,
        val expiredReturnWindowCount: Int
    )

    suspend fun createManualPlaceholderReceipt(
        merchantName: String,
        purchaseDate: Long,
        productName: String
    ): Long {
        val receipt = ScannedReceipt(
            imagePath = null,
            rawOcrText = "Manual warranty entry: $productName",
            parsedTotal = null,
            parsedMerchant = merchantName,
            parsedDate = purchaseDate,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 1f
        )
        return scannedReceiptDao.insert(receipt)
    }
}
