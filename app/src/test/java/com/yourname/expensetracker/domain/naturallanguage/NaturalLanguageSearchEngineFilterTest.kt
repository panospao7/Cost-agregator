package com.yourname.expensetracker.domain.naturallanguage

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.AmountComparison
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.ExtractedAmount
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine.QueryInterpretation
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CancellationException

/**
 * Execution-side tests for the legacy NL engine's amount and merchant filtering (FL-03R, W16).
 *
 * Real engine; only the keyset repository (a recording fake), the currency converter and the settings are
 * substituted. Interpretations come from real query strings through [NaturalLanguageSearchEngine.interpretQuery]
 * except where a merchant filter must be controlled exactly.
 *
 * Page size 500 is the engine's documented keyset page (W30+W31); it is the one pinned constant here.
 */
@Suppress("DEPRECATION")
class NaturalLanguageSearchEngineFilterTest {

    private val pageSize = 500
    private val baseDate = 1_700_000_000_000L
    private val nowMs = 1_710_000_000_000L

    // ---- fixtures ----

    private data class Call(
        val categoryIds: Set<Long>?,
        val merchants: List<String>?,
        val limit: Int,
        val cursor: SearchCursor?
    )

    /** Serves [pages] one per call (empty afterwards) and records every call. */
    private class FakeRepo(
        private val pages: List<List<NaturalLanguageExpense>>,
        private val failure: Throwable? = null
    ) : NaturalLanguageExpenseQueryRepository {
        val calls = mutableListOf<Call>()

        override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> =
            throw AssertionError("getExpensesBetween must not be used")

        @Suppress("OVERRIDE_DEPRECATION")
        override suspend fun getExpensesBetweenFiltered(
            startMs: Long,
            endMs: Long,
            merchants: List<String>?,
            categories: List<String>?,
            minAmount: Double?,
            maxAmount: Double?
        ): List<NaturalLanguageExpense> = throw AssertionError("deprecated query must not be used")

        override suspend fun getExpensesBetweenFilteredKeyset(
            startMs: Long,
            endMs: Long,
            categoryIds: Set<Long>?,
            merchants: List<String>?,
            transactionType: String?,
            keywordSearch: String?,
            limit: Int,
            cursor: SearchCursor?
        ): List<NaturalLanguageExpense> {
            calls += Call(categoryIds, merchants, limit, cursor)
            failure?.let { throw it }
            return pages.getOrElse(calls.size - 1) { emptyList() }
        }
    }

    private fun row(
        id: Long,
        amount: Double,
        currency: String = "EUR",
        merchant: String = "Shop",
        categoryId: Long? = null,
        // Deliberately different from the gross amount by default: a filter that read effectiveAmount
        // instead of the gross (displayed) amount would fail every amount-boundary test in this class.
        effectiveAmount: Double = amount * 0.5,
        // A flagged (spending-excluded) row is built as the repository maps it: not counted, marked.
        flagged: Boolean = false
    ) = NaturalLanguageExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        date = baseDate - id * 1_000L,
        categoryId = categoryId,
        countsInTotal = !flagged,
        isSpendingNeutralised = flagged
    )

    private fun fullPage(firstId: Long, amount: Double = 1.0, merchant: String = "Shop") =
        (0 until pageSize).map { row(firstId + it, amount, merchant = merchant) }

    private fun engine(
        repo: NaturalLanguageExpenseQueryRepository,
        converter: CurrencyConverter = mockk(),
        home: String = "EUR"
    ): NaturalLanguageSearchEngine {
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns nowMs
        val settings = mockk<CurrencySettingsRepository>()
        every { settings.homeCurrency() } returns flowOf(home)
        val merchants = mockk<MerchantNormalizationRepository>()
        coEvery { merchants.getAliasByRawName(any()) } returns null
        coEvery { merchants.getAliasByNormalizedKey(any()) } returns null
        coEvery { merchants.getTopMerchants(any()) } returns emptyList()
        return NaturalLanguageSearchEngine(
            expenseQueryRepository = repo,
            speechInputGateway = mockk(relaxed = true),
            timeProvider = timeProvider,
            currencyConverter = converter,
            currencySettingsRepository = settings,
            categoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = merchants,
        )
    }

    private fun manual(
        amounts: List<ExtractedAmount>? = null,
        merchants: List<String>? = null,
        originalQuery: String = "synthetic"
    ) = QueryInterpretation(
        originalQuery = originalQuery,
        queryType = NaturalLanguageSearchEngine.QueryType.FIND_TRANSACTIONS,
        extractedAmounts = amounts,
        dateRange = null,
        locations = null,
        categories = null,
        merchants = merchants,
        searchFilter = NaturalLanguageSearchEngine.SearchFilter(
            minAmount = null, maxAmount = null, exactAmount = null,
            startDate = null, endDate = null,
            locations = null, categories = null, merchants = merchants
        ),
        confidence = 50.0
    )

    private suspend fun idsFor(query: String, rows: List<NaturalLanguageExpense>): List<Long> {
        val engine = engine(FakeRepo(listOf(rows)))
        return engine.executeSearch(engine.interpretQuery(query)).map { it.expense.id }
    }

    private fun conversion(from: String, amount: Double, converted: Double) = ConversionResult(
        originalAmount = amount,
        originalCurrency = from,
        convertedAmount = converted,
        targetCurrency = "EUR",
        rateUsed = converted / amount,
        timestamp = nowMs
    )

    // ---- amount comparison in cents ----

    @Test
    fun `OVER compares in cents and excludes exactly 50_00`() = runTest {
        val rows = listOf(
            row(1, 49.99), row(2, 50.00), row(3, 50.004), row(4, 50.005), row(5, 50.01)
        )
        // 50.004 rounds to 50.00 (not over); 50.005 rounds HALF_UP to 50.01 (over).
        assertEquals(listOf(4L, 5L), idsFor("over €50", rows))
    }

    @Test
    fun `UNDER compares in cents and excludes exactly 50_00`() = runTest {
        val rows = listOf(
            row(1, 49.99), row(2, 49.994), row(3, 49.995), row(4, 50.00), row(5, 50.01)
        )
        // 49.994 -> 49.99 (under); 49.995 -> 50.00 HALF_UP (not under).
        assertEquals(listOf(1L, 2L), idsFor("under €50", rows))
    }

    @Test
    fun `EXACTLY compares in cents`() = runTest {
        val rows = listOf(
            row(1, 12.49), row(2, 12.50), row(3, 12.499), row(4, 12.504), row(5, 12.505), row(6, 12.51)
        )
        assertEquals(listOf(2L, 3L, 4L), idsFor("€12.50", rows))
    }

    @Test
    fun `BETWEEN is inclusive at both ends`() = runTest {
        val rows = listOf(row(1, 9.99), row(2, 10.00), row(3, 30.0), row(4, 50.00), row(5, 50.01))
        assertEquals(listOf(2L, 3L, 4L), idsFor("between €10 and €50", rows))
    }

    @Test
    fun `BETWEEN 50 and 10 swaps the bounds`() = runTest {
        val rows = listOf(row(1, 9.99), row(2, 10.00), row(3, 30.0), row(4, 50.00), row(5, 50.01))
        assertEquals(listOf(2L, 3L, 4L), idsFor("between €50 and €10", rows))
    }

    @Test
    fun `BETWEEN 20 and 20 matches only 20_00 and not everything`() = runTest {
        val rows = listOf(row(1, 5.0), row(2, 19.99), row(3, 20.00), row(4, 20.01), row(5, 100.0))
        assertEquals(listOf(3L), idsFor("between €20 and €20", rows))
    }

    @Test
    fun `OVER and UNDER together form a range`() = runTest {
        val rows = listOf(row(1, 50.00), row(2, 50.01), row(3, 75.0), row(4, 99.99), row(5, 100.00))
        assertEquals(listOf(2L, 3L, 4L), idsFor("over \$50 under \$100", rows))
    }

    // ---- gross converted amount is what the filter compares (never the effective share) ----

    @Test
    fun `neutralised 1200 euro row with effective 0 is excluded by under 50 and included by over 50`() = runTest {
        val neutralised = row(1, 1200.0, effectiveAmount = 0.0, flagged = true)

        assertEquals(emptyList<Long>(), idsFor("under €50", listOf(neutralised)))
        assertEquals(listOf(1L), idsFor("over €50", listOf(neutralised)))
    }

    @Test
    fun `100 euro row with a 30 euro share is included by over 50 and excluded by under 50`() = runTest {
        val shared = row(1, 100.0, effectiveAmount = 30.0)

        assertEquals(listOf(1L), idsFor("over €50", listOf(shared)))
        assertEquals(emptyList<Long>(), idsFor("under €50", listOf(shared)))
    }

    @Test
    fun `10000 yen row converted to 60 euro is included by over 50 and excluded by under 50`() = runTest {
        val yen = row(1, 10_000.0, currency = "JPY", effectiveAmount = 4_000.0)
        val converter = mockk<CurrencyConverter>()
        // Only the gross amount is stubbed: converting the effective share would find no stub and throw.
        coEvery { converter.convertAsOf(10_000.0, "JPY", "EUR", yen.date) } returns conversion("JPY", 10_000.0, 60.0)

        val over = engine(FakeRepo(listOf(listOf(yen))), converter)
        assertEquals(listOf(1L), over.executeSearch(over.interpretQuery("over €50")).map { it.expense.id })

        val under = engine(FakeRepo(listOf(listOf(yen))), converter)
        assertEquals(emptyList<Long>(), under.executeSearch(under.interpretQuery("under €50")).map { it.expense.id })
    }

    @Test
    fun `gross exactly 50_00 is not OVER even when the effective share is larger`() = runTest {
        // gross 50.00 is not over 50; an effective-based filter (80.0) would wrongly include it.
        assertEquals(emptyList<Long>(), idsFor("over €50", listOf(row(1, 50.00, effectiveAmount = 80.0))))
        // and it is not UNDER either, although the effective share (20.0) is below 50.
        assertEquals(emptyList<Long>(), idsFor("under €50", listOf(row(1, 50.00, effectiveAmount = 20.0))))
    }

    // ---- alternative and combined bounds (end to end through interpretQuery) ----

    @Test
    fun `under 10 or over 50 matches both outer rows and not the middle one`() = runTest {
        val rows = listOf(row(1, 5.0), row(2, 60.0), row(3, 20.0))

        assertEquals(listOf(1L, 2L), idsFor("under 10 or over 50", rows))
    }

    @Test
    fun `over 10 under 50 matches only the middle row`() = runTest {
        val rows = listOf(row(1, 5.0), row(2, 60.0), row(3, 20.0))

        assertEquals(listOf(3L), idsFor("over 10 under 50", rows))
    }

    // ---- currency normalisation ----

    @Test
    fun `foreign row compares its normalised amount at the boundary`() = runTest {
        val usdAtBoundary = row(1, 55.0, currency = "USD")
        val usdJustOver = row(2, 60.0, currency = "USD")
        val usdSmallRawLargeHome = row(3, 40.0, currency = "USD")
        val converter = mockk<CurrencyConverter>()
        coEvery {
            converter.convertAsOf(55.0, "USD", "EUR", usdAtBoundary.date)
        } returns conversion("USD", 55.0, 50.00)
        coEvery {
            converter.convertAsOf(60.0, "USD", "EUR", usdJustOver.date)
        } returns conversion("USD", 60.0, 50.01)
        coEvery {
            converter.convertAsOf(40.0, "USD", "EUR", usdSmallRawLargeHome.date)
        } returns conversion("USD", 40.0, 52.0)
        val engine = engine(FakeRepo(listOf(listOf(usdAtBoundary, usdJustOver, usdSmallRawLargeHome))), converter)

        val results = engine.executeSearch(engine.interpretQuery("over €50"))

        // Raw 55 > 50 would wrongly include the boundary row and raw 40 would wrongly drop the last one.
        assertEquals(listOf(2L, 3L), results.map { it.expense.id })
    }

    @Test
    fun `home currency rows never touch the converter`() = runTest {
        val converter = mockk<CurrencyConverter>()
        val engine = engine(FakeRepo(listOf(listOf(row(1, 60.0), row(2, 10.0)))), converter)

        val results = engine.executeSearch(engine.interpretQuery("over €50"))

        assertEquals(listOf(1L), results.map { it.expense.id })
        coVerify(exactly = 0) { converter.convertAsOf(any(), any<String>(), any<String>(), any()) }
    }

    @Test
    fun `row with no rate is excluded and counted under an amount filter with no raw fallback`() = runTest {
        val noRateBig = row(1, 100.0, currency = "USD")
        val noRateSmall = row(2, 1.0, currency = "USD")
        val home = row(3, 75.0)
        val converter = mockk<CurrencyConverter>()
        coEvery { converter.convertAsOf(100.0, "USD", "EUR", noRateBig.date) } returns null
        coEvery { converter.convertAsOf(1.0, "USD", "EUR", noRateSmall.date) } returns null

        // OVER: raw 100 > 50 would match if there were a raw fallback.
        val over = engine(FakeRepo(listOf(listOf(noRateBig, noRateSmall, home))), converter)
        val overInterpretation = over.interpretQuery("over €50")
        assertEquals(listOf(3L), over.executeSearch(overInterpretation).map { it.expense.id })
        assertEquals(2, overInterpretation.dataQuality.failedCurrencyConversions)
        assertTrue(overInterpretation.dataQuality.hasWarnings)

        // UNDER: raw 1 < 50 would match if there were a raw fallback.
        val under = engine(FakeRepo(listOf(listOf(noRateBig, noRateSmall, home))), converter)
        val underInterpretation = under.interpretQuery("under €80")
        assertEquals(listOf(3L), under.executeSearch(underInterpretation).map { it.expense.id })
        assertEquals(2, underInterpretation.dataQuality.failedCurrencyConversions)
    }

    @Test
    fun `row with no rate is kept and not counted without an amount filter`() = runTest {
        val converter = mockk<CurrencyConverter>()
        val noRate = row(1, 100.0, currency = "USD")
        val engine = engine(FakeRepo(listOf(listOf(noRate, row(2, 5.0)))), converter)
        val interpretation = engine.interpretQuery("show my recent spending")
        assertNull(interpretation.extractedAmounts)

        val results = engine.executeSearch(interpretation)

        assertEquals(listOf(1L, 2L), results.map { it.expense.id })
        assertEquals(0, interpretation.dataQuality.failedCurrencyConversions)
        assertFalse(interpretation.dataQuality.hasWarnings)
        coVerify(exactly = 0) { converter.convertAsOf(any(), any<String>(), any<String>(), any()) }
    }

    // ---- paging ----

    @Test
    fun `amount filter keeps paging after a raw page with no matches and advances the cursor from the raw page`() = runTest {
        val page1 = fullPage(firstId = 1, amount = 1.0)
        val page2 = listOf(row(501, 75.0))
        val repo = FakeRepo(listOf(page1, page2))
        val engine = engine(repo)

        val results = engine.executeSearch(engine.interpretQuery("over €50"))

        assertEquals(listOf(501L), results.map { it.expense.id })
        assertEquals(2, repo.calls.size)
        assertNull(repo.calls[0].cursor)
        assertEquals(SearchCursor(date = page1.last().date, id = 500L), repo.calls[1].cursor)
        assertTrue(repo.calls.all { it.merchants == null && it.limit == pageSize })
    }

    @Test
    fun `several merchants keep paging after a raw page with no merchant matches`() = runTest {
        val page1 = fullPage(firstId = 1, merchant = "Gamma")
        val page2 = listOf(row(501, 5.0, merchant = "ALPHA Cafe"), row(502, 5.0, merchant = "Gamma"))
        val repo = FakeRepo(listOf(page1, page2))
        val engine = engine(repo)

        val results = engine.executeSearch(manual(merchants = listOf("alpha", "Beta")))

        assertEquals(listOf(501L), results.map { it.expense.id })
        assertEquals(2, repo.calls.size)
        assertNull(repo.calls[0].merchants)
        assertNull(repo.calls[1].merchants)
        assertEquals(SearchCursor(date = page1.last().date, id = 500L), repo.calls[1].cursor)
    }

    @Test
    fun `single merchant is pushed down to the repository`() = runTest {
        val repo = FakeRepo(listOf(listOf(row(1, 5.0, merchant = "Alpha Cafe"))))
        val engine = engine(repo)

        val results = engine.executeSearch(manual(merchants = listOf("Alpha")))

        assertEquals(listOf(1L), results.map { it.expense.id })
        assertEquals(1, repo.calls.size)
        assertEquals(listOf("Alpha"), repo.calls.single().merchants)
    }

    @Test
    fun `full page followed by an empty page stops and keeps the full page`() = runTest {
        val page1 = fullPage(firstId = 1)
        val repo = FakeRepo(listOf(page1, emptyList()))
        val engine = engine(repo)

        val results = engine.executeSearch(manual())

        assertEquals(pageSize, results.size)
        assertEquals(2, repo.calls.size)
        assertEquals(SearchCursor(date = page1.last().date, id = 500L), repo.calls[1].cursor)
    }

    @Test
    fun `empty first page makes exactly one call`() = runTest {
        val repo = FakeRepo(listOf(emptyList()))
        val engine = engine(repo)

        assertTrue(engine.executeSearch(manual()).isEmpty())
        assertEquals(1, repo.calls.size)
    }

    @Test
    fun `short last page ends paging and every call uses the page limit`() = runTest {
        val repo = FakeRepo(listOf(fullPage(1), fullPage(501), listOf(row(1001, 1.0))))
        val engine = engine(repo)

        val results = engine.executeSearch(manual())

        assertEquals(1001, results.size)
        assertEquals(3, repo.calls.size)
        assertTrue(repo.calls.all { it.limit == pageSize })
    }

    // ---- cancellation ----

    @Test
    fun `cancellation from the repository propagates`() = runTest {
        val engine = engine(FakeRepo(emptyList(), failure = CancellationException("synthetic")))

        try {
            engine.executeSearch(manual())
            fail("CancellationException was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("synthetic", expected.message)
        }
    }

    @Test
    fun `cancellation from the converter propagates`() = runTest {
        val usd = row(1, 60.0, currency = "USD")
        val converter = mockk<CurrencyConverter>()
        coEvery { converter.convertAsOf(60.0, "USD", "EUR", usd.date) } throws CancellationException("synthetic")
        val engine = engine(FakeRepo(listOf(listOf(usd))), converter)
        val interpretation = engine.interpretQuery("over €50")

        try {
            engine.executeSearch(interpretation)
            fail("CancellationException was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("synthetic", expected.message)
        }
    }

    // ---- logging ----

    @Test
    fun `no-rate log carries a count and currency only and no query or merchant text`() = runTest {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            val a = row(1, 100.0, currency = "USD", merchant = "SecretMerchant Ltd")
            val b = row(2, 200.0, currency = "USD", merchant = "SecretMerchant Ltd")
            val converter = mockk<CurrencyConverter>()
            coEvery { converter.convertAsOf(100.0, "USD", "EUR", a.date) } returns null
            coEvery { converter.convertAsOf(200.0, "USD", "EUR", b.date) } returns null
            val engine = engine(FakeRepo(listOf(listOf(a, b))), converter)
            val interpretation = manual(
                amounts = listOf(ExtractedAmount(50.0, AmountComparison.OVER)),
                merchants = listOf("SecretMerchant"),
                originalQuery = "secret query text"
            )

            engine.executeSearch(interpretation)

            assertTrue("a no-rate warning must be logged", messages.any { it.contains("2") && it.contains("EUR") })
            messages.forEach { message ->
                assertFalse(message, message.contains("SecretMerchant", ignoreCase = true))
                assertFalse(message, message.contains("secret query", ignoreCase = true))
                assertFalse(message, message.contains("Ltd"))
            }
        } finally {
            Timber.uproot(tree)
        }
    }

    // ---- confidence label (pinned as written) ----

    @Test
    fun `amount plus merchant labels EXACT`() = runTest {
        val engine = engine(FakeRepo(listOf(listOf(row(1, 10.0, merchant="Alpha Cafe")))))

        val results = engine.executeSearch(
            manual(amounts = listOf(ExtractedAmount(5.0, AmountComparison.OVER)), merchants = listOf("alpha"))
        )

        assertEquals(listOf(MatchType.EXACT), results.map { it.matchType })
    }

    @Test
    fun `amount only labels EXACT`() = runTest {
        val engine = engine(FakeRepo(listOf(listOf(row(1, 10.0)))))

        val results = engine.executeSearch(
            manual(amounts = listOf(ExtractedAmount(5.0, AmountComparison.OVER)))
        )

        assertEquals(listOf(MatchType.EXACT), results.map { it.matchType })
    }

    @Test
    fun `row whose merchant does not match a pushed-down merchant labels PARTIAL (characterisation)`() = runTest {
        val engine = engine(FakeRepo(listOf(listOf(row(1, 10.0, merchant = "Other")))))

        val results = engine.executeSearch(
            manual(amounts = listOf(ExtractedAmount(5.0, AmountComparison.OVER)), merchants = listOf("Alpha"))
        )

        assertEquals(listOf(MatchType.PARTIAL), results.map { it.matchType })
    }
}
