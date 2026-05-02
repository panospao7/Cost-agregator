package com.yourname.expensetracker.ui.screens.warranty

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [WarrantyTrackerViewModel].
 *
 * ## Test gaps (not yet covered):
 * - Warranty expiration sorting: verify that the warranty list is sorted by
 *   expiration date (ascending) so soon-to-expire warranties appear first.
 * - Manual warranty creation with receipt linking: test that creating a manual
 *   warranty correctly invokes the receipt placeholder path and links the
 *   warranty to the correct receipt/expense.
 * - Price protection detection: test the automatic detection of price-protection
 *   opportunities based on purchase price history for a given merchant.
 * - Warranty status transitions: verify that warranties transition correctly
 *   through ACTIVE → EXPIRING_SOON → EXPIRED statuses based on the current time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarrantyTrackerViewModelTest : ViewModelTestUtils() {

    private val warrantyRepository = mockk<WarrantyTrackerRepository>(relaxed = true)

    private lateinit var warrantiesFlow: MutableStateFlow<List<Warranty>>
    private lateinit var viewModel: WarrantyTrackerViewModel

    @Before
    override fun setup() {
        super.setup()

        warrantiesFlow = MutableStateFlow(emptyList())

        coEvery { warrantyRepository.getAllWarranties() } returns warrantiesFlow
        coEvery { warrantyRepository.getActiveWarrantyCount() } coAnswers {
            warrantiesFlow.value.count { it.status == WarrantyStatus.ACTIVE }
        }
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } coAnswers {
            val cutoff = FIXED_NOW + (30 * DAY_MS)
            warrantiesFlow.value.filter {
                it.status == WarrantyStatus.ACTIVE && it.warrantyEndDate in (FIXED_NOW + 1)..cutoff
            }
        }
        coEvery { warrantyRepository.getTotalProtectedValue() } coAnswers {
            warrantiesFlow.value.size * 100.0
        }
        coEvery {
            warrantyRepository.createManualPlaceholderReceipt(any(), any(), any())
        } returns 10_000L
        coEvery { warrantyRepository.addWarranty(any()) } coAnswers {
            val incoming = invocation.args[0] as Warranty
            val nextId = (warrantiesFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
            val stored = if (incoming.id == 0L) incoming.copy(id = nextId) else incoming
            warrantiesFlow.value = warrantiesFlow.value + stored
            stored.id
        }
        coEvery { warrantyRepository.deleteWarranty(any()) } coAnswers {
            val toDelete = invocation.args[0] as Warranty
            warrantiesFlow.value = warrantiesFlow.value.filterNot { it.id == toDelete.id }
            Unit
        }

        viewModel = WarrantyTrackerViewModel(warrantyRepository)
    }

    @Test
    fun `initial state shows warranties`() = runTest(testDispatcher) {
        val w1 = warranty(id = 1L, product = "Laptop", warrantyEndDate = FIXED_NOW + 10 * DAY_MS)
        val w2 = warranty(id = 2L, product = "Headphones", warrantyEndDate = FIXED_NOW + 90 * DAY_MS)
        warrantiesFlow.value = listOf(w1, w2)

        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.warranties.size)
            assertEquals(2, state.activeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add warranty updates list`() = runTest(testDispatcher) {
        warrantiesFlow.value = emptyList()
        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val initial = awaitItem()
            assertTrue(initial.warranties.isEmpty())

            viewModel.addManualWarranty(
                productName = "Phone",
                merchantName = "Tech Store",
                purchaseDate = FIXED_NOW,
                warrantyDurationMonths = 24,
                supportPhone = "1234567890"
            )
            advanceUntilIdle()

            var updated = awaitItem()
            while (updated.warranties.isEmpty()) {
                updated = awaitItem()
            }

            assertEquals(1, updated.warranties.size)
            assertEquals("Phone", updated.warranties.first().productName)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { warrantyRepository.addWarranty(any()) }
    }

    @Test
    fun `expiring warranties highlighted`() = runTest(testDispatcher) {
        val expiring = warranty(id = 11L, product = "Tablet", warrantyEndDate = FIXED_NOW + 5 * DAY_MS)
        val later = warranty(id = 12L, product = "TV", warrantyEndDate = FIXED_NOW + 120 * DAY_MS)
        warrantiesFlow.value = listOf(expiring, later)

        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.warranties.size)
            assertEquals(1, state.expiringSoonCount)
            assertTrue(state.warranties.any { it.id == expiring.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete warranty removes from list`() = runTest(testDispatcher) {
        val w1 = warranty(id = 21L, product = "Monitor", warrantyEndDate = FIXED_NOW + 15 * DAY_MS)
        val w2 = warranty(id = 22L, product = "Keyboard", warrantyEndDate = FIXED_NOW + 40 * DAY_MS)
        warrantiesFlow.value = listOf(w1, w2)

        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(2, initial.warranties.size)

            viewModel.deleteWarranty(w1)
            advanceUntilIdle()

            var updated = awaitItem()
            while (updated.warranties.size == 2) {
                updated = awaitItem()
            }

            assertEquals(1, updated.warranties.size)
            assertEquals(22L, updated.warranties.first().id)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { warrantyRepository.deleteWarranty(w1) }
    }

    @Test
    fun `auto detected filter chip toggles on and off`() = runTest(testDispatcher) {
        val autoWarranty = warranty(
            id = 31L,
            product = "Camera",
            warrantyEndDate = FIXED_NOW + 20 * DAY_MS
        ).copy(autoDetected = true)
        val manualWarranty = warranty(
            id = 32L,
            product = "Speaker",
            warrantyEndDate = FIXED_NOW + 50 * DAY_MS
        ).copy(autoDetected = false)

        warrantiesFlow.value = listOf(autoWarranty, manualWarranty)
        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.filterByAutoDetected()
        advanceUntilIdle()
        val enabledState = viewModel.state.value
        assertTrue(enabledState.showAutoDetectedOnly)
        assertEquals(listOf(autoWarranty.id), enabledState.warranties.map { it.id })

        viewModel.filterByAutoDetected()
        advanceUntilIdle()
        val disabledState = viewModel.state.value
        assertFalse(disabledState.showAutoDetectedOnly)
        assertEquals(2, disabledState.warranties.size)
    }

    @Test
    fun `auto detected filter remains mutually exclusive with other filters`() = runTest(testDispatcher) {
        val activeAuto = warranty(
            id = 41L,
            product = "Router",
            warrantyEndDate = FIXED_NOW + 12 * DAY_MS,
            status = WarrantyStatus.ACTIVE
        ).copy(autoDetected = true)
        val expiredManual = warranty(
            id = 42L,
            product = "Mixer",
            warrantyEndDate = FIXED_NOW - 2 * DAY_MS,
            status = WarrantyStatus.EXPIRED
        ).copy(autoDetected = false)
        val reviewAuto = warranty(
            id = 43L,
            product = "Mic",
            warrantyEndDate = FIXED_NOW + 40 * DAY_MS,
            status = WarrantyStatus.ACTIVE
        ).copy(autoDetected = true, needsReview = true)

        warrantiesFlow.value = listOf(activeAuto, expiredManual, reviewAuto)
        viewModel = WarrantyTrackerViewModel(warrantyRepository)
        advanceUntilIdle()

        viewModel.filterByStatus(WarrantyStatus.EXPIRED)
        advanceUntilIdle()
        assertEquals(WarrantyStatus.EXPIRED, viewModel.state.value.selectedFilter)

        viewModel.filterByAutoDetected()
        advanceUntilIdle()
        val autoState = viewModel.state.value
        assertTrue(autoState.showAutoDetectedOnly)
        assertFalse(autoState.showNeedsReviewOnly)
        assertEquals(null, autoState.selectedFilter)

        viewModel.showNeedsReview()
        advanceUntilIdle()
        val reviewState = viewModel.state.value
        assertTrue(reviewState.showNeedsReviewOnly)
        assertFalse(reviewState.showAutoDetectedOnly)
        assertEquals(null, reviewState.selectedFilter)
    }

    private fun warranty(
        id: Long,
        product: String,
        warrantyEndDate: Long,
        status: WarrantyStatus = WarrantyStatus.ACTIVE
    ) = Warranty(
        id = id,
        receiptId = 1_000L + id,
        expenseId = null,
        productName = product,
        merchantName = "Merchant-$id",
        purchaseDate = FIXED_NOW - 10 * DAY_MS,
        warrantyDurationMonths = 24,
        warrantyEndDate = warrantyEndDate,
        status = status,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW
    )

    companion object {
        private const val FIXED_NOW = 1_700_000_000_000L
        private const val DAY_MS = 86_400_000L
    }
}
