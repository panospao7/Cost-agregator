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
            database = mockk(relaxed = true),
            warrantyDao = warrantyDao,
            returnWindowDao = returnWindowDao,
            receiptRepository = object : Lazy<ReceiptRepository> {
                override fun get() = receiptRepository
            },
            cloudExtractionService = cloudExtractionService,
            aiSettingsRepository = aiSettingsRepository,
            aiPolicy = aiPolicy,
            aiCapabilityRouter = aiCapabilityRouter,
            timeProvider = timeProvider
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
                },
                false
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

        coEvery { cloudExtractionService.extractWarranty(any(), false) } returns extractionResult

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
        coVerify(exactly = 0) { cloudExtractionService.extractWarranty(any(), any()) }
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

        coEvery { cloudExtractionService.extractWarranty(any(), false) } returns extractionResult

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
}