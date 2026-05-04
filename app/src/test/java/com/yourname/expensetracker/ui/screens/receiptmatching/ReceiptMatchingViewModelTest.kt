package com.yourname.expensetracker.ui.screens.receiptmatching

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptMatchingViewModelTest : ViewModelTestUtils() {

    private val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
    private val matcher = mockk<ReceiptTransactionMatcher>(relaxed = true)

    private lateinit var viewModel: ReceiptMatchingViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { receiptRepository.getUnmatchedReceipts() } returns emptyList()
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns emptyList()
        viewModel = ReceiptMatchingViewModel(receiptRepository, matcher, receiptLinkService = mockk())
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

        viewModel = ReceiptMatchingViewModel(receiptRepository, matcher, receiptLinkService = mockk())
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
        val receipt = scannedReceipt(id = 10L, merchant = "My Store", total = 20.0)
        val expense = expense(id = 200L, merchant = "My Store", amount = 20.0)

        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(
            listOf(receipt),
            emptyList()
        )
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns emptyList()

        viewModel = ReceiptMatchingViewModel(receiptRepository, matcher, receiptLinkService = mockk())
        advanceUntilIdle()

        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(1, initial.unmatchedReceipts.size)

            viewModel.manualMatch(receiptId = receipt.id, expenseId = expense.id)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val updated = awaitItem()
            assertFalse(updated.isLoading)
            assertTrue(updated.unmatchedReceipts.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { receiptRepository.linkReceiptToExpense(10L, 200L, 1.0) }
    }

    @Test
    fun `skip receipt`() = runTest(testDispatcher) {
        val receipt = scannedReceipt(id = 15L, merchant = "Unknown", total = 5.0)

        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(
            listOf(receipt),
            emptyList()
        )
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns emptyList()

        viewModel = ReceiptMatchingViewModel(receiptRepository, matcher, receiptLinkService = mockk())
        advanceUntilIdle()

        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(1, initial.unmatchedReceipts.size)

            viewModel.skipReceipt(receipt.id)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val updated = awaitItem()
            assertFalse(updated.isLoading)
            assertTrue(updated.unmatchedReceipts.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { receiptRepository.rejectAllSuggestions(15L) }
    }

    @Test
    fun `batch match all`() = runTest(testDispatcher) {
        val receiptA = scannedReceipt(id = 31L, merchant = "Shell", total = 45.0)
        val receiptB = scannedReceipt(id = 32L, merchant = "Gym", total = 25.0)
        val txA = expense(id = 901L, merchant = "SHELL", amount = 45.0)
        val txB = expense(id = 902L, merchant = "Gym", amount = 25.0)

        coEvery { receiptRepository.getUnmatchedReceipts() } returnsMany listOf(
            listOf(receiptA, receiptB),
            listOf(receiptA, receiptB),
            emptyList()
        )
        coEvery { receiptRepository.getReceiptsWithSuggestions() } returns emptyList()
        coEvery { matcher.findBestMatch(receiptA) } returns MatchResult.AutoMatch(txA, 0.98)
        coEvery { matcher.findBestMatch(receiptB) } returns MatchResult.Suggested(txB, 0.86)

        viewModel = ReceiptMatchingViewModel(receiptRepository, matcher, receiptLinkService = mockk())
        advanceUntilIdle()

        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(2, initial.unmatchedReceipts.size)

            viewModel.runAutoMatching()
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val intermediate = awaitItem()
            assertFalse(intermediate.isLoading)
            assertEquals(1, intermediate.autoMatchedCount)

            // loadReceipts() inside runAutoMatching launches a new coroutine
            // that first sets isLoading=true, then fetches and sets final state
            val reloading = awaitItem()
            assertTrue(reloading.isLoading)

            val refreshed = awaitItem()
            assertTrue(refreshed.unmatchedReceipts.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { receiptRepository.linkReceiptToExpense(31L, 901L, 0.98) }
        coVerify(exactly = 1) { receiptRepository.saveMatchSuggestion(32L, 902L, 0.86) }
    }

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