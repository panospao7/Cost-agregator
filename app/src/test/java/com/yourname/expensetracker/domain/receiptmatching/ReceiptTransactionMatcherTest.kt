package com.yourname.expensetracker.domain.receiptmatching

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTransactionMatcherTest {

    private val expenseRepository = mockk<ExpenseRepository>()
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val matcher = ReceiptTransactionMatcher(
        expenseRepository = expenseRepository,
        merchantNormalizer = merchantNormalizer,
        stringDistance = StringDistanceUtils,
        timeProvider = mockk(relaxed = true),
        receiptLinkService = mockk(),
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