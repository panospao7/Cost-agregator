package com.yourname.expensetracker.domain.privacy.deeplink

/**
 * Closed set of deep-link hosts that can become a [DeepLinkRequest] (spec 2.6, 5.3).
 *
 * Bank hosts and every unknown host are rejected by the policy before a request exists, so the
 * host string is never carried or recorded.
 */
enum class DeepLinkHost(val token: String) {
    HOME("home"),
    DASHBOARD("dashboard"),
    ACTIVITY("activity"),
    REVIEW("review"),
    PLAN("plan"),
    ADD("add"),
    ANALYTICS("analytics"),
    MAP("map");

    companion object {
        /** Exact (case-sensitive) lookup; null for anything outside the closed set. */
        fun fromToken(token: String?): DeepLinkHost? = entries.firstOrNull { it.token == token }
    }
}

/** Closed set of query parameters a deep link may carry. Used as bounded diagnostic metadata. */
enum class DeepLinkParameter {
    BRIEFING_KEY,
    EXPENSE_ID,
    PERIOD,
    LOCATION
}

/** Hard limits for deep-link input (TECH-11). */
object DeepLinkLimits {
    /** Checked on the raw data string, before any parsing. */
    const val MAX_URI_LENGTH: Int = 2048

    /** Applies to `location` after trimming. */
    const val MAX_LOCATION_LENGTH: Int = 100

    /** Applies to `briefingKey`; the exact format is `dashboard_home:yyyy-MM-dd`. */
    const val MAX_BRIEFING_KEY_LENGTH: Int = 32

    const val BRIEFING_KEY_PREFIX: String = "dashboard_home:"
}

/**
 * A validated deep link (spec 2.6). Pure Kotlin so the domain lock controller can hold it.
 *
 * Only `DeepLinkPolicy` creates instances. Every field is already validated:
 * - [briefingKey]: exactly `dashboard_home:` + a real `yyyy-MM-dd` date (HOME / DASHBOARD only).
 * - [expenseId]: 1..Long.MAX_VALUE (ACTIVITY only).
 * - [period]: a normalized lowercase period token (ANALYTICS only).
 * - [location]: trimmed, 1..100 chars, no control or format characters (MAP only).
 *
 * [toString] deliberately names the host only: the parameter values must never reach a log,
 * diagnostic or exception message (INV-18).
 */
data class DeepLinkRequest(
    val host: DeepLinkHost,
    val briefingKey: String? = null,
    val expenseId: Long? = null,
    val period: String? = null,
    val location: String? = null
) {
    override fun toString(): String = "DeepLinkRequest(host=$host)"
}
