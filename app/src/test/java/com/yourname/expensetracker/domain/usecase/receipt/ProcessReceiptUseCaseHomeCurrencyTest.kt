package com.yourname.expensetracker.domain.usecase.receipt

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

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

    @Test
    fun `parse uses actual home currency not EUR default`() = runTest {
        coEvery { userCurrencyProvider.getHomeCurrency() } returns "USD"
        every { receiptParser.parse(any(), homeCurrency = any()) } returns mockk(relaxed = true)

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
}
