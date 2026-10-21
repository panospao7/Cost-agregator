package com.yourname.expensetracker.ui.screens.map

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.LocatedMoneyExpense
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun heatmapTestCurrencySettings(): CurrencySettingsRepository =
    mockk<CurrencySettingsRepository>(relaxed = true).also {
        every { it.homeCurrency() } returns flowOf("EUR")
    }

/**
 * SpendingMapViewModel state transitions and the PURCHASE-only heatmap rule: only PURCHASE rows
 * feed the heatmap engine, while markers keep every transaction type.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapViewModelHeatmapTest : ViewModelTestUtils() {

    companion object {
        /** Fixed fixture date (2023-11-14 22:13:20 UTC). */
        private const val FIXTURE_DATE = 1_700_000_000_000L
    }

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var locationResolver: LocationResolver
    private lateinit var locationProvider: ForegroundLocationProvider
    private lateinit var merchantLocationRepository: MerchantLocationRepository
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

        viewModel = newViewModel(heatmapEngine)
    }

    private fun newViewModel(engine: SpendingHeatmapEngine): SpendingMapViewModel = SpendingMapViewModel(
        expenseRepository,
        categoryRepository,
        locationResolver,
        locationProvider,
        merchantLocationRepository,
        engine,
        insightsEngine,
        geocodingService,
        currencySettingsRepository = heatmapTestCurrencySettings(),
        currencyConverter = mockk(relaxed = true),
        timeProvider = mockk(relaxed = true),
        privacyGate = mockk(relaxed = true),
    )

    // -- State transitions ----------------------------------------------------

    @Test
    fun `after init isLoading becomes false when data loaded`() = runTest(testDispatcher) {
        viewModel.state.test {
            val loaded = awaitUntil { !it.isLoading }
            assertFalse(loaded.isLoading)
            assertTrue(loaded.markers.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionResult grant then deny updates state`() = runTest(testDispatcher) {
        // Default is false, so grant first to make the deny assertion non-vacuous.
        viewModel.onPermissionResult(true)
        assertTrue(viewModel.state.value.locationPermissionGranted)

        viewModel.onPermissionResult(false)
        assertFalse(viewModel.state.value.locationPermissionGranted)
    }

    @Test
    fun `onShowPermissionRationale updates state`() = runTest(testDispatcher) {
        viewModel.onShowPermissionRationale(true)
        assertTrue(viewModel.state.value.showPermissionRationale)

        viewModel.onShowPermissionRationale(false)
        assertFalse(viewModel.state.value.showPermissionRationale)
    }

    // -- PURCHASE-only heatmap rule (table driven) ----------------------------

    private data class HeatmapCase(
        val name: String,
        val expenses: List<Expense>,
        val expectedHeatmapIds: Set<Long>,
        val expectedHeatmapSpend: Double
    )

    @Test
    fun `heatmap receives only PURCHASE rows while markers keep every type`() = runTest(testDispatcher) {
        val cases = listOf(
            HeatmapCase(
                name = "mixed types: deposit, transfer, withdrawal and unknown are excluded",
                expenses = listOf(
                    makeExpense(1L, TransactionType.PURCHASE, 25.0),
                    makeExpense(2L, TransactionType.DEPOSIT, 500.0),
                    makeExpense(3L, TransactionType.TRANSFER, 100.0),
                    makeExpense(4L, TransactionType.WITHDRAWAL, 200.0),
                    makeExpense(5L, TransactionType.UNKNOWN, 12.0),
                    makeExpense(6L, TransactionType.PURCHASE, 40.0)
                ),
                expectedHeatmapIds = setOf(1L, 6L),
                expectedHeatmapSpend = 65.0
            ),
            HeatmapCase(
                name = "large non-spending amounts do not leak into the spend total",
                expenses = listOf(
                    makeExpense(1L, TransactionType.PURCHASE, 50.0),
                    makeExpense(2L, TransactionType.DEPOSIT, 1000.0),
                    makeExpense(3L, TransactionType.TRANSFER, 300.0),
                    makeExpense(4L, TransactionType.PURCHASE, 30.0),
                    makeExpense(5L, TransactionType.WITHDRAWAL, 200.0)
                ),
                expectedHeatmapIds = setOf(1L, 4L),
                expectedHeatmapSpend = 80.0
            ),
            HeatmapCase(
                name = "purchases only: all spend accounted for",
                expenses = listOf(
                    makeExpense(1L, TransactionType.PURCHASE, 50.0, lat = 40.7128, lon = -74.0060),
                    makeExpense(2L, TransactionType.PURCHASE, 30.0, lat = 40.7128, lon = -74.0060),
                    makeExpense(3L, TransactionType.PURCHASE, 100.0, lat = 40.7200, lon = -74.0100)
                ),
                expectedHeatmapIds = setOf(1L, 2L, 3L),
                expectedHeatmapSpend = 180.0
            )
        )

        for (case in cases) {
            val captured = slot<List<LocatedMoneyExpense>>()
            val realEngine = SpendingHeatmapEngine()
            val spyEngine = mockk<SpendingHeatmapEngine>(relaxed = true)
            every { spyEngine.computeNormalized(capture(captured)) } answers {
                realEngine.computeNormalized(captured.captured)
            }
            every { expenseRepository.getLocatedExpenses() } returns flowOf(case.expenses)
            every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

            val vm = newViewModel(spyEngine)
            vm.state.test {
                awaitUntil { !it.isLoading }
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue("${case.name}: heatmap engine should have been called", captured.isCaptured)
            assertEquals(
                "${case.name}: only PURCHASE IDs passed to heatmap",
                case.expectedHeatmapIds,
                captured.captured.map { it.expenseId }.toSet()
            )

            val state = vm.state.value
            assertEquals(
                "${case.name}: heatmap spend total",
                case.expectedHeatmapSpend,
                state.heatmapPoints.sumOf { it.totalSpend },
                0.01
            )
            assertTrue("${case.name}: heatmap has points", state.heatmapPoints.isNotEmpty())
            assertEquals(
                "${case.name}: all transaction types appear as markers",
                case.expenses.map { it.id }.toSet(),
                state.markers.map { it.expenseId }.toSet()
            )
        }
    }

    // -- Manual resolve --------------------------------------------------------

    @Test
    fun `manual resolve retryable failure shows temporary snackbar`() = runTest(testDispatcher) {
        val marker = MapExpenseMarker(
            expenseId = 42L,
            latitude = 37.98,
            longitude = 23.72,
            amount = 12.5,
            merchant = "Cafe Retry",
            date = FIXTURE_DATE,
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
        // onResolveLocationForMarker launches on Dispatchers.IO (a real pool,
        // not the test scheduler), so advanceUntilIdle cannot guarantee the
        // state update landed; wait on the state flow instead.
        viewModel.state.test {
            val final = awaitUntil { !it.isResolvingLocation && it.snackbarMessage != null }
            assertEquals(
                "Temporary location lookup failure. Please try again.",
                final.snackbarMessage
            )
        }
        assertFalse(viewModel.state.value.isResolvingLocation)
        coVerify(exactly = 0) { expenseRepository.updateExpenseLocation(any(), any(), any(), any(), any(), any()) }
    }

    // -- Helpers ---------------------------------------------------------------

    /** Consumes and discards Turbine emissions until [predicate] holds. */
    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
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
        date = FIXTURE_DATE,
        latitude = lat,
        longitude = lon,
        locationSource = "test"
    )
}
