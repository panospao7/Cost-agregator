package com.yourname.expensetracker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NotificationFilter - package filtering, communication-app
 * heuristic gating, and discovery mode.
 *
 * Table-driven: every row carries a name that is echoed in assertion messages.
 */
class NotificationFilterTest {

    private data class CaptureRow(
        val name: String,
        val pkg: String,
        val title: String,
        val text: String,
        val bigText: String = ""
    )

    private data class DecisionRow(
        val name: String,
        val pkg: String,
        val title: String,
        val text: String,
        val expectedCapture: Boolean,
        val expectedReason: NotificationFilterReason,
        val expectedDirection: TransactionDirection? = null,
        val expectMoneySignal: Boolean = false
    )

    private fun assertAllCaptured(rows: List<CaptureRow>) {
        assertTrue("size guard", rows.isNotEmpty())
        for (row in rows) {
            assertTrue(
                "row '${row.name}' should be captured",
                NotificationFilter.shouldCapture(row.pkg, row.title, row.text, row.bigText)
            )
        }
    }

    private fun assertNoneCaptured(rows: List<CaptureRow>) {
        assertTrue("size guard", rows.isNotEmpty())
        for (row in rows) {
            assertFalse(
                "row '${row.name}' should NOT be captured",
                NotificationFilter.shouldCapture(row.pkg, row.title, row.text, row.bigText)
            )
        }
    }

    private fun assertDecisions(rows: List<DecisionRow>) {
        assertTrue("size guard", rows.isNotEmpty())
        for (row in rows) {
            val decision = NotificationFilter.decide(row.pkg, row.title, row.text, "")
            assertEquals("row '${row.name}' capture", row.expectedCapture, decision.capture)
            assertEquals("row '${row.name}' reason", row.expectedReason, decision.reason)
            if (row.expectedDirection != null) {
                assertEquals("row '${row.name}' direction", row.expectedDirection, decision.direction)
            }
            if (row.expectMoneySignal) {
                assertTrue("row '${row.name}' hasMoneySignal", decision.hasMoneySignal)
            }
        }
    }

    // ── Ignored packages ──────────────────────────────────────────────────

    @Test
    fun `ignored packages return false`() {
        assertNoneCaptured(
            listOf(
                CaptureRow("android", "android", "Title", "Text"),
                CaptureRow("systemui", "com.android.systemui", "Title", "Text"),
                CaptureRow("whatsapp with payment text", "com.whatsapp", "Payment 10.00", "paid"),
                CaptureRow("instagram with amount text", "com.instagram.android", "Amount 5.00", "spent")
            )
        )
    }

    // ── Finance packages — content-gated since P2-09 ─────────────────────
    // P2-09: finance packages no longer capture unconditionally. Content must
    // pass the finance path (deny checks + expense/transaction signal +
    // amount). Empty content therefore falls through to NO_AMOUNT → not
    // captured. (These tests were stale: they asserted pre-P2-09 semantics.)

    @Test
    fun `finance packages with empty content are NOT captured (P2-09 NO_AMOUNT)`() {
        assertTrue("FINANCE_PACKAGES must not be empty", NotificationFilter.FINANCE_PACKAGES.isNotEmpty())
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
    fun `incoming deposit rows are denied INCOMING_ONLY as CREDIT despite pos substring`() {
        assertDecisions(
            listOf(
                // "pos" inside "deposited" must not count as an expense signal.
                DecisionRow(
                    "salary deposited (pos inside deposited)",
                    "com.revolut.revolut", "Salary", "Salary deposited €2000",
                    expectedCapture = false,
                    expectedReason = NotificationFilterReason.INCOMING_ONLY,
                    expectedDirection = TransactionDirection.CREDIT,
                    expectMoneySignal = true
                ),
                // "purpose" must not be an expense signal.
                DecisionRow(
                    "deposit for savings purpose",
                    "gr.nbg.mobilebanking", "Deposit", "Deposit for savings purpose €100",
                    expectedCapture = false,
                    expectedReason = NotificationFilterReason.INCOMING_ONLY,
                    expectedDirection = TransactionDirection.CREDIT
                ),
                // Pinned NEW-P1-2026-001 decision: "Deposit fee €2.50" was captured pre-fix ONLY
                // via the "pos"-in-"deposit" bug. Direction is CREDIT (a fee is not a strong
                // expense signal the filter can prove), so the deny is accepted going forward.
                DecisionRow(
                    "deposit fee (pinned NEW-P1-2026-001 decision)",
                    "gr.nbg.mobilebanking", "Deposit", "Deposit fee €2.50",
                    expectedCapture = false,
                    expectedReason = NotificationFilterReason.INCOMING_ONLY,
                    expectedDirection = TransactionDirection.CREDIT,
                    expectMoneySignal = true
                ),
                // "κατατέθηκε" (deposited) must not trip any expense keyword.
                DecisionRow(
                    "Greek salary deposit",
                    "gr.nbg.mobilebanking", "Μισθός", "Μισθός κατατέθηκε €2000",
                    expectedCapture = false,
                    expectedReason = NotificationFilterReason.INCOMING_ONLY,
                    expectedDirection = TransactionDirection.CREDIT
                )
            )
        )
    }

    @Test
    fun `pos tokens are captured as strong expense`() {
        assertDecisions(
            listOf(
                DecisionRow(
                    "pos payment",
                    "com.revolut.revolut", "Card used", "POS payment €12.50",
                    expectedCapture = true,
                    expectedReason = NotificationFilterReason.ALLOW_STRONG_EXPENSE
                ),
                // Discriminates the NEW-P1-2026-001 fix: no keyword other than the whole-word
                // "pos" regex matches here (pre-fix "pos payment" would also match via the
                // "payment" keyword, hiding a broken regex).
                DecisionRow(
                    "bare pos token alone (whole-word regex)",
                    "com.revolut.revolut", "POS", "POS €4.80",
                    expectedCapture = true,
                    expectedReason = NotificationFilterReason.ALLOW_STRONG_EXPENSE
                )
            )
        )
    }

    // ── Communication packages — must go through heuristics ─────────────

    @Test
    fun `communication packages with financial content capture`() {
        assertAllCaptured(
            listOf(
                CaptureRow("Gmail bank alert", "com.google.android.gm", "NBG Transaction Alert", "You paid 25.50 EUR at Supermarket"),
                CaptureRow("Google Messages card charged", "com.google.android.apps.messaging", "Bank Alert", "Card charged 15.99 EUR"),
                CaptureRow("Samsung Messages debit", "com.samsung.android.messaging", "Transaction", "Debit of $50.00"),
                CaptureRow("AOSP MMS charged", "com.android.mms", "Payment", "Charged €10.00"),
                CaptureRow("Viber card payment", "com.viber.voip", "Alpha Bank", "Card payment of 30.00 EUR at Store")
            )
        )
    }

    @Test
    fun `communication packages with personal or non-financial content do NOT capture`() {
        assertNoneCaptured(
            listOf(
                CaptureRow("Gmail generic", "com.google.android.gm", "Random", "Content"),
                CaptureRow("Gmail meeting", "com.google.android.gm", "Meeting tomorrow", "Let's discuss the project"),
                CaptureRow("Gmail amount without financial keyword", "com.google.android.gm", "Flight itinerary", "Your seat 25.50 is confirmed"),
                CaptureRow("Google Messages dinner", "com.google.android.apps.messaging", "Mom", "Are you coming for dinner?"),
                CaptureRow("Samsung Messages birthday", "com.samsung.android.messaging", "Friend", "Happy birthday!"),
                CaptureRow("Viber personal", "com.viber.voip", "John", "See you tomorrow")
            )
        )
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
    fun `discovery mode - positive rows are captured`() {
        assertAllCaptured(
            listOf(
                CaptureRow("amount plus keyword: paid EUR", "com.unknown.bank", "Payment", "You paid 25.50 EUR"),
                CaptureRow("amount plus keyword: Amount label", "com.some.app", "Transaction", "Amount: 10.00"),
                CaptureRow("amount plus keyword: euro symbol", "com.other.app", "Card charged", "€15.99"),
                CaptureRow("bigText included in content", "com.new.bank", "Alert", "", "You spent 50.00 EUR at store"),
                CaptureRow("Greek keywords", "com.greek.app", "Πληρωμή", "25.50 EUR"),
                CaptureRow("currency symbol dollar", "com.currency.app", "Paid", "$10.00"),
                CaptureRow("currency symbol pound", "com.currency2.app", "Charged", "£5.99"),
                CaptureRow("amount pattern with comma", "com.eu.app", "Payment", "Amount 12,50 EUR"),
                CaptureRow("lowercase currency code", "com.lowercase.currency", "Payment", "Amount 12,50 eur")
            )
        )
    }

    @Test
    fun `discovery mode - negative rows are not captured`() {
        assertNoneCaptured(
            listOf(
                CaptureRow("amount without financial keyword", "com.random.app", "Your score", "Points: 25.50"),
                CaptureRow("financial keyword without amount", "com.unknown.app", "Payment", "Your payment was successful")
            )
        )
    }
}
