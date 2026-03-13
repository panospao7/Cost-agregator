package com.yourname.expensetracker.ui.screens.map

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapViewModelStressTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: ExpenseRepository
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
        locationResolver = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        heatmapEngine = mockk(relaxed = true)
        insightsEngine = mockk(relaxed = true)
        geocodingService = mockk(relaxed = true)

        every { expenseRepository.getLocatedExpenses() } returns flowOf(emptyList())
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns flowOf(emptyList())

        viewModel = SpendingMapViewModel(
            expenseRepository,
            locationResolver,
            locationProvider,
            merchantLocationRepository,
            heatmapEngine,
            insightsEngine,
            geocodingService
        )
    }

    @Test
    fun `stress - after init isLoading becomes false when data loaded`() = runTest {
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun `stress - onPermissionResult denied updates state`() = runTest {
        viewModel.onPermissionResult(false)
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.locationPermissionGranted)
    }

    @Test
    fun `stress - onShowPermissionRationale updates state`() = runTest {
        viewModel.onShowPermissionRationale(true)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.showPermissionRationale)
    }
}
