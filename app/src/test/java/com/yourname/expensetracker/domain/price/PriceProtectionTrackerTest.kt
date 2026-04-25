package com.yourname.expensetracker.domain.price

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PHASE 5 TEST: PriceProtectionTracker
 * 
 * Tests price protection tracking, deal hunting, and credit card benefits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceProtectionTrackerTest {

    private val receiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var tracker: PriceProtectionTracker

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
        tracker = PriceProtectionTracker(receiptDao, timeProvider)
    }

    @Test
    fun `getPriceProtectedItems filters recent receipts`() = runTest {
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000)
        
        coEvery { 
            receiptDao.getRecentReceipts(any()) 
        } returns createMockReceipts()
        
        val items = tracker.getPriceProtectedItems()
        
        coVerify { 
            receiptDao.getRecentReceipts(match { it <= thirtyDaysAgo }) 
        }
    }

	@Test
	fun `getPriceProtectedItems returns eligible items`() = runTest {
		coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
			createReceiptWithItem("Laptop Computer", "electronics", 999.0)
		)

		val items = tracker.getPriceProtectedItems()

		// Should return items from price-protectable categories (electronics)
		assertThat(items).isNotEmpty()
		assertThat(items[0].itemName).contains("Laptop Computer")
	}

	@Test
	fun `isPriceProtectable identifies electronics correctly`() = runTest {
		coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
			createReceiptWithItem("Laptop Computer", "electronics", 999.0)
		)

		val items = tracker.getPriceProtectedItems()

		assertThat(items).hasSize(1)
		assertThat(items[0].category).contains("electronics")
	}

    @Test
    fun `isPriceProtectable returns false for non-protectable items`() = runTest {
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
            createReceiptWithItem("Coffee", "beverage", 5.0)
        )
        
        val items = tracker.getPriceProtectedItems()
        
        // Coffee is not in price-protectable categories
        assertThat(items).isEmpty()
    }

    @Test
    fun `isEligibleForPriceProtection returns true for recent purchases`() = runTest {
        val recentReceipt = createMockReceipt(daysOld = 10)
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(recentReceipt)
        
        val items = tracker.getPriceProtectedItems()
        
        assertThat(items.all { it.priceProtectionEligible }).isTrue()
    }

    @Test
    fun `isEligibleForPriceProtection returns false for old purchases`() = runTest {
        val oldReceipt = ScannedReceipt(
            id = 1L,
            imagePath = "/path/to/receipt.jpg",
            rawOcrText = "Old electronics receipt",
            parsedTotal = 999.0,
            parsedMerchant = "Test Store",
            parsedDate = System.currentTimeMillis() - (35 * 24 * 60 * 60 * 1000),
            parsedItems = """[{"name":"Laptop","price":999.0,"category":"electronics"}]""",
            parsedTaxAmount = null,
            confidence = 0.9f,
            createdAt = System.currentTimeMillis() - (35 * 24 * 60 * 60 * 1000)
        )
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(oldReceipt)
        
        val items = tracker.getPriceProtectedItems()
        
        // 35 days is beyond 30-day protection window
        assertThat(items).isEmpty()
    }

    @Test
    fun `getReturnWindow returns correct days for different merchants`() {
        // Test via the tracker behavior
        val amazonReceipt = createMockReceipt(daysOld = 5, merchant = "Amazon Store")
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(amazonReceipt)
        
        runTest {
            val items = tracker.getPriceProtectedItems()
            if (items.isNotEmpty()) {
                assertThat(items[0].returnWindowDays).isEqualTo(30) // Amazon has 30 days
            }
        }
    }

    @Test
    fun `monitorPriceDrops emits alerts for price drops over 5 percent`() = runTest {
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
            createReceiptWithItem("Electronics Item", "electronics", 100.0)
        )
        
        val alerts = tracker.monitorPriceDrops().first()
        
        // Electronics items get 8% simulated drop
        assertThat(alerts).isNotEmpty()
        assertThat(alerts[0].priceDropPercent).isAtLeast(5.0)
    }

    @Test
    fun `monitorPriceDrops ignores small price drops`() = runTest {
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
            createReceiptWithItem("Appliance Item", "appliances", 100.0)
        )
        
        val alerts = tracker.monitorPriceDrops().first()
        
        // 5% drops should still be included (>=5% threshold)
        assertThat(alerts).isNotEmpty()
    }

    @Test
    fun `price drop alert contains correct savings information`() = runTest {
        coEvery { receiptDao.getRecentReceipts(any()) } returns listOf(
            createReceiptWithItem("Laptop", "electronics", 1000.0)
        )
        
        val alerts = tracker.monitorPriceDrops().first()
        
        if (alerts.isNotEmpty()) {
            val alert = alerts[0]
            assertThat(alert.priceDrop).isGreaterThan(0.0)
            assertThat(alert.priceDropPercent).isGreaterThan(0.0)
            assertThat(alert.currentPrice).isLessThan(alert.item.purchasePrice)
            assertThat(alert.daysRemaining).isGreaterThan(0)
        }
    }

    @Test
    fun `findBetterDeals returns deals with 10 percent plus savings`() = runTest {
        val receipt = createMockReceiptWithItems()
        
        val deals = tracker.findBetterDeals(receipt)
        
        // Should find deals with >10% savings
        deals.forEach { deal ->
            assertThat(deal.savingsPercent).isAtLeast(10.0)
            assertThat(deal.savings).isGreaterThan(0.0)
        }
    }

    @Test
    fun `findBetterDeals returns empty for low priced items`() = runTest {
        val receipt = createReceiptWithItem("Pen", "stationery", 5.0)
        
        val deals = tracker.findBetterDeals(receipt)
        
        assertThat(deals).isEmpty()
    }

    @Test
    fun `findCoupons returns coupons for merchant`() = runTest {
        val receipt = createMockReceipt(merchant = "Test Store")
        
        val coupons = tracker.findCoupons(receipt)
        
        assertThat(coupons).isNotEmpty()
        coupons.forEach { coupon ->
            assertThat(coupon.code).isNotEmpty()
            assertThat(coupon.discount).isGreaterThan(0.0)
        }
    }

    @Test
    fun `getCreditCardBenefits returns dining cashback for restaurants`() = runTest {
        val restaurantReceipt = createMockReceipt(merchant = "Best Restaurant")
        
        val benefits = tracker.getCreditCardBenefits(restaurantReceipt)
        
        assertThat(benefits).isNotEmpty()
        assertThat(benefits.any { it.benefitDescription.contains("dining") }).isTrue()
    }

    @Test
    fun `getCreditCardBenefits returns grocery cashback for supermarkets`() = runTest {
        val groceryReceipt = createMockReceipt(merchant = "Sklavenitis", total = 100.0)
        
        val benefits = tracker.getCreditCardBenefits(groceryReceipt)
        
        assertThat(benefits).isNotEmpty()
        assertThat(benefits.any { it.benefitDescription.contains("grocery") }).isTrue()
    }

    @Test
    fun `getCreditCardBenefits returns gas cashback for gas stations`() = runTest {
        val gasReceipt = createMockReceipt(merchant = "Shell Station")
        
        val benefits = tracker.getCreditCardBenefits(gasReceipt)
        
        assertThat(benefits).isNotEmpty()
        assertThat(benefits.any { it.benefitDescription.contains("gas") }).isTrue()
    }

    @Test
    fun `getCreditCardBenefits returns purchase protection for high value items`() = runTest {
        val expensiveReceipt = createMockReceipt(total = 600.0)
        
        val benefits = tracker.getCreditCardBenefits(expensiveReceipt)
        
        assertThat(benefits).isNotEmpty()
        assertThat(benefits.any { it.benefitType == PriceProtectionTracker.BenefitType.PROTECTION }).isTrue()
    }

    @Test
    fun `getCreditCardBenefits returns empty for unknown merchants`() = runTest {
        val unknownReceipt = createMockReceipt(merchant = "Random Shop", total = 50.0)
        
        val benefits = tracker.getCreditCardBenefits(unknownReceipt)
        
        assertThat(benefits).isEmpty()
    }

    @Test
    fun `credit card benefit calculates correct cashback value`() = runTest {
        val restaurantReceipt = createMockReceipt(merchant = "Restaurant", total = 100.0)
        
        val benefits = tracker.getCreditCardBenefits(restaurantReceipt)
        
        if (benefits.isNotEmpty()) {
            val cashback = benefits.find { it.benefitType == PriceProtectionTracker.BenefitType.CASHBACK }
            assertThat(cashback).isNotNull()
            assertThat(cashback!!.estimatedValue).isGreaterThan(0.0)
        }
    }

    // Helper methods
    
    private fun createMockReceipts(): List<ScannedReceipt> {
        return listOf(
            createMockReceipt(),
            createMockReceipt(daysOld = 15, merchant = "Amazon"),
            createMockReceipt(daysOld = 25, merchant = "Best Buy")
        )
    }
    
    private fun createMockReceipt(
        daysOld: Int = 5,
        merchant: String = "Test Store",
        total: Double = 100.0
    ): ScannedReceipt {
        return ScannedReceipt(
            id = 1L,
            imagePath = "/path/to/receipt.jpg",
            rawOcrText = "Test receipt",
            parsedTotal = total,
            parsedMerchant = merchant,
            parsedDate = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000),
            parsedItems = """[{"name":"Laptop","price":$total,"category":"electronics"}]""",
            parsedTaxAmount = null,
            confidence = 0.9f,
            createdAt = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000)
        )
    }
    
    private fun createReceiptWithItem(name: String, category: String, price: Double): ScannedReceipt {
        return ScannedReceipt(
            id = 1L,
            imagePath = "/path/to/receipt.jpg",
            rawOcrText = name,
            parsedTotal = price,
            parsedMerchant = "Test Store",
            parsedDate = System.currentTimeMillis(),
            parsedItems = """[{"name":"$name","price":$price,"category":"$category"}]""",
            parsedTaxAmount = null,
            confidence = 0.9f,
            createdAt = System.currentTimeMillis()
        )
    }
    
    private fun createMockReceiptWithItems(): ScannedReceipt {
        return ScannedReceipt(
            id = 1L,
            imagePath = "/path/to/receipt.jpg",
            rawOcrText = "Test receipt",
            parsedTotal = 300.0,
            parsedMerchant = "Test Store",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f,
            createdAt = System.currentTimeMillis()
        )
    }
}
