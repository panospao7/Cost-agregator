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
            receiptLinkService.linkReceiptToExpense(31L, 901L, "AUTO_MATCH", "ReceiptMatchingViewModel", confidence = 0.98f)
        } returns Result.success(link(31L, 901L, "AUTO_MATCH", 0.98f))
        coEvery { matchService.saveMatchSuggestion(32L, 902L, 0.86) } returns Unit
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
            receiptLinkService.linkReceiptToExpense(31L, 901L, "AUTO_MATCH", "ReceiptMatchingViewModel", confidence = 0.98f)
        }
        coVerify(exactly = 1) { matchService.saveMatchSuggestion(32L, 902L, 0.86) }
        coVerify(exactly = 0) { receiptRepository.linkReceiptToExpense(any(), any(), any()) }
        coVerify(exactly = 0) { receiptRepository.saveMatchSuggestion(any(), any(), any()) }
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