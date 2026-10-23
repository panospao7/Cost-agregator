package com.yourname.expensetracker.ui.screens.warranty

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** R18b: warranty writes rejected by the maintenance write barrier show the shared message. */
@OptIn(ExperimentalCoroutinesApi::class)
class WarrantyTrackerViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val warrantyRepository = mockk<WarrantyTrackerRepository>(relaxed = true)
    private lateinit var viewModel: WarrantyTrackerViewModel

    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    @Before
    override fun setup() {
        super.setup()
        coEvery { warrantyRepository.getAllWarranties() } returns MutableStateFlow(emptyList())
        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        // Drain init (loadWarranties/loadStats) before arming the failure.
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `blocked addWarranty shows maintenance message and does not signal success`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.addWarranty(any()) } throws blockedWriteException()

        viewModel.addManualWarranty("Laptop", "Shop", 1_700_000_000_000L, 24, null)
        advanceUntilIdle()

        assertEquals(blockedMessage, viewModel.state.value.mutationError)
        assertFalse(viewModel.state.value.addWarrantySuccess)
    }

    @Test
    fun `blocked deleteWarranty shows maintenance message`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.deleteWarranty(any()) } throws blockedWriteException()

        viewModel.deleteWarranty(warranty())
        advanceUntilIdle()

        assertEquals(blockedMessage, viewModel.state.value.mutationError)
    }

    @Test
    fun `blocked confirmWarranty shows maintenance message and releases the mutation guard`() =
        runTest(testDispatcher) {
            // AE-20: the repository rejects the confirm behind the write barrier.
            coEvery { warrantyRepository.confirmWarranty(1L) } throws blockedWriteException()
            viewModel.confirmWarranty(1L)
            advanceUntilIdle()

            assertEquals(blockedMessage, viewModel.state.value.mutationError)
            assertEquals(emptySet<Long>(), viewModel.state.value.mutatingWarrantyIds)
        }

    private fun warranty() = Warranty(
        id = 1L,
        receiptId = null,
        expenseId = null,
        productName = "Laptop",
        merchantName = "Shop",
        purchaseDate = 1_700_000_000_000L,
        warrantyDurationMonths = 24,
        warrantyEndDate = 1_760_000_000_000L,
        supportPhone = null,
        extractionSource = "manual"
    )

    @Test
    fun `unrelated failure keeps the generic failure message`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.markWarrantyAsClaimed(any()) } throws IllegalStateException("db")

        viewModel.markAsClaimed(1L)
        advanceUntilIdle()

        assertEquals(
            UiText.StringResource(R.string.warranty_mutation_failed),
            viewModel.state.value.mutationError
        )
    }
}
