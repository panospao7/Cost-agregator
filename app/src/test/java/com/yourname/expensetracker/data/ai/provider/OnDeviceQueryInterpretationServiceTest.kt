package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

class OnDeviceQueryInterpretationServiceTest {

    private val service = OnDeviceQueryInterpretationService()

    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @Before
    fun plantLogTree() {
        logs.clear()
        Timber.plant(logTree)
    }

    @After
    fun removeLogTree() {
        Timber.uproot(logTree)
    }

    /** Category lookup that throws the given exception when the parser resolves a category name. */
    private class ThrowingCategoryLookup(
        private val toThrow: () -> Throwable
    ) : Map<String, Long> by emptyMap() {
        override fun get(key: String): Long? = throw toThrow()
    }

    private val sampleInput = FinancialQueryInterpretationInput(
        rawQuery = "top merchants this month",
        currentTimeMs = 1_000L,
        localeTag = "en-US",
        categoryNames = listOf("Groceries", "Transport"),
        merchantNames = listOf("Lidl", "Uber"),
        merchantLookupMap = mapOf("Lidl" to "Lidl", "Uber" to "Uber"),
        categoryLookupMap = mapOf("Groceries" to 1L, "Transport" to 2L),
        categoryNameToIdMap = mapOf("Groceries" to 1L, "Transport" to 2L)
    )

    @Test
    fun `buildPrompt includes raw query and known dimensions`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("top merchants this month"))
        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("Lidl"))
        assertTrue(prompt.contains("structured|clarification|unsupported"))
    }

    @Test
    fun `buildPrompt uses alias only lookup keys when redacted`() {
        val input = sampleInput.copy(
            rawQuery = "merchant_a category_a",
            categoryNames = listOf("category_a"),
            merchantNames = listOf("merchant_a"),
            merchantLookupMap = mapOf("merchant_a" to "Lidl", "Lidl" to "Lidl"),
            merchantAliasMap = mapOf("merchant_a" to "Lidl"),
            categoryLookupMap = mapOf("category_a" to 10L, "Groceries" to 10L),
            categoryAliasMap = mapOf("category_a" to "Groceries"),
            categoryNameToIdMap = mapOf("category_a" to 10L, "Groceries" to 10L)
        )

        val prompt = service.buildPrompt(input)

        assertTrue(prompt.contains("Known category lookup keys: category_a"))
        assertTrue(prompt.contains("Known merchant lookup keys: merchant_a"))
        assertTrue(!prompt.contains("Known category lookup keys: Groceries"))
        assertTrue(!prompt.contains("Known merchant lookup keys: Lidl"))
    }

    private fun historySectionLines(prompt: String): List<String> {
        val lines = prompt.lines()
        val headerIndex = lines.indexOf("Conversation history:")
        assertTrue("Conversation history header must be present", headerIndex >= 0)
        return lines.drop(headerIndex + 1).takeWhile { it.isNotBlank() }
    }

    @Test
    fun `AE-C2 buildPrompt renders prior user queries as USER lines only`() {
        val input = sampleInput.copy(
            priorUserQueries = listOf("food last week", "and transport")
        )

        val prompt = service.buildPrompt(input)
        val history = historySectionLines(prompt)

        assertEquals(listOf("- USER: food last week", "- USER: and transport"), history)
        assertTrue(!prompt.contains("ASSISTANT"))
        assertTrue(!prompt.contains("SYSTEM"))
        assertTrue(!prompt.contains("payloadJson"))
    }

    @Test
    fun `AE-C5 buildPrompt renders none when prior user queries are empty`() {
        val input = sampleInput.copy(priorUserQueries = emptyList())

        val prompt = service.buildPrompt(input)
        val history = historySectionLines(prompt)

        assertEquals(listOf("none"), history)
        assertTrue(!prompt.contains("- USER:"))
    }

    @Test
    fun `parseResponse handles structured result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","merchantNames":["Lidl"],"minAmount":null,"maxAmount":null}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryMetric.TOTAL, structured.intent.metric)
        assertEquals(QueryGrouping.MERCHANT, structured.intent.grouping)
        assertEquals(setOf("Lidl"), structured.intent.filters.merchants)
    }

    @Test
    fun `parseResponse handles clarification result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"clarification","clarification":{"prompt":"Do you want a total or a list?","options":["Total","List"]}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Clarification)
        val clarification = result as FinancialQueryInterpretationResult.Clarification
        assertEquals("Do you want a total or a list?", clarification.prompt)
        assertEquals(listOf("Total", "List"), clarification.options)
    }

    @Test
    fun `parseResponse handles unsupported result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"unsupported","unsupportedReason":"This query is not supported."}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `parseResponse resolves redacted aliases and multi value filters`() {
        val input = sampleInput.copy(
            categoryNames = listOf("category_a", "category_b"),
            merchantNames = listOf("merchant_a", "merchant_b"),
            merchantLookupMap = mapOf("merchant_a" to "Lidl", "merchant_b" to "Uber"),
            merchantAliasMap = mapOf("merchant_a" to "Lidl", "merchant_b" to "Uber"),
            categoryLookupMap = mapOf(
                "category_a" to 10L,
                "category_b" to 11L,
                "Groceries" to 10L,
                "Transport" to 11L
            ),
            categoryAliasMap = mapOf("category_a" to "Groceries", "category_b" to "Transport"),
            categoryNameToIdMap = mapOf("category_a" to 10L, "category_b" to 11L)
        )

        val result = service.parseResponse(
            input,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","categoryNames":["category_a","category_b"],"merchantNames":["merchant_a","merchant_b"],"transactionTypes":["PURCHASE","DEPOSIT"],"periodKeyword":"THIS_MONTH"}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(setOf(10L, 11L), structured.intent.filters.categoryIds)
        assertEquals(setOf("Lidl", "Uber"), structured.intent.filters.merchants)
        assertEquals(2, structured.intent.filters.transactionTypes.size)
        assertNotNull(structured.intent.filters.period)
    }

    @Test
    fun `parseResponse honors explicit period payload`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"NONE","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","period":{"startMs":123,"endMs":456}}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(123L, structured.intent.filters.period?.start)
        assertEquals(456L, structured.intent.filters.period?.end)
    }

    @Test(timeout = 10_000)
    fun `AE-C8 model unsupported text is discarded and result carries MODEL_DECLINED only`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"unsupported","unsupportedReason":"rent 4242 4242 4242 4242 at Lidl"}"""
        )

        assertEquals(
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.MODEL_DECLINED),
            result
        )
        val rendered = result.toString()
        assertTrue(!rendered.contains("Lidl"))
        assertTrue(!rendered.contains("4242"))
        assertTrue(logs.none { it.second.contains("Lidl") || it.second.contains("4242") })
    }

    @Test(timeout = 10_000)
    fun `AE-C8 unsupported without any reason text still yields MODEL_DECLINED`() {
        val result = service.parseResponse(sampleInput, """{"kind":"unsupported"}""")

        assertEquals(
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.MODEL_DECLINED),
            result
        )
    }

    @Test(timeout = 10_000)
    fun `INV-C5 buildPrompt schema no longer asks the model for unsupportedReason text`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(!prompt.contains("unsupportedReason"))
        // Control: the kind enum that still lets the model decline is intact.
        assertTrue(prompt.contains("structured|clarification|unsupported"))
    }

    @Test(timeout = 10_000)
    fun `AE-C10 malformed json logs exception class only and returns null`() {
        val sentinel = "SENSITIVE-9911"
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","note":"$sentinel" broken}"""
        )

        assertNull(result)
        assertTrue(
            "a log line must carry the code and exception class",
            logs.any {
                it.second.contains(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID.name) &&
                    it.second.contains("JSONException")
            }
        )
        assertTrue("no throwable may be passed to Timber", logs.all { it.first == null })
        // NOTE: the sentinel-absence check below is NOT probative on its own. On the JVM test
        // classpath (org.json:json:20231013) JSONException messages carry only a position and
        // never the input text, so the sentinel could not appear even if e.message were logged.
        // The probative message-leak check is the next test, which injects the sentinel into the
        // exception message directly.
        assertTrue("input text must not be logged", logs.none { it.second.contains(sentinel) })
    }

    @Test(timeout = 10_000)
    fun `AE-C10 parse failure whose exception message carries the input logs class only`() {
        // On Android, org.json embeds the offending input in JSONException.message. Simulate
        // that by throwing a JSONException whose message carries sensitive text.
        val input = sampleInput.copy(
            categoryLookupMap = ThrowingCategoryLookup {
                org.json.JSONException("SENSITIVE-9911 /data/user/0/x token=abc123")
            }
        )

        val result = service.parseResponse(
            input,
            """{"kind":"structured","intent":{"metric":"TOTAL","categoryNames":["Groceries"]}}"""
        )

        assertNull(result)
        assertTrue(
            "a log line must carry the code and the exception class name",
            logs.any {
                it.second.contains(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID.name) &&
                    it.second.contains("JSONException")
            }
        )
        assertTrue("no throwable may be passed to Timber", logs.all { it.first == null })
        assertTrue(
            "exception message must not be logged",
            logs.none {
                it.second.contains("SENSITIVE-9911") ||
                    it.second.contains("/data/user") ||
                    it.second.contains("abc123")
            }
        )
    }

    @Test(timeout = 10_000)
    fun `AE-C10 non cancellation failure inside parse logs class only without message`() {
        val sentinel = "/data/user/0/x/db token=abc123"
        val input = sampleInput.copy(
            categoryLookupMap = ThrowingCategoryLookup { IllegalStateException(sentinel) }
        )

        val result = service.parseResponse(
            input,
            """{"kind":"structured","intent":{"metric":"TOTAL","categoryNames":["Groceries"]}}"""
        )

        assertNull(result)
        assertTrue(
            logs.any {
                it.second.contains(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID.name) &&
                    it.second.contains("IllegalStateException")
            }
        )
        assertTrue(logs.all { it.first == null })
        assertTrue(logs.none { it.second.contains("abc123") || it.second.contains("/data") })
    }

    @Test(timeout = 10_000)
    fun `AE-C10 CancellationException inside parseResponse propagates and is not logged as a parse failure`() {
        val input = sampleInput.copy(
            categoryLookupMap = ThrowingCategoryLookup { CancellationException("cancelled") }
        )

        var thrown: Throwable? = null
        try {
            service.parseResponse(
                input,
                """{"kind":"structured","intent":{"metric":"TOTAL","categoryNames":["Groceries"]}}"""
            )
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("CancellationException must propagate out of parseResponse", thrown)
        assertTrue(
            "cancellation must not be reported as PROVIDER_RESPONSE_INVALID",
            logs.none { it.second.contains(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID.name) }
        )
    }

    @Test(timeout = 10_000)
    fun `PC-11 validateAmountBound logs no amount digits for excessive bounds`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","minAmount":1987654321,"maxAmount":3456789012}}"""
        )

        val filters = (result as FinancialQueryInterpretationResult.Structured).intent.filters
        assertEquals(1_000_000_000.0, filters.minAmount!!, 0.0)
        assertEquals(1_000_000_000.0, filters.maxAmount!!, 0.0)
        assertTrue(logs.all { it.first == null })
        assertTrue(logs.none { it.second.contains("987654321") })
        assertTrue(logs.none { it.second.contains("456789012") })
    }

    @Test(timeout = 10_000)
    fun `PC-11 validateAmountBound logs no amount digits for negative bounds`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","minAmount":-7654.32}}"""
        )

        val filters = (result as FinancialQueryInterpretationResult.Structured).intent.filters
        assertEquals(0.0, filters.minAmount!!, 0.0)
        assertTrue(logs.none { it.second.contains("7654") })
    }

    // ---- ai-fixes C3: period keywords via AssistantPeriodResolver (INV-C8, AE-C11) --------------
    //
    // The parser resolves keywords in ZoneId.systemDefault(); the JVM default zone is pinned to
    // Europe/Athens and restored afterwards. NOW = Fri 2026-10-16 10:00 EEST.

    private var originalZone: TimeZone? = null
    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    @Before
    fun pinDefaultZoneToAthens() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
    }

    @After
    fun restoreDefaultZone() {
        originalZone?.let { TimeZone.setDefault(it) }
    }

    private fun athensMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, athens).toInstant().toEpochMilli()

    private fun inputAtNow() = sampleInput.copy(currentTimeMs = athensMs(2026, 10, 16, 10, 0))

    private fun parseFilters(intentJsonBody: String): ExpenseQueryFilters {
        val result = service.parseResponse(
            inputAtNow(),
            """{"kind":"structured","intent":{"metric":"TOTAL",$intentJsonBody}}"""
        )
        assertTrue("expected Structured but was $result", result is FinancialQueryInterpretationResult.Structured)
        return (result as FinancialQueryInterpretationResult.Structured).intent.filters
    }

    @Test(timeout = 10_000)
    fun `AE-C11 model keyword LAST_WEEK resolves the previous Monday to Monday week`() {
        val filters = parseFilters(""""periodKeyword":"LAST_WEEK"""")

        assertEquals(athensMs(2026, 10, 5), filters.period?.start)
        assertEquals(athensMs(2026, 10, 12), filters.period?.end)
        assertEquals(PeriodGranularity.WEEK, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `AE-C11 model keyword LAST_QUARTER resolves the previous calendar quarter`() {
        val filters = parseFilters(""""periodKeyword":"LAST_QUARTER"""")

        assertEquals(athensMs(2026, 7, 1), filters.period?.start)
        assertEquals(athensMs(2026, 10, 1), filters.period?.end)
        assertEquals(PeriodGranularity.QUARTER, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `AE-C11 model keyword LAST_YEAR resolves the previous calendar year`() {
        val filters = parseFilters(""""periodKeyword":"LAST_YEAR"""")

        assertEquals(athensMs(2025, 1, 1), filters.period?.start)
        assertEquals(athensMs(2026, 1, 1), filters.period?.end)
        assertEquals(PeriodGranularity.YEAR, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `AE-C11 model keyword LAST_MONTH keeps resolving the previous full month with MONTH granularity`() {
        val filters = parseFilters(""""periodKeyword":"LAST_MONTH"""")

        assertEquals(athensMs(2026, 9, 1), filters.period?.start)
        assertEquals(athensMs(2026, 10, 1), filters.period?.end)
        assertEquals(PeriodGranularity.MONTH, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 model keyword THIS_MONTH control is the current full month with MONTH granularity`() {
        val filters = parseFilters(""""periodKeyword":"THIS_MONTH"""")

        assertEquals(athensMs(2026, 10, 1), filters.period?.start)
        assertEquals(athensMs(2026, 11, 1), filters.period?.end)
        assertEquals(PeriodGranularity.MONTH, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 model keyword is case insensitive`() {
        val filters = parseFilters(""""periodKeyword":"last_week"""")

        assertEquals(athensMs(2026, 10, 5), filters.period?.start)
        assertEquals(athensMs(2026, 10, 12), filters.period?.end)
        assertEquals(PeriodGranularity.WEEK, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 unknown model keyword yields no period and no granularity`() {
        val filters = parseFilters(""""periodKeyword":"LAST_DECADE"""")

        assertNull(filters.period)
        assertNull(filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 explicit model startMs and endMs gives null granularity`() {
        val filters = parseFilters(""""period":{"startMs":123,"endMs":456}""")

        assertEquals(123L, filters.period?.start)
        assertEquals(456L, filters.period?.end)
        assertNull(filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 explicit range wins over a keyword and the granularity stays null`() {
        val filters = parseFilters(""""periodKeyword":"LAST_MONTH","period":{"startMs":123,"endMs":456}""")

        assertEquals(123L, filters.period?.start)
        assertEquals(456L, filters.period?.end)
        assertNull(filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 invalid explicit range with end not after start falls back to the keyword`() {
        val filters = parseFilters(""""periodKeyword":"THIS_MONTH","period":{"startMs":456,"endMs":123}""")

        assertEquals(athensMs(2026, 10, 1), filters.period?.start)
        assertEquals(athensMs(2026, 11, 1), filters.period?.end)
        assertEquals(PeriodGranularity.MONTH, filters.periodGranularity)
    }

    @Test(timeout = 10_000)
    fun `INV-C8 buildPrompt schema periodKeyword line lists all nine keywords`() {
        val prompt = service.buildPrompt(sampleInput)

        val schemaLine = prompt.lines().single { it.contains("\"periodKeyword\":") }
        val allKeywords = listOf(
            "TODAY", "THIS_WEEK", "LAST_WEEK", "THIS_MONTH", "LAST_MONTH",
            "THIS_QUARTER", "LAST_QUARTER", "THIS_YEAR", "LAST_YEAR"
        )
        allKeywords.forEach { keyword ->
            assertTrue("schema line must contain $keyword", schemaLine.contains(keyword))
        }
        // The enumerated value of "periodKeyword" is exactly the nine keywords plus null.
        val enumeration = Regex("\"periodKeyword\":\"([^\"]*)\"").find(schemaLine)!!.groupValues[1].split("|")
        assertEquals(allKeywords.toSet(), enumeration.filter { it != "null" }.toSet())
        assertEquals(allKeywords.size, enumeration.filter { it != "null" }.size)
        assertTrue(enumeration.contains("null"))
    }
}
