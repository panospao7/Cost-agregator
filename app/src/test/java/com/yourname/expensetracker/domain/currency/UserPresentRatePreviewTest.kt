package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.SnapshotRounding
import com.yourname.expensetracker.domain.core.money.SnapshotRoundingUnavailableReason
import com.yourname.expensetracker.domain.core.money.roundSnapshot
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * manual-entry-currency A1: [UserPresentRatePreview] (INV-15, INV-20; AE-1 product, AE-15 preview,
 * AE-22 inputs, AE-38 domain part).
 *
 * The preview amount must equal the `Rounded.amount` of `roundSnapshot(amount * rate, home)` computed
 * from the same operands in the same order as the A2 snapshot. Formatting is the caller's job and
 * is not tested here.
 */
class UserPresentRatePreviewTest {

    private val athens: ZoneId = ZoneId.of("Europe/Athens")
    private val eur = CurrencyCode("EUR")
    private val usd = CurrencyCode("USD")
    private val jpy = CurrencyCode("JPY")
    private val bgn = CurrencyCode("BGN")

    private lateinit var store: PreviewRateStoreFake
    private lateinit var preview: UserPresentRatePreview

    @Before
    fun setUp() {
        store = PreviewRateStoreFake()
        val converter = CurrencyConverter(store, FakeTimeProvider(0L))
        preview = UserPresentRatePreview(RateEligibilityEvaluator(converter), store)
    }

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun put(from: String, to: String, rate: Double, date: LocalDate, source: String = "ecb") {
        store.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 0L,
            source = source,
            validDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(athens).toInstant().toEpochMilli()

    private fun rounded(value: Double, home: CurrencyCode): Double =
        (roundSnapshot(value, home) as SnapshotRounding.Rounded).amount

    // ---- AE-1 / AE-38 domain part ----

    @Test
    fun `AE-1 USD 20_00 at 1 over 1_08 previews 18_52 EUR with the rate date and ECB label`() = runTest {
        val rate = 1.0 / 1.08
        put("USD", "EUR", rate, oct(15))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(
            UserPresentRatePreviewResult.ConvertedPreview(
                amountInHome = 18.52,
                home = eur,
                rateDate = oct(15),
                sourceLabel = RateSourceLabel.ECB
            ),
            result
        )
    }

    @Test
    fun `AE-38 the preview amount is exactly roundSnapshot of the same product the snapshot uses`() = runTest {
        val rate = 1.0 / 1.08
        put("USD", "EUR", rate, oct(15))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        // A2 snapshot operands and order: request amount * Eligible.rate, then roundSnapshot(product, home).
        val expected = (roundSnapshot(20.0 * rate, eur) as SnapshotRounding.Rounded).amount
        assertEquals(expected, (result as UserPresentRatePreviewResult.ConvertedPreview).amountInHome, 0.0)
    }

    @Test
    fun `the preview is rounded, never the raw product`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertTrue(20.0 * (1.0 / 1.08) != (result as UserPresentRatePreviewResult.ConvertedPreview).amountInHome)
    }

    @Test
    fun `AE-15 JPY 1500 previews 9_23 EUR`() = runTest {
        put("JPY", "EUR", 1.0 / 162.5, oct(15))
        val result = preview.preview(1500.0, jpy, eur, noon(oct(15)), athens)
        assertEquals(9.23, (result as UserPresentRatePreviewResult.ConvertedPreview).amountInHome, 0.0)
    }

    @Test
    fun `home digits drive the rounding and ties go to even (HALF_EVEN)`() = runTest {
        put("USD", "JPY", 150.0, oct(15))
        // 10.75 * 150.0 = 1612.5 exactly; HALF_EVEN at 0 digits gives 1612, HALF_UP would give 1613.
        val result = preview.preview(10.75, usd, jpy, noon(oct(15)), athens)
        assertEquals(1612.0, (result as UserPresentRatePreviewResult.ConvertedPreview).amountInHome, 0.0)
    }

    @Test
    fun `a positive product that rounds to zero previews one minor unit, never zero`() = runTest {
        put("USD", "EUR", 0.9, oct(15))
        val result = preview.preview(0.001, usd, eur, noon(oct(15)), athens)
        assertEquals(0.01, (result as UserPresentRatePreviewResult.ConvertedPreview).amountInHome, 0.0)
    }

    // ---- no amount when there is none ----

    @Test
    fun `a Home currency has no preview and reads no rates`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        val result = preview.preview(12.5, eur, eur, noon(oct(15)), athens)
        assertEquals(UserPresentRatePreviewResult.NotApplicableHome, result)
        assertEquals(0, store.reads)
    }

    @Test
    fun `a stale rate is Blocked with no amount`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(7))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(
            UserPresentRatePreviewResult.Blocked(RateEligibility.Stale(oct(7), 8), latestStoredPairDate = oct(7)),
            result
        )
    }

    // ---- INV-1b-5 input: latestStoredPairDate ----

    @Test
    fun `INV-1b-5 Stale carries the UTC date of the newest stored pair even when newer than the entry`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(7))
        put("USD", "EUR", 1.0 / 1.09, oct(20))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(
            UserPresentRatePreviewResult.Blocked(RateEligibility.Stale(oct(7), 8), latestStoredPairDate = oct(20)),
            result
        )
        assertEquals(listOf("USD" to "EUR"), store.latestPairCalls)
    }

    @Test
    fun `INV-1b-5 None with a newer stored pair carries that date`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(20))
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(
            UserPresentRatePreviewResult.Blocked(RateEligibility.None, latestStoredPairDate = oct(20)),
            result
        )
    }

    @Test
    fun `INV-1b-5 a pair without a valid date yields a null latestStoredPairDate`() = runTest {
        store.rows += DomainExchangeRate("USD", "EUR", 0.9, 0L, "ecb", validDate = null)
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(UserPresentRatePreviewResult.Blocked(RateEligibility.None, latestStoredPairDate = null), result)
        assertEquals(listOf("USD" to "EUR"), store.latestPairCalls)
    }

    @Test
    fun `INV-1b-5 Home Eligible Unsupported and AmountUnavailable never fetch the latest pair`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        put("BGN", "EUR", 0.51, oct(15))
        preview.preview(12.5, eur, eur, noon(oct(15)), athens)
        preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        preview.preview(0.0, usd, eur, noon(oct(15)), athens)
        preview.preview(20.0, bgn, eur, noon(oct(15)), athens)
        assertTrue(store.latestPairCalls.isEmpty())
    }

    @Test
    fun `no rate is Blocked None and never a zero amount`() = runTest {
        val result = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(UserPresentRatePreviewResult.Blocked(RateEligibility.None), result)
    }

    @Test
    fun `an unsupported currency is Blocked Unsupported`() = runTest {
        put("BGN", "EUR", 0.51, oct(15))
        val result = preview.preview(20.0, bgn, eur, noon(oct(15)), athens)
        assertEquals(
            UserPresentRatePreviewResult.Blocked(RateEligibility.Unsupported(RateUnsupportedReason.NOT_SELECTABLE)),
            result
        )
    }

    @Test
    fun `an eligible rate with an unusable amount is AmountUnavailable, not zero`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "amount $bad",
                UserPresentRatePreviewResult.AmountUnavailable(
                    reason = SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE,
                    rateDate = oct(15),
                    sourceLabel = RateSourceLabel.ECB
                ),
                preview.preview(bad, usd, eur, noon(oct(15)), athens)
            )
        }
    }

    // ---- AE-22 inputs: the preview is advisory and re-evaluates on every call ----

    @Test
    fun `AE-22 clearing the rate table after an eligible preview makes the next preview Blocked None`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        assertTrue(preview.preview(20.0, usd, eur, noon(oct(15)), athens) is UserPresentRatePreviewResult.ConvertedPreview)
        store.rows.clear()
        assertEquals(
            UserPresentRatePreviewResult.Blocked(RateEligibility.None),
            preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        )
    }

    @Test
    fun `AE-22 a refresh that stores the expense day changes the next preview to the new rate`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(14))
        val before = preview.preview(20.0, usd, eur, noon(oct(15)), athens) as UserPresentRatePreviewResult.ConvertedPreview
        assertEquals(oct(14), before.rateDate)
        put("USD", "EUR", 1.0 / 1.10, oct(15))
        val after = preview.preview(20.0, usd, eur, noon(oct(15)), athens) as UserPresentRatePreviewResult.ConvertedPreview
        assertEquals(oct(15), after.rateDate)
        assertEquals(rounded(20.0 * (1.0 / 1.10), eur), after.amountInHome, 0.0)
    }

    @Test
    fun `AE-22 with no rate write in between two previews are identical`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        val first = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        val second = preview.preview(20.0, usd, eur, noon(oct(15)), athens)
        assertEquals(first, second)
    }

    // ---- fake ----

    /** In-memory store with the DAO semantics: latest `validDate <= bound`. */
    private class PreviewRateStoreFake : ExchangeRateStore {
        val rows = mutableListOf<DomainExchangeRate>()
        var reads = 0

        override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            reads++
            return null
        }

        val latestPairCalls = mutableListOf<Pair<String, String>>()

        override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            reads++
            latestPairCalls += fromCurrency to toCurrency
            return rows
                .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
                .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
        }

        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
            reads++
            return rows
                .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
                .filter { row -> row.validDate?.let { it <= atMillis } ?: false }
                .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
        }

        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rows += rate
        }

        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
            rows += rates
        }

        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())

        override suspend fun getLatestRate(): DomainExchangeRate? = null

        override suspend fun deleteOldRates(olderThan: Long) = Unit
    }
}
