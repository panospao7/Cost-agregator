package com.yourname.expensetracker.domain.usecase.receipt

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.CategorizationResult
import com.yourname.expensetracker.domain.categorization.MatchType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * P3-PR1 (P3-P1-07): Verifies ProcessReceiptUseCase passes actual home currency
 * to ReceiptParser.parse() instead of relying on the hardcoded "EUR" default.
 */
class ProcessReceiptUseCaseHomeCurrencyTest {

    private val ocrService = mockk<ReceiptOcrService>(relaxed = true)
    private val receiptParser = mockk<ReceiptParser>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val userCurrencyProvider = mockk<UserCurrencyProvider>()
    private val useCase = ProcessReceiptUseCase(
        ocrService, receiptParser, merchantNormalizer, categorizationEngine, userCurrencyProvider
    )

    @Before
    fun setup() {
        coEvery { userCurrencyProvider.getHomeCurrency() } returns "USD"
        every { receiptParser.parse(any(), homeCurrency = any()) } returns
            ReceiptParser.ParsedReceipt("Market", null, null, null, null, "", emptyList(), 0f)
        coEvery { categorizationEngine.categorize(any()) } returns
            CategorizationResult(1L, "Food", MatchType.EXACT)
    }

    @Test
    fun `parse uses actual home currency not EUR default`() = runTest {
        coEvery { userCurrencyProvider.getHomeCurrency() } returns "USD"
        every { receiptParser.parse(any(), homeCurrency = any()) } returns
            ReceiptParser.ParsedReceipt("Market", null, null, null, null, "", emptyList(), 0f)

        val useCase = ProcessReceiptUseCase(
            ocrService = ocrService,
            receiptParser = receiptParser,
            merchantNormalizer = merchantNormalizer,
            categorizationEngine = categorizationEngine,
            userCurrencyProvider = userCurrencyProvider
        )

        useCase(ReceiptSource.ParsedContent(rawText = "Total: 50.00"))

        // Verify parser was called with "USD" not the default "EUR"
        verify { receiptParser.parse(any(), homeCurrency = "USD") }
    }

    @Test
    fun `unresolved source money fails before merchant or category work`() = runTest {
        val result = useCase(ReceiptSource.ParsedContent(rawText = "Total: 50.00"))

        assertTrue(result.exceptionOrNull() is ReceiptMoneyUnavailableException)
        assertEquals("RECEIPT_MONEY_UNAVAILABLE", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { merchantNormalizer.normalize(any(), any(), any()) }
        coVerify(exactly = 0) { categorizationEngine.categorize(any()) }
    }

    @Test
    fun `missing home currency is passed as null without EUR fallback`() = runTest {
        coEvery { userCurrencyProvider.getHomeCurrency() } returns null

        val result = useCase(ReceiptSource.ParsedContent(rawText = "Total: 50.00"))

        assertTrue(result.isFailure)
        verify { receiptParser.parse(any(), homeCurrency = null) }
    }

    @Test
    fun `parsed source money retains its currency without home conversion`() = runTest {
        val money = MoneyAmount(50.0, CurrencyCode.GBP)
        every { receiptParser.parse(any(), homeCurrency = any()) } returns
            ReceiptParser.ParsedReceipt("Market", money, null, null, null, "GBP", emptyList(), 0.9f)

        val result = useCase(ReceiptSource.ParsedContent(rawText = "Total: 50.00 GBP")).getOrThrow()

        assertEquals(money, result.amount)
    }

    @Test
    fun `explicit typed manual money works without OCR currency`() = runTest {
        val money = MoneyAmount(20.0, CurrencyCode.CHF)

        val result = useCase(ReceiptSource.ParsedContent(rawText = "", amount = money)).getOrThrow()

        assertEquals(money, result.amount)
    }

    @Test
    fun `unsupported manual currency fails closed`() = runTest {
        val result = useCase(ReceiptSource.ParsedContent(
            rawText = "", amount = MoneyAmount(20.0, CurrencyCode("ZZZ"))
        ))

        assertTrue(result.exceptionOrNull() is ReceiptMoneyUnavailableException)
    }

    @Test
    fun `dependency cancellation propagates rather than becoming Result failure`() = runTest {
        every { receiptParser.parse(any(), homeCurrency = any()) } throws CancellationException("cancelled")

        val failure = assertFailsWith<CancellationException> {
            useCase(ReceiptSource.ParsedContent(rawText = ""))
        }

        assertEquals("cancelled", failure.message)
        coVerify(exactly = 0) { categorizationEngine.categorize(any()) }
    }
}
