package com.yourname.expensetracker.ui.screens.map

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-15 (15-D) — SpendingMapViewModel denial convergence.
 *
 * The GPS blocked state must be set on gate denial AND on permission races
 * (SecurityException) — not only a snackbar — with a resource-backed message
 * and a controlled reason code. Permitted fetches leave the state null.
 *
 * Note: fetchDeviceLocation runs on Dispatchers.IO (real time), so these tests
 * poll with real time via runBlocking — the virtual-time test scheduler cannot
 * drive the IO dispatcher (same reason the map stress tests are @Ignore'd).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingMapViewModelPrivacyDenialTest : ViewModelTestUtils() {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val locationResolver = mockk<LocationResolver>(relaxed = true)
    private val locationProvider = mockk<ForegroundLocationProvider>(relaxed = true)
    private val merchantLocationRepository = mockk<MerchantLocationRepository>(relaxed = true)
    private val heatmapEngine = mockk<SpendingHeatmapEngine>(relaxed = true)
    private val insightsEngine = mockk<LocationInsightsEngine>(relaxed = true)
    private val geocodingService = mockk<GeocodingService>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
        every { it.homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
    }
    private val privacyGate = mockk<PrivacyGate>(relaxed = true)

    private lateinit var viewModel: SpendingMapViewModel

    @Before
    override fun setup() {
        super.setup()
        every { expenseRepository.getLocatedExpenses() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { expenseRepository.getUnlocatedExpensesFlow(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { categoryRepository.allCategories } returns kotlinx.coroutines.flow.flowOf(emptyList())

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

    /** fetchDeviceLocation runs on Dispatchers.IO — poll with REAL time. */
    private fun awaitBlocked(): SpendingMapState {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.gpsPrivacyBlocked == null) {
            if (System.currentTimeMillis() > deadline) {
                error("gpsPrivacyBlocked was never set")
            }
            Thread.sleep(10)
        }
        return viewModel.state.value
    }

    // ── Denial class 1: privacy-gate denial ───────────────────────────────────

    @Test
    fun `gate denial sets typed blocked state`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns
            PrivacyDecision.Denied("Device GPS location is disabled by user setting")

        viewModel.onCenterOnMeRequested()
        val state = awaitBlocked()

        val blocked = state.gpsPrivacyBlocked!!
        assertEquals(PrivacyCapability.DEVICE_GPS_LOCATION, blocked.capability)
        assertEquals(PrivacyGateReasonCodes.DEVICE_GPS_DISABLED, blocked.reasonCode)
        assertTrue(
            "state must reference a resource-backed message",
            blocked.messageResId != 0
        )
    }

    // ── Denial class 2: fail-closed gate outcome ──────────────────────────────

    @Test
    fun `fail-closed gate decision also sets typed blocked state`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns
            PrivacyDecision.FailClosed("Privacy check failed: INTERNAL_DETAIL")

        viewModel.onCenterOnMeRequested()
        val state = awaitBlocked()

        val blocked = state.gpsPrivacyBlocked!!
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked.reasonCode)
        // The fail-closed detail must never become user-facing state text.
        assertTrue("fail-closed detail must not leak into the code", !blocked.reasonCode.contains("INTERNAL_DETAIL"))
    }

    // ── Denial class 3: permission race (SecurityException) ───────────────────

    @Test
    fun `permission race sets typed blocked state in addition to the snackbar`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { locationProvider.getLastKnownLocation() } throws SecurityException("permission revoked mid-fetch")

        viewModel.onCenterOnMeRequested()
        val state = awaitBlocked()

        val blocked = state.gpsPrivacyBlocked!!
        assertEquals(PrivacyCapability.DEVICE_GPS_LOCATION, blocked.capability)
        assertEquals(PrivacyGateReasonCodes.DEVICE_GPS_DISABLED, blocked.reasonCode)
        assertNotNull("permission race must ALSO keep the snackbar", state.snackbarMessage)
    }

    // ── Permitted path unchanged ───────────────────────────────────────────────

    @Test
    fun `permitted fetch leaves blocked state null`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { locationProvider.getLastKnownLocation() } returns Pair(48.20, 16.37)

        viewModel.onCenterOnMeRequested()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.deviceLatitude == null) {
            if (System.currentTimeMillis() > deadline) error("device location was never set")
            Thread.sleep(10)
        }

        assertNull(viewModel.state.value.gpsPrivacyBlocked)
        assertEquals(48.20, viewModel.state.value.deviceLatitude!!, 0.0001)
        assertEquals(16.37, viewModel.state.value.deviceLongitude!!, 0.0001)
    }
}
