package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WarrantyTrackerRepositoryTest {

    private lateinit var repository: WarrantyTrackerRepository
    private val database: AppDatabase = mockk(relaxed = true)
    private val currencySettingsRepository: CurrencySettingsRepository = mockk(relaxed = true)
    private val warrantyDao: WarrantyDao = mockk()
    private val returnWindowDao: ReturnWindowDao = mockk()
    private val receiptRepository: ReceiptRepository = mockk(relaxed = true)
    private val cloudExtractionService: CloudWarrantyExtractionService = mockk()
    private val aiSettingsRepository: AiSettingsRepository = mockk()
    private val aiPolicy: AiPolicy = mockk()
    private val aiCapabilityRouter: AiCapabilityRouter = mockk()
    private val timeProvider = FakeTimeProvider(1_700_000_000_000L)
    private val settingsFlow = MutableStateFlow(AiSettings())

    @Before
    fun setup() {
        repository = WarrantyTrackerRepository(
            database = database,
            warrantyDao = warrantyDao,
            returnWindowDao = returnWindowDao,
            receiptRepository = object : Lazy<ReceiptRepository> {
                override fun get() = receiptRepository
            },
            cloudExtractionService = cloudExtractionService,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy,
            aiCapabilityRouter = aiCapabilityRouter,
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            receiptLifecycleEventWriter = mockk(relaxed = true)
        )

        every { aiSettingsRepository.settings() } returns settingsFlow
        coEvery {
            aiCapabilityRouter.decide(AiCapability.WARRANTY_EXTRACTION, any(), any())
        } returns AiRouteDecision(AiRoute.CLOUD, "test")
        every { aiPolicy.shouldRedact(any(), AiCapability.WARRANTY_EXTRACTION) } returns false
    }

    @Test
    fun `getActiveWarranties returns flow from dao`() = runTest {
        val warranties = listOf(
            Warranty(id = 1, receiptId = 1, productName = "Laptop", merchantName = "Amazon", 
                purchaseDate = 1000, warrantyDurationMonths = 12, warrantyEndDate = 2000)
        )
        every { warrantyDao.getActiveWarranties(any()) } returns flowOf(warranties)

        val result = repository.getActiveWarranties()
        
        result.collect { 
            assertEquals(1, it.size)
            assertEquals("Laptop", it[0].productName)
        }
    }

    @Test
    fun `extractWarrantyFromReceipt delegates to cloud service`() = runTest {
        val receipt = ScannedReceipt(
            id = 1,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "MacBook Pro",
            warrantyMonths = 12,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = "Original packaging required",
            confidence = 0.95f
        )

        coEvery {
            cloudExtractionService.extractWarranty(
                match {
                    it.receiptText == receipt.rawOcrText &&
                        it.merchant == receipt.parsedMerchant &&
                        it.totalAmount == receipt.parsedTotal &&
                        it.purchaseDate == receipt.parsedDate &&
                        it.currency == receipt.currency
                }
            )
        } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)
        
        assertNotNull(result)
        assertEquals("MacBook Pro", result?.productName)
        assertEquals(12, result?.warrantyDurationMonths)
        assertEquals("Original packaging required", result?.notes)
    }

    @Test
    fun `extractWarrantyFromReceipt uses calendar month addition for warranty end date`() = runTest {
        val purchaseDate = Instant.parse("2024-01-31T00:00:00Z").toEpochMilli()
        val receipt = ScannedReceipt(
            id = 3,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Calendar Store",
            parsedDate = purchaseDate,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Camera",
            warrantyMonths = 1,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.95f
        )

        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        val endDate = Instant.ofEpochMilli(result!!.warrantyEndDate).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(2024, endDate.year)
        assertEquals(2, endDate.monthValue)
        assertEquals(29, endDate.dayOfMonth)
    }

    @Test
    fun `extractWarrantyFromReceipt skips cloud extraction when route is not cloud`() = runTest {
        val receipt = ScannedReceipt(
            id = 2,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 50.0,
            parsedMerchant = "Test Store",
            parsedDate = 2000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )

        coEvery {
            aiCapabilityRouter.decide(AiCapability.WARRANTY_EXTRACTION, any(), any())
        } returns AiRouteDecision(AiRoute.ON_DEVICE, "on-device selected")

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNull(result)
        coVerify(exactly = 0) { cloudExtractionService.extractWarranty(any()) }
    }

    @Test
    fun `getWarrantiesExpiringSoon calculates correct future time`() = runTest {
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
    fun `markWarrantyAsClaimed updates status`() = runTest {
        coEvery { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any(), any()) } just Runs
        
        repository.markWarrantyAsClaimed(1)
        
        coVerify { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any(), any()) }
    }

    @Test
    fun `extractReturnWindow uses default 30 days when no merchant match`() = runTest {
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
    fun `extractReturnWindow uses Amazon 30 day policy`() = runTest {
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
    fun `extractReturnWindow persists extracted return metadata`() = runTest {
        val receipt = ScannedReceipt(
            id = 4,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt",
            parsedTotal = 100.0,
            parsedMerchant = "Policy Store",
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Sneakers",
            warrantyMonths = null,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = 45,
            returnConditions = "Tags attached",
            confidence = 0.8f
        )

        val result = repository.extractReturnWindow(receipt, null, extractionResult)

        assertNotNull(result)
        assertEquals(45, result?.returnDays)
        assertEquals("Tags attached", result?.returnConditions)
    }

    @Test
    fun `processReceiptForWarranty creates return window for return policy only extraction`() = runTest {
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
        val extractionResult = WarrantyExtractionResult(
            productName = "Jacket",
            warrantyMonths = null,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = 21,
            returnConditions = "Receipt required",
            confidence = 0.85f
        )

        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.processReceiptForWarranty(receipt)

        assertNull(result.first)
        assertNotNull(result.second)
        assertEquals(21, result.second?.returnDays)
        assertEquals("Receipt required", result.second?.returnConditions)
    }

    @Test
    fun `reconcileExpiredItems marks active records as expired`() = runTest {
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 2
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 3

        val result = repository.reconcileExpiredItems(1_700_000_000_000L)

        assertEquals(2, result.expiredWarrantyCount)
        assertEquals(3, result.expiredReturnWindowCount)
        coVerify { warrantyDao.markExpiredWarranties(1_700_000_000_000L, 1_700_000_000_000L) }
        coVerify { returnWindowDao.markExpiredReturnWindows(1_700_000_000_000L, 1_700_000_000_000L) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR1 — No-schema hardening tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `addWarrantyIgnoreConflicts sets createdAt and updatedAt when zero`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }
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
    fun `addWarrantyIgnoreConflicts preserves existing createdAt`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }
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
    fun `addWarrantyIgnoreConflicts writes created event after insert`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }
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
            database.warrantyLifecycleEventDao().insert(match {
                it.warrantyId == 3L && it.eventType == "CREATED"
            })
        }
    }

    @Test
    fun `manualPlaceholder uses documentType ManualPlaceholder`() = runTest {
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
    fun `manualPlaceholder uses sourceType ManualRecord`() = runTest {
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
    fun `manualPlaceholder sets createdAt and updatedAt`() = runTest {
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
    fun `manualPlaceholder doesNotStoreProductNameInRawOcrText`() = runTest {
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
    fun `upsertReturnWindowForReceipt skips MANUAL_PLACEHOLDER receipts`() = runTest {
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
    fun `updateWarranty_writesUpdatedEvent`() = runTest {
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
            database.warrantyLifecycleEventDao().insert(match {
                it.eventType == "UPDATED" && it.warrantyId == testWarranty.id
            })
        }
    }

    @Test
    fun `deleteWarranty_writesDeletedEvent`() = runTest {
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
            database.warrantyLifecycleEventDao().insert(match {
                it.eventType == "DELETED" && it.warrantyId == testWarranty.id
            })
        }
    }

    @Test
    fun `reconcileExpiredItems_writesBatchExpiredEventWhenWarrantiesExpired`() = runTest {
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 2
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 1

        repository.reconcileExpiredItems(1_700_000_000_000L)

        coVerify {
            database.warrantyLifecycleEventDao().insert(match {
                it.eventType == "EXPIRED" && it.warrantyId == -1L
            })
        }
    }

    @Test
    fun `reconcileExpiredItems_writesNoEventWhenNothingExpired`() = runTest {
        coEvery { warrantyDao.markExpiredWarranties(any(), any()) } returns 0
        coEvery { returnWindowDao.markExpiredReturnWindows(any(), any()) } returns 0

        repository.reconcileExpiredItems(1_700_000_000_000L)

        coVerify(exactly = 0) { database.warrantyLifecycleEventDao().insert(any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR4 — Three-band confidence threshold
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `cloudWarrantyConfidence_0_8_autoCreatesWithoutReview`() = runTest {
        val receipt = ScannedReceipt(
            id = 10,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "MacBook Pro",
            warrantyMonths = 12,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.8f
        )
        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNotNull(result)
        assertFalse(result!!.needsReview)
        assertEquals(WarrantyStatus.ACTIVE, result.status)
    }

    @Test
    fun `cloudWarrantyConfidence_0_4_createsNeedsReviewDraft`() = runTest {
        val receipt = ScannedReceipt(
            id = 11,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Tablet",
            warrantyMonths = 12,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.4f
        )
        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNotNull(result)
        assertTrue(result!!.needsReview)
        assertEquals(WarrantyStatus.PENDING_REVIEW, result.status)
    }

    @Test
    fun `cloudWarrantyConfidence_0_1_discardsWithDiagnostic`() = runTest {
        val receipt = ScannedReceipt(
            id = 12,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Phone",
            warrantyMonths = 12,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.1f
        )
        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNull(result)
    }

    @Test
    fun `cloudWarrantyConfidence_0_75_exactBoundary_autoAccepts`() = runTest {
        val receipt = ScannedReceipt(
            id = 13,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Laptop",
            warrantyMonths = 24,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.75f
        )
        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNotNull(result)
        assertFalse(result!!.needsReview)
        assertEquals(WarrantyStatus.ACTIVE, result.status)
    }

    @Test
    fun `cloudWarrantyConfidence_0_30_exactBoundary_createsReviewDraft`() = runTest {
        val receipt = ScannedReceipt(
            id = 14,
            imagePath = "/path/to/image.jpg",
            rawOcrText = "Receipt text",
            parsedTotal = 100.0,
            parsedMerchant = "Test Store",
            parsedDate = 1000,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val extractionResult = WarrantyExtractionResult(
            productName = "Headphones",
            warrantyMonths = 12,
            warrantyType = "MANUFACTURER",
            supportPhone = null,
            supportEmail = null,
            returnDays = null,
            returnConditions = null,
            confidence = 0.30f
        )
        coEvery { cloudExtractionService.extractWarranty(any()) } returns extractionResult

        val result = repository.extractWarrantyFromReceipt(receipt)

        assertNotNull(result)
        assertTrue(result!!.needsReview)
        assertEquals(WarrantyStatus.PENDING_REVIEW, result.status)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PR3 — Lifecycle event failure resilience
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `addWarranty_lifecycleEventFailure_doesNotFailPrimaryTransaction`() = runTest {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }
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
        every { database.warrantyLifecycleEventDao() } returns mockk {
            coEvery { insert(any()) } throws RuntimeException("Simulated DB failure")
        }

        val result = repository.addWarranty(testWarranty)

        coVerify { warrantyDao.insertWarranty(any()) }
        assertEquals(4L, result)
    }

    @Test
    fun `lifecycleEventFailure_doesNotFailPrimaryTransaction`() = runTest {
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
        every { database.warrantyLifecycleEventDao() } returns mockk {
            coEvery { insert(any()) } throws RuntimeException("Simulated DB failure")
        }

        repository.updateWarranty(testWarranty)

        coVerify { warrantyDao.updateWarranty(testWarranty) }
    }
}