package com.yourname.expensetracker.domain.receiptmatching

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTransactionMatcherTest {

    private val expenseRepository = mockk<ExpenseRepository>()
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)

    // Strict mock: nothing is owned unless a test says so; unexpected reads fail loudly.
    private val receiptLinkService = mockk<ReceiptLinkService>().also {
        coEvery { it.isExpenseLinkedToOtherReceipt(any(), any()) } returns false
    }
    private val matcher = ReceiptTransactionMatcher(
        expenseRepository = expenseRepository,
        merchantNormalizer = merchantNormalizer,
        stringDistance = StringDistanceUtils,
        timeProvider = mockk(relaxed = true),
        receiptLinkService = receiptLinkService,
        currencyConverter = mockk(relaxed = true),
    )

    @Test
    fun `findBestMatch ignores non purchase compatible positive transactions`() = runTest {
        val receipt = sampleReceipt(parsedMerchant = "Store", parsedTotal = 12.34)
        val deposit = sampleExpense(
            id = 99L,
            merchant = "Store",
            amount = 12.34,
            transactionType = TransactionType.DEPOSIT,
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(deposit)

        val result = matcher.findBestMatch(receipt)

        assertTrue(result is MatchResult.NoMatch)
    }

    @Test
    fun `findBestMatch keeps greek merchants comparable after normalization`() = runTest {
        val receipt = sampleReceipt(
            parsedMerchant = "Σκλαβενίτης",
            parsedTotal = 24.50,
            parsedDate = 1_700_000_000_000L
        )
        val purchase = sampleExpense(
            id = 42L,
            merchant = "ΣΚΛΑΒΕΝΙΤΗΣ",
            amount = 24.50,
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(purchase)

        val result = matcher.findBestMatch(receipt)

        assertTrue(result is MatchResult.AutoMatch)
    }

    // ---- Expense ownership filter (P-03-1) -------------------------------------------------
    //
    // Hand-derived scores for receipt "Store" 12.34 EUR dated T (weights 0.35 amount, 0.40
    // merchant, 0.20 date within 48h, 0.05 type). A candidate with identical amount/merchant
    // scores 0.80 + 0.20 * (1 - hours/48):
    //   0h -> 1.0 (AutoMatch)   2h -> 0.991667 (AutoMatch)
    //   24h -> 0.90 (Suggested) 36h -> 0.85 (Suggested)
    // A candidate with another merchant and amount 100.0 scores about 0.29 (below 0.80).

    private val receiptTime = 1_700_000_000_000L

    private fun candidate(id: Long, hoursFromReceipt: Long = 0L) = sampleExpense(
        id = id,
        merchant = "Store",
        amount = 12.34,
        transactionType = TransactionType.PURCHASE,
        date = receiptTime + hoursFromReceipt * 3_600_000L
    )

    private fun lowScoreCandidate(id: Long) = sampleExpense(
        id = id,
        merchant = "Qqqqq",
        amount = 100.0,
        transactionType = TransactionType.PURCHASE
    )

    private fun owned(vararg expenseIds: Long) {
        expenseIds.forEach { id ->
            coEvery { receiptLinkService.isExpenseLinkedToOtherReceipt(id, 1L) } returns true
        }
    }

    private fun candidatesAre(vararg expenses: Expense) {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses.toList()
    }

    private val receipt: ScannedReceipt
        get() = sampleReceipt(parsedMerchant = "Store", parsedTotal = 12.34, parsedDate = receiptTime)

    @Test
    fun `INV_P031 control - unowned top AutoMatch candidate wins and later candidates are not read`() = runTest {
        candidatesAre(candidate(1L, 0), candidate(2L, 2))

        val result = matcher.findBestMatch(receipt)

        assertTrue(result is MatchResult.AutoMatch)
        result as MatchResult.AutoMatch
        assertEquals(1L, result.transaction.id)
        assertEquals(1.0, result.score, 1e-6)
        coVerify(exactly = 1) { receiptLinkService.isExpenseLinkedToOtherReceipt(1L, 1L) }
        coVerify(exactly = 0) { receiptLinkService.isExpenseLinkedToOtherReceipt(2L, any()) }
    }

    @Test
    fun `INV_P031 owned top AutoMatch candidate lets the next AutoMatch candidate win`() = runTest {
        candidatesAre(candidate(1L, 0), candidate(2L, 2))
        owned(1L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected AutoMatch but was $result", result is MatchResult.AutoMatch)
        result as MatchResult.AutoMatch
        assertEquals(2L, result.transaction.id)
        assertEquals(0.991667, result.score, 1e-5)
    }

    @Test
    fun `INV_P031 owned top AutoMatch candidate lets the next Suggested candidate win with its own type`() = runTest {
        candidatesAre(candidate(1L, 0), candidate(2L, 24))
        owned(1L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected Suggested but was $result", result is MatchResult.Suggested)
        result as MatchResult.Suggested
        assertEquals(2L, result.transaction.id)
        assertEquals(0.90, result.score, 1e-6)
    }

    @Test
    fun `INV_P031 owned top AutoMatch candidate with only a low scoring next candidate gives NoMatch`() = runTest {
        candidatesAre(candidate(1L, 0), lowScoreCandidate(3L))
        owned(1L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected NoMatch but was $result", result is MatchResult.NoMatch)
        coVerify(exactly = 0) { receiptLinkService.isExpenseLinkedToOtherReceipt(3L, any()) }
    }

    @Test
    fun `INV_P031 owned top Suggested candidate lets the next Suggested candidate win and never suggests the owned expense`() = runTest {
        candidatesAre(candidate(1L, 24), candidate(2L, 36))
        owned(1L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected Suggested but was $result", result is MatchResult.Suggested)
        result as MatchResult.Suggested
        assertEquals(2L, result.transaction.id)
        assertEquals(0.85, result.score, 1e-6)
    }

    @Test
    fun `INV_P031 owned top Suggested candidate with only a low scoring next candidate gives NoMatch`() = runTest {
        candidatesAre(candidate(1L, 24), lowScoreCandidate(3L))
        owned(1L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected NoMatch but was $result", result is MatchResult.NoMatch)
        coVerify(exactly = 0) { receiptLinkService.isExpenseLinkedToOtherReceipt(3L, any()) }
    }

    @Test
    fun `INV_P031 every candidate at or above 0_80 owned gives NoMatch`() = runTest {
        candidatesAre(
            candidate(1L, 0),
            candidate(2L, 2),
            candidate(3L, 24),
            candidate(4L, 36),
            lowScoreCandidate(5L)
        )
        owned(1L, 2L, 3L, 4L)

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected NoMatch but was $result", result is MatchResult.NoMatch)
        coVerify(exactly = 0) { receiptLinkService.isExpenseLinkedToOtherReceipt(5L, any()) }
    }

    @Test
    fun `INV_P031 candidates below 0_80 trigger no ownership read`() = runTest {
        candidatesAre(lowScoreCandidate(7L), lowScoreCandidate(8L))

        val result = matcher.findBestMatch(receipt)

        assertTrue("expected NoMatch but was $result", result is MatchResult.NoMatch)
        coVerify(exactly = 0) { receiptLinkService.isExpenseLinkedToOtherReceipt(any(), any()) }
    }

    @Test
    fun `INV_P031 ownership is read for the receipt being matched`() = runTest {
        candidatesAre(candidate(10L, 0))
        owned(10L)

        val result = matcher.findBestMatch(receipt)

        assertTrue(result is MatchResult.NoMatch)
        coVerify(exactly = 1) { receiptLinkService.isExpenseLinkedToOtherReceipt(10L, 1L) }
    }

    @Test
    fun `INV_P031 equal scores keep candidate order and skip an owned first candidate`() = runTest {
        candidatesAre(candidate(10L, 0), candidate(11L, 0))
        val firstWins = matcher.findBestMatch(receipt)
        assertTrue(firstWins is MatchResult.AutoMatch)
        assertEquals(10L, (firstWins as MatchResult.AutoMatch).transaction.id)

        candidatesAre(candidate(11L, 0), candidate(10L, 0))
        val listOrderWins = matcher.findBestMatch(receipt)
        assertTrue(listOrderWins is MatchResult.AutoMatch)
        assertEquals(11L, (listOrderWins as MatchResult.AutoMatch).transaction.id)

        candidatesAre(candidate(10L, 0), candidate(11L, 0))
        owned(10L)
        val nextWins = matcher.findBestMatch(receipt)
        assertTrue("expected AutoMatch but was $nextWins", nextWins is MatchResult.AutoMatch)
        assertEquals(11L, (nextWins as MatchResult.AutoMatch).transaction.id)

        owned(11L)
        val bothOwned = matcher.findBestMatch(receipt)
        assertTrue("expected NoMatch but was $bothOwned", bothOwned is MatchResult.NoMatch)
    }

    private fun sampleReceipt(
        parsedMerchant: String?,
        parsedTotal: Double?,
        parsedDate: Long? = 1_700_000_000_000L
    ): ScannedReceipt {
        return ScannedReceipt(
            id = 1L,
            imagePath = null,
            rawOcrText = "sample",
            parsedTotal = parsedTotal,
            parsedMerchant = parsedMerchant,
            parsedDate = parsedDate,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f,
            matchStatus = MatchStatus.UNMATCHED
        )
    }

    private fun sampleExpense(
        id: Long,
        merchant: String,
        amount: Double,
        transactionType: TransactionType,
        date: Long = 1_700_000_000_000L
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = transactionType,
            date = date
        )
    }
}