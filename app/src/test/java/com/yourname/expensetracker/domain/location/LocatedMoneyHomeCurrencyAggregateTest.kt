package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.testCurrencyConverter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S19-4: located expenses are already normalized, so aggregation never converts.
 * Home-currency rows sum with IDENTITY; any other currency (or a row without a
 * usable normalized amount) is excluded and the aggregate is marked partial.
 */
class LocatedMoneyHomeCurrencyAggregateTest {

    private val engineConverter = testCurrencyConverter()
    private val dayMs = 24L * 60 * 60 * 1000

    // ── toHomeCurrencyAggregate (direct) ─────────────────────────────────

    @Test
    fun `S19-4 home-currency rows keep their sum with IDENTITY rate basis`() {
        val rows = listOf(row(10.0), row(20.5), row(0.25))

        val aggregate = rows.toHomeCurrencyAggregate("EUR")

        assertEquals(30.75, aggregate.displayAmount, 1e-9)
        assertEquals(CurrencyCode.EUR, aggregate.displayCurrency)
        assertEquals(RateBasis.IDENTITY, aggregate.rateBasis)
        assertEquals(RateBasis.IDENTITY, aggregate.actualRateBasis)
        assertFalse(aggregate.isPartial)
        assertEquals(ConversionQuality.COMPLETE, aggregate.conversionQuality)
        assertEquals(3, aggregate.metadata.includedTransactionCount)
        assertEquals(0, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `converted upstream row labels the aggregate with the upstream rate basis`() {
        val rows = listOf(
            row(10.0),
            row(20.5),
            row(0.25, status = ConversionStatus.CONVERTED, rateBasis = RateBasis.TRANSACTION_DATE)
        )

        val aggregate = rows.toHomeCurrencyAggregate("EUR")

        assertEquals(30.75, aggregate.displayAmount, 1e-9)
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.rateBasis)
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.actualRateBasis)
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.requestedRateBasis)
        assertFalse(aggregate.isPartial)
    }

    @Test
    fun `additional excluded rows make the aggregate partial`() {
        val aggregate = listOf(row(10.0)).toHomeCurrencyAggregate("EUR", additionalExcludedCount = 2)

        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.metadata.includedTransactionCount)
        assertEquals(2, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `S19-4 non-home row is excluded and the aggregate is partial`() {
        val rows = listOf(row(10.0), row(20.0), row(500.0, currency = "USD"))

        val aggregate = rows.toHomeCurrencyAggregate("EUR")

        assertEquals(30.0, aggregate.displayAmount, 1e-9)
        assertEquals(CurrencyCode.EUR, aggregate.displayCurrency)
        assertTrue(aggregate.isPartial)
        assertEquals(ConversionQuality.PARTIAL, aggregate.conversionQuality)
        assertEquals(RateBasis.IDENTITY, aggregate.rateBasis)
        assertEquals(2, aggregate.metadata.includedTransactionCount)
        assertEquals(1, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `S19-4 FAILED and null or non-finite normalized amounts are excluded`() {
        val rows = listOf(
            row(10.0),
            row(99.0, status = ConversionStatus.FAILED),
            row(99.0, normalizedAmount = null),
            row(99.0, normalizedAmount = Double.NaN)
        )

        val aggregate = rows.toHomeCurrencyAggregate("EUR")

        assertEquals(10.0, aggregate.displayAmount, 1e-9)
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.metadata.includedTransactionCount)
        assertEquals(3, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `S19-4 all rows excluded reports unavailable partial zero with exclusion count`() {
        val rows = listOf(row(50.0, currency = "USD"), row(70.0, currency = "GBP"))

        val aggregate = rows.toHomeCurrencyAggregate("EUR")

        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(CurrencyCode.EUR, aggregate.displayCurrency)
        assertTrue(aggregate.isPartial)
        assertEquals(ConversionQuality.UNAVAILABLE, aggregate.conversionQuality)
        assertEquals(RateBasis.IDENTITY, aggregate.rateBasis)
        assertNotNull(aggregate.warningMessage)
        assertEquals(2, aggregate.metadata.excludedTransactionCount)
        assertEquals(0, aggregate.metadata.includedTransactionCount)
    }

    @Test
    fun `S19-4 non-EUR home currency selects rows in that currency only`() {
        val rows = listOf(row(10.0, currency = "USD"), row(5.0, currency = "EUR"))

        val aggregate = rows.toHomeCurrencyAggregate("USD")

        assertEquals(10.0, aggregate.displayAmount, 1e-9)
        assertEquals(CurrencyCode.USD, aggregate.displayCurrency)
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `S19-4 unsupported home currency falls back to EUR`() {
        val aggregate = listOf(row(10.0)).toHomeCurrencyAggregate("ZZZ")

        assertEquals(CurrencyCode.EUR, aggregate.displayCurrency)
        assertEquals(10.0, aggregate.displayAmount, 1e-9)
        assertFalse(aggregate.isPartial)
    }

    @Test
    fun `S19-4 empty input is a complete zero aggregate`() {
        val aggregate = emptyList<LocatedMoneyExpense>().toHomeCurrencyAggregate("EUR")

        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertFalse(aggregate.isPartial)
        assertEquals(0, aggregate.metadata.excludedTransactionCount)
    }

    // ── AreaSpendingEngine public API ────────────────────────────────────

    @Test
    fun `S19-4 area spending home-currency rows are unchanged with IDENTITY`() = runTest {
        val result = AreaSpendingEngine().computeNormalized(
            listOf(
                row(100.0, address = "Shop, Manhattan, NY"),
                row(50.0, address = "Cafe, Manhattan, NY")
            ),
            "EUR",
            engineConverter
        )

        assertEquals(1, result.size)
        val area = result.single()
        assertEquals(150.0, area.aggregate.displayAmount, 1e-9)
        assertEquals(RateBasis.IDENTITY, area.aggregate.rateBasis)
        assertFalse(area.aggregate.isPartial)
        assertEquals(75.0, area.avgTransaction, 1e-9)
    }

    @Test
    fun `S19-4 area spending excludes a non-home row and marks the area partial`() = runTest {
        val result = AreaSpendingEngine().computeNormalized(
            listOf(
                row(100.0, address = "Shop, Manhattan, NY"),
                row(900.0, currency = "USD", address = "Cafe, Manhattan, NY")
            ),
            "EUR",
            engineConverter
        )

        val area = result.single()
        assertEquals(100.0, area.aggregate.displayAmount, 1e-9)
        assertTrue(area.aggregate.isPartial)
        assertEquals(RateBasis.IDENTITY, area.aggregate.rateBasis)
        assertEquals(1, area.aggregate.metadata.excludedTransactionCount)
        assertEquals(1, area.aggregate.metadata.includedTransactionCount)
        assertEquals(2, area.transactionCount)
        // Average is over the included rows only.
        assertEquals(100.0, area.avgTransaction, 1e-9)
    }

    @Test
    fun `S19-4 area spending with every row excluded reports unavailable partial and zero average`() = runTest {
        val result = AreaSpendingEngine().computeNormalized(
            listOf(
                row(100.0, currency = "USD", address = "Shop, Manhattan, NY"),
                row(50.0, currency = "USD", address = "Cafe, Manhattan, NY")
            ),
            "EUR",
            engineConverter
        )

        val area = result.single()
        assertEquals(0.0, area.aggregate.displayAmount, 0.0)
        assertTrue(area.aggregate.isPartial)
        assertEquals(ConversionQuality.UNAVAILABLE, area.aggregate.conversionQuality)
        assertEquals(2, area.aggregate.metadata.excludedTransactionCount)
        assertEquals(0.0, area.avgTransaction, 0.0)
    }

    @Test
    fun `area spending counts a FAILED row as excluded and marks the area partial`() = runTest {
        val result = AreaSpendingEngine().computeNormalized(
            listOf(
                row(100.0, address = "Shop, Manhattan, NY"),
                row(
                    900.0, status = ConversionStatus.FAILED, normalizedAmount = null,
                    address = "Cafe, Manhattan, NY"
                )
            ),
            "EUR",
            engineConverter
        )

        val area = result.single()
        assertEquals(100.0, area.aggregate.displayAmount, 1e-9)
        assertTrue(area.aggregate.isPartial)
        assertEquals(1, area.aggregate.metadata.excludedTransactionCount)
        assertEquals(1, area.aggregate.metadata.includedTransactionCount)
        // Centroid and transaction count are unchanged by the FAILED row.
        assertEquals(1, area.transactionCount)
    }

    // ── TravelDetectionEngine public API ─────────────────────────────────

    @Test
    fun `travel detection counts a FAILED travel row as excluded and marks travel and trip partial`() = runTest {
        val insight = TravelDetectionEngine().computeNormalized(
            homeSeed() + listOf(
                row(100.0, lat = 41.8, lon = -87.6, date = 100_000L, address = "Shop, Chicago, IL"),
                row(
                    900.0, status = ConversionStatus.FAILED, normalizedAmount = null,
                    lat = 41.8, lon = -87.6, date = 100_000L, address = "Cafe, Chicago, IL"
                )
            ),
            "EUR",
            engineConverter
        )

        assertNotNull(insight)
        assertEquals(100.0, insight!!.travelAggregate.displayAmount, 1e-9)
        assertTrue(insight.travelAggregate.isPartial)
        assertEquals(1, insight.travelAggregate.metadata.excludedTransactionCount)
        val trip = insight.travelTrips.single()
        assertTrue(trip.aggregate.isPartial)
        assertEquals(1, trip.aggregate.metadata.excludedTransactionCount)
        assertFalse(insight.homeAggregate.isPartial)
    }

    @Test
    fun `S19-4 travel detection home-currency rows are unchanged with IDENTITY`() = runTest {
        val insight = TravelDetectionEngine().computeNormalized(
            homeSeed() + row(100.0, lat = 41.8, lon = -87.6, date = 100_000L, address = "Shop, Chicago, IL"),
            "EUR",
            engineConverter
        )

        assertNotNull(insight)
        assertEquals(60.0, insight!!.homeAggregate.displayAmount, 1e-9)
        assertEquals(RateBasis.IDENTITY, insight.homeAggregate.rateBasis)
        assertFalse(insight.homeAggregate.isPartial)
        assertEquals(100.0, insight.travelAggregate.displayAmount, 1e-9)
        assertEquals(RateBasis.IDENTITY, insight.travelAggregate.rateBasis)
        assertFalse(insight.travelAggregate.isPartial)
    }

    @Test
    fun `S19-4 travel detection excludes a non-home row and marks travel and trip partial`() = runTest {
        val insight = TravelDetectionEngine().computeNormalized(
            homeSeed() + listOf(
                row(100.0, lat = 41.8, lon = -87.6, date = 100_000L, address = "Shop, Chicago, IL"),
                row(900.0, currency = "USD", lat = 41.8, lon = -87.6, date = 100_000L, address = "Cafe, Chicago, IL")
            ),
            "EUR",
            engineConverter
        )

        assertNotNull(insight)
        assertEquals(100.0, insight!!.travelAggregate.displayAmount, 1e-9)
        assertTrue(insight.travelAggregate.isPartial)
        assertEquals(RateBasis.IDENTITY, insight.travelAggregate.rateBasis)
        assertEquals(1, insight.travelAggregate.metadata.excludedTransactionCount)
        val trip = insight.travelTrips.single()
        assertEquals(100.0, trip.aggregate.displayAmount, 1e-9)
        assertTrue(trip.aggregate.isPartial)
        assertEquals(1, trip.aggregate.metadata.excludedTransactionCount)
        // The home slice is untouched by the travel exclusion.
        assertFalse(insight.homeAggregate.isPartial)
    }

    @Test
    fun `S19-4 travel detection with every travel row excluded reports unavailable partial`() = runTest {
        val insight = TravelDetectionEngine().computeNormalized(
            homeSeed() + row(
                900.0, currency = "USD", lat = 41.8, lon = -87.6, date = 100_000L, address = "Shop, Chicago, IL"
            ),
            "EUR",
            engineConverter
        )

        assertNotNull(insight)
        assertEquals(0.0, insight!!.travelAggregate.displayAmount, 0.0)
        assertTrue(insight.travelAggregate.isPartial)
        assertEquals(ConversionQuality.UNAVAILABLE, insight.travelAggregate.conversionQuality)
        assertEquals(1, insight.travelAggregate.metadata.excludedTransactionCount)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun homeSeed(): List<LocatedMoneyExpense> =
        (1..6).map { i ->
            row(
                amount = 10.0,
                lat = 40.7128 + i * 0.0001,
                lon = -74.0060 + i * 0.0001,
                date = 1_000L + i * dayMs
            )
        }

    private var nextId = 1L

    private fun row(
        amount: Double,
        currency: String = "EUR",
        status: ConversionStatus = ConversionStatus.HOME_CURRENCY,
        normalizedAmount: Double? = amount,
        lat: Double = 40.7128,
        lon: Double = -74.0060,
        date: Long = 1_000_000L,
        address: String? = null,
        rateBasis: RateBasis = RateBasis.IDENTITY
    ) = LocatedMoneyExpense(
        expenseId = nextId++,
        latitude = lat,
        longitude = lon,
        normalizedAmount = normalizedAmount,
        normalizedCurrency = currency,
        originalAmount = amount,
        originalCurrency = currency,
        conversionStatus = status,
        merchant = "Test Merchant",
        date = date,
        resolvedAddress = address,
        rateBasis = rateBasis
    )
}
