package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WarrantyTrackerRepositoryTest {

    private lateinit var repository: WarrantyTrackerRepository
    private val warrantyDao: WarrantyDao = mockk()
    private val returnWindowDao: ReturnWindowDao = mockk()
    private val cloudExtractionService: CloudWarrantyExtractionService = mockk()
    private val timeProvider = FakeTimeProvider(1_700_000_000_000L)

    @Before
    fun setup() {
        repository = WarrantyTrackerRepository(
            warrantyDao,
            returnWindowDao,
            cloudExtractionService,
            timeProvider
        )
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
        val expectedWarranty = Warranty(
            id = 1,
            receiptId = 1,
            productName = "MacBook Pro",
            merchantName = "Apple",
            purchaseDate = 1000,
            warrantyDurationMonths = 12,
            warrantyEndDate = 2000
        )
        
        coEvery { cloudExtractionService.extractWarranty(receipt) } returns expectedWarranty

        val result = repository.extractWarrantyFromReceipt(receipt)
        
        assertNotNull(result)
        assertEquals("MacBook Pro", result?.productName)
    }

    @Test
    fun `getWarrantiesExpiringSoon calculates correct future time`() = runTest {
        val days = 7
        val currentTime = System.currentTimeMillis()
        val expectedFutureTime = currentTime + (days * 24 * 60 * 60 * 1000)
        
        val warranties = listOf(
            Warranty(id = 1, receiptId = 1, productName = "Phone", merchantName = "Samsung",
                purchaseDate = 1000, warrantyDurationMonths = 24, warrantyEndDate = expectedFutureTime - 1000)
        )
        
        coEvery { 
            warrantyDao.getWarrantiesExpiringSoon(match { it >= expectedFutureTime - 5000 }, any()) 
        } returns warranties

        val result = repository.getWarrantiesExpiringSoon(days)
        
        assertEquals(1, result.size)
        assertEquals("Phone", result[0].productName)
    }

    @Test
    fun `markWarrantyAsClaimed updates status`() = runTest {
        coEvery { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any()) } just Runs
        
        repository.markWarrantyAsClaimed(1)
        
        coVerify { warrantyDao.updateWarrantyStatus(1, WarrantyStatus.CLAIMED, any()) }
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
}
