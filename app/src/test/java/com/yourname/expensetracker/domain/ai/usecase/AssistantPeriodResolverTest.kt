package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * ai-fixes Part C, C-3: period vocabulary, comparison cue and calendar-aware previous equivalent
 * (INV-C8..INV-C12, AE-C11..AE-C19). Expected instants are derived from calendar fields in
 * Europe/Athens; no raw epoch numbers except the documented CUSTOM example (AE-C17).
 */
class AssistantPeriodResolverTest {

    private val zone: ZoneId = ZoneId.of("Europe/Athens")

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** 2026-10-16T12:00 Europe/Athens (a Friday). */
    private val now = ms(2026, 10, 16, 12, 0)

    private fun assertPeriod(
        expectedStart: Long,
        expectedEnd: Long,
        expectedGranularity: PeriodGranularity?,
        actual: AssistantPeriod
    ) {
        assertEquals(AssistantPeriod(expectedStart, expectedEnd, expectedGranularity), actual)
    }

    private fun resolve(query: String, nowMs: Long = now) =
        AssistantPeriodResolver.resolveText(query, nowMs, zone)

    // ---- AE-C11 (INV-C8, INV-C9): last/previous <unit> is the previous FULL unit ----------------

    @Test
    fun `AE-C11 food last month resolves to September full month without comparison`() {
        val result = resolve("food last month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `AE-C11 last week resolves to the previous Monday-based week`() {
        val result = resolve("last week")
        assertPeriod(ms(2026, 10, 5), ms(2026, 10, 12), PeriodGranularity.WEEK, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `AE-C11 last quarter resolves to the previous full quarter`() {
        val result = resolve("last quarter")
        assertPeriod(ms(2026, 7, 1), ms(2026, 10, 1), PeriodGranularity.QUARTER, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `AE-C11 last year resolves to the previous full year`() {
        val result = resolve("last year")
        assertPeriod(ms(2025, 1, 1), ms(2026, 1, 1), PeriodGranularity.YEAR, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `AE-C11 spending previous month resolves to September without comparison`() {
        val result = resolve("spending previous month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `AE-C11 prior is accepted as a synonym of last`() {
        val result = resolve("spending prior month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
    }

    // ---- INV-C9 shapes of the current unit (P3) and precedence -----------------------------------

    @Test
    fun `INV-C9 today is DAY to-date ending at now`() {
        val result = resolve("what did i spend today")
        assertPeriod(ms(2026, 10, 16), now, PeriodGranularity.DAY, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `INV-C9 this week and this month are FULL units`() {
        assertPeriod(
            ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK,
            resolve("this week").period
        )
        assertPeriod(
            ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH,
            resolve("this month").period
        )
    }

    @Test
    fun `INV-C9 this quarter and this year are TO_DATE units ending at now`() {
        assertPeriod(
            ms(2026, 10, 1), now, PeriodGranularity.QUARTER,
            resolve("this quarter").period
        )
        assertPeriod(
            ms(2026, 1, 1), now, PeriodGranularity.YEAR,
            resolve("current year").period
        )
    }

    @Test
    fun `INV-C9 default with no period phrase is this month`() {
        val result = resolve("food")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertFalse(result.comparisonRequested)
    }

    @Test
    fun `INV-C9 bare unit keeps substring semantics and resolves to the current unit`() {
        assertPeriod(
            ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK,
            resolve("weekly spending").period
        )
        assertPeriod(
            ms(2026, 1, 1), now, PeriodGranularity.YEAR,
            resolve("yearly spending").period
        )
    }

    @Test
    fun `INV-C9 last phrase uses word boundaries so last yearly is not last year`() {
        // "last yearly": step 3 needs "year" at a word boundary; falls through to the bare-unit tier.
        assertPeriod(
            ms(2026, 1, 1), now, PeriodGranularity.YEAR,
            resolve("last yearly").period
        )
    }

    @Test
    fun `INV-C9 today wins over last unit`() {
        assertPeriod(
            ms(2026, 10, 16), now, PeriodGranularity.DAY,
            resolve("today last month").period
        )
    }

    @Test
    fun `INV-C9 last unit wins over this unit`() {
        assertPeriod(
            ms(2025, 1, 1), ms(2026, 1, 1), PeriodGranularity.YEAR,
            resolve("last year this month").period
        )
    }

    @Test
    fun `INV-C9 within one tier the smaller unit wins`() {
        assertPeriod(
            ms(2026, 10, 5), ms(2026, 10, 12), PeriodGranularity.WEEK,
            resolve("last month last week").period
        )
    }

    // ---- AE-C12 / AE-C13 / AE-C14: comparison target removal -------------------------------------

    @Test
    fun `AE-C12 compare food with previous month has base October FULL and previous September`() {
        val result = resolve("compare food with previous month")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, previous)
    }

    @Test
    fun `AE-C13 compare last month with previous month has base September and previous August`() {
        val result = resolve("compare last month with previous month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        assertPeriod(ms(2026, 8, 1), ms(2026, 9, 1), PeriodGranularity.MONTH, previous)
    }

    @Test
    fun `AE-C14 food this month vs last month has base October and comparison cue`() {
        val result = resolve("food this month vs last month")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    @Test
    fun `AE-C14 vs with trailing dot also removes the target`() {
        val result = resolve("food this month vs. last month")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    @Test
    fun `INV-C9 versus and against connectors remove the comparison target`() {
        // "versus" is a cue; "against" is a connector only (cue present through "compare").
        assertPeriod(
            ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH,
            resolve("this month versus previous month").period
        )
        assertPeriod(
            ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH,
            resolve("compare this month against last month").period
        )
    }

    @Test
    fun `INV-C9 last unit without a comparison connector is not removed even when a cue is present`() {
        // The cue is present, but "last month" does not follow a connector, so it is the period.
        val result = resolve("compare last month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    @Test
    fun `INV-C9 connector false positive - with a non-period word keeps last month as the base period`() {
        // "with coffee" is not followed by (the)? last|previous|prior <unit>, so nothing is removed.
        val result = resolve("compare food with coffee last month")
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    @Test
    fun `INV-C9 connector positive - compare to last month removes the target and keeps this month`() {
        val result = resolve("compare my spending to last month")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    // ---- AE-C15 / AE-C16: TO_DATE previous equivalent --------------------------------------------

    @Test
    fun `AE-C15 compare this quarter previous is the same elapsed span of the previous quarter`() {
        val result = resolve("compare this quarter")
        assertPeriod(ms(2026, 10, 1), now, PeriodGranularity.QUARTER, result.period)
        assertTrue(result.comparisonRequested)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        assertPeriod(ms(2026, 7, 1), ms(2026, 7, 16, 12, 0), PeriodGranularity.QUARTER, previous)
        // Explicitly not the previous month's span.
        assertNotEquals(ms(2026, 9, 1), previous.startMs)
    }

    @Test
    fun `AE-C15 compare this year previous is the same elapsed span of the previous year`() {
        val result = resolve("compare this year")
        assertPeriod(ms(2026, 1, 1), now, PeriodGranularity.YEAR, result.period)
        assertTrue(result.comparisonRequested)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        assertPeriod(ms(2025, 1, 1), ms(2025, 10, 16, 12, 0), PeriodGranularity.YEAR, previous)
    }

    @Test
    fun `AE-C16 compare this quarter on May 31 clamps previous end to Feb 28`() {
        val may31 = ms(2026, 5, 31, 12, 0)
        val result = resolve("compare this quarter", may31)
        assertPeriod(ms(2026, 4, 1), may31, PeriodGranularity.QUARTER, result.period)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        assertPeriod(ms(2026, 1, 1), ms(2026, 2, 28, 12, 0), PeriodGranularity.QUARTER, previous)
    }

    @Test
    fun `INV-C11 TO_DATE month on Mar 31 clamps previous end to Feb 28`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 3, 1), ms(2026, 3, 31, 12, 0), PeriodGranularity.MONTH, zone
        )
        assertPeriod(ms(2026, 2, 1), ms(2026, 2, 28, 12, 0), PeriodGranularity.MONTH, previous)
    }

    @Test
    fun `INV-C11 TO_DATE week keeps the same elapsed span of the previous week`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 10, 12), ms(2026, 10, 14, 12, 0), PeriodGranularity.WEEK, zone
        )
        assertPeriod(ms(2026, 10, 5), ms(2026, 10, 7, 12, 0), PeriodGranularity.WEEK, previous)
    }

    // ---- AE-C18 DST (INV-C11) --------------------------------------------------------------------

    @Test
    fun `AE-C18 October 2026 is 745 hours long because of the DST fall-back`() {
        val hours = Duration.ofMillis(ms(2026, 11, 1) - ms(2026, 10, 1)).toHours()
        // 31 days * 24 h + 1 extra hour on Sunday 2026-10-25.
        assertEquals(745L, hours)
    }

    @Test
    fun `AE-C18 previous of October FULL with MONTH granularity is September 1 EEST to October 1 EEST`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, zone
        )
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, previous)
        assertDstPreviousMonthBoundaries(previous)
    }

    @Test
    fun `INV-C11 MONTH granularity with an unaligned start is CUSTOM not FULL`() {
        val start = ms(2026, 10, 10)
        val end = ms(2026, 11, 1)
        val previous = AssistantPeriodResolver.previousEquivalent(start, end, PeriodGranularity.MONTH, zone)
        assertNull(previous.granularity)
        assertEquals(start, previous.endMs)
        assertEquals(end - start, previous.endMs - previous.startMs)
    }

    @Test
    fun `AE-C18 previous of October FULL with null granularity classifies to MONTH exactly`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 10, 1), ms(2026, 11, 1), null, zone
        )
        assertPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH, previous)
        assertDstPreviousMonthBoundaries(previous)
    }

    private fun assertDstPreviousMonthBoundaries(previous: AssistantPeriod) {
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(previous.startMs), zone)
        val end = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(previous.endMs), zone)
        // September and the instant of Oct 1 00:00 are both EEST (UTC+3).
        assertEquals(ZoneOffset.ofHours(3), start.offset)
        assertEquals(ZoneOffset.ofHours(3), end.offset)
        assertEquals(0, start.hour)
        assertEquals(0, end.hour)
        // (The old 745 h duration rule would have landed at Aug 30 23:00 EEST; the exact
        // assertPeriod calls in the callers are the real check that it does not.)
    }

    @Test
    fun `AE-C18 DST TO_DATE today on Oct 26 maps to Oct 25 same local time`() {
        val nowAfterFallBack = ms(2026, 10, 26, 10, 0)
        val result = resolve("today", nowAfterFallBack)
        assertPeriod(ms(2026, 10, 26), nowAfterFallBack, PeriodGranularity.DAY, result.period)

        val previous = AssistantPeriodResolver.previousEquivalent(
            result.period.startMs, result.period.endMs, result.period.granularity, zone
        )
        // Oct 25 is 25 h long; local-time arithmetic gives Oct 25 00:00 -> Oct 25 10:00 local.
        assertPeriod(ms(2026, 10, 25), ms(2026, 10, 25, 10, 0), PeriodGranularity.DAY, previous)
        assertTrue(previous.endMs <= result.period.startMs)
    }

    @Test
    fun `AE-C18 FULL 25 hour day Oct 25 has previous Oct 24 with exact join`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 10, 25), ms(2026, 10, 26), null, zone
        )
        assertPeriod(ms(2026, 10, 24), ms(2026, 10, 25), PeriodGranularity.DAY, previous)
    }

    @Test
    fun `AE-C18 FULL 23 hour day Mar 29 has previous Mar 28 with exact join`() {
        val previous = AssistantPeriodResolver.previousEquivalent(
            ms(2026, 3, 29), ms(2026, 3, 30), PeriodGranularity.DAY, zone
        )
        assertPeriod(ms(2026, 3, 28), ms(2026, 3, 29), PeriodGranularity.DAY, previous)
    }

    // ---- AE-C17 CUSTOM (INV-C11) -----------------------------------------------------------------

    @Test
    fun `AE-C17 custom range 100 to 200 with null granularity shifts by its duration`() {
        // Existing ExecuteFinancialQueryUseCaseTest duration example: duration 100 ms.
        val previous = AssistantPeriodResolver.previousEquivalent(100L, 200L, null, zone)
        assertPeriod(0L, 100L, null, previous)
    }

    @Test
    fun `AE-C17 non-calendar multi-day range is CUSTOM and keeps null granularity`() {
        val start = ms(2026, 10, 3)
        val end = ms(2026, 10, 10)
        val previous = AssistantPeriodResolver.previousEquivalent(start, end, null, zone)
        assertEquals(start - (end - start), previous.startMs)
        assertEquals(start, previous.endMs)
        assertNull(previous.granularity)
    }

    @Test
    fun `AE-C17 to-date range with null granularity is not an exact FULL match so it is CUSTOM`() {
        val start = ms(2026, 10, 1)
        val end = ms(2026, 10, 16, 12, 0)
        val previous = AssistantPeriodResolver.previousEquivalent(start, end, null, zone)
        assertEquals(start - (end - start), previous.startMs)
        assertEquals(start, previous.endMs)
        assertNull(previous.granularity)
    }

    // ---- INV-C12 join invariant -------------------------------------------------------------------

    @Test
    fun `INV-C12 FULL units join exactly for every granularity`() {
        val cases = listOf(
            Triple(ms(2026, 10, 15), ms(2026, 10, 16), PeriodGranularity.DAY),
            Triple(ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK),
            Triple(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH),
            Triple(ms(2026, 7, 1), ms(2026, 10, 1), PeriodGranularity.QUARTER),
            Triple(ms(2026, 1, 1), ms(2027, 1, 1), PeriodGranularity.YEAR)
        )
        for ((start, end, granularity) in cases) {
            val explicit = AssistantPeriodResolver.previousEquivalent(start, end, granularity, zone)
            assertEquals("explicit $granularity", start, explicit.endMs)
            assertTrue("explicit $granularity start < end", explicit.startMs < explicit.endMs)
            assertEquals(granularity, explicit.granularity)

            val classified = AssistantPeriodResolver.previousEquivalent(start, end, null, zone)
            assertEquals("classified $granularity", start, classified.endMs)
            assertEquals(explicit, classified)
        }
    }

    @Test
    fun `INV-C12 FULL previous year and quarter boundaries are the previous calendar units`() {
        assertPeriod(
            ms(2025, 1, 1), ms(2026, 1, 1), PeriodGranularity.YEAR,
            AssistantPeriodResolver.previousEquivalent(ms(2026, 1, 1), ms(2027, 1, 1), null, zone)
        )
        assertPeriod(
            ms(2026, 4, 1), ms(2026, 7, 1), PeriodGranularity.QUARTER,
            AssistantPeriodResolver.previousEquivalent(ms(2026, 7, 1), ms(2026, 10, 1), null, zone)
        )
        // Month-end: previous of March is the whole of February (no 31st overflow).
        assertPeriod(
            ms(2026, 2, 1), ms(2026, 3, 1), PeriodGranularity.MONTH,
            AssistantPeriodResolver.previousEquivalent(ms(2026, 3, 1), ms(2026, 4, 1), null, zone)
        )
    }

    @Test
    fun `INV-C12 TO_DATE and CUSTOM never overlap the current period`() {
        val toDate = listOf(
            Triple(ms(2026, 10, 1), now, PeriodGranularity.QUARTER),
            Triple(ms(2026, 1, 1), now, PeriodGranularity.YEAR),
            Triple(ms(2026, 10, 26), ms(2026, 10, 26, 10, 0), PeriodGranularity.DAY),
            Triple(ms(2026, 3, 1), ms(2026, 3, 31, 12, 0), PeriodGranularity.MONTH),
            Triple(ms(2026, 10, 12), ms(2026, 10, 14, 12, 0), PeriodGranularity.WEEK)
        )
        for ((start, end, granularity) in toDate) {
            val previous = AssistantPeriodResolver.previousEquivalent(start, end, granularity, zone)
            assertTrue("TO_DATE $granularity prevEnd <= start", previous.endMs <= start)
            assertTrue("TO_DATE $granularity prevStart < prevEnd", previous.startMs < previous.endMs)
        }

        val custom = listOf(100L to 200L, ms(2026, 10, 3) to ms(2026, 10, 10))
        for ((start, end) in custom) {
            val previous = AssistantPeriodResolver.previousEquivalent(start, end, null, zone)
            assertTrue("CUSTOM prevEnd <= start", previous.endMs <= start)
        }
    }

    // ---- AE-C19 / INV-C10 comparison cue set -----------------------------------------------------

    @Test
    fun `AE-C19 food previous month is not a comparison`() {
        assertFalse(AssistantPeriodResolver.isComparisonRequested("food previous month"))
        assertFalse(resolve("food previous month").comparisonRequested)
    }

    @Test
    fun `AE-C19 bare previous is not a comparison cue`() {
        assertFalse(AssistantPeriodResolver.isComparisonRequested("previous"))
        assertFalse(AssistantPeriodResolver.isComparisonRequested("what about the previous one"))
    }

    @Test
    fun `AE-C19 comparing alone is not in the cue set`() {
        assertFalse(AssistantPeriodResolver.isComparisonRequested("comparing"))
    }

    @Test
    fun `AE-C19 cue words need word boundaries`() {
        assertFalse(AssistantPeriodResolver.isComparisonRequested("tvs and radios"))
        assertFalse(AssistantPeriodResolver.isComparisonRequested("conversations"))
    }

    @Test
    fun `AE-C19 food this month compared to last month is a comparison with base October`() {
        val result = resolve("food this month compared to last month")
        assertPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH, result.period)
        assertTrue(result.comparisonRequested)
    }

    @Test
    fun `AE-C19 each INV-C10 cue form is recognised`() {
        val cues = listOf(
            "compare",
            "compared",
            "comparison of food",
            "vs",
            "vs.",
            "food vs last month",
            "versus",
            "than the last month",
            "than last month",
            "more than previous quarter",
            "higher than prior year",
            "previous period"
        )
        for (cue in cues) {
            assertTrue("expected comparison cue for: $cue", AssistantPeriodResolver.isComparisonRequested(cue))
        }
    }

    @Test
    fun `AE-C19 than without a period target is not a cue`() {
        assertFalse(AssistantPeriodResolver.isComparisonRequested("more than 50 euros"))
    }

    // ---- resolveKeyword --------------------------------------------------------------------------

    @Test
    fun `resolveKeyword resolves all nine keywords with the correct shapes`() {
        val expected = mapOf(
            "TODAY" to AssistantPeriod(ms(2026, 10, 16), now, PeriodGranularity.DAY),
            "THIS_WEEK" to AssistantPeriod(ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK),
            "LAST_WEEK" to AssistantPeriod(ms(2026, 10, 5), ms(2026, 10, 12), PeriodGranularity.WEEK),
            "THIS_MONTH" to AssistantPeriod(ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH),
            "LAST_MONTH" to AssistantPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH),
            "THIS_QUARTER" to AssistantPeriod(ms(2026, 10, 1), now, PeriodGranularity.QUARTER),
            "LAST_QUARTER" to AssistantPeriod(ms(2026, 7, 1), ms(2026, 10, 1), PeriodGranularity.QUARTER),
            "THIS_YEAR" to AssistantPeriod(ms(2026, 1, 1), now, PeriodGranularity.YEAR),
            "LAST_YEAR" to AssistantPeriod(ms(2025, 1, 1), ms(2026, 1, 1), PeriodGranularity.YEAR)
        )
        for ((keyword, period) in expected) {
            assertEquals(keyword, period, AssistantPeriodResolver.resolveKeyword(keyword, now, zone))
        }
    }

    @Test
    fun `resolveKeyword is case-insensitive and trims whitespace`() {
        assertEquals(
            AssistantPeriod(ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK),
            AssistantPeriodResolver.resolveKeyword("  this_week \t", now, zone)
        )
        assertEquals(
            AssistantPeriod(ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH),
            AssistantPeriodResolver.resolveKeyword("Last_Month", now, zone)
        )
        assertEquals(
            AssistantPeriod(ms(2026, 10, 16), now, PeriodGranularity.DAY),
            AssistantPeriodResolver.resolveKeyword(" tOdAy ", now, zone)
        )
        assertEquals(
            AssistantPeriod(ms(2025, 1, 1), ms(2026, 1, 1), PeriodGranularity.YEAR),
            AssistantPeriodResolver.resolveKeyword("\nlast_year\n", now, zone)
        )
    }

    @Test
    fun `resolveKeyword returns null for unknown or malformed keywords`() {
        assertNull(AssistantPeriodResolver.resolveKeyword("THIS_DECADE", now, zone))
        assertNull(AssistantPeriodResolver.resolveKeyword("", now, zone))
        assertNull(AssistantPeriodResolver.resolveKeyword("   ", now, zone))
        assertNull(AssistantPeriodResolver.resolveKeyword("THIS MONTH", now, zone))
        assertNull(AssistantPeriodResolver.resolveKeyword("LAST_DAY", now, zone))
    }

    // ---- resolveChoice ---------------------------------------------------------------------------

    @Test
    fun `resolveChoice THIS_MONTH is the current FULL month`() {
        assertPeriod(
            ms(2026, 10, 1), ms(2026, 11, 1), PeriodGranularity.MONTH,
            AssistantPeriodResolver.resolveChoice(PeriodChoice.THIS_MONTH, now, zone)
        )
    }

    @Test
    fun `resolveChoice THIS_WEEK is the current FULL week`() {
        assertPeriod(
            ms(2026, 10, 12), ms(2026, 10, 19), PeriodGranularity.WEEK,
            AssistantPeriodResolver.resolveChoice(PeriodChoice.THIS_WEEK, now, zone)
        )
    }

    @Test
    fun `resolveChoice LAST_MONTH is the previous FULL month`() {
        assertPeriod(
            ms(2026, 9, 1), ms(2026, 10, 1), PeriodGranularity.MONTH,
            AssistantPeriodResolver.resolveChoice(PeriodChoice.LAST_MONTH, now, zone)
        )
    }

    @Test
    fun `resolveChoice uses the supplied now and zone for a different date`() {
        val january = ms(2027, 1, 5, 9, 0)
        assertPeriod(
            ms(2026, 12, 1), ms(2027, 1, 1), PeriodGranularity.MONTH,
            AssistantPeriodResolver.resolveChoice(PeriodChoice.LAST_MONTH, january, zone)
        )
    }
}
