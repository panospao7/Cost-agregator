package com.yourname.expensetracker.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NotificationFilter - package filtering and discovery mode.
 */
class NotificationFilterTest {

    @Test
    fun `ignored packages return false`() {
        assertFalse(NotificationFilter.shouldCapture("android", "Title", "Text", ""))
        assertFalse(NotificationFilter.shouldCapture("com.android.systemui", "Title", "Text", ""))
        assertFalse(NotificationFilter.shouldCapture("com.whatsapp", "Payment 10.00", "paid", ""))
        assertFalse(NotificationFilter.shouldCapture("com.instagram.android", "Amount 5.00", "spent", ""))
    }

    @Test
    fun `monitored packages return true regardless of content`() {
        assertTrue(NotificationFilter.shouldCapture("com.revolut.revolut", "", "", ""))
        assertTrue(NotificationFilter.shouldCapture("gr.nbg.mobilebanking", "Title", "Text", ""))
        assertTrue(NotificationFilter.shouldCapture("com.google.android.gm", "Random", "Content", ""))
    }

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
