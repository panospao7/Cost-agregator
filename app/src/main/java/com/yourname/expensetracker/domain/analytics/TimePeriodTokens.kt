package com.yourname.expensetracker.domain.analytics

/**
 * Single allowlist of analytics period tokens (F13). Shared by `AnalyticsScreen` and the
 * deep-link policy so the set is never duplicated.
 *
 * Matching is trimmed and case-insensitive (root locale), identical to the previous private
 * parser in `AnalyticsScreen`.
 */
object TimePeriodTokens {

    /** Canonical lowercase tokens, in declaration order of [TimePeriod]. */
    val ALLOWED: List<String> = listOf("today", "week", "month", "quarter", "year", "all")

    /** Returns the canonical lowercase token for [value], or null when it is not allowed. */
    fun normalizeOrNull(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it in ALLOWED }

    /** Parses [value] into a [TimePeriod], or null when it is not an allowed token. */
    fun parseOrNull(value: String): TimePeriod? {
        return when (normalizeOrNull(value)) {
            "today" -> TimePeriod.TODAY
            "week" -> TimePeriod.WEEK
            "month" -> TimePeriod.MONTH
            "quarter" -> TimePeriod.QUARTER
            "year" -> TimePeriod.YEAR
            "all" -> TimePeriod.ALL
            else -> null
        }
    }
}
