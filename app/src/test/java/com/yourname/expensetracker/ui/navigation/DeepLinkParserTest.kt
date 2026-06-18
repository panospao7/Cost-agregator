package com.yourname.expensetracker.ui.navigation

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkParserTest {

    @Test
    fun `home host allows navigation to Home`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://home"))
        assertTrue(decision is DeepLinkDecision.Allow)
        assertEquals(NavigationDestination.Home, (decision as DeepLinkDecision.Allow).destination)
    }

    @Test
    fun `dashboard host allows navigation to Home`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://dashboard"))
        assertTrue(decision is DeepLinkDecision.Allow)
    }

    @Test
    fun `activity without expenseId allows transactions list`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://activity"))
        assertTrue(decision is DeepLinkDecision.Allow)
        assertTrue((decision as DeepLinkDecision.Allow).destination is NavigationDestination.Transactions)
    }

    @Test
    fun `activity with expenseId requires confirmation`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://activity?expenseId=123"))
        assertTrue(decision is DeepLinkDecision.RequireConfirmation)
        val dest = (decision as DeepLinkDecision.RequireConfirmation).destination
        assertTrue(dest is NavigationDestination.Transactions)
        assertEquals(123L, (dest as NavigationDestination.Transactions).initialExpenseId)
    }

    @Test
    fun `review requires confirmation`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://review"))
        assertTrue(decision is DeepLinkDecision.RequireConfirmation)
        assertEquals(NavigationDestination.Review, (decision as DeepLinkDecision.RequireConfirmation).destination)
    }

    @Test
    fun `add requires confirmation`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://add"))
        assertTrue(decision is DeepLinkDecision.RequireConfirmation)
    }

    @Test
    fun `plan allows budget navigation`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://plan"))
        assertTrue(decision is DeepLinkDecision.Allow)
        assertEquals(NavigationDestination.Budget, (decision as DeepLinkDecision.Allow).destination)
    }

    @Test
    fun `analytics preserves period parameter`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://analytics?period=MONTH"))
        assertTrue(decision is DeepLinkDecision.Allow)
        val dest = (decision as DeepLinkDecision.Allow).destination as NavigationDestination.Analytics
        assertEquals("MONTH", dest.initialPeriod)
    }

    @Test
    fun `map preserves location parameter`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://map?location=Athens"))
        assertTrue(decision is DeepLinkDecision.Allow)
        val dest = (decision as DeepLinkDecision.Allow).destination as NavigationDestination.SpendingMap
        assertEquals("Athens", dest.initialLocationQuery)
    }

    @Test
    fun `unknown host rejects`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://unknown"))
        assertTrue(decision is DeepLinkDecision.Reject)
    }

    @Test
    fun `wrong scheme rejects`() {
        val decision = parseDeepLink(Uri.parse("https://expensetracker.app/home"))
        assertTrue(decision is DeepLinkDecision.Reject)
    }

    @Test
    fun `invalid expenseId treated as no expenseId`() {
        val decision = parseDeepLink(Uri.parse("expensetracker://activity?expenseId=abc"))
        assertTrue(decision is DeepLinkDecision.Allow)
    }
}
