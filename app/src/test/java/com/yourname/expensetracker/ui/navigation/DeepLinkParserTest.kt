package com.yourname.expensetracker.ui.navigation

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkParserTest {

    private class Row(
        val name: String,
        val uri: String,
        val expectedKind: KClass<out DeepLinkDecision>,
        val expectedDestination: NavigationDestination?
    )

    @Test
    fun `deep link table yields exact decision kind and destination`() {
        val rows = listOf(
            Row("home host", "expensetracker://home",
                DeepLinkDecision.Allow::class, NavigationDestination.Home),
            Row("dashboard host", "expensetracker://dashboard",
                DeepLinkDecision.Allow::class, NavigationDestination.Home),
            Row("activity without expenseId", "expensetracker://activity",
                DeepLinkDecision.Allow::class, NavigationDestination.Transactions(initialExpenseId = null)),
            Row("activity with expenseId", "expensetracker://activity?expenseId=123",
                DeepLinkDecision.RequireConfirmation::class, NavigationDestination.Transactions(initialExpenseId = 123L)),
            Row("activity with invalid expenseId", "expensetracker://activity?expenseId=abc",
                DeepLinkDecision.Allow::class, NavigationDestination.Transactions(initialExpenseId = null)),
            Row("review", "expensetracker://review",
                DeepLinkDecision.RequireConfirmation::class, NavigationDestination.Review),
            Row("add", "expensetracker://add",
                DeepLinkDecision.RequireConfirmation::class, NavigationDestination.AddExpense),
            Row("plan", "expensetracker://plan",
                DeepLinkDecision.Allow::class, NavigationDestination.Budget),
            Row("analytics with period", "expensetracker://analytics?period=MONTH",
                DeepLinkDecision.Allow::class, NavigationDestination.Analytics(initialPeriod = "MONTH")),
            Row("map with location", "expensetracker://map?location=Athens",
                DeepLinkDecision.Allow::class, NavigationDestination.SpendingMap(initialLocationQuery = "Athens")),
            Row("unknown host", "expensetracker://unknown",
                DeepLinkDecision.Reject::class, null),
            Row("wrong scheme", "https://expensetracker.app/home",
                DeepLinkDecision.Reject::class, null)
        )

        rows.forEach { row ->
            val decision = parseDeepLink(Uri.parse(row.uri))
            val actualDestination = when (decision) {
                is DeepLinkDecision.Allow -> decision.destination
                is DeepLinkDecision.RequireConfirmation -> decision.destination
                is DeepLinkDecision.Reject -> null
            }
            assertEquals("Row '${row.name}' (${row.uri}) decision kind", row.expectedKind, decision::class)
            assertEquals("Row '${row.name}' (${row.uri}) destination", row.expectedDestination, actualDestination)
        }
    }

    @Test
    fun `deferred feature hosts are not deep-link routable`() {
        listOf(
            "tax", "tax_configuration", "investment", "investment_portfolio",
            "challenges", "spending_challenges"
        ).forEach { host ->
            val decision = parseDeepLink(Uri.parse("expensetracker://$host"))
            assertTrue("Host '$host' must be rejected", decision is DeepLinkDecision.Reject)
        }
    }

    // ---- loan-installments S10a-1: dormant installments deep links ----

    @Test
    fun `installments links are rejected while the flag is false`() {
        listOf(
            "expensetracker://installments",
            "expensetracker://installment_plan?id=42"
        ).forEach { uri ->
            assertEquals(
                "Flag false must reject $uri",
                DeepLinkDecision.Reject,
                parseDeepLink(Uri.parse(uri), installmentsEnabled = false)
            )
        }
    }

    @Test
    fun `installments links are rejected by default while the compile-time gate is false`() {
        // Canary: the enablement slice (S13) flips InstallmentsFeature.ENABLED and must update this test.
        assertFalse(InstallmentsFeature.ENABLED)
        listOf(
            "expensetracker://installments",
            "expensetracker://installment_plan?id=42"
        ).forEach { uri ->
            assertEquals(DeepLinkDecision.Reject, parseDeepLink(Uri.parse(uri)))
        }
    }

    @Test
    fun `installments overview link requires confirmation when enabled`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://installments"), installmentsEnabled = true)

        assertTrue(decision is DeepLinkDecision.RequireConfirmation)
        assertEquals(
            NavigationDestination.Installments,
            (decision as DeepLinkDecision.RequireConfirmation).destination
        )
    }

    @Test
    fun `installment plan link with a positive id requires confirmation when enabled`() {
        val decision = parseDeepLink(
            Uri.parse("expensetracker://installment_plan?id=42"),
            installmentsEnabled = true
        )

        assertTrue(decision is DeepLinkDecision.RequireConfirmation)
        assertEquals(
            NavigationDestination.InstallmentPlanDetail(42L),
            (decision as DeepLinkDecision.RequireConfirmation).destination
        )
    }

    @Test
    fun `installment plan link with a missing or malformed id is rejected even when enabled`() {
        listOf(
            "expensetracker://installment_plan",
            "expensetracker://installment_plan?id=",
            "expensetracker://installment_plan?id=abc",
            "expensetracker://installment_plan?id=1.5",
            "expensetracker://installment_plan?id=0",
            "expensetracker://installment_plan?id=-7",
            "expensetracker://installment_plan?plan=42",
            "expensetracker://installment_plan?id=99999999999999999999"
        ).forEach { uri ->
            assertEquals(
                "Enabled flag must still reject $uri",
                DeepLinkDecision.Reject,
                parseDeepLink(Uri.parse(uri), installmentsEnabled = true)
            )
        }
    }

    @Test
    fun `installments flag does not change unrelated hosts`() {
        listOf(false, true).forEach { enabled ->
            assertEquals(
                DeepLinkDecision.Allow(NavigationDestination.Home),
                parseDeepLink(Uri.parse("expensetracker://home"), installmentsEnabled = enabled)
            )
            assertEquals(
                DeepLinkDecision.Reject,
                parseDeepLink(Uri.parse("expensetracker://unknown"), installmentsEnabled = enabled)
            )
            assertEquals(
                DeepLinkDecision.Reject,
                parseDeepLink(Uri.parse("https://expensetracker.app/installments"), installmentsEnabled = enabled)
            )
        }
    }
}
