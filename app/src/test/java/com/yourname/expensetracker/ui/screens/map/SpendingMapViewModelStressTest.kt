package com.yourname.expensetracker.ui.screens.map

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.LocatedExpense
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapViewModelStressTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var locationResolver: LocationResolver
    private lateinit var locationProvider: com.yourname.expensetracker.domain.location.ForegroundLocationProvider
    private lateinit var merchantLocationRepository: com.yourname.expensetracker.data.repository.MerchantLocationRepository
    private lateinit var heatmapEngine: SpendingHeatmapEngine
    private lateinit var insightsEngine: LocationInsightsEngine
    private lateinit var geocodingService: GeocodingService

    private lateinit var viewModel: SpendingMapViewModel

    @Before
    override fun setup() {
        super.setup()
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        locationResolver = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        heatmapEngine = mockk(relaxed = true)
        insightsEngine = mockk(relaxed = true)
        geocodingService = mockk(relaxed = true)

        every { expenseRepository.getLocatedExpenses() } returns flowOf(emptyList())
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())

        viewModel = SpendingMapViewModel(
            expenseRepository,
            categoryRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            heatmapEngine,
            insightsEngine,
            geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )
    }

    @Ignore("Stress test: may hang in CI, run manually")
    @Test
    fun `stress - after init isLoading becomes false when data loaded`() = runTest(testDispatcher) {
        viewModel.state.test {
            var state = awaitItem()
            if (state.isLoading) {
                state = awaitItem()
            }
            assertFalse(state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Ignore("Stress test: may hang in CI, run manually")
    @Test
    fun `stress - onPermissionResult denied updates state`() = runTest(testDispatcher) {
        viewModel.onPermissionResult(false)
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.locationPermissionGranted)
    }

    @Ignore("Stress test: may hang in CI, run manually")
    @Test
    fun `stress - onShowPermissionRationale updates state`() = runTest(testDispatcher) {
        viewModel.onShowPermissionRationale(true)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.showPermissionRationale)
    }

    // ── Batch 2: heatmap spending-only filter ─────────────────────────────────

    @Test
    fun `stress - heatmap receives only spending transactions`() = runTest(testDispatcher) {
        // Given: a mix of PURCHASE, DEPOSIT, TRANSFER, WITHDRAWAL, UNKNOWN expenses
        val mixedExpenses = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 25.0),
            makeExpense(2L, TransactionType.DEPOSIT, 500.0),
            makeExpense(3L, TransactionType.TRANSFER, 100.0),
            makeExpense(4L, TransactionType.WITHDRAWAL, 200.0),
            makeExpense(5L, TransactionType.UNKNOWN, 12.0),
            makeExpense(6L, TransactionType.PURCHASE, 40.0)
        )

        val captured = slot<List<LocatedExpense>>()
        every { heatmapEngine.compute(capture(captured)) } returns emptyList()
        every { expenseRepository.getLocatedExpenses() } returns flowOf(mixedExpenses)
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

        // Re-create ViewModel so init picks up the new flow
        viewModel = SpendingMapViewModel(
            expenseRepository,
            categoryRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            heatmapEngine,
            insightsEngine,
            geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )

        // Wait for the init coroutine (runs on Dispatchers.IO) to finish
        viewModel.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        // Then: heatmapEngine.compute() was called with only PURCHASE rows
        assertTrue("compute should have been called", captured.isCaptured)
        val ids = captured.captured.map { it.expenseId }.toSet()
        assertEquals("Only PURCHASE IDs passed to heatmap", setOf(1L, 6L), ids)
    }

    @Test
    fun `stress - markers include all transaction types`() = runTest(testDispatcher) {
        // Given: mixed transaction types
        val mixedExpenses = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 25.0),
            makeExpense(2L, TransactionType.DEPOSIT, 500.0),
            makeExpense(3L, TransactionType.TRANSFER, 100.0),
            makeExpense(4L, TransactionType.WITHDRAWAL, 200.0),
            makeExpense(5L, TransactionType.UNKNOWN, 12.0),
        )

        every { expenseRepository.getLocatedExpenses() } returns flowOf(mixedExpenses)
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

        viewModel = SpendingMapViewModel(
            expenseRepository,
            categoryRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            heatmapEngine,
            insightsEngine,
            geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )

        // Wait for the init coroutine to complete
        viewModel.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        // Then: all 5 located expenses appear as markers (UI pins)
        val markerIds = viewModel.state.value.markers.map { it.expenseId }.toSet()
        assertEquals("All types should appear as markers",
            setOf(1L, 2L, 3L, 4L, 5L), markerIds)
    }

    @Test
    fun `stress - deposits and transfers excluded from heatmap spend total`() = runTest(testDispatcher) {
        // Use real heatmap engine to verify end-to-end spend totals
        val realEngine = SpendingHeatmapEngine()

        val mixedExpenses = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 50.0),
            makeExpense(2L, TransactionType.DEPOSIT, 1000.0),
            makeExpense(3L, TransactionType.TRANSFER, 300.0),
            makeExpense(4L, TransactionType.PURCHASE, 30.0),
            makeExpense(5L, TransactionType.WITHDRAWAL, 200.0)
        )

        every { expenseRepository.getLocatedExpenses() } returns flowOf(mixedExpenses)
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

        viewModel = SpendingMapViewModel(
            expenseRepository,
            categoryRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            realEngine,
            insightsEngine,
            geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )

        // Wait for data load
        viewModel.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        // The heatmap should only contain spend from PURCHASE rows (50 + 30 = 80)
        val totalHeatmapSpend = viewModel.state.value.heatmapPoints.sumOf { it.totalSpend }
        assertEquals("Only purchase amounts in heatmap", 80.0, totalHeatmapSpend, 0.01)
    }

    @Test
    fun `stress - only purchases with no non-spending rows produces same heatmap`() = runTest(testDispatcher) {
        // If all expenses are PURCHASE, heatmap should work exactly as before
        val realEngine = SpendingHeatmapEngine()

        val purchases = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 50.0, lat = 40.7128, lon = -74.0060),
            makeExpense(2L, TransactionType.PURCHASE, 30.0, lat = 40.7128, lon = -74.0060),
            makeExpense(3L, TransactionType.PURCHASE, 100.0, lat = 40.7200, lon = -74.0100)
        )

        every { expenseRepository.getLocatedExpenses() } returns flowOf(purchases)
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

        viewModel = SpendingMapViewModel(
            expenseRepository,
            categoryRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            realEngine,
            insightsEngine,
            geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )

        // Wait for data load
        viewModel.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        val heatmap = viewModel.state.value.heatmapPoints
        val totalSpend = heatmap.sumOf { it.totalSpend }
        assertEquals("All purchase spend accounted for", 180.0, totalSpend, 0.01)
        assertTrue("Heatmap has points", heatmap.isNotEmpty())
    }

    @Test
    fun `stress - manual resolve retryable failure shows temporary snackbar`() = runTest(testDispatcher) {
        val marker = MapExpenseMarker(
            expenseId = 42L,
            latitude = 37.98,
            longitude = 23.72,
            amount = 12.5,
            merchant = "Cafe Retry",
            date = 1_700_000_000_000L,
            locationSource = null,
            placeId = null
        )
        coEvery {
            locationResolver.resolve(
                rawMerchantName = marker.merchant,
                transactionDateMs = marker.date,
                forceRefresh = true
            )
        } returns LocationResolutionResult.Retryable(GeocodingError.Timeout)

        viewModel.onResolveLocationForMarker(marker)
        advanceUntilIdle()

        assertEquals(
            "Temporary location lookup failure. Please try again.",
            viewModel.state.value.snackbarMessage
        )
        assertFalse(viewModel.state.value.isResolvingLocation)
        coVerify(exactly = 0) { expenseRepository.updateExpenseLocation(any(), any(), any(), any(), any(), any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Await emissions from a Turbine receiver until [predicate] returns true.
     * Consumes and discards intermediate emissions.
     */
    private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitUntil(
        predicate: (T) -> Boolean
    ): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun makeExpense(
        id: Long,
        type: TransactionType,
        amount: Double,
        lat: Double = 40.7128 + id * 0.0001,
        lon: Double = -74.0060 + id * 0.0001
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Merchant$id",
        transactionType = type,
        date = System.currentTimeMillis(),
        latitude = lat,
        longitude = lon,
        locationSource = "test"
    )
}

// ── Active (non-@Ignore) focused tests for PURCHASE-only heatmap rule ────────

/**
 * Lightweight, CI-safe tests that verify the canonical spending predicate:
 * only [TransactionType.PURCHASE] expenses feed the heatmap engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapHeatmapFilterTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
    private lateinit var locationResolver: LocationResolver
    private lateinit var locationProvider: com.yourname.expensetracker.domain.location.ForegroundLocationProvider
    private lateinit var merchantLocationRepository: com.yourname.expensetracker.data.repository.MerchantLocationRepository
    private lateinit var heatmapEngine: SpendingHeatmapEngine
    private lateinit var insightsEngine: LocationInsightsEngine
    private lateinit var geocodingService: GeocodingService

    @Before
    override fun setup() {
        super.setup()
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        locationResolver = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        heatmapEngine = mockk(relaxed = true)
        insightsEngine = mockk(relaxed = true)
        geocodingService = mockk(relaxed = true)

        every { expenseRepository.getLocatedExpenses() } returns flowOf(emptyList())
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())
        every { categoryRepository.allCategories } returns flowOf(emptyList())
    }

    @Test
    fun `heatmap excludes UNKNOWN and non-spending types`() = runTest(testDispatcher) {
        val mixedExpenses = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 25.0),
            makeExpense(2L, TransactionType.DEPOSIT, 500.0),
            makeExpense(3L, TransactionType.TRANSFER, 100.0),
            makeExpense(4L, TransactionType.WITHDRAWAL, 200.0),
            makeExpense(5L, TransactionType.UNKNOWN, 12.0),
            makeExpense(6L, TransactionType.PURCHASE, 40.0)
        )

        val captured = slot<List<LocatedExpense>>()
        every { heatmapEngine.compute(capture(captured)) } returns emptyList()
        every { expenseRepository.getLocatedExpenses() } returns flowOf(mixedExpenses)

        val vm = SpendingMapViewModel(
            expenseRepository, categoryRepository, locationResolver,
            locationProvider, merchantLocationRepository,
            heatmapEngine, insightsEngine, geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
        )

        vm.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("compute should have been called", captured.isCaptured)
        val ids = captured.captured.map { it.expenseId }.toSet()
        assertEquals("Only PURCHASE IDs passed to heatmap", setOf(1L, 6L), ids)
    }

    @Test
    fun `markers still include all transaction types`() = runTest(testDispatcher) {
        val mixedExpenses = listOf(
            makeExpense(1L, TransactionType.PURCHASE, 25.0),
            makeExpense(2L, TransactionType.DEPOSIT, 500.0),
            makeExpense(3L, TransactionType.UNKNOWN, 12.0)
        )

        every { expenseRepository.getLocatedExpenses() } returns flowOf(mixedExpenses)

        val vm = SpendingMapViewModel(
            expenseRepository, categoryRepository, locationResolver,
            locationProvider, merchantLocationRepository,
            heatmapEngine, insightsEngine, geocodingService,
            currencySettingsRepository = mockk(),
            currencyConverter = mockk(),
            timeProvider = mockk(),
            )

        vm.state.test {
            awaitUntil { !it.isLoading }
            cancelAndIgnoreRemainingEvents()
        }

        val markerIds = vm.state.value.markers.map { it.expenseId }.toSet()
        assertEquals("All types should appear as markers", setOf(1L, 2L, 3L), markerIds)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitUntil(
        predicate: (T) -> Boolean
    ): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun makeExpense(
        id: Long,
        type: TransactionType,
        amount: Double,
        lat: Double = 40.7128 + id * 0.0001,
        lon: Double = -74.0060 + id * 0.0001
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Merchant$id",
        transactionType = type,
        date = System.currentTimeMillis(),
        latitude = lat,
        longitude = lon,
        locationSource = "test"
    )
}