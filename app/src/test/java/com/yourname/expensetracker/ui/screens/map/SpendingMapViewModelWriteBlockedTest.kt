package com.yourname.expensetracker.ui.screens.map

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.NearbyPoi
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R18 review: user-started location writes rejected by the backup/restore write barrier must
 * surface the bounded maintenance message, never crash, and never leave the resolving spinner on.
 *
 * Production work runs on Dispatchers.IO, so states are awaited on a real dispatcher with a timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val locationResolver = mockk<LocationResolver>(relaxed = true)
    private val locationProvider = mockk<ForegroundLocationProvider>(relaxed = true)
    private val merchantLocationRepository = mockk<MerchantLocationRepository>(relaxed = true)
    private val heatmapEngine = mockk<SpendingHeatmapEngine>(relaxed = true)
    private val insightsEngine = mockk<LocationInsightsEngine>(relaxed = true)
    private val geocodingService = mockk<GeocodingService>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
        every { it.homeCurrency() } returns flowOf("EUR")
    }
    private val privacyGate = mockk<PrivacyGate>(relaxed = true)

    private lateinit var viewModel: SpendingMapViewModel

    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    private val marker = MapExpenseMarker(
        expenseId = 7L,
        latitude = 48.2,
        longitude = 16.3,
        amount = 10.0,
        merchant = "Cafe",
        date = 1_710_000_000_000L,
        locationSource = null,
        placeId = null
    )

    @Before
    override fun setup() {
        super.setup()
        every { expenseRepository.getLocatedExpenses() } returns flowOf(emptyList())
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
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            timeProvider = mockk<TimeProvider>(relaxed = true),
            privacyGate = privacyGate
        )
    }

    @After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                if (::viewModel.isInitialized) {
                    viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            super.tearDown()
        }
    }

    private suspend fun awaitState(predicate: (SpendingMapState) -> Boolean): SpendingMapState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { viewModel.state.first { predicate(it) } }
        }

    @Test
    fun `blocked resolve write shows maintenance message and resets resolving flag`() =
        runTest(testDispatcher) {
            coEvery {
                locationResolver.resolve(any(), any(), any(), any())
            } returns LocationResolutionResult.Resolved(latitude = 48.2, longitude = 16.3, source = "OSM")
            coEvery {
                expenseRepository.updateExpenseLocation(any(), any(), any(), any(), any(), any())
            } throws blockedWriteException()

            viewModel.onResolveLocationForMarker(marker)
            val state = awaitState { it.snackbarUiText != null }

            assertEquals(blockedMessage, state.snackbarUiText)
            assertFalse("spinner must not stay on after a blocked write", state.isResolvingLocation)
            assertNull("no success message after a blocked write", state.snackbarMessage)
        }

    @Test
    fun `blocked merchant save after the expense write surfaces the message without crashing`() =
        runTest(testDispatcher) {
            coEvery {
                merchantLocationRepository.saveLocation(any(), any(), any())
            } throws blockedWriteException()
            val poi = NearbyPoi(
                osmId = "node/1", name = "Cafe", latitude = 48.2, longitude = 16.3,
                distanceMetres = 5.0, category = "cafe", displayAddress = null
            )

            viewModel.onPoiSelected(poi, marker)
            val state = awaitState { it.snackbarUiText != null }

            assertEquals(blockedMessage, state.snackbarUiText)
            assertNull("no success message after a blocked write", state.snackbarMessage)
            coVerify(exactly = 1) {
                expenseRepository.updateExpenseLocation(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `ui-text dismissal clears the blocked message`() =
        runTest(testDispatcher) {
            coEvery {
                merchantLocationRepository.saveLocation(any(), any(), any())
            } throws blockedWriteException()
            val poi = NearbyPoi("node/1", "Cafe", 48.2, 16.3, 5.0, "cafe", null)
            viewModel.onPoiSelected(poi, marker)
            awaitState { it.snackbarUiText != null }

            viewModel.onSnackbarUiTextDismissed()

            assertNull(viewModel.state.value.snackbarUiText)
        }

    @Test
    fun `dismissing the ui-text message keeps a pending plain message`() =
        runTest(testDispatcher) {
            coEvery {
                locationResolver.resolve(any(), any(), any(), any())
            } throws RuntimeException("temporary")
            viewModel.onResolveLocationForMarker(marker)
            awaitState { it.snackbarMessage != null }
            coEvery {
                merchantLocationRepository.saveLocation(any(), any(), any())
            } throws blockedWriteException()
            val poi = NearbyPoi("node/1", "Cafe", 48.2, 16.3, 5.0, "cafe", null)
            viewModel.onPoiSelected(poi, marker)
            awaitState { it.snackbarUiText != null && it.snackbarMessage != null }

            viewModel.onSnackbarUiTextDismissed()

            assertNull(viewModel.state.value.snackbarUiText)
            assertNotNull("plain message must survive ui-text dismissal", viewModel.state.value.snackbarMessage)
        }
}
