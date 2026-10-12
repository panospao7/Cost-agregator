package com.yourname.expensetracker.domain.receipt

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserMoneyBoundaryTest {
    @Test
    fun `unsupported total currency cannot be overridden by a supported tax signal`() {
        val result = parser.parse("SHOP\nTOTAL 12.50 XYZ\nTAX 1.00 EUR")
        assertEquals("", result.currency)
        assertNull(result.total)
    }

    private val parser = ReceiptParser(
        MerchantRulesRepository(),
        timeProvider = FakeTimeProvider.forDate(2026, 6, 1)
    )

    @Test
    fun `explicit receipt currency overrides home currency without conversion`() {
        val parsed = parser.parse("MARKET\nTOTAL 12,50 USD", homeCurrency = "EUR")
        val aggregate = (parsed.totalAggregate as MoneyAggregateResult.Available).aggregate

        assertEquals("USD", parsed.currency)
        assertEquals(12.5, parsed.total!!.amount, 0.0)
        assertEquals(CurrencyCode.USD, aggregate.displayCurrency)
        assertEquals(12.5, aggregate.displayAmount, 0.0)
        assertEquals(RateBasis.IDENTITY, aggregate.actualRateBasis)
        assertEquals(1, aggregate.metadata.includedTransactionCount)
        assertEquals(ReceiptParser.TotalSource.OCR_TOTAL, parsed.totalSource)
    }

    @Test
    fun `line item serialization preserves explicit adjustment provenance`() {
        val items = listOf(
            ReceiptParser.LineItem("product", null, null, 100.0),
            ReceiptParser.LineItem("discount", null, null, -20.0, isAdjustment = true)
        )

        assertEquals(items, parser.lineItemsFromJson(parser.lineItemsToJson(items)))
        val legacy = parser.lineItemsFromJson(
            """[{"description":"product","totalPrice":-20.0}]"""
        )
        assertFalse(legacy.single().isAdjustment)
    }

    @Test
    fun `supported ISO source currency is recognized beyond the three legacy symbol currencies`() {
        val parsed = parser.parse("MARKET\nTOTAL 100.00 JPY", homeCurrency = "EUR")
        val aggregate = (parsed.totalAggregate as MoneyAggregateResult.Available).aggregate

        assertEquals("JPY", parsed.currency)
        assertEquals(CurrencyCode.JPY, aggregate.displayCurrency)
        assertEquals(100.0, aggregate.displayAmount, 0.0)
    }

    @Test
    fun `currency absent from OCR remains unresolved even with a valid home currency`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.50", homeCurrency = " gbp ")

        assertEquals("", parsed.currency)
        assertTrue(parsed.totalAggregate is MoneyAggregateResult.Unavailable)
        assertNull(parsed.total?.amount)
    }

    @Test
    fun `currency code substrings in merchant words leave source currency unresolved`() {
        val parsed = parser.parse("USDA MARKET\nTOTAL 12.50", homeCurrency = "GBP")

        assertEquals("", parsed.currency)
        assertNull(parsed.total?.amount)
    }

    @Test
    fun `supported currency words outside monetary context do not override receipt currency`() {
        val parsed = parser.parse("TRY OUR COFFEE\nTOTAL 12.50 EUR", homeCurrency = "GBP")

        assertEquals("EUR", parsed.currency)
        assertEquals(12.5, parsed.total!!.amount, 0.0)
    }

    @Test
    fun `currency codes attached to amounts retain their source currency`() {
        val parsed = parser.parse("MARKET\nTOTAL 100.00JPY", homeCurrency = "EUR")

        assertEquals("JPY", parsed.currency)
        assertEquals(100.0, parsed.total!!.amount, 0.0)
    }

    @Test
    fun `explicit dollar currency and qualified dollar symbols are not converted to USD`() {
        val examples = mapOf(
            "TOTAL $12.50 CAD" to "CAD", "TOTAL C$12.50" to "CAD",
            "TOTAL CA$12.50" to "CAD", "TOTAL AU$12.50" to "AUD",
            "TOTAL SG$12.50" to "SGD", "TOTAL US$12.50" to "USD"
        )
        for ((text, currency) in examples) {
            val parsed = parser.parse("MARKET\n$text", homeCurrency = "EUR")

            assertEquals(currency, parsed.currency)
            assertEquals(CurrencyCode.parse(currency), (parsed.totalAggregate as MoneyAggregateResult.Available).aggregate.displayCurrency)
            assertEquals(12.5, parsed.total!!.amount, 0.0)
        }
    }

    @Test
    fun `unsupported explicitly labeled currency does not fall back to home currency`() {
        val parsed = parser.parse("MARKET\nCURRENCY: ZZZ\nTOTAL 12.50", homeCurrency = "EUR")

        assertEquals("", parsed.currency)
        assertNull(parsed.total?.amount)
        assertEquals(ReceiptParser.TotalSource.MANUAL, parsed.totalSource)
    }

    @Test
    fun `missing or unsupported supplied currency cannot produce a receipt total`() {
        for (currency in listOf("", "ZZZ")) {
            val parsed = parser.parse("MARKET\nTOTAL 12.50", homeCurrency = currency)

            assertEquals("", parsed.currency)
            assertNull(parsed.total?.amount)
            assertEquals(
                MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                (parsed.totalAggregate as MoneyAggregateResult.Unavailable).reason
            )
        }
    }

    @Test
    fun `conflicting explicit source currencies never fall back to home currency`() {
        for (text in listOf("TOTAL 12.50 EUR\nUSD", "TOTAL 12.50 €\nJPY", "TOTAL 12.50 £\nEUR")) {
            val parsed = parser.parse("MARKET\n$text", homeCurrency = "CHF")

            assertEquals("", parsed.currency)
            assertNull(parsed.total?.amount)
            assertFalse(parsed.taxInclusive)
            assertTrue(parsed.totalAggregate is MoneyAggregateResult.Unavailable)
        }
    }

    @Test
    fun `item fallback retains receipt currency optional subtotal and total provenance`() {
        val parsed = parser.parse(
            "MARKET\nProduct123456789A   1,20\nOther123456789A   2,30\nUSD",
            homeCurrency = "EUR"
        )

        assertEquals(2, parsed.lineItems.size)
        assertEquals(3.5, parsed.total!!.amount, 0.000001)
        assertNull(parsed.subtotal?.amount)
        assertEquals(ReceiptParser.TotalSource.LINE_ITEMS, parsed.totalSource)
        val aggregate = (parsed.totalAggregate as MoneyAggregateResult.Available).aggregate
        assertEquals(CurrencyCode.USD, aggregate.displayCurrency)
        assertEquals(3.5, aggregate.displayAmount, 0.000001)
    }

    @Test
    fun `empty and malformed monetary text remain unavailable rather than zero spend`() {
        for (text in listOf("MARKET", "MARKET\nTOTAL NaN\nITEM Infinity")) {
            val parsed = parser.parse(text, homeCurrency = "EUR")

            assertNull(parsed.total?.amount)
            assertNull(parsed.subtotal?.amount)
            assertEquals("RECEIPT_TOTAL_MISSING", (parsed.totalAggregate as MoneyAggregateResult.Unavailable).reason)
        }
    }

    @Test
    fun `tax inclusion keeps the strict five percent threshold in receipt currency`() {
        assertTrue(ReceiptParser.isTaxInclusive(100.0, 5.0, items(95.01), "USD"))
        assertTrue(ReceiptParser.isTaxInclusive(100.0, 5.0, items(104.99), "USD"))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, 5.0, items(95.0), "USD"))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, 5.0, items(105.0), "USD"))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, null, items(100.0), "USD"))
    }

    @Test
    fun `tax inclusion rejects invalid currencies non finite items and overflowing totals`() {
        assertFalse(ReceiptParser.isTaxInclusive(100.0, 5.0, items(100.0), ""))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, 5.0, items(100.0), "ZZZ"))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, 5.0, items(Double.NaN), "EUR"))
        assertFalse(ReceiptParser.isTaxInclusive(100.0, Double.NaN, items(100.0), "EUR"))
        assertFalse(ReceiptParser.isTaxInclusive(Double.POSITIVE_INFINITY, 5.0, items(100.0), "EUR"))
        assertFalse(ReceiptParser.isTaxInclusive(
            Double.MAX_VALUE, 5.0, items(Double.MAX_VALUE, Double.MAX_VALUE), "EUR"
        ))
    }

    @Test
    fun `parser tax inclusion and subtotal retain existing receipt semantics`() {
        val parsed = parser.parse("MARKET\nCoffee   40.00\nBread   56.00\nVAT 1.00\nTOTAL 100.00 EUR")

        assertTrue(parsed.taxInclusive)
        assertEquals(1.0, parsed.tax!!.amount, 0.0)
        assertEquals(99.0, parsed.subtotal!!.amount, 0.0)
    }

    @Test
    fun `confidence cross validation keeps the strict ten percent threshold`() {
        val atBoundary = parser.parse("MARKET\nCoffee   40.00\nBread   50.00\nTOTAL 100.00 EUR")
        val withinBoundary = parser.parse("MARKET\nCoffee   40.00\nBread   50.01\nTOTAL 100.00 EUR")

        assertEquals(2, atBoundary.lineItems.size)
        assertEquals(2, withinBoundary.lineItems.size)
        assertEquals(atBoundary.confidence + 0.10f, withinBoundary.confidence, 0.00001f)
    }

    @Test
    fun `typed total copy preserves currency and rejects relabeling`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.50 EUR")
        val edited = parsed.copy(
            total = requireNotNull(ReceiptParser.sourceMoneyOrNull(20.0, "GBP")),
            currency = "GBP"
        )
        val aggregate = (edited.totalAggregate as MoneyAggregateResult.Available).aggregate

        assertEquals(20.0, aggregate.displayAmount, 0.0)
        assertEquals(CurrencyCode.GBP, aggregate.displayCurrency)
        assertEquals("RECEIPT_TOTAL_MISSING", (edited.copy(total = null).totalAggregate as MoneyAggregateResult.Unavailable).reason)
        assertThrows(IllegalArgumentException::class.java) { parsed.copy(currency = "GBP") }
        assertNull(ReceiptParser.sourceMoneyOrNull(Double.NaN, "GBP"))
    }

    @Test
    fun `serialized receipt stores the typed total`() {
        val parsed = parser.parse("MARKET\nTOTAL 12.50 EUR")
        val json = JsonParser.parseString(Gson().toJson(parsed)).asJsonObject

        assertEquals(12.5, json.getAsJsonObject("total").get("amount").asDouble, 0.0)
        assertEquals("EUR", json.get("currency").asString)
        assertFalse(json.has("totalAggregate"))
    }

    @Test
    fun `line item only unresolved receipt retains review evidence without money`() {
        val parsed = parser.parse("MARKET\nProduct123456789A   1,20\nOther123456789A   2,30", homeCurrency = "USD")

        assertEquals(2, parsed.lineItems.size)
        assertNull(parsed.total)
        assertTrue(parsed.hasUnresolvedTotalEvidence)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
            (parsed.totalAggregate as MoneyAggregateResult.Unavailable).reason
        )
    }

    @Test
    fun `source money factory rejects unsupported missing and nonfinite values`() {
        assertNull(ReceiptParser.sourceMoneyOrNull(12.0, ""))
        assertNull(ReceiptParser.sourceMoneyOrNull(12.0, "ZZZ"))
        assertNull(ReceiptParser.sourceMoneyOrNull(null, "EUR"))
        assertNull(ReceiptParser.sourceMoneyOrNull(Double.POSITIVE_INFINITY, "EUR"))
        assertEquals(CurrencyCode.JPY, requireNotNull(ReceiptParser.sourceMoneyOrNull(12.0, "JPY")).currency)
    }

    private fun items(vararg amounts: Double): List<ReceiptParser.LineItem> = amounts.mapIndexed { index, amount ->
        ReceiptParser.LineItem("Item $index", null, null, amount, itemIndex = index)
    }
}
