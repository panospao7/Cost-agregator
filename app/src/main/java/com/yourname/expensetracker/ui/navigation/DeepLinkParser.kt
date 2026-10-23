package com.yourname.expensetracker.ui.navigation

import android.content.Intent
import android.net.Uri
import com.yourname.expensetracker.domain.analytics.TimePeriodTokens
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkLimits
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkParameter
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
import java.time.DateTimeException
import java.time.LocalDate

/** Why a link was rejected. Maps 1:1 to the closed `DL_REJECTED_*` reason codes (spec 5.7). */
enum class DeepLinkRejection {
    /** A non-VIEW intent carried an `expensetracker` data URI. The intent is ignored. */
    ACTION,

    /** Failed structural validation (spec 5.4). */
    MALFORMED,

    /** The host is not in the closed [DeepLinkHost] set. The host string is never recorded. */
    UNKNOWN_HOST,

    /** RP-17 bank-sync host. */
    BANK_HOST
}

/**
 * Parameters dropped from an accepted link (spec 5.3). Only counts and enums, never values.
 *
 * @property unknownCount number of distinct unknown (or host-disallowed) keys that were dropped.
 * @property invalid parameters whose value was invalid or duplicated, in enum order.
 */
data class DeepLinkDrops(
    val unknownCount: Int = 0,
    val invalid: Set<DeepLinkParameter> = emptySet()
) {
    companion object {
        val NONE = DeepLinkDrops()
    }
}

/** Outcome of [DeepLinkPolicy.evaluate]. None of the variants carries a raw URI string. */
sealed interface DeepLinkPolicyResult {

    /** The link is valid (possibly with parameters dropped). */
    data class Accepted(
        val request: DeepLinkRequest,
        val drops: DeepLinkDrops = DeepLinkDrops.NONE
    ) : DeepLinkPolicyResult

    /**
     * The link is rejected. Every reason except [DeepLinkRejection.ACTION] lands on Home;
     * an [DeepLinkRejection.ACTION] rejection is ignored (no navigation at all).
     */
    data class Rejected(val reason: DeepLinkRejection) : DeepLinkPolicyResult {
        val fallsBackToHome: Boolean
            get() = reason != DeepLinkRejection.ACTION
    }

    /** Not an `expensetracker` link at all (no data, or a different scheme). Nothing to do. */
    data object NotADeepLink : DeepLinkPolicyResult
}

/**
 * Pure deep-link policy (INV-14, INV-15, INV-17, INV-21; spec 5).
 *
 * Every incoming URI is untrusted, internal notification taps included (F9). This object does
 * no IO, holds no state, never throws, and never logs. It is the only parser of
 * `expensetracker://` links.
 *
 * Structure (spec 5.4): the whole link is rejected (`MALFORMED`) when the data string is longer
 * than 2048 chars (checked on the raw string before parsing), is not hierarchical, has a
 * fragment, userinfo or port, a path other than empty or "/", or non-ASCII in the authority.
 *
 * Per host (spec 5.3), only the listed parameter is read; every other key is dropped and
 * counted, duplicates and invalid values are dropped to the host default, extras are ignored:
 * - `home`, `dashboard`: `briefingKey` = `dashboard_home:` + real `yyyy-MM-dd` (<= 32 chars)
 * - `activity`: `expenseId` = 1..19 ASCII digits, value 1..Long.MAX_VALUE
 * - `analytics`: `period` from the shared [TimePeriodTokens] allowlist
 * - `map`: `location` trimmed, 1..100 chars, no control or format characters
 * - `review`, `plan`, `add`: no parameters (no link creates or prefills data, INV-17)
 * - bank hosts: rejected via [BankFeatureAvailability.isBankDeepLinkHost]
 * - any other host: rejected
 */
object DeepLinkPolicy {

    const val SCHEME: String = "expensetracker"

    private const val SCHEME_PREFIX = "$SCHEME:"
    private const val BRIEFING_DATE_LENGTH = 10 // yyyy-MM-dd
    private const val MAX_EXPENSE_ID_DIGITS = 19

    /**
     * Evaluates an intent's action and data string.
     *
     * @param action the intent action (`Intent.getAction()`).
     * @param dataString the intent data as a string (`Intent.getDataString()`), unparsed.
     */
    fun evaluate(action: String?, dataString: String?): DeepLinkPolicyResult {
        if (dataString == null) return DeepLinkPolicyResult.NotADeepLink
        // Exact-case scheme, same as the previous `uri.scheme != "expensetracker"` check.
        if (!dataString.startsWith(SCHEME_PREFIX)) return DeepLinkPolicyResult.NotADeepLink
        if (action != Intent.ACTION_VIEW) {
            return DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION)
        }
        // TECH-11: the length is checked on the raw string before it is parsed.
        if (dataString.length > DeepLinkLimits.MAX_URI_LENGTH) {
            return DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED)
        }
        return try {
            evaluateParsed(Uri.parse(dataString))
        } catch (_: RuntimeException) {
            // Malformed input must never crash navigation (FG-22). Nothing about the input is kept.
            DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED)
        }
    }

    private fun evaluateParsed(uri: Uri): DeepLinkPolicyResult {
        if (uri.scheme != SCHEME) return DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED)
        if (!isStructurallyValid(uri)) {
            return DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED)
        }

        // The authority is exactly the host here: userinfo and port were rejected above.
        // The raw (not percent-decoded) value is matched, which is stricter than `Uri.getHost()`.
        val host = uri.encodedAuthority

        // RP-17 17-A: bank hosts are recognized and rejected (moved here, not duplicated).
        if (BankFeatureAvailability.isBankDeepLinkHost(host)) {
            return DeepLinkPolicyResult.Rejected(DeepLinkRejection.BANK_HOST)
        }
        val knownHost = DeepLinkHost.fromToken(host)
            ?: return DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST)

        return readParameters(uri, knownHost)
    }

    private fun isStructurallyValid(uri: Uri): Boolean {
        if (!uri.isHierarchical) return false
        val raw = uri.toString()
        // Any '#' means a fragment, including an empty one.
        if (raw.indexOf('#') >= 0) return false
        // Everything before the query is scheme + authority + (empty or "/") path, so a
        // non-ASCII character there is non-ASCII in the host (checked on the raw string, so it
        // does not depend on how the platform encodes the authority).
        if (raw.substringBefore('?').any { it.code > MAX_ASCII }) return false

        val authority = uri.encodedAuthority
        if (authority != null) {
            // '@' = userinfo, ':' = port (or an IPv6 literal, which is never a valid host here).
            if (authority.indexOf('@') >= 0 || authority.indexOf(':') >= 0) return false
        }

        val path = uri.encodedPath
        return path.isNullOrEmpty() || path == "/"
    }

    private fun readParameters(uri: Uri, host: DeepLinkHost): DeepLinkPolicyResult {
        val allowed = allowedParameter(host)
        val allowedName = allowed?.let { wireName(it) }

        var unknownCount = 0
        var allowedNamePresent = false
        for (name in uri.queryParameterNames) {
            if (name.isEmpty()) continue // "?" or "&&": nothing to count
            if (name == allowedName) allowedNamePresent = true else unknownCount++
        }

        val invalid = mutableSetOf<DeepLinkParameter>()
        var accepted: Any? = null

        if (allowed != null && allowedName != null) {
            // getQueryParameters matches the encoded key; used only for the duplicate check.
            val values = uri.getQueryParameters(allowedName)
            when {
                // Decoded name present but no encoded match (e.g. a percent-encoded key):
                // dropped, and reported as invalid rather than silently ignored.
                values.isEmpty() -> if (allowedNamePresent) invalid += allowed
                values.size > 1 -> invalid += allowed // duplicate key
                else -> {
                    // getQueryParameter decodes '+' to a space, as MainActivity does today.
                    val raw = uri.getQueryParameter(allowedName) ?: values[0]
                    accepted = when (allowed) {
                        DeepLinkParameter.BRIEFING_KEY -> validBriefingKey(raw)
                        DeepLinkParameter.EXPENSE_ID -> validExpenseId(raw)
                        DeepLinkParameter.PERIOD -> TimePeriodTokens.normalizeOrNull(raw)
                        DeepLinkParameter.LOCATION -> validLocation(raw)
                    }
                    if (accepted == null) invalid += allowed
                }
            }
        }

        // Built once from validated values (no copy() per field).
        val request = DeepLinkRequest(
            host = host,
            briefingKey = (accepted as? String).takeIf { allowed == DeepLinkParameter.BRIEFING_KEY },
            expenseId = (accepted as? Long).takeIf { allowed == DeepLinkParameter.EXPENSE_ID },
            period = (accepted as? String).takeIf { allowed == DeepLinkParameter.PERIOD },
            location = (accepted as? String).takeIf { allowed == DeepLinkParameter.LOCATION }
        )

        return DeepLinkPolicyResult.Accepted(
            request = request,
            drops = DeepLinkDrops(unknownCount = unknownCount, invalid = invalid)
        )
    }

    private fun allowedParameter(host: DeepLinkHost): DeepLinkParameter? = when (host) {
        DeepLinkHost.HOME, DeepLinkHost.DASHBOARD -> DeepLinkParameter.BRIEFING_KEY
        DeepLinkHost.ACTIVITY -> DeepLinkParameter.EXPENSE_ID
        DeepLinkHost.ANALYTICS -> DeepLinkParameter.PERIOD
        DeepLinkHost.MAP -> DeepLinkParameter.LOCATION
        DeepLinkHost.REVIEW, DeepLinkHost.PLAN, DeepLinkHost.ADD -> null
    }

    private fun wireName(parameter: DeepLinkParameter): String = when (parameter) {
        DeepLinkParameter.BRIEFING_KEY -> "briefingKey"
        DeepLinkParameter.EXPENSE_ID -> "expenseId"
        DeepLinkParameter.PERIOD -> "period"
        DeepLinkParameter.LOCATION -> "location"
    }

    /** `dashboard_home:` + a real calendar date `yyyy-MM-dd`, ASCII only, <= 32 chars. */
    private fun validBriefingKey(value: String): String? {
        val prefix = DeepLinkLimits.BRIEFING_KEY_PREFIX
        if (value.length > DeepLinkLimits.MAX_BRIEFING_KEY_LENGTH) return null
        if (value.length != prefix.length + BRIEFING_DATE_LENGTH) return null
        if (!value.startsWith(prefix)) return null
        val date = value.substring(prefix.length)
        for (i in date.indices) {
            val c = date[i]
            val ok = if (i == 4 || i == 7) c == '-' else c in '0'..'9'
            if (!ok) return null
        }
        return try {
            LocalDate.of(
                date.substring(0, 4).toInt(),
                date.substring(5, 7).toInt(),
                date.substring(8, 10).toInt()
            )
            value
        } catch (_: DateTimeException) {
            null
        }
    }

    /** 1..19 ASCII digits, no sign, value 1..Long.MAX_VALUE. */
    private fun validExpenseId(value: String): Long? {
        if (value.isEmpty() || value.length > MAX_EXPENSE_ID_DIGITS) return null
        if (value.any { it !in '0'..'9' }) return null
        return value.toLongOrNull()?.takeIf { it >= 1L }
    }

    /** Trimmed, 1..100 chars, no Unicode control or format characters. */
    private fun validLocation(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > DeepLinkLimits.MAX_LOCATION_LENGTH) return null
        val hasControlOrFormat = trimmed.any {
            val type = Character.getType(it)
            type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
        }
        return if (hasControlOrFormat) null else trimmed
    }

    private const val MAX_ASCII = 0x7F
}

/**
 * The destination for this request before any lookup. For [DeepLinkHost.ACTIVITY] the applier
 * performs the read-only lookup (INV-22); for [DeepLinkHost.ADD] it opens an empty add sheet
 * (INV-17). The period is emitted as the upper-case enum name, as before.
 */
fun DeepLinkRequest.toNavigationDestination(): NavigationDestination = when (host) {
    DeepLinkHost.HOME, DeepLinkHost.DASHBOARD -> NavigationDestination.Home
    DeepLinkHost.ACTIVITY -> NavigationDestination.Transactions(initialExpenseId = expenseId)
    DeepLinkHost.REVIEW -> NavigationDestination.Review
    DeepLinkHost.PLAN -> NavigationDestination.Budget
    DeepLinkHost.ADD -> NavigationDestination.AddExpense
    DeepLinkHost.ANALYTICS -> NavigationDestination.Analytics(initialPeriod = period?.uppercase())
    DeepLinkHost.MAP -> NavigationDestination.SpendingMap(initialLocationQuery = location)
}
