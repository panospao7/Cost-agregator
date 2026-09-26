package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.CurrencyResolution
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import com.yourname.expensetracker.domain.notification.money.NotificationMoneySignalDetector
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessingPipelineOversizedAmountTest {

    @Test
    fun `routes oversized transaction-like notification to review candidate`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Paid 1,200,000.00 EUR at ACME Stores",
            text = "Card transaction approved",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNotNull(candidate)
        assertTrue(candidate!!.amount > 1_000_000.0)
        assertEquals("EUR", candidate.currency)
        assertEquals(CurrencyResolution.EXPLICIT_ISO_CODE, candidate.currencyResolution)
    }

    @Test
    fun `ignores oversized number without transaction and currency context`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Order id 1200000 updated",
            text = "System notification",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )
        assertNull(candidate)
    }

    @Test
    fun `does not route normal high but valid amounts`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Paid EUR 999,999.99 at Store",
            text = "Card transaction",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )
        assertNull(candidate)
    }

    @Test
    fun `detectTransactionSignalCandidate returns candidate for normal transaction-like text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Payment €4.08",
            text = "Transaction completed",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNotNull(candidate)
        assertEquals(4.08, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
        assertEquals(CurrencyResolution.EXPLICIT_ISO_CODE, candidate.currencyResolution)
    }

    @Test
    fun `detectTransactionSignalCandidate returns null for non-transaction text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Hello",
            text = "World",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNull(candidate)
    }

    @Test
    fun `detectTransactionSignalCandidate prefers currency-attached amount over bare numbers`() {
        // Text contains both a bare number (1234 from a masked PAN) and a
        // currency-attached transaction amount (€4.08). The detector should
        // select 4.08, not 1234.
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Card *1234",
            text = "Payment €4.08 completed",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNotNull(candidate)
        assertEquals(4.08, candidate!!.amount, 0.0001)
    }

    @Test
    fun `detectTransactionSignalCandidate picks amount near transaction keyword`() {
        // Two decimal amounts in the text; the one closer to a transaction
        // keyword should win (€4.08 near "payment" vs 12.34 with no context).
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Balance 12.34€",
            text = "Payment €4.08 at store",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNotNull(candidate)
        // €4.08 is near "Payment" keyword → higher score → should be selected
        assertEquals(4.08, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `detectTransactionSignalCandidate handles suffix currency and PAN tail`() {
        // Text with a masked PAN (*1234) and a transaction amount with suffix
        // currency. The PAN fragment should be penalised, the real amount
        // with suffix currency ("4 EUR") should win.
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Card *1234",
            text = "Payment 4 EUR completed",
            bigText = null,
            resolvedCurrency = knownEurCurrency()
        )

        assertNotNull(candidate)
        assertEquals(4.0, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `normal and oversized candidates retain unresolved currency provenance`() {
        val unresolvedCurrency = NotificationProcessingPipeline.ResolvedNotificationCurrency(
            code = null,
            resolution = CurrencyResolution.AMBIGUOUS_UNRESOLVED,
        )

        val normal = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Payment 42 kr",
            text = "Card purchase completed",
            bigText = null,
            resolvedCurrency = unresolvedCurrency,
        )
        val oversized = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Paid 1,200,000.00 kr at ACME Stores",
            text = "Card transaction approved",
            bigText = null,
            resolvedCurrency = unresolvedCurrency,
        )

        assertNotNull(normal)
        assertNull(normal!!.currency)
        assertEquals(CurrencyResolution.AMBIGUOUS_UNRESOLVED, normal.currencyResolution)
        assertNotNull(oversized)
        assertNull(oversized!!.currency)
        assertEquals(CurrencyResolution.AMBIGUOUS_UNRESOLVED, oversized.currencyResolution)
    }

    @Test
    fun `real detector word fragments do not override pipeline dollar home resolution`() = runTest {
        val detector = NotificationMoneySignalDetector(
            userCurrencyProvider = mockk<UserCurrencyProvider>(relaxed = true)
        )
        val text = "Payment $42 from account at ACME"
        val signal = detector.bestTransactionAmount(text, "CAD")!!

        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = text,
            text = null,
            bigText = null,
            resolvedCurrency = NotificationProcessingPipeline.ResolvedNotificationCurrency(
                code = signal.currencyCode,
                resolution = signal.resolution,
            ),
        )

        assertNotNull(candidate)
        assertEquals(42.0, candidate!!.amount, 0.0)
        assertEquals("CAD", candidate.currency)
        assertEquals(
            CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME,
            candidate.currencyResolution,
        )
    }

    private fun knownEurCurrency() = NotificationProcessingPipeline.ResolvedNotificationCurrency(
        code = "EUR",
        resolution = CurrencyResolution.EXPLICIT_ISO_CODE
    )
}
