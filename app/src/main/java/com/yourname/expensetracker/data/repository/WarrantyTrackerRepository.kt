package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import org.json.JSONObject
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
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val receiptLifecycleEventWriter: com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter,
    private val transactionRunner: DomainTransactionRunner
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

        // PR-W1: Record CREATED lifecycle event.
        // R29: fail closed. The event write is part of this transaction; a failed nested
        // write marks the outer Room transaction as failed (silent rollback), so the
        // failure must propagate instead of reporting an unsaved warranty id.
        warrantyLifecycleEventDao.insert(
            WarrantyLifecycleEvent(
                warrantyId = warrantyId,
                eventType = WarrantyLifecycleEventTypes.CREATED,
                occurredAt = now,
                description = "WARRANTY_CREATED"
            )
        )

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
            // PR-W1: Record CREATED lifecycle event (R29: fail closed, see addWarranty).
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = id,
                    eventType = WarrantyLifecycleEventTypes.CREATED,
                    occurredAt = now,
                    description = "WARRANTY_CREATED"
                )
            )

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
                // R29: fail closed. Any audit failure (barrier-blocked or otherwise) must
                // roll back the warranty insert AND surface to the caller instead of
                // returning the id of an unsaved warranty.
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
            // R29: fail closed inside the transaction (see addWarranty).
            warrantyLifecycleEventDao.insert(
                WarrantyLifecycleEvent(
                    warrantyId = warranty.id,
                    eventType = WarrantyLifecycleEventTypes.AI_EXTRACTION_DISCARDED,
                    occurredAt = timeProvider.now(),
                    description = "WARRANTY_AI_EXTRACTION_DISCARDED"
                )
            )
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

        // PR-W1: Record CLAIMED lifecycle event (R29: fail closed inside the transaction).
        warrantyLifecycleEventDao.insert(
            WarrantyLifecycleEvent(
                warrantyId = warrantyId,
                eventType = WarrantyLifecycleEventTypes.CLAIMED,
                occurredAt = now,
                description = "Warranty claimed"
            )
        )
        }
    }

    /**
     * ai-fixes INV-13/INV-14: the single legal path out of review for the user.
     * (The other exit is [rejectAutoDetectedWarranty].) The ViewModel passes the id only and
     * never builds `status` / `needsReview` itself.
     *
     * One transaction: barrier check (first) -> fresh read by id -> decide -> write -> event.
     * - Row missing -> [ConfirmWarrantyResult.NotFound] (no write, no event).
     * - Row not in review (`needsReview=false` and not PENDING_REVIEW, or CLAIMED/TRANSFERRED)
     *   -> [ConfirmWarrantyResult.AlreadyConfirmed] (no write, no event); idempotent.
     * - Otherwise `needsReview=false`, `status = EXPIRED` when the row is already EXPIRED or
     *   `warrantyEndDate < now` (same predicate as the expiry sweep), else ACTIVE.
     *   An EXPIRED row is never moved back to ACTIVE. A [WarrantyLifecycleEventTypes.CONFIRMED]
     *   event is written in the same transaction.
     *
     * R29: the event write is fail-closed. A failed nested write marks the outer Room transaction
     * as failed, so the failure must surface instead of reporting a confirm that was rolled back.
     */
    suspend fun confirmWarranty(warrantyId: Long): ConfirmWarrantyResult {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.confirmWarranty")
        return database.withTransaction {
            val current = warrantyDao.getWarrantyById(warrantyId)
            if (current == null) {
                ConfirmWarrantyResult.NotFound
            } else if (isNoLongerInReview(current)) {
                ConfirmWarrantyResult.AlreadyConfirmed
            } else {
                val now = timeProvider.now()
                val newStatus =
                    if (current.status == WarrantyStatus.EXPIRED || current.warrantyEndDate < now) {
                        WarrantyStatus.EXPIRED
                    } else {
                        WarrantyStatus.ACTIVE
                    }
                warrantyDao.updateWarranty(
                    current.copy(needsReview = false, status = newStatus, updatedAt = now)
                )
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = current.id,
                        eventType = WarrantyLifecycleEventTypes.CONFIRMED,
                        occurredAt = now,
                        description = "WARRANTY_CONFIRMED"
                    )
                )
                ConfirmWarrantyResult.Confirmed(warrantyId = current.id, status = newStatus)
            }
        }
    }

    /**
     * ai-fixes F-AI-1: edit the content of a review draft and leave review as ONE atomic operation.
     *
     * One transaction (behind the write barrier): fresh read by id -> [edit] -> update of CONTENT
     * fields only -> [confirmWarranty] on that same row (nested call joins this transaction, so the
     * past-end rule, the CONFIRMED event and the idempotency rules are not duplicated here).
     *
     * [edit] receives the freshly read row and returns the desired content. Only these fields of its
     * result are applied: product, merchant, purchase date, duration, end date, type, support
     * phone/email, notes, autoDetected, extractionConfidence, extractionSource. `status`,
     * `needsReview`, `claimedAt` and every other field always come from the fresh row, so a
     * concurrent expiry sweep or claim between the caller's read and this call is never undone.
     * [edit] must be pure and non-suspending (it runs inside the Room transaction).
     *
     * Content is applied only while the row is still in review, so a concurrent claim, confirm or
     * user edit is never overwritten.
     *
     * - Row missing -> [ConfirmWarrantyResult.NotFound] (no write, no event).
     * - Row no longer in review (same predicate as [confirmWarranty], [isNoLongerInReview]) ->
     *   [ConfirmWarrantyResult.AlreadyConfirmed] with NO write and NO event; [edit] is not invoked.
     * - Otherwise the content is applied and the [confirmWarranty] result is returned (Confirmed).
     */
    suspend fun promoteReviewDraft(
        warrantyId: Long,
        edit: (Warranty) -> Warranty
    ): ConfirmWarrantyResult {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.promoteReviewDraft")
        return database.withTransaction {
            val fresh = warrantyDao.getWarrantyById(warrantyId)
            if (fresh == null) {
                ConfirmWarrantyResult.NotFound
            } else if (isNoLongerInReview(fresh)) {
                ConfirmWarrantyResult.AlreadyConfirmed
            } else {
                val edited = edit(fresh)
                warrantyDao.updateWarranty(
                    fresh.copy(
                        productName = edited.productName,
                        merchantName = edited.merchantName,
                        purchaseDate = edited.purchaseDate,
                        warrantyDurationMonths = edited.warrantyDurationMonths,
                        warrantyEndDate = edited.warrantyEndDate,
                        warrantyType = edited.warrantyType,
                        supportPhone = edited.supportPhone,
                        supportEmail = edited.supportEmail,
                        notes = edited.notes,
                        autoDetected = edited.autoDetected,
                        extractionConfidence = edited.extractionConfidence,
                        extractionSource = edited.extractionSource,
                        updatedAt = timeProvider.now()
                    )
                )
                confirmWarranty(warrantyId)
            }
        }
    }

    /**
     * Single review-exit predicate shared by [confirmWarranty] and [promoteReviewDraft]:
     * CLAIMED / TRANSFERRED, or `needsReview == false` while not PENDING_REVIEW.
     */
    private fun isNoLongerInReview(warranty: Warranty): Boolean =
        warranty.status == WarrantyStatus.CLAIMED ||
            warranty.status == WarrantyStatus.TRANSFERRED ||
            (!warranty.needsReview && warranty.status != WarrantyStatus.PENDING_REVIEW)

    suspend fun getActiveWarrantyCount(): Int =warrantyDao.getActiveWarrantyCount(timeProvider.now())
    
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
    
    suspend fun getReturnWindowByReceiptId(receiptId: Long): ReturnWindow? =
        returnWindowDao.getReturnWindowByReceiptId(receiptId)

    /**
     * ai-fixes INV-11: sole writer of any transition into EXPIRED.
     *
     * Expires ACTIVE and PENDING_REVIEW warranties whose `warrantyEndDate < now` (pending rows keep
     * `needsReview=true`, i.e. "expired unconfirmed") plus RETURNABLE return windows past their
     * deadline. Idempotent: a second run with the same [now] changes nothing and writes no event.
     * The pending count is read and the sweep is applied in one transaction so the counts agree.
     * The batch EXPIRED event description carries counts only.
     */
    suspend fun reconcileExpiredItems(now: Long = timeProvider.now()): ExpiryReconciliationResult {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.reconcileExpiredItems")
        val (expiredWarranties, expiredPendingWarranties) = database.withTransaction {
            val pending = warrantyDao.getExpirablePendingWarrantyCount(currentTime = now)
            val total = warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now)
            total to pending.coerceAtMost(total)
        }
        val expiredReturnWindows = returnWindowDao.markExpiredReturnWindows(currentTime = now, updatedAt = now)
        val resultNow = now
        if (expiredWarranties > 0 || expiredReturnWindows > 0) {
            try {
                warrantyLifecycleEventDao.insert(
                    WarrantyLifecycleEvent(
                        warrantyId = -1L,
                        eventType = WarrantyLifecycleEventTypes.EXPIRED,
                        occurredAt = resultNow,
                        description = "Batch expired: $expiredWarranties warranties " +
                            "($expiredPendingWarranties unconfirmed), $expiredReturnWindows return windows"
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w("Failed to write EXPIRED batch lifecycle event (%s)", e::class.simpleName ?: "UnknownException")
            }
        }
        return ExpiryReconciliationResult(
            expiredWarrantyCount = expiredWarranties,
            expiredReturnWindowCount = expiredReturnWindows,
            expiredPendingWarrantyCount = expiredPendingWarranties
        )
    }

    /**
     * ai-fixes INV-5/INV-6: return windows derive only from local structured receipt fields
     * (read through [ReceiptRepository]) and the merchant-default day table. No AI or cloud
     * call, and persisted `rawOcrText` is never read here.
     *
     * Write barrier first -> structured receipt fields -> merchant-default `returnDays` ->
     * insert, or update preserving status, returnedAt, refundAmount, createdAt and (when the new
     * value is null, which is always the case locally) the existing conditions / policy URL.
     */
    suspend fun upsertReturnWindowForReceipt(receiptId: Long, fallbackWarranty: Warranty? = null): Long? {
        writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.upsertReturnWindowForReceipt")
        val receipt = receiptRepository.get().getReceiptById(receiptId) ?: return null
        // Skip return window creation for bank statements, manual placeholders, and failed OCR receipts
        if (receipt.documentType == "BANK_STATEMENT" || receipt.documentType == "MANUAL_PLACEHOLDER" ||
            receipt.processingStatus == "OCR_FAILED") {
            return null
        }
        val returnWindow = extractReturnWindow(receipt, fallbackWarranty) ?: return null
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
    
    /**
     * ai-fixes INV-6: derive a return window from local structured receipt fields only.
     * `returnDays` is the merchant default; `returnConditions` / `returnPolicyUrl` are never
     * produced here (null on insert; existing values are preserved on update by
     * [upsertReturnWindowForReceipt]).
     */
    suspend fun extractReturnWindow(
        receipt: ScannedReceipt,
        warranty: Warranty?
    ): ReturnWindow? {
        val returnDays = defaultReturnDaysForMerchant(receipt.parsedMerchant)
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
            returnConditions = null,
            createdAt = timestamps.createdAt,
            updatedAt = timestamps.updatedAt
        )
    }
    
    private fun defaultReturnDaysForMerchant(merchant: String?): Int {
        return when {
            merchant?.contains("Amazon", ignoreCase = true) == true -> 30
            merchant?.contains("Best Buy", ignoreCase = true) == true -> 15
            merchant?.contains("Apple", ignoreCase = true) == true -> 14
            else -> 30
        }
    }

    /**
     * @property expiredWarrantyCount total ACTIVE + PENDING_REVIEW warranties expired by this run.
     * @property expiredPendingWarrantyCount subset of [expiredWarrantyCount] that were
     *   PENDING_REVIEW (now "expired unconfirmed": status=EXPIRED, needsReview=true).
     */
    data class ExpiryReconciliationResult(
        val expiredWarrantyCount: Int,
        val expiredReturnWindowCount: Int,
        val expiredPendingWarrantyCount: Int = 0
    )

    /** ai-fixes INV-13: outcome of [confirmWarranty]. */
    sealed class ConfirmWarrantyResult {
        /** Review cleared; [status] is EXPIRED when the row was already past its end date, else ACTIVE. */
        data class Confirmed(val warrantyId: Long, val status: WarrantyStatus) : ConfirmWarrantyResult()

        /** Row exists but is not in review (idempotent no-op, no write, no event). */
        object AlreadyConfirmed : ConfirmWarrantyResult()

        /** No row with that id (no write, no event). */
        object NotFound : ConfirmWarrantyResult()
    }

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
