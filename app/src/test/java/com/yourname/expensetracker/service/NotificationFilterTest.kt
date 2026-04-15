package com.yourname.expensetracker.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NotificationFilter - package filtering, communication-app
 * heuristic gating, and discovery mode.
 */
class NotificationFilterTest {

    // ── Ignored packages ──────────────────────────────────────────────────

    @Test
    fun `ignored packages return false`() {
        assertFalse(NotificationFilter.shouldCapture("android", "Title", "Text", ""))
        assertFalse(NotificationFilter.shouldCapture("com.android.systemui", "Title", "Text", ""))
        assertFalse(NotificationFilter.shouldCapture("com.whatsapp", "Payment 10.00", "paid", ""))
        assertFalse(NotificationFilter.shouldCapture("com.instagram.android", "Amount 5.00", "spent", ""))
    }

    // ── Finance packages — always captured ─────────────────────────────

    @Test
    fun `finance packages return true regardless of content`() {
        assertTrue(NotificationFilter.shouldCapture("com.revolut.revolut", "", "", ""))
        assertTrue(NotificationFilter.shouldCapture("gr.nbg.mobilebanking", "Title", "Text", ""))
        assertTrue(NotificationFilter.shouldCapture("com.eurobank.mobile", "Random", "Content", ""))
        assertTrue(NotificationFilter.shouldCapture("com.google.android.apps.walletnfcrel", "", "", ""))
        assertTrue(NotificationFilter.shouldCapture("gr.alpha.mobile", "", "", ""))
        assertTrue(NotificationFilter.shouldCapture("com.winbank.mobile", "", "", ""))
    }

    // ── Communication packages — must go through heuristics ─────────────

    @Test
    fun `Gmail with bank-like financial content captures`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.google.android.gm",
            "NBG Transaction Alert",
            "You paid 25.50 EUR at Supermarket",
            ""
        ))
    }

    @Test
    fun `Gmail with personal non-financial content does NOT capture`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.google.android.gm",
            "Random",
            "Content",
            ""
        ))
        assertFalse(NotificationFilter.shouldCapture(
            "com.google.android.gm",
            "Meeting tomorrow",
            "Let's discuss the project",
            ""
        ))
    }

    @Test
    fun `Gmail with amount but without financial keyword does NOT capture`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.google.android.gm",
            "Flight itinerary",
            "Your seat 25.50 is confirmed",
            ""
        ))
    }

    @Test
    fun `SMS apps with financial content captures`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.google.android.apps.messaging",
            "Bank Alert",
            "Card charged 15.99 EUR",
            ""
        ))
        assertTrue(NotificationFilter.shouldCapture(
            "com.samsung.android.messaging",
            "Transaction",
            "Debit of $50.00",
            ""
        ))
        assertTrue(NotificationFilter.shouldCapture(
            "com.android.mms",
            "Payment",
            "Charged €10.00",
            ""
        ))
    }

    @Test
    fun `SMS apps with personal content does NOT capture`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.google.android.apps.messaging",
            "Mom",
            "Are you coming for dinner?",
            ""
        ))
        assertFalse(NotificationFilter.shouldCapture(
            "com.samsung.android.messaging",
            "Friend",
            "Happy birthday!",
            ""
        ))
    }

    @Test
    fun `Viber with financial content captures`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.viber.voip",
            "Alpha Bank",
            "Card payment of 30.00 EUR at Store",
            ""
        ))
    }

    @Test
    fun `Viber with personal content does NOT capture`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.viber.voip",
            "John",
            "See you tomorrow",
            ""
        ))
    }

    // ── MONITORED_PACKAGES backward compat ──────────────────────────────

    @Test
    fun `MONITORED_PACKAGES contains both finance and communication packages`() {
        // Finance
        assertTrue(NotificationFilter.MONITORED_PACKAGES.contains("com.revolut.revolut"))
        assertTrue(NotificationFilter.MONITORED_PACKAGES.contains("gr.nbg.mobilebanking"))
        // Communication
        assertTrue(NotificationFilter.MONITORED_PACKAGES.contains("com.google.android.gm"))
        assertTrue(NotificationFilter.MONITORED_PACKAGES.contains("com.viber.voip"))
        assertTrue(NotificationFilter.MONITORED_PACKAGES.contains("com.google.android.apps.messaging"))
    }

    // ── Discovery mode (unknown packages) ───────────────────────────────

    @Test
    fun `discovery mode - amount plus financial keyword returns true`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.unknown.bank",
            "Payment",
            "You paid 25.50 EUR",
            ""
        ))
        assertTrue(NotificationFilter.shouldCapture(
            "com.some.app",
            "Transaction",
            "Amount: 10.00",
            ""
        ))
        assertTrue(NotificationFilter.shouldCapture(
            "com.other.app",
            "Card charged",
            "€15.99",
            ""
        ))
    }

    @Test
    fun `discovery mode - amount without financial keyword returns false`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.random.app",
            "Your score",
            "Points: 25.50",
            ""
        ))
    }

    @Test
    fun `discovery mode - financial keyword without amount returns false`() {
        assertFalse(NotificationFilter.shouldCapture(
            "com.unknown.app",
            "Payment",
            "Your payment was successful",
            ""
        ))
    }

    @Test
    fun `discovery mode - bigText included in content`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.new.bank",
            "Alert",
            "",
            "You spent 50.00 EUR at store"
        ))
    }

    @Test
    fun `discovery mode - Greek keywords`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.greek.app",
            "Πληρωμή",
            "25.50 EUR",
            ""
        ))
    }

    @Test
    fun `discovery mode - currency symbols`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.currency.app",
            "Paid",
            "$10.00",
            ""
        ))
        assertTrue(NotificationFilter.shouldCapture(
            "com.currency2.app",
            "Charged",
            "£5.99",
            ""
        ))
    }

    @Test
    fun `discovery mode - amount pattern with comma`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.eu.app",
            "Payment",
            "Amount 12,50 EUR",
            ""
        ))
    }
}
