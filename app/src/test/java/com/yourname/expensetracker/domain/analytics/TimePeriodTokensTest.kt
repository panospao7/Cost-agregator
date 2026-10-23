package com.yourname.expensetracker.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F13: one shared period allowlist for `AnalyticsScreen` and the deep-link policy
 * (app-lock-privacy-mode S3; supports AE-25, INV-15).
 */
class TimePeriodTokensTest {

    /** The private parser that lived in AnalyticsScreen before S3, kept as a behaviour oracle. */
    private fun legacyParse(value: String): TimePeriod? {
        return when (value.trim().lowercase()) {
            "today" -> TimePeriod.TODAY
            "week" -> TimePeriod.WEEK
            "month" -> TimePeriod.MONTH
            "quarter" -> TimePeriod.QUARTER
            "year" -> TimePeriod.YEAR
            "all" -> TimePeriod.ALL
            else -> null
        }
    }

    @Test
    fun `allowlist covers exactly the TimePeriod values so it cannot drift`() {
        assertEquals(
            TimePeriod.entries.map { it.name.lowercase() },
            TimePeriodTokens.ALLOWED
        )
    }

    @Test
    fun `every token parses to its TimePeriod in any case`() {
        TimePeriod.entries.forEach { period ->
            val token = period.name.lowercase()
            listOf(token, token.uppercase(), token.replaceFirstChar { it.uppercase() }).forEach { value ->
                assertEquals(value, period, TimePeriodTokens.parseOrNull(value))
                assertEquals(value, token, TimePeriodTokens.normalizeOrNull(value))
            }
        }
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(TimePeriod.MONTH, TimePeriodTokens.parseOrNull("  Month\t"))
        assertEquals("month", TimePeriodTokens.normalizeOrNull(" MONTH \n"))
    }

    @Test
    fun `anything else is null`() {
        listOf(
            "", " ", "decade", "months", "mon th", "MONTH;", "-1", "0", "mo\u0000nth",
            "монтх", "mönth", "today,week"
        ).forEach { value ->
            assertNull("'$value'", TimePeriodTokens.parseOrNull(value))
            assertNull("'$value'", TimePeriodTokens.normalizeOrNull(value))
        }
        assertNull(TimePeriodTokens.normalizeOrNull(null))
    }

    @Test
    fun `behaviour is identical to the previous AnalyticsScreen parser`() {
        val samples = listOf(
            "today", "TODAY", "Week", " month ", "QUARTER", "year", "ALL", "all ",
            "", "decade", "weeks", "m", "Y E A R", "\tall", "Today\n", "nonsense"
        )
        samples.forEach { value ->
            assertEquals("'$value'", legacyParse(value), TimePeriodTokens.parseOrNull(value))
        }
    }
}
