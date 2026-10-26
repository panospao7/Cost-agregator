package com.yourname.expensetracker.ui.navigation

import android.content.Intent
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkLimits
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkParameter
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host and parameter decision table for [DeepLinkPolicy] (spec 5.3, 5.4).
 * Covers INV-14, INV-15, INV-17, INV-21 and AE-19, AE-23, AE-25..AE-31 at parse level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkPolicyTest {

    private fun eval(uri: String, action: String? = Intent.ACTION_VIEW): DeepLinkPolicyResult =
        DeepLinkPolicy.evaluate(action, uri)

    private fun accepted(uri: String): DeepLinkPolicyResult.Accepted =
        eval(uri) as? DeepLinkPolicyResult.Accepted
            ?: throw AssertionError("Expected Accepted for a test link, got ${eval(uri)}")

    private fun assertRejected(uri: String, reason: DeepLinkRejection) {
        assertEquals("Rejection for '$uri'", DeepLinkPolicyResult.Rejected(reason), eval(uri))
    }

    // ---------------------------------------------------------------- INV-14 / AE-31

    @Test
    fun `AE-31 INV-14 non-VIEW action carrying an expensetracker link is rejected and not routed to Home`() {
        listOf(Intent.ACTION_MAIN, Intent.ACTION_SEND, "com.evil.CUSTOM", null).forEach { action ->
            val result = eval("expensetracker://add", action)
            assertEquals(DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION), result)
            assertFalse((result as DeepLinkPolicyResult.Rejected).fallsBackToHome)
        }
    }

    @Test
    fun `INV-14 no data or a foreign scheme is not a deep link whatever the action`() {
        assertEquals(DeepLinkPolicyResult.NotADeepLink, DeepLinkPolicy.evaluate(Intent.ACTION_MAIN, null))
        assertEquals(DeepLinkPolicyResult.NotADeepLink, DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, null))
        listOf(
            "https://expensetracker.app/home",
            "content://expensetracker/home",
            "Expensetracker://home",
            " expensetracker://home",
            "expensetrackerx://home",
            ""
        ).forEach { uri ->
            assertEquals(uri, DeepLinkPolicyResult.NotADeepLink, eval(uri))
            assertEquals(uri, DeepLinkPolicyResult.NotADeepLink, eval(uri, Intent.ACTION_MAIN))
        }
    }

    @Test
    fun `INV-14 every rejection except ACTION falls back to Home`() {
        listOf(
            DeepLinkRejection.MALFORMED,
            DeepLinkRejection.UNKNOWN_HOST,
            DeepLinkRejection.BANK_HOST
        ).forEach { reason ->
            assertTrue(DeepLinkPolicyResult.Rejected(reason).fallsBackToHome)
        }
        assertFalse(DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION).fallsBackToHome)
    }

    // ---------------------------------------------------------------- host set (spec 5.3)

    @Test
    fun `INV-15 host set is closed and each host maps to its destination`() {
        assertEquals(
            setOf("home", "dashboard", "activity", "review", "plan", "add", "analytics", "map"),
            DeepLinkHost.entries.map { it.token }.toSet()
        )
        val expected = mapOf(
            "home" to NavigationDestination.Home,
            "dashboard" to NavigationDestination.Home,
            "activity" to NavigationDestination.Transactions(),
            "review" to NavigationDestination.Review,
            "plan" to NavigationDestination.Budget,
            "add" to NavigationDestination.AddExpense,
            "analytics" to NavigationDestination.Analytics(),
            "map" to NavigationDestination.SpendingMap()
        )
        expected.forEach { (host, destination) ->
            val result = accepted("expensetracker://$host")
            assertEquals(host, DeepLinkHost.fromToken(host), result.request.host)
            assertEquals(host, destination, result.request.toNavigationDestination())
            assertEquals(host, DeepLinkDrops.NONE, result.drops)
        }
    }

    @Test
    fun `AE-29 INV-15 unknown or differently cased host is rejected and the result never holds the host`() {
        listOf("evilhost", "HOME", "Home", "hom%65", "", "home.evil", "tax", "settings").forEach { host ->
            val result = eval("expensetracker://$host")
            assertEquals(host, DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST), result)
            assertFalse(host.isNotEmpty() && result.toString().contains(host, ignoreCase = true))
        }
        assertRejected("expensetracker:///", DeepLinkRejection.UNKNOWN_HOST)
    }

    @Test
    fun `AE-28 INV-21 bank hosts are rejected through BankFeatureAvailability including other casing`() {
        BankFeatureAvailability.BANK_DEEP_LINK_HOSTS.forEach { host ->
            assertRejected("expensetracker://$host", DeepLinkRejection.BANK_HOST)
            assertRejected("expensetracker://${host.uppercase()}", DeepLinkRejection.BANK_HOST)
            assertRejected("expensetracker://$host?foo=1", DeepLinkRejection.BANK_HOST)
        }
        assertTrue(DeepLinkPolicyResult.Rejected(DeepLinkRejection.BANK_HOST).fallsBackToHome)
    }

    // ---------------------------------------------------------------- AE-30 / spec 5.4

    @Test
    fun `AE-30 structural violations are rejected as MALFORMED`() {
        listOf(
            "expensetracker://home/x",
            "expensetracker://home//",
            "expensetracker://home#f",
            "expensetracker://home#",
            "expensetracker://map?location=a#b",
            "expensetracker://user@home",
            "expensetracker://user:pw@home",
            "expensetracker://home:8080",
            "expensetracker://home:",
            "expensetracker://[::1]",
            "expensetracker://höme",
            "expensetracker://हome",
            "expensetracker:home",
            "expensetracker:/home",
            "expensetracker:"
        ).forEach { uri -> assertRejected(uri, DeepLinkRejection.MALFORMED) }
    }

    @Test
    fun `AE-30 an empty path and a single slash are allowed`() {
        assertEquals(DeepLinkHost.HOME, accepted("expensetracker://home").request.host)
        assertEquals(DeepLinkHost.HOME, accepted("expensetracker://home/").request.host)
        assertEquals(DeepLinkHost.REVIEW, accepted("expensetracker://review/?x=1").request.host)
    }

    @Test
    fun `AE-30 TECH-11 length limit of 2048 is checked on the raw string`() {
        val prefix = "expensetracker://home?x="
        val atLimit = prefix + "a".repeat(DeepLinkLimits.MAX_URI_LENGTH - prefix.length)
        assertEquals(DeepLinkLimits.MAX_URI_LENGTH, atLimit.length)
        val atLimitResult = accepted(atLimit)
        assertEquals(DeepLinkHost.HOME, atLimitResult.request.host)
        assertEquals(1, atLimitResult.drops.unknownCount)

        assertRejected(atLimit + "a", DeepLinkRejection.MALFORMED)
        assertRejected("expensetracker://home?" + "a".repeat(100_000), DeepLinkRejection.MALFORMED)
    }

    @Test
    fun `AE-31 the length check runs after the action check and before parsing`() {
        val huge = "expensetracker://home?" + "a".repeat(100_000)
        assertEquals(
            DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION),
            DeepLinkPolicy.evaluate(Intent.ACTION_MAIN, huge)
        )
        assertEquals(
            DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED),
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, huge)
        )
    }

    // ---------------------------------------------------------------- briefingKey / AE-19

    @Test
    fun `INV-15 a valid briefingKey is kept for home and dashboard (parse level of AE-18)`() {
        val key = "dashboard_home:2027-03-12"
        listOf("home" to DeepLinkHost.HOME, "dashboard" to DeepLinkHost.DASHBOARD).forEach { (host, enum) ->
            val result = accepted("expensetracker://$host?briefingKey=$key")
            assertEquals(DeepLinkRequest(enum, briefingKey = key), result.request)
            assertEquals(DeepLinkDrops.NONE, result.drops)
        }
        assertEquals("a leap day is a real date", "dashboard_home:2028-02-29",
            accepted("expensetracker://dashboard?briefingKey=dashboard_home:2028-02-29").request.briefingKey)
        // Percent-encoded colon decodes to the same key.
        assertEquals(key, accepted("expensetracker://dashboard?briefingKey=dashboard_home%3A2027-03-12").request.briefingKey)
    }

    @Test
    fun `AE-19 INV-15 malformed briefingKey is dropped to Home and reported`() {
        listOf(
            "dashboard_home:2027-13-40",
            "dashboard_home:2027-02-29",
            "dashboard_home:2027-00-10",
            "dashboard_home:2027-3-12",
            "dashboard_home:20270312",
            "dashboard_home:2027-03-12x",
            "dashboard_home:2027-03-12%20",
            "dashboard_home:",
            "Dashboard_home:2027-03-12",
            "dashboard_home:٢٠٢٧-03-12",
            "dashboard_home:2027-03-12'%3BDROP",
            "x'%3BDROP",
            "x';DROP",
            "a".repeat(500),
            "dashboard_home:2027-03-12" + "a".repeat(500),
            ""
        ).forEach { key ->
            listOf("home", "dashboard").forEach { host ->
                val result = accepted("expensetracker://$host?briefingKey=$key")
                assertNull("key '$key' must be dropped", result.request.briefingKey)
                assertEquals(setOf(DeepLinkParameter.BRIEFING_KEY), result.drops.invalid)
                assertEquals(0, result.drops.unknownCount)
                assertEquals(NavigationDestination.Home, result.request.toNavigationDestination())
            }
        }
    }

    @Test
    fun `INV-15 a duplicate briefingKey is dropped even when both values are valid`() {
        val result = accepted(
            "expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12&briefingKey=dashboard_home:2027-03-12"
        )
        assertNull(result.request.briefingKey)
        assertEquals(setOf(DeepLinkParameter.BRIEFING_KEY), result.drops.invalid)
    }

    @Test
    fun `INV-15 briefingKey is not allowed on other hosts`() {
        val result = accepted("expensetracker://review?briefingKey=dashboard_home:2027-03-12")
        assertEquals(DeepLinkRequest(DeepLinkHost.REVIEW), result.request)
        assertEquals(DeepLinkDrops(unknownCount = 1), result.drops)
    }

    // ---------------------------------------------------------------- expenseId / AE-23

    @Test
    fun `INV-15 a valid expenseId is kept (parse level of AE-22)`() {
        assertEquals(77L, accepted("expensetracker://activity?expenseId=77").request.expenseId)
        assertEquals(7L, accepted("expensetracker://activity?expenseId=0007").request.expenseId)
        assertEquals(1L, accepted("expensetracker://activity?expenseId=1").request.expenseId)
        assertEquals(
            Long.MAX_VALUE,
            accepted("expensetracker://activity?expenseId=${Long.MAX_VALUE}").request.expenseId
        )
        assertEquals(
            NavigationDestination.Transactions(initialExpenseId = 77L),
            accepted("expensetracker://activity?expenseId=77").request.toNavigationDestination()
        )
    }

    @Test
    fun `AE-23 INV-15 invalid expenseId is dropped to the list and reported`() {
        listOf(
            "-5", "abc", "0", "00", "99999999999999999999", "9223372036854775808",
            "+5", "1.5", "1e3", " ", "", "1&expenseId=2", "%EF%BC%91", "5%00", "0x10"
        ).forEach { value ->
            val result = accepted("expensetracker://activity?expenseId=$value")
            // "1&expenseId=2" is a duplicate key, every other value fails the digit/range rule.
            assertNull("value '$value' must be dropped", result.request.expenseId)
            assertEquals(setOf(DeepLinkParameter.EXPENSE_ID), result.drops.invalid)
            assertEquals(NavigationDestination.Transactions(), result.request.toNavigationDestination())
        }
    }

    @Test
    fun `AE-23 duplicate expenseId keys drop the parameter`() {
        val result = accepted("expensetracker://activity?expenseId=1&expenseId=2")
        assertNull(result.request.expenseId)
        assertEquals(setOf(DeepLinkParameter.EXPENSE_ID), result.drops.invalid)

        val encoded = accepted("expensetracker://activity?expenseId=1%26expenseId%3D2")
        assertNull(encoded.request.expenseId)
        assertEquals(setOf(DeepLinkParameter.EXPENSE_ID), encoded.drops.invalid)
    }

    // ---------------------------------------------------------------- period / AE-25

    @Test
    fun `AE-25 period is matched case-insensitively against the shared allowlist`() {
        listOf("today", "week", "month", "quarter", "year", "all").forEach { token ->
            listOf(token, token.uppercase(), token.replaceFirstChar { it.uppercase() }).forEach { value ->
                val result = accepted("expensetracker://analytics?period=$value")
                assertEquals(token, result.request.period)
                assertEquals(
                    NavigationDestination.Analytics(initialPeriod = token.uppercase()),
                    result.request.toNavigationDestination()
                )
                assertEquals(DeepLinkDrops.NONE, result.drops)
            }
        }
        // Surrounding whitespace is trimmed, as the previous AnalyticsScreen parser did.
        assertEquals("month", accepted("expensetracker://analytics?period=%20Month%20").request.period)
    }

    @Test
    fun `AE-25 unknown period is dropped to the default period and reported`() {
        listOf("decade", "", "months", "mon th", "mo\u0000nth", "MONTH;", "-1").forEach { value ->
            val result = accepted("expensetracker://analytics?period=$value")
            assertNull("value '$value' must be dropped", result.request.period)
            assertEquals(setOf(DeepLinkParameter.PERIOD), result.drops.invalid)
            assertEquals(NavigationDestination.Analytics(initialPeriod = null), result.request.toNavigationDestination())
        }
        val duplicate = accepted("expensetracker://analytics?period=month&period=week")
        assertNull(duplicate.request.period)
        assertEquals(setOf(DeepLinkParameter.PERIOD), duplicate.drops.invalid)
    }

    // ---------------------------------------------------------------- location / AE-26

    @Test
    fun `AE-26 a plus in a parameter value decodes to a space as before`() {
        assertEquals("Demo Cafe", accepted("expensetracker://map?location=Demo+Cafe").request.location)
    }

    @Test
    fun `INV-15 a percent-encoded allowed key is dropped and reported invalid`() {
        val result = accepted("expensetracker://map?loc%61tion=Athens")
        assertEquals(null, result.request.location)
        assertEquals(setOf(DeepLinkParameter.LOCATION), result.drops.invalid)
        assertEquals(0, result.drops.unknownCount)
    }

    @Test
    fun `AE-26 a valid location is decoded and trimmed`() {
        assertEquals("Demo Cafe", accepted("expensetracker://map?location=Demo%20Cafe").request.location)
        assertEquals("Athens", accepted("expensetracker://map?location=%20%20Athens%09").request.location)
        assertEquals("Café Αθήνα", accepted("expensetracker://map?location=Caf%C3%A9%20%CE%91%CE%B8%CE%AE%CE%BD%CE%B1").request.location)
        val max = "a".repeat(DeepLinkLimits.MAX_LOCATION_LENGTH)
        assertEquals(max, accepted("expensetracker://map?location=$max").request.location)
        assertEquals(
            NavigationDestination.SpendingMap(initialLocationQuery = "Demo Cafe"),
            accepted("expensetracker://map?location=Demo%20Cafe").request.toNavigationDestination()
        )
    }

    @Test
    fun `AE-26 an invalid location gives the map with no highlight and is reported`() {
        listOf(
            "a%00b",
            "%00",
            "a%0Ab",
            "a%1Fb",
            "a%7Fb",
            "a%C2%85b",
            "%E2%80%AEevil",
            "a%E2%80%8Eb",
            "a%E2%81%A6b",
            "a%EF%BB%BFb",
            "a".repeat(DeepLinkLimits.MAX_LOCATION_LENGTH + 1),
            "%20%20%20",
            ""
        ).forEach { value ->
            val result = accepted("expensetracker://map?location=$value")
            assertNull("value of length ${value.length} must be dropped", result.request.location)
            assertEquals(setOf(DeepLinkParameter.LOCATION), result.drops.invalid)
            assertEquals(NavigationDestination.SpendingMap(initialLocationQuery = null), result.request.toNavigationDestination())
        }
        val duplicate = accepted("expensetracker://map?location=A&location=B")
        assertNull(duplicate.request.location)
        assertEquals(setOf(DeepLinkParameter.LOCATION), duplicate.drops.invalid)
    }

    // ---------------------------------------------------------------- extras / INV-17 / AE-27

    @Test
    fun `AE-27 INV-17 add never carries a prefill and counts the dropped keys`() {
        val result = accepted("expensetracker://add?amount=50&merchant=X")
        assertEquals(DeepLinkRequest(DeepLinkHost.ADD), result.request)
        assertEquals(DeepLinkDrops(unknownCount = 2), result.drops)
        assertEquals(NavigationDestination.AddExpense, result.request.toNavigationDestination())
    }

    @Test
    fun `INV-17 parameterless hosts drop every key including the ones other hosts accept`() {
        listOf("review", "plan", "add").forEach { host ->
            val result = accepted("expensetracker://$host?expenseId=1&period=month&location=x&briefingKey=y")
            assertEquals(DeepLinkRequest(DeepLinkHost.valueOf(host.uppercase())), result.request)
            assertEquals(4, result.drops.unknownCount)
            assertTrue(result.drops.invalid.isEmpty())
        }
    }

    @Test
    fun `INV-15 unknown keys next to a valid one are counted and the valid one survives`() {
        val result = accepted("expensetracker://analytics?period=month&foo=1&bar=2&foo=3")
        assertEquals("month", result.request.period)
        assertEquals(DeepLinkDrops(unknownCount = 2), result.drops)
    }

    @Test
    fun `INV-15 keys are case-sensitive and empty keys are not counted`() {
        val wrongCase = accepted("expensetracker://activity?ExpenseId=5")
        assertNull(wrongCase.request.expenseId)
        assertEquals(DeepLinkDrops(unknownCount = 1), wrongCase.drops)

        assertEquals(DeepLinkDrops.NONE, accepted("expensetracker://home?").drops)
        assertEquals(DeepLinkDrops.NONE, accepted("expensetracker://home?&&").drops)
    }

    // ---------------------------------------------------------------- no input throws (FG-22 JVM half)

    @Test
    fun `FG-22 hostile inputs never throw and always yield a known result`() {
        val hostile = listOf(
            "expensetracker:",
            "expensetracker://",
            "expensetracker:///",
            "expensetracker://?",
            "expensetracker://#",
            "expensetracker://[",
            "expensetracker://]",
            "expensetracker://home?%",
            "expensetracker://home?%zz=%zz",
            "expensetracker://home?briefingKey=%",
            "expensetracker://home?briefingKey=%E2%80",
            "expensetracker://activity?expenseId=%FF%FE",
            "expensetracker://analytics?period=%00",
            "expensetracker://map?location=%ED%A0%80",
            "expensetracker://map?location=\uD800",
            "expensetracker://map?location=%EF%BF%BD",
            "expensetracker://home?=&=&=",
            "expensetracker://home?&&&&&&&&",
            "expensetracker://home?a=b=c=d",
            "expensetracker://home\u0000",
            "expensetracker://home\n",
            "expensetracker://home space",
            "expensetracker://😀",
            "expensetracker://home?" + "&k=v".repeat(400),
            "expensetracker://home?" + "%".repeat(2000),
            "expensetracker:" + "/".repeat(2000)
        )
        hostile.forEach { uri ->
            listOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN, null).forEach { action ->
                val result = DeepLinkPolicy.evaluate(action, uri)
                assertTrue(
                    "Unexpected result type for action $action",
                    result is DeepLinkPolicyResult.Accepted ||
                        result is DeepLinkPolicyResult.Rejected ||
                        result is DeepLinkPolicyResult.NotADeepLink
                )
            }
        }
    }

    // ---------------------------------------------------------------- INV-18 redaction

    @Test
    fun `INV-18 request and result string forms never include parameter values`() {
        val request = DeepLinkRequest(
            host = DeepLinkHost.MAP,
            briefingKey = "dashboard_home:2027-03-12",
            expenseId = 4242L,
            period = "month",
            location = "SecretCafe"
        )
        val forms = listOf(
            request.toString(),
            DeepLinkPolicyResult.Accepted(request, DeepLinkDrops(unknownCount = 1)).toString()
        )
        forms.forEach { text ->
            assertTrue(text, text.contains("MAP"))
            listOf("SecretCafe", "dashboard_home", "2027-03-12", "4242", "month").forEach { secret ->
                assertFalse("'$secret' leaked in $text", text.contains(secret))
            }
        }
    }

    @Test
    fun `INV-17 DeepLinkRequest exposes no field that could carry a prefill`() {
        val fields = DeepLinkRequest::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("host", "briefingKey", "expenseId", "period", "location"), fields)
    }
}
