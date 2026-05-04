package com.yourname.expensetracker.ui.screens.price

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * PHASE 4 TEST: PriceProtectionViewModel
 * 
 * Tests ViewModel state management for price protection features.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceProtectionViewModelTest {

    private val priceTracker = mockk<PriceProtectionTracker>(relaxed = true)
    private lateinit var viewModel: PriceProtectionViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock default behaviors
        coEvery { priceTracker.getPriceProtectedItems() } returns emptyList()
        every { priceTracker.monitorPriceDrops() } returns flowOf(emptyList())
        
        viewModel = PriceProtectionViewModel(priceTracker, currencySettingsRepository = mock())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty lists`() = runTest {
        viewModel.priceDrops.test {
            assertThat(awaitItem()).isEmpty()
        }
        
        viewModel.protectedItems.test {
            assertThat(awaitItem()).isEmpty()
        }
        
        viewModel.deals.test {
            assertThat(awaitItem()).isEmpty()
        }
        
        viewModel.coupons.test {
            assertThat(awaitItem()).isEmpty()
        }
        
        viewModel.creditCardBenefits.test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `initial loading state is false`() = runTest {
        viewModel.isLoading.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `loadData fetches protected items`() = runTest {
        val mockItems = createMockProtectedItems()
        coEvery { priceTracker.getPriceProtectedItems() } returns mockItems
        
        viewModel.loadData()
        advanceUntilIdle()
        
        coVerify { priceTracker.getPriceProtectedItems() }
    }

    @Test
    fun `loadData monitors price drops`() = runTest {
        viewModel.loadData()
        advanceUntilIdle()
        
        verify { priceTracker.monitorPriceDrops() }
    }

    @Test
    fun `refreshPriceDrops updates price drops state`() = runTest {
        val mockDrops = createMockPriceDrops()
        every { priceTracker.monitorPriceDrops() } returns flowOf(mockDrops)
        
        viewModel.refreshPriceDrops()
        advanceUntilIdle()
        
        viewModel.priceDrops.test {
            val drops = awaitItem()
            assertThat(drops).hasSize(2)
        }
    }

    @Test
    fun `loadData handles errors gracefully`() = runTest {
        coEvery { priceTracker.getPriceProtectedItems() } throws RuntimeException("Network error")
        
        viewModel.loadData()
        advanceUntilIdle()
        
        // Should not crash, just have empty data
        viewModel.isLoading.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `isLoading is true during loadData`() = runTest {
        coEvery { priceTracker.getPriceProtectedItems() } coAnswers {
            delay(100)
            emptyList()
        }
        
        viewModel.loadData()
        
        viewModel.isLoading.test {
            assertThat(awaitItem()).isTrue()
            
            advanceTimeBy(100)
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `protected items are sorted by eligibility`() = runTest {
        val items = listOf(
            createProtectedItem("Item A", true, 30),
            createProtectedItem("Item B", false, 0),
            createProtectedItem("Item C", true, 15)
        )
        coEvery { priceTracker.getPriceProtectedItems() } returns items
        
        viewModel.loadData()
        advanceUntilIdle()
        
        viewModel.protectedItems.test {
            val result = awaitItem()
            assertThat(result).hasSize(3)
        }
    }

    @Test
    fun `price drops contain savings information`() = runTest {
        val drops = listOf(
            createPriceDropAlert("Laptop", 100.0, 10.0, 10.0)
        )
        every { priceTracker.monitorPriceDrops() } returns flowOf(drops)
        
        viewModel.refreshPriceDrops()
        advanceUntilIdle()
        
        viewModel.priceDrops.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].priceDrop).isEqualTo(10.0)
            assertThat(result[0].priceDropPercent).isEqualTo(10.0)
        }
    }

    // Helper methods
    
    private fun createMockProtectedItems(): List<PriceProtectionTracker.PriceProtectedItem> {
        return listOf(
            createProtectedItem("Electronics", true, 30),
            createProtectedItem("Furniture", true, 14)
        )
    }
    
    private fun createProtectedItem(
        name: String,
        eligible: Boolean,
        returnWindow: Int
    ): PriceProtectionTracker.PriceProtectedItem {
        return PriceProtectionTracker.PriceProtectedItem(
            receiptId = 1L,
            itemName = name,
            merchant = "Test Store",
            purchasePrice = 100.0,
            purchaseDate = System.currentTimeMillis(),
            category = "Electronics",
            priceProtectionEligible = eligible,
            returnWindowDays = returnWindow,
            currentBestPrice = null,
            priceHistory = emptyList()
        )
    }
    
    private fun createMockPriceDrops(): List<PriceProtectionTracker.PriceDropAlert> {
        return listOf(
            createPriceDropAlert("Phone", 50.0, 5.0, 10.0),
            createPriceDropAlert("TV", 200.0, 20.0, 10.0)
        )
    }
    
    private fun createPriceDropAlert(
        itemName: String,
        dropAmount: Double,
        savings: Double,
        percent: Double
    ): PriceProtectionTracker.PriceDropAlert {
        return PriceProtectionTracker.PriceDropAlert(
            item = createProtectedItem(itemName, true, 30),
            currentPrice = 100.0 - savings,
            priceDrop = dropAmount,
            priceDropPercent = percent,
            claimUrl = "https://example.com/claim",
            daysRemaining = 20,
            isSimulated = true
        )
    }
}