package com.yourname.expensetracker.ui.navigation

import android.content.Intent
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkParameter
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * App-lock-privacy-mode S3: this table was rewritten from the old `parseDeepLink` /
 * `DeepLinkDecision` contract to [DeepLinkPolicy] (INV-14, INV-15, INV-17, INV-21; AE-25,
 * AE-26, AE-27, AE-28, AE-29). No case was dropped: the `RequireConfirmation` rows are now
 * `Accepted` (no link needs confirmation because no link has a caller-controlled side effect,
 * INV-16/INV-17), and the `Reject` rows assert the exact rejection reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkParserTest {

    private class Row(
        val name: String,
        val uri: String,
        val expectedKind: KClass<out DeepLinkPolicyResult>,
        val expectedDestination: NavigationDestination?,
        val expectedRejection: DeepLinkRejection? = null,
        val expectedRequest: DeepLinkRequest? = null
    )

    @Test
    fun `deep link table yields exact result kind and destination`() {
        val rows = listOf(
            Row("home host", "expensetracker://home",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Home,
                expectedRequest = DeepLinkRequest(DeepLinkHost.HOME)),
            Row("dashboard host", "expensetracker://dashboard",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Home,
                expectedRequest = DeepLinkRequest(DeepLinkHost.DASHBOARD)),
            Row("activity without expenseId", "expensetracker://activity",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Transactions(initialExpenseId = null),
                expectedRequest = DeepLinkRequest(DeepLinkHost.ACTIVITY)),
            // Was RequireConfirmation: a valid id is now plain Accepted (the applier does the read-only lookup).
            Row("activity with expenseId", "expensetracker://activity?expenseId=123",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Transactions(initialExpenseId = 123L),
                expectedRequest = DeepLinkRequest(DeepLinkHost.ACTIVITY, expenseId = 123L)),
            Row("activity with invalid expenseId", "expensetracker://activity?expenseId=abc",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Transactions(initialExpenseId = null),
                expectedRequest = DeepLinkRequest(DeepLinkHost.ACTIVITY)),
            // Was RequireConfirmation.
            Row("review", "expensetracker://review",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Review,
                expectedRequest = DeepLinkRequest(DeepLinkHost.REVIEW)),
            // Was RequireConfirmation: add opens an EMPTY sheet (INV-17), no parameter is carried.
            Row("add", "expensetracker://add",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.AddExpense,
                expectedRequest = DeepLinkRequest(DeepLinkHost.ADD)),
            Row("plan", "expensetracker://plan",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Budget,
                expectedRequest = DeepLinkRequest(DeepLinkHost.PLAN)),
            Row("analytics with period", "expensetracker://analytics?period=MONTH",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.Analytics(initialPeriod = "MONTH"),
                expectedRequest = DeepLinkRequest(DeepLinkHost.ANALYTICS, period = "month")),
            Row("map with location", "expensetracker://map?location=Athens",
                DeepLinkPolicyResult.Accepted::class, NavigationDestination.SpendingMap(initialLocationQuery = "Athens"),
                expectedRequest = DeepLinkRequest(DeepLinkHost.MAP, location = "Athens")),
            Row("unknown host", "expensetracker://unknown",
                DeepLinkPolicyResult.Rejected::class, null,
                expectedRejection = DeepLinkRejection.UNKNOWN_HOST),
            Row("wrong scheme", "https://expensetracker.app/home",
                DeepLinkPolicyResult.NotADeepLink::class, null)
        )

        rows.forEach { row ->
            val result = DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, row.uri)
            val actualDestination = (result as? DeepLinkPolicyResult.Accepted)?.request?.toNavigationDestination()
            assertEquals("Row '${row.name}' result kind", row.expectedKind, result::class)
            assertEquals("Row '${row.name}' destination", row.expectedDestination, actualDestination)
            assertEquals(
                "Row '${row.name}' request",
                row.expectedRequest,
                (result as? DeepLinkPolicyResult.Accepted)?.request
            )
            assertEquals(
                "Row '${row.name}' rejection",
                row.expectedRejection,
                (result as? DeepLinkPolicyResult.Rejected)?.reason
            )
        }
    }

    @Test
    fun `invalid expenseId is dropped and reported not silently ignored`() {
        val result = DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://activity?expenseId=abc")
        val accepted = result as DeepLinkPolicyResult.Accepted
        assertEquals(setOf(DeepLinkParameter.EXPENSE_ID), accepted.drops.invalid)
    }

    @Test
    fun `deferred feature hosts are not deep-link routable`() {
        listOf(
            "tax", "tax_configuration", "investment", "investment_portfolio",
            "challenges", "spending_challenges"
        ).forEach { host ->
            val result = DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://$host")
            assertEquals(
                "Host '$host' must be rejected as unknown",
                DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST),
                result
            )
        }
    }

    // ---- loan-installments S10a-1: dormant installments deep links ----

    // mec merge: ported from the removed parseDeepLink to DeepLinkPolicy.evaluate. DeepLinkPolicy has no
    // installment hosts until loan S13-a, so every installment link is Rejected(UNKNOWN_HOST) and lands on Home.
    @Test
    fun `installments links are rejected as unknown host while the flag is false`() {
        listOf(
            "expensetracker://installments",
            "expensetracker://installment_plan?id=42"
        ).forEach { uri ->
            // S13-b: explicit seam, so flag-off behaviour stays covered now that the default is enabled.
            val result = DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, uri, installmentsEnabled = false)
            assertEquals(
                "Flag false must reject $uri",
                DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST),
                result
            )
            assertTrue("$uri falls back to Home", (result as DeepLinkPolicyResult.Rejected).fallsBackToHome)
        }
    }

    @Test
    fun `installments links are accepted by default while the compile-time gate is true`() {
        // Canary: S13-b (owner-approvals 91(b)): enabled on this branch; flip back = revert S13-b.
        assertTrue(InstallmentsFeature.ENABLED)
        assertEquals(
            DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.INSTALLMENTS)),
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://installments")
        )
        assertEquals(
            DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.INSTALLMENT_PLAN, planId = 42L)),
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://installment_plan?id=42")
        )
    }

    @Test
    fun `installments links stay rejected when the explicit seam is false regardless of the default`() {
        listOf(
            "expensetracker://installments",
            "expensetracker://installment_plan?id=42"
        ).forEach { uri ->
            assertEquals(
                DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST),
                DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, uri, installmentsEnabled = false)
            )
        }
    }

    // Enabled-path installment link cases retired (owner-approvals 81a); restated as loan S13-a acceptance examples in loan-installments-handoff.md.

    @Test
    fun `installments flag does not change unrelated hosts`() {
        assertEquals(
            DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.HOME)),
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://home")
        )
        assertEquals(
            DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST),
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "expensetracker://unknown")
        )
        assertEquals(
            DeepLinkPolicyResult.NotADeepLink,
            DeepLinkPolicy.evaluate(Intent.ACTION_VIEW, "https://expensetracker.app/installments")
        )
    }
}
