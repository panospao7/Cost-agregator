package com.yourname.expensetracker.ui.screens.receiptmatching

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptMatchLifecycleService
import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class ReceiptMatchingViewModelTest : ViewModelTestUtils() {

    private val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
    private val matcher = mockk<ReceiptTransactionMatcher>(relaxed = true)

    private val receiptLinkService = mockk<ReceiptLinkService>()
    private val matchService = mockk<ReceiptMatchLifecycleService>()
    private val viewModels = mutableListOf<ReceiptMatchingViewModel>()
    private lateinit var viewModel: ReceiptMatchingViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { receiptRepository.getUnmatchedReceipts() } returns emptyList()
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns emptyList()
    }

    @Test
    fun `initial state shows unmatched receipts`() = runTest(testDispatcher) {
        val receipt1 = scannedReceipt(id = 1L, merchant = "Lidl", total = 14.5)
        val receipt2 = scannedReceipt(id = 2L, merchant = "Coffee Island", total = 3.8)
        val suggested = scannedReceipt(
            id = 3L,
            merchant = "Shell",
            total = 30.0,
            suggestedExpenseId = 100L,
            matchConfidence = 0.91f,
            matchStatus = MatchStatus.SUGGESTED
        )
        val expense = expense(id = 100L, merchant = "SHELL", amount = 30.0)

        coEvery { receiptRepository.getUnmatchedReceipts() } returns listOf(receipt1, receipt2)
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns listOf(suggested)
        coEvery { receiptRepository.getExpenseById(100L) } returns expense

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.unmatchedReceipts.size)
            assertEquals(1, state.suggestedMatches.size)
            assertEquals(1, state.pendingSuggestionCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `match receipt to expense`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(10L, "My Store", 20.0)
        val releaseLink = CompletableDeferred<Unit>()
        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(listOf(receipt), emptyList())
        coEvery {
            receiptLinkService.linkReceiptToExpense(10L, 200L, "MANUAL_MATCH", "ReceiptMatchingViewModel", confidence = 1.0f)
        } coAnswers {
            releaseLink.await()
            Result.success(link(10L, 200L, "MANUAL_MATCH", 1.0f))
        }
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.unmatchedReceipts.size)

        viewModel.manualMatch(10L, 200L)
        runCurrent()
        assertTrue(viewModel.state.value.mutatingReceiptIds.contains(10L))
        releaseLink.complete(Unit)
        advanceUntilIdle()

        val updated = viewModel.state.value
        assertFalse(updated.isLoading)
        assertTrue(updated.mutatingReceiptIds.isEmpty())
        assertTrue(updated.unmatchedReceipts.isEmpty())
        assertNull(updated.error)
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(10L, 200L, "MANUAL_MATCH", "ReceiptMatchingViewModel", confidence = 1.0f)
        }
        coVerify(exactly = 0) { receiptRepository.linkReceiptToExpense(any(), any(), any()) }
    }

    @Test
    fun `skip receipt`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(15L, "Unknown", 5.0)
        val releaseSkip = CompletableDeferred<Unit>()
        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(listOf(receipt), emptyList())
        coEvery { matchService.rejectAllSuggestions(15L) } coAnswers { releaseSkip.await() }
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.unmatchedReceipts.size)

        viewModel.skipReceipt(15L)
        runCurrent()
        assertTrue(viewModel.state.value.mutatingReceiptIds.contains(15L))
        releaseSkip.complete(Unit)
        advanceUntilIdle()

        val updated = viewModel.state.value
        assertFalse(updated.isLoading)
        assertTrue(updated.unmatchedReceipts.isEmpty())
        assertTrue(updated.mutatingReceiptIds.isEmpty())
        assertNull(updated.error)
        coVerify(exactly = 1) { matchService.rejectAllSuggestions(15L) }
        coVerify(exactly = 0) { receiptRepository.rejectAllSuggestions(any()) }
    }

    @Test
    fun `batch match all`() = runTest(testDispatcher) {
        val receiptA = scannedReceipt(31L, "Shell", 45.0)
        val receiptB = scannedReceipt(32L, "Gym", 25.0)
        val txA = expense(901L, "SHELL", 45.0)
        val txB = expense(902L, "Gym", 25.0)
        val releaseMatch = CompletableDeferred<Unit>()
        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(
            listOf(receiptA, receiptB), listOf(receiptA, receiptB), emptyList()
        )
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returnsMany listOf(
            emptyList(), listOf(receiptB.copy(suggestedExpenseId = 902L, matchConfidence = 0.86f, matchStatus = MatchStatus.SUGGESTED))
        )
        coEvery { receiptRepository.getExpenseById(902L) } returns txB
        coEvery { matcher.findBestMatch(receiptA) } coAnswers {
            releaseMatch.await()
            MatchResult.AutoMatch(txA, 0.98)
        }
        coEvery { matcher.findBestMatch(receiptB) } returns MatchResult.Suggested(txB, 0.86)
        coEvery {
            receiptLinkService.linkReceiptToExpense(31L, 901L, "AUTO_MATCH", "ReceiptMatchingViewModel", confidence = 0.98f, requireUnmatchedClaim = true)
        } returns Result.success(link(31L, 901L, "AUTO_MATCH", 0.98f))
        coEvery { matchService.saveMatchSuggestion(32L, 902L, 0.86) } returns true
        viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.unmatchedReceipts.size)

        viewModel.runAutoMatching()
        runCurrent()
        assertTrue(viewModel.state.value.isAutoMatching)
        releaseMatch.complete(Unit)
        advanceUntilIdle()

        val updated = viewModel.state.value
        assertFalse(updated.isAutoMatching)
        assertFalse(updated.isLoading)
        assertNull(updated.error)
        assertEquals(1, updated.autoMatchedCount)
        assertTrue(updated.unmatchedReceipts.isEmpty())
        assertEquals(1, updated.pendingSuggestionCount)
        assertEquals(902L, updated.suggestedMatches.single().suggestedExpenseId)
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(31L, 901L, "AUTO_MATCH", "ReceiptMatchingViewModel", confidence = 0.98f, requireUnmatchedClaim = true)
        }
        coVerify(exactly = 1) { matchService.saveMatchSuggestion(32L, 902L, 0.86) }
        coVerify(exactly = 0) { receiptRepository.linkReceiptToExpense(any(), any(), any()) }
        coVerify(exactly = 0) { receiptRepository.saveMatchSuggestion(any(), any(), any()) }
    }

    // ---- P-03-2: claim flag, claimed receipts, bounded error text, cancellation ----

    private val rawSentinel = "SENTINEL_RAW_EXC_7f3a"

    private suspend fun approveLink(receiptId: Long, expenseId: Long) =
        receiptLinkService.linkReceiptToExpense(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "REVIEW_APPROVAL",
            source = com.yourname.expensetracker.domain.transaction.ExpenseSource.REVIEW_APPROVAL.name,
            matchStatus = MatchStatus.MANUALLY_MATCHED,
            requireUnmatchedClaim = true
        )

    private suspend fun autoLink(receiptId: Long, expenseId: Long, confidence: Float) =
        receiptLinkService.linkReceiptToExpense(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "AUTO_MATCH",
            source = "ReceiptMatchingViewModel",
            confidence = confidence,
            requireUnmatchedClaim = true
        )

    private fun suggested(id: Long, expenseId: Long) = scannedReceipt(
        id, "Shop", 10.0, suggestedExpenseId = expenseId, matchConfidence = 0.8f, matchStatus = MatchStatus.SUGGESTED
    )

    private fun assertBoundedError(error: String?) {
        assertTrue("error must be set", error != null)
        assertFalse("raw exception text leaked: $error", error!!.contains("SENTINEL"))
    }

    @Test
    fun `INV_P032 approve passes the claim flag and succeeds without error`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } returns Result.success(link(20L, 300L, "REVIEW_APPROVAL", 0.8f))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 1) { approveLink(20L, 300L) }
    }

    @Test
    fun `INV_P032 approve of an already claimed receipt gives a bounded failure and no overwrite`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException(20L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()

        assertEquals("Failed to approve match. Please try again.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 1) { approveLink(20L, 300L) }
        coVerify(exactly = 0) { matchService.rejectAllSuggestions(any()) }
        coVerify(exactly = 0) { matchService.clearMatchForReceipt(any()) }
        coVerify(exactly = 0) { matchService.saveMatchSuggestion(any(), any(), any()) }
    }

    @Test
    fun `INV_P032 approve failure and thrown exception never expose raw exception text`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } returns Result.failure(IllegalStateException(rawSentinel))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()
        assertBoundedError(viewModel.state.value.error)

        viewModel.clearError()
        coEvery { approveLink(20L, 300L) } throws RuntimeException(rawSentinel)
        viewModel.approveSuggestion(20L)
        advanceUntilIdle()
        assertBoundedError(viewModel.state.value.error)
    }

    @Test
    fun `INV_P032 approve rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 auto-match passes the claim flag and a claimed receipt is not counted`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(31L, "Shell", 45.0)
        val tx = expense(901L, "SHELL", 45.0)
        coEvery { receiptRepository.getUnmatchedReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.AutoMatch(tx, 0.98)
        coEvery { autoLink(31L, 901L, 0.98f) } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException(31L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runAutoMatching()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.autoMatchedCount)
        assertFalse(viewModel.state.value.isAutoMatching)
        coVerify(exactly = 1) { autoLink(31L, 901L, 0.98f) }
        coVerify(exactly = 0) { matchService.rejectAllSuggestions(any()) }
        coVerify(exactly = 0) { matchService.clearMatchForReceipt(any()) }
    }

    @Test
    fun `INV_P032 auto-match failure never exposes raw exception text`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getUnmatchedReceipts() } returns emptyList() andThenThrows RuntimeException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runAutoMatching()
        advanceUntilIdle()

        assertBoundedError(viewModel.state.value.error)
        assertFalse(viewModel.state.value.isAutoMatching)
    }

    @Test
    fun `INV_P032 auto-match rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getUnmatchedReceipts() } returns emptyList() andThenThrows
            kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runAutoMatching()
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
    }

    @Test
    fun `INV_P032 rerun passes the claim flag`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val tx = expense(940L, "SHELL", 45.0)
        coEvery { receiptRepository.getReceiptById(40L) } returns receipt
        coEvery { matchService.clearMatchForReceipt(40L) } returns Unit
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.AutoMatch(tx, 0.97)
        coEvery { autoLink(40L, 940L, 0.97f) } returns Result.success(link(40L, 940L, "AUTO_MATCH", 0.97f))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 1) { autoLink(40L, 940L, 0.97f) }
    }

    @Test
    fun `INV_P032 rerun of a claimed receipt gives a bounded failure and no overwrite`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val tx = expense(940L, "SHELL", 45.0)
        coEvery { receiptRepository.getReceiptById(40L) } returns receipt
        coEvery { matchService.clearMatchForReceipt(40L) } returns Unit
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.AutoMatch(tx, 0.97)
        coEvery { autoLink(40L, 940L, 0.97f) } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException(40L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertEquals("Rerun link failed. Please try again.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 0) { matchService.saveMatchSuggestion(any(), any(), any()) }
        coVerify(exactly = 0) { matchService.rejectAllSuggestions(any()) }
    }

    @Test
    fun `INV_P032 rerun failure never exposes raw exception text`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        coEvery { receiptRepository.getReceiptById(40L) } returns receipt
        coEvery { matchService.clearMatchForReceipt(40L) } returns Unit
        coEvery { matcher.findBestMatch(receipt) } throws RuntimeException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertBoundedError(viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 rerun rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        coEvery { receiptRepository.getReceiptById(40L) } returns receipt
        coEvery { matchService.clearMatchForReceipt(40L) } returns Unit
        coEvery { matcher.findBestMatch(receipt) } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 reject rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        coEvery { matchService.rejectAllSuggestions(50L) } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rejectSuggestion(50L)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 manual match rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 51L,
                expenseId = 301L,
                linkType = "MANUAL_MATCH",
                source = "ReceiptMatchingViewModel",
                confidence = 1.0f
            )
        } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.manualMatch(51L, 301L)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 skip rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        coEvery { matchService.rejectAllSuggestions(52L) } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.skipReceipt(52L)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    // ---- Review fixes: rerun reload rule and the expense-already-linked message ----

    private fun stubRerunSetup(receipt: ScannedReceipt, tx: Expense) {
        coEvery { receiptRepository.getReceiptById(receipt.id) } returns receipt
        coEvery { matchService.clearMatchForReceipt(receipt.id) } returns Unit
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.AutoMatch(tx, 0.97)
    }

    @Test
    fun `INV_P032 failed rerun link keeps the error visible and does not reload`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val tx = expense(940L, "SHELL", 45.0)
        stubRerunSetup(receipt, tx)
        coEvery { autoLink(40L, 940L, 0.97f) } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException(40L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertEquals("Rerun link failed. Please try again.", viewModel.state.value.error)
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }
        coVerify(exactly = 1) { receiptRepository.getReceiptsWithSuggestions() }
    }

    @Test
    fun `INV_P032 successful rerun reloads the receipts`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val tx = expense(940L, "SHELL", 45.0)
        stubRerunSetup(receipt, tx)
        coEvery { autoLink(40L, 940L, 0.97f) } returns Result.success(link(40L, 940L, "AUTO_MATCH", 0.97f))
        viewModel = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        coVerify(exactly = 2) { receiptRepository.getUnmatchedReceipts() }
        coVerify(exactly = 2) { receiptRepository.getReceiptsWithSuggestions() }
    }

    @Test
    fun `INV_P032 failed unlink sets the error and does not run the rematch`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val linked = receipt.copy(expenseId = 77L)
        coEvery { receiptRepository.getReceiptById(40L) } returns linked
        coEvery { receiptLinkService.unlinkReceiptFromExpense(40L, 77L) } returns Result.failure(IllegalStateException(rawSentinel))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertEquals("Rerun failed. Please try again.", viewModel.state.value.error)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 successful unlink proceeds to the rematch and reloads`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val linked = receipt.copy(expenseId = 77L)
        coEvery { receiptRepository.getReceiptById(40L) } returns linked
        coEvery { receiptLinkService.unlinkReceiptFromExpense(40L, 77L) } returns Result.success(Unit)
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.NoMatch
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        coVerify(exactly = 1) { matcher.findBestMatch(receipt) }
        coVerify(exactly = 2) { receiptRepository.getUnmatchedReceipts() }
    }

    @Test
    fun `INV_P032 unlink rethrows cancellation instead of mapping it to an error`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val linked = receipt.copy(expenseId = 77L)
        coEvery { receiptRepository.getReceiptById(40L) } returns linked
        coEvery { receiptLinkService.unlinkReceiptFromExpense(40L, 77L) } throws kotlinx.coroutines.CancellationException(rawSentinel)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull("cancellation must not become a user-visible error", viewModel.state.value.error)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `INV_P032 an earlier rerun error does not block the reload of a later successful rerun`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(40L, "Shell", 45.0)
        val tx = expense(940L, "SHELL", 45.0)
        stubRerunSetup(receipt, tx)
        coEvery { autoLink(40L, 940L, 0.97f) } returnsMany listOf(
            Result.failure(com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException(40L)),
            Result.success(link(40L, 940L, "AUTO_MATCH", 0.97f))
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()
        assertEquals("Rerun link failed. Please try again.", viewModel.state.value.error)
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }

        viewModel.rerunForReceipt(receipt)
        advanceUntilIdle()

        assertNull("a later successful rerun must clear the earlier error", viewModel.state.value.error)
        coVerify(exactly = 2) { receiptRepository.getUnmatchedReceipts() }
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
    }

    @Test
    fun `approveSuggestion shows the expense already has a receipt text for ExpenseAlreadyLinkedException`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ExpenseAlreadyLinkedException(300L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()

        assertEquals("This expense already has a receipt.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 1) { approveLink(20L, 300L) }
        coVerify(exactly = 0) { matchService.rejectAllSuggestions(any()) }
        coVerify(exactly = 0) { matchService.clearMatchForReceipt(any()) }
    }

    @Test
    fun `approveSuggestion keeps its existing text for other failures`() = runTest(testDispatcher) {
        coEvery { receiptRepository.getReceiptById(20L) } returns suggested(20L, 300L)
        coEvery { approveLink(20L, 300L) } returns Result.failure(IllegalStateException(rawSentinel))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approveSuggestion(20L)
        advanceUntilIdle()

        assertEquals("Failed to approve match. Please try again.", viewModel.state.value.error)
    }

    @Test
    fun `manualMatch shows the expense already has a receipt text for ExpenseAlreadyLinkedException`() = runTest(testDispatcher) {
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 51L,
                expenseId = 301L,
                linkType = "MANUAL_MATCH",
                source = "ReceiptMatchingViewModel",
                confidence = 1.0f
            )
        } returns Result.failure(
            com.yourname.expensetracker.domain.receipt.lifecycle.ExpenseAlreadyLinkedException(301L)
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.manualMatch(51L, 301L)
        advanceUntilIdle()

        assertEquals("This expense already has a receipt.", viewModel.state.value.error)
        assertTrue(viewModel.state.value.mutatingReceiptIds.isEmpty())
        coVerify(exactly = 1) { receiptRepository.getUnmatchedReceipts() }
    }

    @Test
    fun `manualMatch keeps its existing text for other failures`() = runTest(testDispatcher) {
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 51L,
                expenseId = 301L,
                linkType = "MANUAL_MATCH",
                source = "ReceiptMatchingViewModel",
                confidence = 1.0f
            )
        } returns Result.failure(IllegalStateException(rawSentinel))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.manualMatch(51L, 301L)
        advanceUntilIdle()

        assertEquals("Failed to link receipt. Please try again.", viewModel.state.value.error)
    }

    @org.junit.After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                viewModels.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            }
        } finally { super.tearDown() }
    }

    private fun createViewModel() = ReceiptMatchingViewModel(
        receiptRepository, matcher, receiptLinkService, matchService
    ).also { viewModels += it }

    private fun link(receiptId: Long, expenseId: Long, type: String, confidence: Float) = ReceiptExpenseLink(
        receiptId = receiptId, expenseId = expenseId, linkType = type, confidence = confidence,
        source = "ReceiptMatchingViewModel", createdAt = 1_700_000_000_000L, createdBy = null
    )

    private fun scannedReceipt(
        id: Long,
        merchant: String,
        total: Double,
        suggestedExpenseId: Long? = null,
        matchConfidence: Float? = null,
        matchStatus: MatchStatus = MatchStatus.UNMATCHED
    ) = ScannedReceipt(
        id = id,
        imagePath = "path/$id.jpg",
        rawOcrText = "receipt-$id",
        parsedTotal = total,
        parsedMerchant = merchant,
        parsedDate = 1_700_000_000_000L,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 0.9f,
        expenseId = null,
        suggestedExpenseId = suggestedExpenseId,
        matchConfidence = matchConfidence,
        matchStatus = matchStatus,
        createdAt = 1_700_000_000_000L
    )

    private fun expense(
        id: Long,
        merchant: String,
        amount: Double
    ) = Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L
    )
}
