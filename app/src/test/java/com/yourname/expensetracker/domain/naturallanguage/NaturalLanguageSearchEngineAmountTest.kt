package com.yourname.expensetracker.domain.naturallanguage

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.AmountComparison
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.DateRange
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.ExtractedAmount
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * FL-03: digits inside a full date (dd/mm/yyyy or ISO yyyy-mm-dd) are not read as amounts, so a date search
 * is not filtered by amount, and the date range is still parsed.
 *
 * FL-03R: a number is an amount only with a currency marker or a directly preceding comparison keyword.
 * Bare numbers, years, counts and time spans are never amounts. Separators are parsed by locale-neutral rules.
 */
@Suppress("DEPRECATION")
class NaturalLanguageSearchEngineAmountTest {

    private val today = LocalDate.of(2024, 6, 15)
    private val nowMs = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun engine(): NaturalLanguageSearchEngine {
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns nowMs
        val merchants = mockk<MerchantNormalizationRepository>()
        coEvery { merchants.getAliasByRawName(any()) } returns null
        coEvery { merchants.getAliasByNormalizedKey(any()) } returns null
        coEvery { merchants.getTopMerchants(any()) } returns emptyList()
        return NaturalLanguageSearchEngine(
            expenseQueryRepository = mockk(relaxed = true),
            speechInputGateway = mockk(relaxed = true),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = merchants,
        )
    }

    private suspend fun amounts(query: String): List<ExtractedAmount>? =
        engine().interpretQuery(query).extractedAmounts

    private fun amount(value: Double, comparison: AmountComparison) = ExtractedAmount(value, comparison)

    // ---- FL-03 existing date tests ----

    @Test
    fun `slash date digits are not extracted as amounts`() = runTest {
        val result = engine().interpretQuery("spent 15/03/2024 on coffee")

        assertNull(result.extractedAmounts)
        assertEquals(
            DateRange(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15)),
            result.dateRange
        )
    }

    @Test
    fun `iso date digits are not extracted as amounts`() = runTest {
        val result = engine().interpretQuery("paid 2024-03-15 for rent")

        assertNull(result.extractedAmounts)
    }

    // ---- Positive controls: a real amount next to a date is still extracted ----

    @Test
    fun `slash date with a real amount keeps the amount and the parsed date`() = runTest {
        val result = engine().interpretQuery("spent 15/03/2024 over 20 euro")

        assertEquals(listOf(amount(20.0, AmountComparison.OVER)), result.extractedAmounts)
        assertEquals(
            DateRange(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15)),
            result.dateRange
        )
    }

    @Test
    fun `iso date with a real amount keeps the amount and drops the date digits`() = runTest {
        val result = engine().interpretQuery("paid 2024-03-15 over 20 euro")

        assertEquals(listOf(amount(20.0, AmountComparison.OVER)), result.extractedAmounts)
    }

    // ---- Comparison keywords ----

    @Test
    fun `over with euro symbol is OVER 50`() = runTest {
        assertEquals(listOf(amount(50.0, AmountComparison.OVER)), amounts("over €50"))
    }

    @Test
    fun `under with euro word is UNDER 20`() = runTest {
        assertEquals(listOf(amount(20.0, AmountComparison.UNDER)), amounts("under 20 euro"))
    }

    @Test
    fun `between two euro amounts is a BETWEEN pair and not a date range`() = runTest {
        val result = engine().interpretQuery("between €10 and €50")

        assertEquals(
            listOf(amount(10.0, AmountComparison.BETWEEN), amount(50.0, AmountComparison.BETWEEN)),
            result.extractedAmounts
        )
        assertNull(result.dateRange)
    }

    @Test
    fun `over and under in one query keep their own numbers`() = runTest {
        assertEquals(
            listOf(amount(50.0, AmountComparison.OVER), amount(100.0, AmountComparison.UNDER)),
            amounts("over \$50 under \$100")
        )
    }

    // ---- Numbers that are not amounts ----

    @Test
    fun `bare number after a word is not an amount`() = runTest {
        assertNull(amounts("coffee 15"))
    }

    @Test
    fun `number followed by a time unit is not an amount`() = runTest {
        assertNull(amounts("last 3 months"))
    }

    @Test
    fun `top N is not an amount`() = runTest {
        assertNull(amounts("top 5"))
    }

    @Test
    fun `bare year is not an amount`() = runTest {
        assertNull(amounts("2024"))
    }

    @Test
    fun `count with times is not an amount`() = runTest {
        assertNull(amounts("3 times"))
    }

    @Test
    fun `between two years is not an amount and parses no date range (characterisation)`() = runTest {
        val result = engine().interpretQuery("between 2023 and 2024")

        assertNull(result.extractedAmounts)
        // Characterisation pin: the date pattern deliberately skips amount-like tokens, so years give no range.
        assertNull(result.dateRange)
    }

    @Test
    fun `over embedded in discover is not a comparison keyword`() = runTest {
        assertNull(amounts("discover 5"))
    }

    @Test
    fun `over embedded in cover is not a comparison keyword`() = runTest {
        assertNull(amounts("cover 5"))
    }

    @Test
    fun `discover with a marked amount is EXACTLY and not OVER`() = runTest {
        assertEquals(listOf(amount(5.0, AmountComparison.EXACTLY)), amounts("discover 5 euro"))
    }

    // ---- Separators ----

    @Test
    fun `comma followed by three digits is a thousands separator`() = runTest {
        assertEquals(listOf(amount(1000.0, AmountComparison.OVER)), amounts("over €1,000"))
    }

    @Test
    fun `single comma with one digit is a decimal separator`() = runTest {
        assertEquals(listOf(amount(1.5, AmountComparison.OVER)), amounts("over €1,5"))
    }

    @Test
    fun `dot decimal separator is read as decimal`() = runTest {
        assertEquals(listOf(amount(1.5, AmountComparison.OVER)), amounts("over €1.50"))
    }

    @Test
    fun `marked amount with two decimals is EXACTLY 12_50`() = runTest {
        val extracted = amounts("€12.50")

        assertNotNull(extracted)
        assertEquals(1, extracted!!.size)
        assertEquals(12.5, extracted.single().value, 0.0)
        assertEquals(AmountComparison.EXACTLY, extracted.single().comparison)
    }

    // ---- Other extractors still read the original query ----

    @Test
    fun `merchant extraction still works next to an amount`() = runTest {
        val result = engine().interpretQuery("at Starbucks over €5")

        assertEquals(listOf("Starbucks"), result.merchants)
        assertEquals(listOf(amount(5.0, AmountComparison.OVER)), result.extractedAmounts)
    }

    @Test
    fun `category extraction still works next to an amount`() = runTest {
        val result = engine().interpretQuery("grocery over €5")

        assertEquals(listOf("food"), result.categories)
        assertEquals(listOf(amount(5.0, AmountComparison.OVER)), result.extractedAmounts)
    }

    @Test
    fun `relative date extraction still works next to an amount`() = runTest {
        val result = engine().interpretQuery("last month over €5")

        assertEquals(DateRange(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31)), result.dateRange)
        assertEquals(listOf(amount(5.0, AmountComparison.OVER)), result.extractedAmounts)
    }

    @Test
    fun `between yesterday and today yields no amounts and the yesterday date (characterisation)`() = runTest {
        val result = engine().interpretQuery("between yesterday and today")

        assertNull(result.extractedAmounts)
        // Characterisation pin: the "(yesterday|today)" pattern runs before "between X and Y", so the range
        // collapses to yesterday..yesterday rather than yesterday..today. Pre-existing behaviour.
        val yesterday = today.minusDays(1)
        assertEquals(DateRange(yesterday, yesterday), result.dateRange)
    }

    // ---- Marker binds to the number directly after it (fix104-g) ----

    @Test
    fun `route 66 with a trailing dollar amount gives 20 and not 66`() = runTest {
        assertEquals(listOf(amount(20.0, AmountComparison.EXACTLY)), amounts("route 66 \$20"))
    }

    @Test
    fun `march 15 with a euro amount gives 20 and not 15`() = runTest {
        assertEquals(listOf(amount(20.0, AmountComparison.EXACTLY)), amounts("march 15 €20"))
    }

    @Test
    fun `suffix marker is still taken when no number follows it`() = runTest {
        // Control for the two cases above: the suffix rule must not disable suffix markers.
        assertEquals(listOf(amount(20.0, AmountComparison.EXACTLY)), amounts("march 15 20€"))
    }

    @Test
    fun `50 euro 20 euro yields both amounts in query order`() = runTest {
        // A suffix marker binds to the number directly before it; only a digit right after it blocks that.
        assertEquals(
            listOf(amount(50.0, AmountComparison.EXACTLY), amount(20.0, AmountComparison.EXACTLY)),
            amounts("50€ 20€")
        )
    }

    @Test
    fun `20 euro symbol before 15 march keeps 20 and drops the bare 15`() = runTest {
        assertEquals(listOf(amount(20.0, AmountComparison.EXACTLY)), amounts("20€ 15 march"))
    }

    @Test
    fun `over 20 eur before a bare year gives OVER 20 and no EXACTLY 2024`() = runTest {
        assertEquals(listOf(amount(20.0, AmountComparison.OVER)), amounts("over 20 eur 2024"))
    }

    @Test
    fun `coffee 50 euros before a relative period keeps the 50`() = runTest {
        assertEquals(listOf(amount(50.0, AmountComparison.EXACTLY)), amounts("coffee 50 euros 3 months ago"))
    }

    // ---- Repeated dot groups of exactly three digits are thousands separators ----

    @Test
    fun `over 1_234_567 is one million two hundred thousand and more`() = runTest {
        assertEquals(listOf(amount(1234567.0, AmountComparison.OVER)), amounts("over 1.234.567"))
    }

    @Test
    fun `over 1_234_56 with a short group is ambiguous and not extracted`() = runTest {
        assertNull(amounts("over 1.234.56"))
    }

    @Test
    fun `over 10_000 comma 50 is ten thousand and a half`() = runTest {
        assertEquals(listOf(amount(10000.5, AmountComparison.OVER)), amounts("over 10.000,50"))
    }

    @Test
    fun `over 12_30 stays a decimal`() = runTest {
        assertEquals(listOf(amount(12.3, AmountComparison.OVER)), amounts("over 12.30"))
    }

    // ---- Dot as thousands separator only for exactly three digits after a non-zero integer part ----

    @Test
    fun `over 1_500 is fifteen hundred`() = runTest {
        assertEquals(listOf(amount(1500.0, AmountComparison.OVER)), amounts("over 1.500"))
    }

    @Test
    fun `1_50 stays a decimal`() = runTest {
        assertEquals(listOf(amount(1.5, AmountComparison.OVER)), amounts("over 1.50"))
    }

    @Test
    fun `0_500 stays a decimal`() = runTest {
        assertEquals(listOf(amount(0.5, AmountComparison.OVER)), amounts("over 0.500"))
    }

    // ---- Names, counts and durations that must stay free of amounts ----

    @Test
    fun `under armour is a brand and not an amount`() = runTest {
        assertNull(amounts("Under Armour"))
    }

    @Test
    fun `7 eleven is a brand and not an amount`() = runTest {
        assertNull(amounts("7 Eleven"))
    }

    @Test
    fun `under 5 minutes is a duration and not an amount`() = runTest {
        assertNull(amounts("under 5 minutes"))
    }

    @Test
    fun `over 3 weeks ago is a duration and not an amount`() = runTest {
        assertNull(amounts("over 3 weeks ago"))
    }

    @Test
    fun `coffee 15 and last 3 months stay free of amounts together`() = runTest {
        assertNull(amounts("coffee 15 last 3 months"))
    }

    // ---- Alternatives versus range, extraction side ----

    @Test
    fun `under 10 or over 50 extracts both bounds in query order`() = runTest {
        assertEquals(
            listOf(amount(10.0, AmountComparison.UNDER), amount(50.0, AmountComparison.OVER)),
            amounts("under 10 or over 50")
        )
    }
}
