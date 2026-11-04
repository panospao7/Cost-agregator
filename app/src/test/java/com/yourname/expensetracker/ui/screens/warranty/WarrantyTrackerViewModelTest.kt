package com.yourname.expensetracker.ui.screens.warranty

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
    }

    @Test
    fun `initial state shows warranties`() = runTest(testDispatcher) {
        val w1 = warranty(id = 1L, product = "Laptop", warrantyEndDate = FIXED_NOW + 10 * DAY_MS)
        val w2 = warranty(id = 2L, product = "Headphones", warrantyEndDate = FIXED_NOW + 90 * DAY_MS)
        warrantiesFlow.value = listOf(w1, w2)

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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
        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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
        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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
        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
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

    // ---- R29: fail-closed repository writes must not crash the VM ----

    private val expectedMutationError =
        UiText.StringResource(R.string.warranty_mutation_failed)

    @Test
    fun `addManualWarranty repository failure sets bounded error and does not crash`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.addWarranty(any()) } throws RuntimeException("secret raw detail")

        viewModel.addManualWarranty("Phone", "Store", FIXED_NOW, 12, null)
        advanceUntilIdle()

        assertEquals(expectedMutationError, viewModel.state.value.mutationError)
        assertFalse(viewModel.state.value.addWarrantySuccess)
    }

    @Test
    fun `addManualWarranty cancellation propagates and is not converted to error`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.addWarranty(any()) } throws CancellationException("cancelled")

        viewModel.addManualWarranty("Phone", "Store", FIXED_NOW, 12, null)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
        assertFalse(viewModel.state.value.addWarrantySuccess)
    }

    // ---- warranty-edit S3: add flow end date unchanged (INV-1), default type MANUFACTURER ----

    @Test
    fun `addManualWarranty end date is unchanged and the default type is MANUFACTURER`() = runTest(testDispatcher) {
        val purchase = FIXED_NOW + 3 * 3_600_000L
        viewModel.addManualWarranty("Phone", "Store", purchase, 13, null)
        advanceUntilIdle()

        val saved = slot<Warranty>()
        coVerify(exactly = 1) { warrantyRepository.addWarranty(capture(saved)) }
        // The arithmetic addManualWarranty has always used: start of day + months, end of that day.
        val start = TimePeriodUtils.getStartOfDay(purchase)
        val expectedEnd = TimePeriodUtils.getEndOfDay(TimePeriodUtils.addMonths(start, 13))
        assertEquals(start, saved.captured.purchaseDate)
        assertEquals(expectedEnd, saved.captured.warrantyEndDate)
        assertEquals(WarrantyType.MANUFACTURER, saved.captured.warrantyType)
        assertEquals("manual", saved.captured.extractionSource)
        assertTrue(viewModel.state.value.addWarrantySuccess)
    }

    @Test
    fun `addManualWarranty persists an explicitly chosen warranty type`() = runTest(testDispatcher) {
        viewModel.addManualWarranty("Phone", "Store", FIXED_NOW, 12, null, WarrantyType.EXTENDED)
        advanceUntilIdle()

        val saved = slot<Warranty>()
        coVerify(exactly = 1) { warrantyRepository.addWarranty(capture(saved)) }
        assertEquals(WarrantyType.EXTENDED, saved.captured.warrantyType)
    }

    // ---- ai-fixes B1b: confirm goes through repository.confirmWarranty(id) only (INV-13) ----

    private val expectedConfirmFailedError =
        UiText.StringResource(R.string.warranty_error_confirm_failed)

    /** AE-3: bounded "could not confirm" resource, no raw/SQL text, in-flight id released. */
    @Test
    fun `confirmWarranty repository failure sets bounded error without raw message`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.confirmWarranty(51L) } throws
            IllegalStateException("no such table: warranties")

        viewModel.confirmWarranty(51L)
        advanceUntilIdle()

        assertEquals(expectedConfirmFailedError, viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `confirmWarranty cancellation propagates and is not converted to error`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.confirmWarranty(52L) } throws CancellationException("cancelled")

        viewModel.confirmWarranty(52L)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `confirmWarranty delegates the id to the repository and never builds a status copy`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.confirmWarranty(60L) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(60L, WarrantyStatus.ACTIVE)

        viewModel.confirmWarranty(60L)
        advanceUntilIdle()

        coVerify(exactly = 1) { warrantyRepository.confirmWarranty(60L) }
        coVerify(exactly = 0) { warrantyRepository.updateWarranty(any()) }
        assertNull(viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `confirmWarranty AlreadyConfirmed is a silent success`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.confirmWarranty(61L) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed

        viewModel.confirmWarranty(61L)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `confirmWarranty NotFound shows the bounded not found message`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.confirmWarranty(62L) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.NotFound

        viewModel.confirmWarranty(62L)
        advanceUntilIdle()

        assertEquals(
            UiText.StringResource(R.string.warranty_error_not_found),
            viewModel.state.value.mutationError
        )
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `confirmWarranty in-flight guard ignores a second tap for the same id`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<WarrantyTrackerRepository.ConfirmWarrantyResult>()
        coEvery { warrantyRepository.confirmWarranty(63L) } coAnswers { gate.await() }

        viewModel.confirmWarranty(63L)
        advanceUntilIdle()
        assertTrue(63L in viewModel.state.value.mutatingWarrantyIds)

        viewModel.confirmWarranty(63L)
        advanceUntilIdle()
        coVerify(exactly = 1) { warrantyRepository.confirmWarranty(63L) }

        gate.complete(WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    // ---- ai-fixes B1b: INV-10 counts/filters from WarrantyReviewState ----

    private fun reviewViewModel(): WarrantyTrackerViewModel {
        val clock = mockk<TimeProvider>()
        every { clock.now() } returns FIXED_NOW
        return WarrantyTrackerViewModel(warrantyRepository, timeProvider = clock)
    }

    private fun reviewRow(id: Long, status: WarrantyStatus, endOffsetDays: Long, needsReview: Boolean) =
        warranty(id = id, product = "P$id", warrantyEndDate = FIXED_NOW + endOffsetDays * DAY_MS, status = status)
            .copy(autoDetected = true, needsReview = needsReview)

    /** AE-16: a PENDING_REVIEW row past its end date is EXPIRED_UNCONFIRMED before reconcile runs. */
    @Test
    fun `pending review row past end date is expired unconfirmed and excluded from review count`() =
        runTest(testDispatcher) {
            val staleCaught = reviewRow(1L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = -1, needsReview = true)
            val awaiting = reviewRow(2L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = 5, needsReview = true)
            warrantiesFlow.value = listOf(staleCaught, awaiting)

            viewModel = reviewViewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, state.needsReviewCount)
            assertEquals(1, state.expiredUnconfirmedCount)
            assertEquals(WarrantyReviewState.EXPIRED_UNCONFIRMED, state.reviewStateOf(staleCaught))
            assertEquals(WarrantyReviewState.AWAITING_REVIEW, state.reviewStateOf(awaiting))
        }

    @Test
    fun `needs review filter lists awaiting rows only`() = runTest(testDispatcher) {
        val staleCaught = reviewRow(1L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = -1, needsReview = true)
        val awaiting = reviewRow(2L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = 5, needsReview = true)
        val confirmedActive = reviewRow(3L, WarrantyStatus.ACTIVE, endOffsetDays = 90, needsReview = false)
        warrantiesFlow.value = listOf(staleCaught, awaiting, confirmedActive)
        viewModel = reviewViewModel()
        advanceUntilIdle()

        viewModel.showNeedsReview()
        advanceUntilIdle()

        assertEquals(listOf(2L), viewModel.state.value.warranties.map { it.id })
    }

    /** G2-8 + never handled as ACTIVE: expired-unconfirmed rows are under Expired, not Active. */
    @Test
    fun `expired filter includes expired unconfirmed rows and active filter excludes review rows`() =
        runTest(testDispatcher) {
            val unreconciled = reviewRow(1L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = -1, needsReview = true)
            val reconciled = reviewRow(2L, WarrantyStatus.EXPIRED, endOffsetDays = -3, needsReview = true)
            val confirmedExpired = reviewRow(3L, WarrantyStatus.EXPIRED, endOffsetDays = -9, needsReview = false)
            val awaiting = reviewRow(4L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = 5, needsReview = true)
            val active = reviewRow(5L, WarrantyStatus.ACTIVE, endOffsetDays = 60, needsReview = false)
            warrantiesFlow.value = listOf(unreconciled, reconciled, confirmedExpired, awaiting, active)
            viewModel = reviewViewModel()
            advanceUntilIdle()

            viewModel.filterByStatus(WarrantyStatus.EXPIRED)
            advanceUntilIdle()
            assertEquals(setOf(1L, 2L, 3L), viewModel.state.value.warranties.map { it.id }.toSet())

            viewModel.filterByStatus(WarrantyStatus.ACTIVE)
            advanceUntilIdle()
            assertEquals(listOf(5L), viewModel.state.value.warranties.map { it.id })
            assertEquals(2, viewModel.state.value.expiredUnconfirmedCount)
        }

    @Test
    fun `list orders awaiting review rows first then by end date`() = runTest(testDispatcher) {
        val later = reviewRow(1L, WarrantyStatus.ACTIVE, endOffsetDays = 20, needsReview = false)
        val sooner = reviewRow(2L, WarrantyStatus.ACTIVE, endOffsetDays = 10, needsReview = false)
        val awaiting = reviewRow(3L, WarrantyStatus.PENDING_REVIEW, endOffsetDays = 40, needsReview = true)
        warrantiesFlow.value = listOf(later, sooner, awaiting)
        viewModel = reviewViewModel()
        advanceUntilIdle()

        assertEquals(listOf(3L, 2L, 1L), viewModel.state.value.warranties.map { it.id })
    }

    // ---- :93 fix: protected-value lookup must not swallow CancellationException ----

    @Test
    fun `loadStats rethrows cancellation from the protected value lookup`() = runTest(testDispatcher) {
        warrantiesFlow.value = listOf(warranty(id = 70L, product = "TV", warrantyEndDate = FIXED_NOW + DAY_MS))
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } throws CancellationException("cancelled")

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        // The stats coroutine is cancelled, not continued with a zero value.
        assertEquals(0, viewModel.state.value.activeCount)
        assertEquals(0.0, viewModel.state.value.totalProtectedValue, 0.0)
        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `loadStats still completes when the protected value lookup fails`() = runTest(testDispatcher) {
        warrantiesFlow.value = listOf(warranty(id = 71L, product = "TV", warrantyEndDate = FIXED_NOW + DAY_MS))
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } throws RuntimeException("secret raw detail")

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.activeCount)
        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `rejectAutoDetectedWarranty repository failure sets bounded error and does not crash`() = runTest(testDispatcher) {
        val target = warranty(id = 53L, product = "Cam", warrantyEndDate = FIXED_NOW + DAY_MS)
        coEvery { warrantyRepository.rejectAutoDetectedWarranty(any()) } throws RuntimeException("secret raw detail")

        viewModel.rejectAutoDetectedWarranty(target)
        advanceUntilIdle()

        assertEquals(expectedMutationError, viewModel.state.value.mutationError)
    }

    @Test
    fun `rejectAutoDetectedWarranty cancellation propagates and is not converted to error`() = runTest(testDispatcher) {
        val target = warranty(id = 54L, product = "Cam", warrantyEndDate = FIXED_NOW + DAY_MS)
        coEvery { warrantyRepository.rejectAutoDetectedWarranty(any()) } throws CancellationException("cancelled")

        viewModel.rejectAutoDetectedWarranty(target)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `markAsClaimed repository failure sets bounded error and does not crash`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.markWarrantyAsClaimed(any()) } throws RuntimeException("secret raw detail")

        viewModel.markAsClaimed(55L)
        advanceUntilIdle()

        assertEquals(expectedMutationError, viewModel.state.value.mutationError)
    }

    @Test
    fun `markAsClaimed cancellation propagates and is not converted to error`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.markWarrantyAsClaimed(any()) } throws CancellationException("cancelled")

        viewModel.markAsClaimed(56L)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `deleteWarranty repository failure sets bounded error and does not crash`() = runTest(testDispatcher) {
        val target = warranty(id = 57L, product = "Desk", warrantyEndDate = FIXED_NOW + DAY_MS)
        coEvery { warrantyRepository.deleteWarranty(any()) } throws RuntimeException("secret raw detail")

        viewModel.deleteWarranty(target)
        advanceUntilIdle()

        assertEquals(expectedMutationError, viewModel.state.value.mutationError)
    }

    @Test
    fun `deleteWarranty cancellation propagates and is not converted to error`() = runTest(testDispatcher) {
        val target = warranty(id = 58L, product = "Desk", warrantyEndDate = FIXED_NOW + DAY_MS)
        coEvery { warrantyRepository.deleteWarranty(any()) } throws CancellationException("cancelled")

        viewModel.deleteWarranty(target)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `clearMutationError resets the error state`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.markWarrantyAsClaimed(any()) } throws RuntimeException("x")
        viewModel.markAsClaimed(59L)
        advanceUntilIdle()
        assertEquals(expectedMutationError, viewModel.state.value.mutationError)

        viewModel.clearMutationError()

        assertNull(viewModel.state.value.mutationError)
    }

    // ---- E-05-1: protected value currency, availability, and partial flags ----

    @Test
    fun `E-05-1 loadStats exposes non-EUR currency and availability from complete aggregate`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } returns
            MoneyAggregate.singleCurrency(
                amount = 1250.50,
                currency = CurrencyCode("USD"),
                transactionCount = 2
            )

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1250.50, state.totalProtectedValue, 0.01)
        assertEquals("USD", state.protectedValueCurrency)
        assertTrue(state.isProtectedValueAvailable)
        assertFalse(state.isProtectedValuePartial)
    }

    @Test
    fun `E-05-1 loadStats preserves EUR currency for complete EUR aggregate`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } returns
            MoneyAggregate.singleCurrency(
                amount = 800.0,
                currency = CurrencyCode("EUR"),
                transactionCount = 1
            )

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(800.0, state.totalProtectedValue, 0.01)
        assertEquals("EUR", state.protectedValueCurrency)
        assertTrue(state.isProtectedValueAvailable)
        assertFalse(state.isProtectedValuePartial)
    }

    @Test
    fun `E-05-1 loadStats sets isProtectedValuePartial true when aggregate is partial`() = runTest(testDispatcher) {
        val usd = CurrencyCode("USD")
        val jpy = CurrencyCode("JPY")
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } returns
            MoneyAggregate.partial(
                displayAmount = 500.0,
                displayCurrency = usd,
                sourceBuckets = listOf(MoneyBucket(usd, 500.0, 1), MoneyBucket(jpy, 10000.0, 1)),
                failures = listOf(
                    ConversionFailure(
                        originalAmount = MoneyAmount(10000.0, jpy),
                        targetCurrency = usd,
                        reason = FailureReason.MISSING_RATE,
                        transactionCount = 1
                    )
                )
            )

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(500.0, state.totalProtectedValue, 0.01)
        assertEquals("USD", state.protectedValueCurrency)
        assertTrue(state.isProtectedValueAvailable)
        assertTrue(state.isProtectedValuePartial)
    }

    @Test
    fun `E-05-1 loadStats sets isProtectedValueAvailable false when conversionQuality is UNAVAILABLE or lookup fails`() = runTest(testDispatcher) {
        val usd = CurrencyCode("USD")
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } returns
            MoneyAggregate(
                displayAmount = 0.0,
                displayCurrency = usd,
                sourceBuckets = emptyList(),
                conversionFailures = emptyList(),
                isPartial = true,
                conversionQuality = ConversionQuality.UNAVAILABLE
            )

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        val unavailableState = viewModel.state.value
        assertFalse(unavailableState.isProtectedValueAvailable)
        assertFalse(unavailableState.isProtectedValuePartial)

        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } throws IllegalStateException("rate table missing")
        viewModel.refresh()
        advanceUntilIdle()

        val exceptionState = viewModel.state.value
        assertFalse(exceptionState.isProtectedValueAvailable)
        assertFalse(exceptionState.isProtectedValuePartial)
    }

    @Test
    fun `E-05-1 loadStats unavailable state when lookup fails has empty protectedValueCurrency and zero value`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.getTotalProtectedValueAggregate() } throws IllegalStateException("rate table missing")

        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isProtectedValueAvailable)
        assertEquals("unavailable must not claim a currency", "", state.protectedValueCurrency)
        assertEquals(0.0, state.totalProtectedValue, 0.0)
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