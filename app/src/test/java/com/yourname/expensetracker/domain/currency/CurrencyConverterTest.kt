package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MEC 1b S1: [CurrencyConverter.storeRateHistory] (INV-1b-10, INV-1b-11, INV-1b-12).
 */
class CurrencyConverterTest {

    private val nowMillis = 1_793_000_000_000L
    private val store = mockk<ExchangeRateStore>(relaxed = true)
    private val time = object : TimeProvider {
        override fun now(): Long = nowMillis
    }
    private val converter = CurrencyConverter(store, time)

    private val day1 = LocalDate.of(2026, 10, 29)
    private val day2 = LocalDate.of(2026, 10, 28)

    private fun midnightUtc(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun captureStoredRows(): MutableList<List<DomainExchangeRate>> {
        val calls = mutableListOf<List<DomainExchangeRate>>()
        coEvery { store.insertOrUpdateAll(any()) } answers { calls += firstArg<List<DomainExchangeRate>>() }
        return calls
    }

    @Test
    fun `INV-1b-11 storeRateHistory makes exactly one bulk write and returns the row count`() = runTest {
        val calls = captureStoredRows()
        val days = listOf(
            RateHistoryDay(day1, mapOf("EUR" to 1.0, "USD" to 1.10, "GBP" to 0.80)),
            RateHistoryDay(day2, mapOf("EUR" to 1.0, "USD" to 1.20, "GBP" to 0.90))
        )

        val rows = converter.storeRateHistory(days)

        assertEquals(12, rows)
        assertEquals(1, calls.size)
        assertEquals(12, calls.single().size)
        coVerify(exactly = 1) { store.insertOrUpdateAll(any()) }
        coVerify(exactly = 0) { store.insertOrUpdate(any()) }
    }

    @Test
    fun `INV-1b-10 rows are catalog cross pairs dated at UTC midnight with one lastUpdated and source`() = runTest {
        val calls = captureStoredRows()
        val days = listOf(
            RateHistoryDay(day1, mapOf("EUR" to 1.0, "USD" to 1.10, "GBP" to 0.80, "IDR" to 18000.0))
        )

        converter.storeRateHistory(days, source = "ecb")

        val stored = calls.single()
        // EUR, USD, GBP are listed catalog codes: 3 x 2 = 6 pairs. IDR is not in the catalog.
        assertEquals(6, stored.size)
        assertTrue(stored.none { it.fromCurrency == "IDR" || it.toCurrency == "IDR" })
        assertTrue(stored.none { it.fromCurrency == it.toCurrency })
        assertTrue(stored.all { it.validDate == midnightUtc(day1) })
        assertTrue(stored.all { it.lastUpdated == nowMillis })
        assertTrue(stored.all { it.source == "ecb" })
        val usdToGbp = stored.single { it.fromCurrency == "USD" && it.toCurrency == "GBP" }
        assertEquals(0.80 / 1.10, usdToGbp.rate, 1e-12)
        val eurToUsd = stored.single { it.fromCurrency == "EUR" && it.toCurrency == "USD" }
        assertEquals(1.10, eurToUsd.rate, 0.0)
    }

    @Test
    fun `INV-1b-10 forward and reverse cross rates multiply to one`() = runTest {
        val calls = captureStoredRows()

        converter.storeRateHistory(
            listOf(RateHistoryDay(day1, mapOf("EUR" to 1.0, "JPY" to 175.43, "CHF" to 0.9371)))
        )

        val stored = calls.single()
        for (from in listOf("EUR", "JPY", "CHF")) {
            for (to in listOf("EUR", "JPY", "CHF")) {
                if (from == to) continue
                val forward = stored.single { it.fromCurrency == from && it.toCurrency == to }.rate
                val reverse = stored.single { it.fromCurrency == to && it.toCurrency == from }.rate
                assertEquals("$from/$to", 1.0, forward * reverse, 1e-12)
            }
        }
    }

    @Test
    fun `INV-1b-10 non positive and non finite quotes are not listed for that day`() = runTest {
        val calls = captureStoredRows()
        val days = listOf(
            RateHistoryDay(
                day1,
                mapOf(
                    "EUR" to 1.0, "USD" to 1.10, "GBP" to 0.80,
                    "JPY" to 0.0, "CHF" to -0.9, "CAD" to Double.NaN, "AUD" to Double.POSITIVE_INFINITY
                )
            )
        )

        val rows = converter.storeRateHistory(days)

        assertEquals(6, rows)
        val codes = calls.single().flatMap { listOf(it.fromCurrency, it.toCurrency) }.toSet()
        assertEquals(setOf("EUR", "USD", "GBP"), codes)
    }

    @Test
    fun `INV-1b-11 nothing to store returns 0 and never calls the store`() = runTest {
        // A day that lists only EUR yields no pair; an empty list yields no write either.
        assertEquals(0, converter.storeRateHistory(listOf(RateHistoryDay(day1, mapOf("EUR" to 1.0)))))
        assertEquals(0, converter.storeRateHistory(emptyList()))

        coVerify(exactly = 0) { store.insertOrUpdateAll(any()) }
        coVerify(exactly = 0) { store.insertOrUpdate(any()) }
    }

    @Test
    fun `INV-1b-12 storeRateHistory never deletes or cleans up other rows`() = runTest {
        captureStoredRows()

        converter.storeRateHistory(
            listOf(RateHistoryDay(day1, mapOf("EUR" to 1.0, "USD" to 1.10)))
        )

        coVerify(exactly = 0) { store.deleteOldRates(any()) }
    }

    @Test
    fun `INV-1b-10 F1 parity history day rows equal the daily path cross pairs for the same quotes`() = runTest {
        // Daily path: the real repository refreshFromStream over a one-day ECB document.
        val dailyCalls = mutableListOf<List<DomainExchangeRate>>()
        val dailyStore = mockk<ExchangeRateStore>(relaxed = true)
        coEvery { dailyStore.insertOrUpdateAll(any()) } answers { dailyCalls += firstArg<List<DomainExchangeRate>>() }
        val dailyRepository = com.yourname.expensetracker.data.repository.CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(dailyStore, time),
            currencySettingsRepository = mockk(relaxed = true),
            timeProvider = time,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            privacyGate = mockk(relaxed = true)
        )
        val xml = """
            <?xml version='1.0' encoding='UTF-8'?>
            <gesmes:Envelope xmlns:gesmes='http://www.gesmes.org/xml/2002-08-01'
                xmlns='http://www.ecb.int/vocabulary/2002-08-01/eurofxref'>
                <Cube><Cube time='2026-10-29'>
                    <Cube currency='USD' rate='1.1403'/>
                    <Cube currency='GBP' rate='0.8735'/>
                    <Cube currency='JPY' rate='175.43'/>
                    <Cube currency='CHF' rate='0.9371'/>
                    <Cube currency='IDR' rate='18000.5'/>
                </Cube></Cube>
            </gesmes:Envelope>
        """.trimIndent()
        dailyRepository.refreshFromStream("USD", java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        // History path: the same EUR quotes for one day.
        val historyCalls = captureStoredRows()
        converter.storeRateHistory(
            listOf(
                RateHistoryDay(
                    day1,
                    mapOf(
                        "EUR" to 1.0, "USD" to 1.1403, "GBP" to 0.8735,
                        "JPY" to 175.43, "CHF" to 0.9371, "IDR" to 18000.5
                    )
                )
            )
        )

        fun triples(rows: List<DomainExchangeRate>) =
            rows.map { Triple(it.fromCurrency, it.toCurrency, it.rate) }.toSet()
        val daily = triples(dailyCalls.single())
        val history = triples(historyCalls.single())
        assertTrue(daily.isNotEmpty())
        assertEquals(dailyCalls.single().size, historyCalls.single().size)
        assertEquals(daily, history)
    }

    @Test
    fun `INV-1b-11 a store failure propagates unchanged so the caller can map STORE_FAILED`() = runTest {
        coEvery { store.insertOrUpdateAll(any()) } throws IllegalStateException("db closed")

        try {
            converter.storeRateHistory(
                listOf(RateHistoryDay(day1, mapOf("EUR" to 1.0, "USD" to 1.10)))
            )
            fail("store failure must not be swallowed")
        } catch (error: IllegalStateException) {
            assertNotNull(error)
            assertFalse(error.message.isNullOrEmpty())
        }
    }
}
