package com.yourname.expensetracker.service

import org.junit.Assert.assertEquals
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

    // ── Finance packages — content-gated since P2-09 ─────────────────────
    // P2-09: finance packages no longer capture unconditionally. Content must
    // pass the finance path (deny checks + expense/transaction signal +
    // amount). Empty content therefore falls through to NO_AMOUNT → not
    // captured. (These tests were stale: they asserted pre-P2-09 semantics.)

    @Test
    fun `finance packages with empty content are NOT captured (P2-09 NO_AMOUNT)`() {
        for (pkg in NotificationFilter.FINANCE_PACKAGES) {
            val decision = NotificationFilter.decide(pkg, "", "", "")
            assertFalse("package $pkg with empty content should not capture", decision.capture)
            assertEquals(NotificationFilterReason.NO_AMOUNT, decision.reason)
        }
    }

    @Test
    fun `finance packages with benign amount content are NOT captured (NO_TRANSACTION_SIGNAL)`() {
        val decision = NotificationFilter.decide("com.revolut.revolut", "Title", "Random 12.50", "Content")
        assertFalse(decision.capture)
        assertEquals(NotificationFilterReason.NO_TRANSACTION_SIGNAL, decision.reason)
    }

    // ── NEW-P1-2026-001: bare "pos" substring false positives ───────────
    // "pos" was an EXPENSE_SIGNAL_KEYWORDS substring match, so "deposit(ed)",
    // "purpose", "suppose", "position", "positive", "post" all counted as a
    // strong expense signal, defeating the incoming/deposit deny.

    @Test
    fun `salary deposited is denied INCOMING_ONLY despite pos inside deposited`() {
        val decision = NotificationFilter.decide(
            "com.revolut.revolut",
            "Salary",
            "Salary deposited €2000",
            ""
        )
        assertFalse(decision.capture)
        assertEquals(NotificationFilterReason.INCOMING_ONLY, decision.reason)
        assertEquals(TransactionDirection.CREDIT, decision.direction)
        assertTrue(decision.hasMoneySignal)
    }

    @Test
    fun `pos payment is captured as strong expense`() {
        val decision = NotificationFilter.decide(
            "com.revolut.revolut",
            "Card used",
            "POS payment €12.50",
            ""
        )
        assertTrue(decision.capture)
        assertEquals(NotificationFilterReason.ALLOW_STRONG_EXPENSE, decision.reason)
    }

    @Test
    fun `bare pos token alone is a strong expense signal (whole-word regex)`() {
        // Discriminates the NEW-P1-2026-001 fix: no keyword other than the
        // whole-word "pos" regex matches here (pre-fix "pos payment" would
        // also match via the "payment" keyword, hiding a broken regex).
        val decision = NotificationFilter.decide(
            "com.revolut.revolut",
            "POS",
            "POS €4.80",
            ""
        )
        assertTrue(decision.capture)
        assertEquals(NotificationFilterReason.ALLOW_STRONG_EXPENSE, decision.reason)
    }

    @Test
    fun `deposit for savings purpose is denied - purpose must not be expense signal`() {
        val decision = NotificationFilter.decide(
            "gr.nbg.mobilebanking",
            "Deposit",
            "Deposit for savings purpose €100",
            ""
        )
        assertFalse(decision.capture)
        assertEquals(NotificationFilterReason.INCOMING_ONLY, decision.reason)
        assertEquals(TransactionDirection.CREDIT, decision.direction)
    }

    @Test
    fun `deposit fee is denied INCOMING_ONLY - pinned NEW-P1-2026-001 decision`() {
        // Pinned decision: "Deposit fee €2.50" was captured pre-fix ONLY via the
        // "pos"-in-"deposit" bug. Direction is CREDIT (a fee is not a strong
        // expense signal the filter can prove), so the incoming/deposit deny is
        // accepted as the correct behavior going forward.
        val decision = NotificationFilter.decide(
            "gr.nbg.mobilebanking",
            "Deposit",
            "Deposit fee €2.50",
            ""
        )
        assertFalse(decision.capture)
        assertEquals(NotificationFilterReason.INCOMING_ONLY, decision.reason)
        assertEquals(TransactionDirection.CREDIT, decision.direction)
        assertTrue(decision.hasMoneySignal)
    }

    @Test
    fun `Greek salary deposit is denied INCOMING_ONLY`() {
        // "κατατέθηκε" (deposited) must not trip any expense keyword.
        val decision = NotificationFilter.decide(
            "gr.nbg.mobilebanking",
            "Μισθός",
            "Μισθός κατατέθηκε €2000",
            ""
        )
        assertFalse(decision.capture)
        assertEquals(NotificationFilterReason.INCOMING_ONLY, decision.reason)
        assertEquals(TransactionDirection.CREDIT, decision.direction)
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

    @Test
    fun `discovery mode - lowercase currency code captures`() {
        assertTrue(NotificationFilter.shouldCapture(
            "com.lowercase.currency",
            "Payment",
            "Amount 12,50 eur",
            ""
        ))
    }
}
