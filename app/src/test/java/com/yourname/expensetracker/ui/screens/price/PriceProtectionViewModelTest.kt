package com.yourname.expensetracker.ui.screens.price

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import android.content.Context
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
        
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty lists and is not loading`()= runTest {
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

        viewModel.isLoading.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `loadData fetches protected items and exposes them in tracker order`() = runTest {
        // Row: (label, items supplied by the tracker, exact expected names in supplied order).
        val rows = listOf(
            Triple(
                "two eligible items",
                listOf(
                    createProtectedItem("Electronics", true, 30),
                    createProtectedItem("Furniture", true, 14)
                ),
                listOf("Electronics", "Furniture")
            ),
            Triple(
                "mixed eligibility, unsorted input",
                listOf(
                    createProtectedItem("Item A", true, 30),
                    createProtectedItem("Item B", false, 0),
                    createProtectedItem("Item C", true, 15)
                ),
                listOf("Item A", "Item B", "Item C")
            )
        )
        assertThat(rows).hasSize(2) // size guard: do not silently lose a row

        for ((label, items, expectedNames) in rows) {
            coEvery { priceTracker.getPriceProtectedItems() } returns items
            viewModel = createViewModel()

            viewModel.loadData()
            advanceUntilIdle()

            coVerify { priceTracker.getPriceProtectedItems() }
            assertWithMessage("row '$label'")
                .that(viewModel.protectedItems.value.map { it.itemName })
                .containsExactlyElementsIn(expectedNames)
                .inOrder()
        }
    }

    @Test
    fun `loadData monitors price drops`() = runTest {
        // Subscribe to priceDrops first so the stateIn upstream is active
        viewModel.priceDrops.test {
            viewModel.loadData()
            advanceUntilIdle()
            
            coVerify { priceTracker.monitorPriceDrops() }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `refreshPriceDrops publishes tracker drops with exact savings fields`() = runTest {
        val rows = listOf(
            DropRow(
                label = "two drops",
                drops = listOf(
                    createPriceDropAlert("Phone", 50.0, 5.0, 10.0),
                    createPriceDropAlert("TV", 200.0, 20.0, 10.0)
                ),
                expectedItemNames = listOf("Phone", "TV"),
                expectedPriceDrops = listOf(50.0, 200.0),
                expectedPercents = listOf(10.0, 10.0)
            ),
            DropRow(
                label = "single drop",
                drops = listOf(createPriceDropAlert("Laptop", dropAmount = 10.0, savings = 10.0, percent = 10.0)),
                expectedItemNames = listOf("Laptop"),
                expectedPriceDrops = listOf(10.0),
                expectedPercents = listOf(10.0)
            )
        )
        assertThat(rows).hasSize(2) // size guard: do not silently lose a row

        for (row in rows) {
            every { priceTracker.monitorPriceDrops() } returns flowOf(row.drops)
            viewModel = createViewModel()

            viewModel.priceDrops.test {
                // Skip initial empty emission
                assertWithMessage("row '${row.label}' initial").that(awaitItem()).isEmpty()

                viewModel.refreshPriceDrops()
                advanceUntilIdle()

                val result = awaitItem()
                assertWithMessage("row '${row.label}' names").that(result.map { it.item.itemName })
                    .containsExactlyElementsIn(row.expectedItemNames).inOrder()
                assertWithMessage("row '${row.label}' priceDrop").that(result.map { it.priceDrop })
                    .containsExactlyElementsIn(row.expectedPriceDrops).inOrder()
                assertWithMessage("row '${row.label}' percent").that(result.map { it.priceDropPercent })
                    .containsExactlyElementsIn(row.expectedPercents).inOrder()
                cancelAndConsumeRemainingEvents()
            }
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
        
        viewModel.isLoading.test {
            // Skip initial false
            val initial = awaitItem()
            assertThat(initial).isFalse()
            
            viewModel.loadData()
            
            val loading = awaitItem()
            assertThat(loading).isTrue()
            
            advanceTimeBy(100)
            val done = awaitItem()
            assertThat(done).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    // Helper methods

    private fun createViewModel(): PriceProtectionViewModel {
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return PriceProtectionViewModel(priceTracker, currencySettingsRepository = currencyRepo, context = mockk<Context>(relaxed = true))
    }

    private data class DropRow(
        val label: String,
        val drops: List<PriceProtectionTracker.PriceDropAlert>,
        val expectedItemNames: List<String>,
        val expectedPriceDrops: List<Double>,
        val expectedPercents: List<Double>
    )

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