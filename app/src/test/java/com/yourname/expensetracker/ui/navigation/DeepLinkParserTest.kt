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
}
