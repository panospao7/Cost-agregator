package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.ConfirmWarrantyResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import dagger.Lazy
import io.mockk.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WarrantyTrackerRepositoryTest {

    private lateinit var repository: WarrantyTrackerRepository
    private val database: AppDatabase = mockk(relaxed = true)
    private val currencySettingsRepository: CurrencySettingsRepository = mockk(relaxed = true)
    private val warrantyDao: WarrantyDao = mockk()
    private val returnWindowDao: ReturnWindowDao = mockk()
    private val warrantyLifecycleEventDao: WarrantyLifecycleEventDao = mockk(relaxed = true)
    private val receiptRepository: ReceiptRepository = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_700_000_000_000L)

    @Before
    fun setup() {
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2), so a
        // pass-through stub only intercepts with mockkStatic — without it the
        // real Room body runs against the relaxed AppDatabase mock and suspends
        // forever (measured: markWarrantyAsClaimed et al. UncompletedCoroutines
        // 1m timeouts; docs/testing/test-sweep-outcomes-2026-09-18.md §Hangs).
        // Recorded args are positional with the RECEIVER as arg 0 and the block
        // as arg 1, so the block is secondArg.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        repository = WarrantyTrackerRepository(
            database = database,
            warrantyDao = warrantyDao,
            returnWindowDao = returnWindowDao,
            warrantyLifecycleEventDao = warrantyLifecycleEventDao,
            receiptRepository = object : Lazy<ReceiptRepository> {
                override fun get() = receiptRepository
            },
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            receiptLifecycleEventWriter = mockk(relaxed = true),
            transactionRunner = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // NOTE: getActiveWarranties() wraps warrantyDao.getActiveWarranties in an internal
    // unbounded ticker flow (WarrantyTrackerRepository.activeItemsTickerFlow:
    // while(true) { emit; delay(1h) }). It is private and the class is final, so it
    // cannot be stubbed. Collecting it unbounded never terminates: runTest advances
    // virtual time through delay(1h) forever, re-invoking the mocked DAO on every
    // virtual tick — a measured OOM/hang (vr-20260918-082037-8ec8f982). Always bound
    // the collection (take(1)) and keep runTest timeouts fail-fast.

    @Test(timeout = 60_000L)
    fun `getActiveWarranties returns flow from dao`() = runTest(timeout = 60.seconds) {
        val warranties = listOf(
            Warranty(id = 1, receiptId = 1, productName = "Laptop", merchantName = "Amazon", 
                purchaseDate = 1000, warrantyDurationMonths = 12, warrantyEndDate = 2000)
        )
        every { warrantyDao.getActiveWarranties(any()) } returns flowOf(warranties)

        val result = repository.getActiveWarranties()
        
        // take(1): terminate after the first emission instead of subscribing to the
        // repository's internal 1-hour ticker forever (infinite under virtual time).
        result.take(1).collect { 
            assertEquals(1, it.size)
            assertEquals("Laptop", it[0].productName)
        }
    }

    // ai-fixes B1a (G2-6 retarget of `extractWarrantyFromReceipt delegates to cloud service`):
    // the cloud path is retired, so the return window is derived from merchant defaults only.
    // Two sibling retired tests were retargeted into AutoCreateWarrantyFromReceiptUseCaseTest:
    //   - calendar month end date  -> AE-24 (production end-date path)
    //   - route-not-cloud skip     -> AE-11 (review draft without any cloud gate)
    @Test
    fun `upsertReturnWindowForReceipt uses merchant default and no AI even when rawOcrText contains a return policy`() = runTest(timeout = 60.seconds) {
        // AE-9 (INV-5, INV-6)
        val receipt = ScannedReceipt(
            id = 10,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Returns accepted within 60 days. Items must be unopened.",
            parsedTotal = 100.0,
            parsedMerchant = "Best Buy",
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        coEvery { receiptRepository.getReceiptById(10) } returns receipt
        coEvery { returnWindowDao.getReturnWindowByReceiptId(10) } returns null
        val inserted = slot<ReturnWindow>()
        coEvery { returnWindowDao.insertReturnWindow(capture(inserted)) } returns 5L

        val id = repository.upsertReturnWindowForReceipt(10)

        assertEquals(5L, id)
        // Merchant default for Best Buy (15), not the 60 days found in the receipt text.
        assertEquals(15, inserted.captured.returnDays)
        assertNull(inserted.captured.returnConditions)
        assertNull(inserted.captured.returnPolicyUrl)
        assertEquals("Purchase from Best Buy", inserted.captured.productName)
    }

    @Test
    fun `getWarrantiesExpiringSoon calculates correct future time`() = runTest(timeout = 60.seconds) {
        val days = 7
        val currentStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(timeProvider.now())
        val futureExclusive = com.yourname.expensetracker.domain.util.TimePeriodUtils.addDays(currentStart, days)

        val warranties = listOf(
            Warranty(id = 1, receiptId = 1, productName = "Phone", merchantName = "Samsung",
            purchaseDate = 1000, warrantyDurationMonths = 24, warrantyEndDate = futureExclusive - 1000)
        )

        coEvery {
            warrantyDao.getWarrantiesExpiringSoon(
                futureTime = futureExclusive,
                currentTime = currentStart
            )
        } returns warranties

        val result = repository.getWarrantiesExpiringSoon(days)

        assertEquals(1, result.size)
        assertEquals("Phone", result[0].productName)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `markWarrantyAsClaimed updates status`() = runTest(timeout = 60.seconds) {
        coEvery { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any(), any()) } just Runs
        
        repository.markWarrantyAsClaimed(1)
        
        coVerify { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any(), any()) }
    }

    @Test
    fun `extractReturnWindow uses default 30 days when no merchant match`() = runTest(timeout = 60.seconds) {
        val receipt = ScannedReceipt(
            id = 1,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt",
            parsedTotal = 50.0,
            parsedMerchant = "Unknown Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f
        )
        
        val result = repository.extractReturnWindow(receipt, null)
        
        assertNotNull(result)
        assertEquals(30, result?.returnDays)
        assertEquals("Purchase from Unknown Store", result?.productName)
    }

    @Test
    fun `extractReturnWindow uses Amazon 30 day policy`() = runTest(timeout = 60.seconds) {
        val receipt = ScannedReceipt(
            id = 1,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt",
            parsedTotal = 100.0,
            parsedMerchant = "Amazon",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f
        )
        
        val result = repository.extractReturnWindow(receipt, null)
        
        assertNotNull(result)
        assertEquals(30, result?.returnDays)
    }

    @Test
    fun `upsertReturnWindowForReceipt recomputes returnDays on a legacy row and preserves conditions and status`() = runTest(timeout = 60.seconds) {
        // AE-23 (INV-6, legacy): retarget of `extractReturnWindow persists extracted return metadata`.
        val receipt = ScannedReceipt(
            id = 13,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt",
            parsedTotal = 100.0,
            parsedMerchant = "Policy Store",
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f
        )
        val legacy = ReturnWindow(
            id = 77L,
            receiptId = 13L,
            productName = "Sneakers",
            merchantName = "Policy Store",
            purchaseDate = 1_700_000_000_000L,
            returnDays = 45,
            returnDeadline = 1_700_000_000_000L + 45L * 86_400_000L,
            returnConditions = "Tags attached",
            status = ReturnStatus.RETURNED,
            returnedAt = 1_700_100_000_000L,
            refundAmount = 99.5,
            createdAt = 1_690_000_000_000L,
            updatedAt = 1_690_000_000_000L
        )
        coEvery { receiptRepository.getReceiptById(13) } returns receipt
        coEvery { returnWindowDao.getReturnWindowByReceiptId(13) } returns legacy
        val updated = slot<ReturnWindow>()
        coEvery { returnWindowDao.updateReturnWindow(capture(updated)) } just Runs

        val id = repository.upsertReturnWindowForReceipt(13)

        assertEquals(77L, id)
        val saved = updated.captured
        // returnDays is the merchant default (30), and the deadline is recomputed from it.
        assertEquals(30, saved.returnDays)
        assertNotEquals(legacy.returnDeadline, saved.returnDeadline)
        // User-visible state is preserved.
        assertEquals("Tags attached", saved.returnConditions)
        assertEquals(ReturnStatus.RETURNED, saved.status)
        assertEquals(legacy.returnedAt, saved.returnedAt)
        assertEquals(legacy.refundAmount, saved.refundAmount)
        assertEquals(legacy.createdAt, saved.createdAt)
    }

    @Test
    fun `upsertReturnWindowForReceipt with no warranty creates a merchant default window named after the merchant`() = runTest(timeout = 60.seconds) {
        // Retarget of `processReceiptForWarranty creates return window for return policy only extraction`.
        val receipt = ScannedReceipt(
            id = 5,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Returns Store",
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        coEvery { receiptRepository.getReceiptById(5) } returns receipt
        coEvery { returnWindowDao.getReturnWindowByReceiptId(5) } returns null
        val inserted = slot<ReturnWindow>()
        coEvery { returnWindowDao.insertReturnWindow(capture(inserted)) } returns 6L

        val id = repository.upsertReturnWindowForReceipt(5, fallbackWarranty = null)

        assertEquals(6L, id)
        assertEquals(30, inserted.captured.returnDays)
        assertEquals("Purchase from Returns Store", inserted.captured.productName)
        assertNull(inserted.captured.returnConditions)
    }

    @Test
    fun `reconcileExpiredItems marks active records as expired`() = runTest(timeout = 60.seconds) {
        coEvery { warrantyDao.getExpirablePendingWarrantyCount(any()) } returns 0
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 2
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 3

        val result = repository.reconcileExpiredItems(1_700_000_000_000L)

        assertEquals(2, result.expiredWarrantyCount)
        assertEquals(3, result.expiredReturnWindowCount)
        assertEquals(0, result.expiredPendingWarrantyCount)
        coVerify { warrantyDao.markExpiredWarranties(1_700_000_000_000L, 1_700_000_000_000L) }
        coVerify { returnWindowDao.markExpiredReturnWindows(1_700_000_000_000L, 1_700_000_000_000L) }
    }

    @Test
    fun `reconcileExpiredItems reports pending subset and writes a counts-only batch event`() = runTest(timeout = 60.seconds) {
        // AE-14 (INV-11): W1 pending + W2 active expired -> total 2, pending 1.
        coEvery { warrantyDao.getExpirablePendingWarrantyCount(any()) } returns 1
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 2
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 0

        val result = repository.reconcileExpiredItems(1_700_000_000_000L)

        assertEquals(2, result.expiredWarrantyCount)
        assertEquals(1, result.expiredPendingWarrantyCount)
        coVerify(exactly = 1) {
            warrantyLifecycleEventDao.insert(match {
                it.eventType == WarrantyLifecycleEventTypes.EXPIRED &&
                    it.warrantyId == -1L &&
                    it.description == "Batch expired: 2 warranties (1 unconfirmed), 0 return windows"
            })
        }
        // The count is read before the sweep within the same transaction.
        coVerifyOrder {
            warrantyDao.getExpirablePendingWarrantyCount(1_700_000_000_000L)
            warrantyDao.markExpiredWarranties(1_700_000_000_000L, 1_700_000_000_000L)
        }
    }

    @Test
    fun `reconcileExpiredItems second run is idempotent and writes no event`() = runTest(timeout = 60.seconds) {
        // AE-14 (second run) / AE-15 (future pending row is not expired by the sweep).
        coEvery { warrantyDao.getExpirablePendingWarrantyCount(any()) } returns 0
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 0
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 0

        val result = repository.reconcileExpiredItems(1_700_000_000_000L)

        assertEquals(0, result.expiredWarrantyCount)
        assertEquals(0, result.expiredPendingWarrantyCount)
        coVerify(exactly = 0) { warrantyLifecycleEventDao.insert(any()) }
    }

    @Test
    fun `reconcileExpiredItems checks the write barrier before touching any dao`() = runTest(timeout = 60.seconds) {
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val op = "WarrantyTrackerRepository.reconcileExpiredItems"
        every { barrier.checkWritesAllowed(op) } throws blockedException(op)
        val blockedRepository = repositoryWith(barrier, mockk(relaxed = true))

        val thrown = runCatching { blockedRepository.reconcileExpiredItems(1_700_000_000_000L) }

        assertTrue(thrown.exceptionOrNull() is DatabaseAccessBlockedException)
        coVerify(exactly = 0) { warrantyDao.markExpiredWarranties(any(), any()) }
        coVerify(exactly = 0) { returnWindowDao.markExpiredReturnWindows(any(), any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR1 — No-schema hardening tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `addWarrantyIgnoreConflicts sets createdAt and updatedAt when zero`() = runTest(timeout = 60.seconds) {
        val fixedNow = timeProvider.now()
        val warranty = Warranty(
            receiptId = 1,
            productName = "Test Product",
            merchantName = "Test Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 1L

        repository.addWarrantyIgnoreConflicts(warranty)

        coVerify {
            warrantyDao.insertWarrantyIgnore(match {
                it.createdAt == fixedNow && it.updatedAt == fixedNow
            })
        }
    }

    @Test
    fun `addWarrantyIgnoreConflicts preserves existing createdAt`() = runTest(timeout = 60.seconds) {
        val existingCreatedAt = 1_000_000L
        val existingUpdatedAt = 2_000_000L
        val warranty = Warranty(
            receiptId = 2,
            productName = "Existing Product",
            merchantName = "Existing Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 24,
            warrantyEndDate = 2000,
            createdAt = existingCreatedAt,
            updatedAt = existingUpdatedAt
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 2L

        repository.addWarrantyIgnoreConflicts(warranty)

        coVerify {
            warrantyDao.insertWarrantyIgnore(match {
                it.createdAt == existingCreatedAt && it.updatedAt == existingUpdatedAt
            })
        }
    }

    @Test
    fun `addWarrantyIgnoreConflicts writes created event after insert`() = runTest(timeout = 60.seconds) {
        val warranty = Warranty(
            receiptId = 3,
            productName = "Event Test",
            merchantName = "Event Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 3L

        repository.addWarrantyIgnoreConflicts(warranty)

        coVerify {
            warrantyLifecycleEventDao.insert(match {
                it.warrantyId == 3L && it.eventType == "CREATED"
            })
        }
    }

    @Test
    fun `manualPlaceholder uses documentType ManualPlaceholder`() = runTest(timeout = 60.seconds) {
        coEvery { receiptRepository.insertReceipt(any()) } returns 1L
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
            )

        val id = repository.createManualPlaceholderReceipt("Test Store", 1000L, "Test Product")

        assertEquals(1L, id)
        coVerify {
            receiptRepository.insertReceipt(match { receipt ->
                receipt.documentType == "MANUAL_PLACEHOLDER"
            })
        }
    }

    @Test
    fun `manualPlaceholder uses sourceType ManualRecord`() = runTest(timeout = 60.seconds) {
        coEvery { receiptRepository.insertReceipt(any()) } returns 1L
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
            )

        repository.createManualPlaceholderReceipt("Test Store", 1000L, "Test Product")

        coVerify {
            receiptRepository.insertReceipt(match { receipt ->
                receipt.sourceType == "MANUAL_RECORD"
            })
        }
    }

    @Test
    fun `manualPlaceholder sets createdAt and updatedAt`() = runTest(timeout = 60.seconds) {
        coEvery { receiptRepository.insertReceipt(any()) } returns 1L
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
            )
        val fixedNow = timeProvider.now()

        repository.createManualPlaceholderReceipt("Test Store", 1000L, "Test Product")

        coVerify {
            receiptRepository.insertReceipt(match { receipt ->
                receipt.createdAt == fixedNow && receipt.updatedAt == fixedNow
            })
        }
    }

    @Test
    fun `manualPlaceholder doesNotStoreProductNameInRawOcrText`() = runTest(timeout = 60.seconds) {
        coEvery { receiptRepository.insertReceipt(any()) } returns 1L
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
            )

        repository.createManualPlaceholderReceipt("Test Store", 1000L, "Super Secret Product")

        coVerify {
            receiptRepository.insertReceipt(match { receipt ->
                receipt.rawOcrText == "Manual warranty entry" &&
                    !receipt.rawOcrText.contains("Super Secret Product")
            })
        }
    }

    @Test
    fun `upsertReturnWindowForReceipt skips MANUAL_PLACEHOLDER receipts`() = runTest(timeout = 60.seconds) {
        val receipt = ScannedReceipt(
            id = 1,
            imagePath = null,
            rawOcrText = "Manual warranty entry",
            parsedTotal = null,
            parsedMerchant = "Test Store",
            parsedDate = 1000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 1f,
            documentType = "MANUAL_PLACEHOLDER"
        )
        coEvery { receiptRepository.getReceiptById(1) } returns receipt

        val result = repository.upsertReturnWindowForReceipt(1)

        assertNull(result)
        coVerify(exactly = 0) { returnWindowDao.getReturnWindowByReceiptId(any()) }
        coVerify(exactly = 0) { returnWindowDao.insertReturnWindow(any()) }
        coVerify(exactly = 0) { returnWindowDao.updateReturnWindow(any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR3 — Warranty lifecycle events
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateWarranty_writesUpdatedEvent`() = runTest(timeout = 60.seconds) {
        val testWarranty = Warranty(
            id = 1,
            receiptId = 1,
            productName = "Test Product",
            merchantName = "Test Store",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000
        )
        coEvery { warrantyDao.updateWarranty(any()) } just Runs

        repository.updateWarranty(testWarranty)

        coVerify {
            warrantyLifecycleEventDao.insert(match {
                it.eventType == "UPDATED" && it.warrantyId == testWarranty.id
            })
        }
    }

    @Test
    fun `deleteWarranty_writesDeletedEvent`() = runTest(timeout = 60.seconds) {
        val testWarranty = Warranty(
            id = 2,
            receiptId = 2,
            productName = "Test Product",
            merchantName = "Test Store",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000
        )
        coEvery { warrantyDao.deleteWarranty(any()) } just Runs

        repository.deleteWarranty(testWarranty)

        coVerify {
            warrantyLifecycleEventDao.insert(match {
                it.eventType == "DELETED" && it.warrantyId == testWarranty.id
            })
        }
    }

    @Test
    fun `reconcileExpiredItems_writesBatchExpiredEventWhenWarrantiesExpired`() = runTest(timeout = 60.seconds) {
        coEvery { warrantyDao.getExpirablePendingWarrantyCount(any()) } returns 0
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 2
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 1

        repository.reconcileExpiredItems(1_700_000_000_000L)

        coVerify {
            warrantyLifecycleEventDao.insert(match {
                it.eventType == "EXPIRED" && it.warrantyId == -1L
            })
        }
    }

    @Test
    fun `reconcileExpiredItems_writesNoEventWhenNothingExpired`() = runTest(timeout = 60.seconds) {
        coEvery { warrantyDao.getExpirablePendingWarrantyCount(any()) } returns 0
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 0
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 0

        repository.reconcileExpiredItems(1_700_000_000_000L)

        coVerify(exactly = 0) { warrantyLifecycleEventDao.insert(any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ai-fixes B1a — confirmWarranty (INV-13/INV-14) and reject of an expired-unconfirmed row
    // (The five PR4 `cloudWarrantyConfidence_*` band tests were deleted with the retired
    // cloud extraction path: G2-6.)
    // ──────────────────────────────────────────────────────────────────────────

    private val dayMs = 86_400_000L

    private fun reviewWarranty(
        id: Long,
        status: WarrantyStatus,
        needsReview: Boolean,
        endDate: Long,
        receiptId: Long? = id
    ) = Warranty(
        id = id,
        receiptId = receiptId,
        productName = "Synthetic Product",
        merchantName = "Synthetic Merchant",
        purchaseDate = 1_600_000_000_000L,
        warrantyDurationMonths = 12,
        warrantyEndDate = endDate,
        status = status,
        autoDetected = true,
        extractionSource = "ocr",
        needsReview = needsReview,
        createdAt = 1_600_000_000_000L,
        updatedAt = 1_600_000_000_000L
    )

    @Test
    fun `confirmWarranty clears review and activates an awaiting-review draft and writes a CONFIRMED event`() = runTest(timeout = 60.seconds) {
        // AE-17
        val now = timeProvider.now()
        val w3 = reviewWarranty(3L, WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now + 5 * dayMs)
        coEvery { warrantyDao.getWarrantyById(3L) } returns w3
        val updated = slot<Warranty>()
        coEvery { warrantyDao.updateWarranty(capture(updated)) } just Runs
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val confirmRepository = repositoryWith(barrier, mockk(relaxed = true))

        val result = confirmRepository.confirmWarranty(3L)

        assertEquals(ConfirmWarrantyResult.Confirmed(3L, WarrantyStatus.ACTIVE), result)
        assertEquals(WarrantyStatus.ACTIVE, updated.captured.status)
        assertFalse(updated.captured.needsReview)
        assertEquals(now, updated.captured.updatedAt)
        coVerify(exactly = 1) {
            warrantyLifecycleEventDao.insert(match {
                it.warrantyId == 3L && it.eventType == WarrantyLifecycleEventTypes.CONFIRMED
            })
        }
        // The write barrier is checked before any dao access.
        coVerifyOrder {
            barrier.checkWritesAllowed("WarrantyTrackerRepository.confirmWarranty")
            warrantyDao.getWarrantyById(3L)
        }
    }

    @Test
    fun `confirmWarranty keeps an expired-unconfirmed row EXPIRED and never reactivates it`() = runTest(timeout = 60.seconds) {
        // AE-18 (INV-13, INV-12)
        val now = timeProvider.now()
        val w1 = reviewWarranty(1L, WarrantyStatus.EXPIRED, needsReview = true, endDate = now - dayMs)
        coEvery { warrantyDao.getWarrantyById(1L) } returns w1
        val updated = slot<Warranty>()
        coEvery { warrantyDao.updateWarranty(capture(updated)) } just Runs

        val result = repository.confirmWarranty(1L)

        assertEquals(ConfirmWarrantyResult.Confirmed(1L, WarrantyStatus.EXPIRED), result)
        assertEquals(WarrantyStatus.EXPIRED, updated.captured.status)
        assertFalse(updated.captured.needsReview)
    }

    @Test
    fun `confirmWarranty on a pending row already past its end date confirms to EXPIRED not ACTIVE`() = runTest(timeout = 60.seconds) {
        // INV-13: past-end predicate is `warrantyEndDate < now` (reconcile not yet run).
        val now = timeProvider.now()
        val pendingPastEnd = reviewWarranty(4L, WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now - 1L)
        coEvery { warrantyDao.getWarrantyById(4L) } returns pendingPastEnd
        val updated = slot<Warranty>()
        coEvery { warrantyDao.updateWarranty(capture(updated)) } just Runs

        val result = repository.confirmWarranty(4L)

        assertEquals(ConfirmWarrantyResult.Confirmed(4L, WarrantyStatus.EXPIRED), result)
        assertEquals(WarrantyStatus.EXPIRED, updated.captured.status)
        assertFalse(updated.captured.needsReview)
    }

    @Test
    fun `confirmWarranty is an idempotent no-op for a confirmed or claimed row and NotFound for a missing id`() = runTest(timeout = 60.seconds) {
        // AE-19
        val now = timeProvider.now()
        val w2 = reviewWarranty(2L, WarrantyStatus.EXPIRED, needsReview = false, endDate = now - 2 * dayMs)
        val claimed = reviewWarranty(5L, WarrantyStatus.CLAIMED, needsReview = true, endDate = now + dayMs)
        coEvery { warrantyDao.getWarrantyById(2L) } returns w2
        coEvery { warrantyDao.getWarrantyById(5L) } returns claimed
        coEvery { warrantyDao.getWarrantyById(999L) } returns null

        assertEquals(ConfirmWarrantyResult.AlreadyConfirmed, repository.confirmWarranty(2L))
        assertEquals(ConfirmWarrantyResult.AlreadyConfirmed, repository.confirmWarranty(5L))
        assertEquals(ConfirmWarrantyResult.NotFound, repository.confirmWarranty(999L))

        coVerify(exactly = 0) { warrantyDao.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyLifecycleEventDao.insert(any()) }
    }

    @Test
    fun `confirmWarranty fails closed under maintenance mode and does not read or write`() = runTest(timeout = 60.seconds) {
        // AE-20
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val op = "WarrantyTrackerRepository.confirmWarranty"
        every { barrier.checkWritesAllowed(op) } throws blockedException(op)
        val blockedRepository = repositoryWith(barrier, mockk(relaxed = true))

        val thrown = runCatching { blockedRepository.confirmWarranty(3L) }

        assertTrue(thrown.exceptionOrNull() is DatabaseAccessBlockedException)
        coVerify(exactly = 0) { warrantyDao.getWarrantyById(any()) }
        coVerify(exactly = 0) { warrantyDao.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyLifecycleEventDao.insert(any()) }
    }

    @Test
    fun `promoteReviewDraft fails closed under maintenance mode and does not read write or invoke the edit`() = runTest(timeout = 60.seconds) {
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val op = "WarrantyTrackerRepository.promoteReviewDraft"
        every { barrier.checkWritesAllowed(op) } throws blockedException(op)
        val blockedRepository = repositoryWith(barrier, mockk(relaxed = true))
        var invocations = 0

        val thrown = runCatching {
            blockedRepository.promoteReviewDraft(3L) { fresh ->
                invocations++
                fresh
            }
        }

        assertTrue(thrown.exceptionOrNull() is DatabaseAccessBlockedException)
        assertEquals(0, invocations)
        coVerify(exactly = 0) { warrantyDao.getWarrantyById(any()) }
        coVerify(exactly = 0) { warrantyDao.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyLifecycleEventDao.insert(any()) }
    }

    @Test
    fun `confirmWarranty propagates a failed CONFIRMED event write instead of reporting success`()= runTest(timeout = 60.seconds) {
        // R29: fail closed inside the transaction.
        val now = timeProvider.now()
        coEvery { warrantyDao.getWarrantyById(3L) } returns
            reviewWarranty(3L, WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now + dayMs)
        coEvery { warrantyDao.updateWarranty(any()) } just Runs
        coEvery { warrantyLifecycleEventDao.insert(any()) } throws RuntimeException("Simulated DB failure")

        val thrown = runCatching { repository.confirmWarranty(3L) }

        assertEquals("Simulated DB failure", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `rejectAutoDetectedWarranty deletes an expired-unconfirmed warranty and its return window`() = runTest(timeout = 60.seconds) {
        // AE-21
        val now = timeProvider.now()
        val w1 = reviewWarranty(1L, WarrantyStatus.EXPIRED, needsReview = true, endDate = now - dayMs, receiptId = 31L)
        val window = ReturnWindow(
            id = 9L,
            receiptId = 31L,
            productName = "Synthetic Product",
            merchantName = "Synthetic Merchant",
            purchaseDate = 1_600_000_000_000L,
            returnDays = 30,
            returnDeadline = now - dayMs,
            createdAt = 1_600_000_000_000L,
            updatedAt = 1_600_000_000_000L
        )
        coEvery { returnWindowDao.getReturnWindowByReceiptId(31L) } returns window
        coEvery { returnWindowDao.deleteReturnWindow(any()) } just Runs
        coEvery { warrantyDao.deleteWarranty(any()) } just Runs

        repository.rejectAutoDetectedWarranty(w1)

        coVerify(exactly = 1) { returnWindowDao.deleteReturnWindow(window) }
        coVerify(exactly = 1) { warrantyDao.deleteWarranty(w1) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR3 — Lifecycle event failure resilience
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `addWarranty_lifecycleEventFailure_propagatesAndFailsTransaction`() = runTest(timeout = 60.seconds) {
        val testWarranty = Warranty(
            receiptId = 4,
            productName = "Test Product",
            merchantName = "Test Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarranty(any()) } returns 4L
        coEvery { warrantyLifecycleEventDao.insert(any()) } throws RuntimeException("Simulated DB failure")

        // R29: fail closed. An in-transaction event failure must not be swallowed
        // (Android nested-transaction semantics would silently roll back the warranty).
        val thrown = runCatching { repository.addWarranty(testWarranty) }

        coVerify { warrantyDao.insertWarranty(any()) }
        assertEquals(
            "Event write failure must propagate",
            "Simulated DB failure",
            thrown.exceptionOrNull()?.message
        )
    }

    @Test
    fun `lifecycleEventFailure_doesNotFailPrimaryTransaction`() = runTest(timeout = 60.seconds) {
        val testWarranty = Warranty(
            id = 3,
            receiptId = 3,
            productName = "Test Product",
            merchantName = "Test Store",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000
        )
        coEvery { warrantyDao.updateWarranty(any()) } just Runs
        coEvery { warrantyLifecycleEventDao.insert(any()) } throws RuntimeException("Simulated DB failure")

        repository.updateWarranty(testWarranty)

        coVerify { warrantyDao.updateWarranty(testWarranty) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // G3 S5 — write-barrier handling for AID-9 audit (the discard-audit test was deleted with
    // the retired cloud extraction path, G2-6)
    // ──────────────────────────────────────────────────────────────────────────

    private fun repositoryWith(
        barrier: DatabaseWriteBarrier,
        runner: DomainTransactionRunner
    ) = WarrantyTrackerRepository(
        database = database,
        warrantyDao = warrantyDao,
        returnWindowDao = returnWindowDao,
        warrantyLifecycleEventDao = warrantyLifecycleEventDao,
        receiptRepository = object : Lazy<ReceiptRepository> {
            override fun get() = receiptRepository
        },
        timeProvider = timeProvider,
        currencyConverter = mockk(relaxed = true),
        currencySettingsRepository = currencySettingsRepository,
        writeBarrier = barrier,
        receiptLifecycleEventWriter = mockk(relaxed = true),
        transactionRunner = runner
    )

    private fun blockedException(operation: String) = DatabaseAccessBlockedException(
        accessType = DatabaseAccessType.WRITE,
        operation = DatabaseAccessOperation(operation),
        mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
    )

    @Test
    fun `addWarrantyIgnoreConflicts propagates blocked AID-9 audit write`() = runTest(timeout = 60.seconds) {
        val warranty = Warranty(
            receiptId = 22,
            productName = "Synthetic Product",
            merchantName = "Synthetic Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            autoDetected = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 22L
        val runner = mockk<DomainTransactionRunner>()
        coEvery {
            runner.runInTransaction<Any?>(any(), any(), any(), any(), any(), any())
        } throws blockedException("warranty.ai_warranty_created")
        val blockedRepository = repositoryWith(mockk(relaxed = true), runner)

        val thrown = runCatching { blockedRepository.addWarrantyIgnoreConflicts(warranty) }

        assertTrue(
            "Blocked audit write must propagate instead of returning an id",
            thrown.exceptionOrNull() is DatabaseAccessBlockedException
        )
    }

    @Test
    fun `addWarrantyIgnoreConflicts propagates AID-9 audit failure for other reasons`() = runTest(timeout = 60.seconds) {
        val warranty = Warranty(
            receiptId = 23,
            productName = "Synthetic Product",
            merchantName = "Synthetic Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            autoDetected = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 23L
        val runner = mockk<DomainTransactionRunner>()
        coEvery {
            runner.runInTransaction<Any?>(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("Simulated audit failure")
        val failClosedRepository = repositoryWith(mockk(relaxed = true), runner)

        // R29: fail closed. The audit failure must surface (and roll back the insert),
        // not return the id of an unsaved warranty.
        val thrown = runCatching { failClosedRepository.addWarrantyIgnoreConflicts(warranty) }

        assertEquals(
            "AID-9 audit failure must propagate instead of returning an id",
            "Simulated audit failure",
            thrown.exceptionOrNull()?.message
        )
    }

    @Test
    fun `addWarrantyIgnoreConflicts propagates CREATED event failure`() = runTest(timeout = 60.seconds) {
        val warranty = Warranty(
            receiptId = 24,
            productName = "Synthetic Product",
            merchantName = "Synthetic Merchant",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { warrantyDao.insertWarrantyIgnore(any()) } returns 24L
        coEvery { warrantyLifecycleEventDao.insert(any()) } throws RuntimeException("Simulated DB failure")

        val thrown = runCatching { repository.addWarrantyIgnoreConflicts(warranty) }

        assertEquals("Simulated DB failure", thrown.exceptionOrNull()?.message)
    }
}
