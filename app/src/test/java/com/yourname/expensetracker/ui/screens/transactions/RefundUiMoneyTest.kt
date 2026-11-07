package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.transaction.RefundLinkRefusal
import com.yourname.expensetracker.domain.transaction.RefundWriteFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refund netting S4 (ux-flows sections 3.3 and 4): display arithmetic is BigDecimal at the currency's own
 * fraction digits, HALF_EVEN, never negative; a currency without known digits omits the figure (null, never
 * a zero); every domain result maps to exactly one string resource.
 */
class RefundUiMoneyTest {

    // ---- net: gross, refunded, net in the row currency ------------------------------------------

    @Test
    fun `net subtracts refunded from gross at the currency scale`() {
        assertEquals(60.0, RefundUiMoney.net(100.0, 40.0, "EUR")!!, 0.0)
        assertEquals(0.2, RefundUiMoney.net(0.3, 0.1, "EUR")!!, 0.0) // no binary-float residue
    }

    @Test
    fun `net with nothing refunded equals the gross`() {
        assertEquals(100.0, RefundUiMoney.net(100.0, 0.0, "EUR")!!, 0.0)
    }

    @Test
    fun `net is clamped at zero when refunded equals or exceeds the gross`() {
        assertEquals(0.0, RefundUiMoney.net(100.0, 100.0, "EUR")!!, 0.0)
        assertEquals(0.0, RefundUiMoney.net(100.0, 100.01, "EUR")!!, 0.0)
        assertEquals(0.0, RefundUiMoney.net(10.0, 15.0, "EUR")!!, 0.0)
    }

    @Test
    fun `fully refunded net is zero and not null`() {
        // "Net 0,00" must render (ux-flows 9); null would hide the figure.
        assertNotNull(RefundUiMoney.net(25.0, 25.0, "EUR"))
    }

    @Test
    fun `net uses zero fraction digits for JPY and three for BHD`() {
        assertEquals(600.0, RefundUiMoney.net(1000.0, 400.0, "JPY")!!, 0.0)
        assertEquals(0.999, RefundUiMoney.net(1.0, 0.001, "BHD")!!, 0.0)
    }

    // ---- HALF_EVEN ------------------------------------------------------------------------------

    @Test
    fun `subtract rounds half to even at two digits`() {
        assertEquals(0.12, RefundUiMoney.subtract(0.125, 0.0, "EUR")!!, 0.0) // tie, 2 is even: down
        assertEquals(0.14, RefundUiMoney.subtract(0.135, 0.0, "EUR")!!, 0.0) // tie, 3 is odd: up
        assertEquals(10.0, RefundUiMoney.subtract(10.005, 0.0, "EUR")!!, 0.0)
        assertEquals(10.02, RefundUiMoney.subtract(10.015, 0.0, "EUR")!!, 0.0)
    }

    @Test
    fun `subtract rounds half to even at zero digits for JPY`() {
        assertEquals(10.0, RefundUiMoney.subtract(10.5, 0.0, "JPY")!!, 0.0)
        assertEquals(12.0, RefundUiMoney.subtract(11.5, 0.0, "JPY")!!, 0.0)
    }

    @Test
    fun `minOf compares at the currency scale and keeps the smaller`() {
        assertEquals(40.0, RefundUiMoney.minOf(100.0, 40.0, "EUR")!!, 0.0)
        assertEquals(40.0, RefundUiMoney.minOf(40.0, 100.0, "EUR")!!, 0.0)
        assertEquals(0.12, RefundUiMoney.minOf(0.125, 99.0, "EUR")!!, 0.0)
    }

    @Test
    fun `isLess compares after rounding to the currency scale`() {
        assertTrue(RefundUiMoney.isLess(40.0, 100.0, "EUR"))
        assertFalse(RefundUiMoney.isLess(100.0, 100.0, "EUR"))
        assertFalse(RefundUiMoney.isLess(0.124, 0.125, "EUR")) // both round to 0.12
        assertTrue(RefundUiMoney.isLess(0.12, 0.13, "EUR"))
    }

    // ---- unavailable scale never becomes a zero ---------------------------------------------------

    @Test
    fun `a currency without defined digits gives no figure`() {
        assertNull(RefundUiMoney.scaleOf("XXX"))
        assertNull(RefundUiMoney.net(100.0, 40.0, "XXX"))
        assertNull(RefundUiMoney.subtract(100.0, 40.0, "XXX"))
        assertNull(RefundUiMoney.minOf(100.0, 40.0, "XXX"))
    }

    @Test
    fun `a malformed or unknown currency gives no figure`() {
        for (bad in listOf("", "EU", "EURO", "E1R", "ZZZ")) {
            assertNull("scale for '$bad'", RefundUiMoney.scaleOf(bad))
            assertNull("net for '$bad'", RefundUiMoney.net(100.0, 0.0, bad))
        }
    }

    @Test
    fun `known currencies report their digits`() {
        assertEquals(2, RefundUiMoney.scaleOf("EUR"))
        assertEquals(0, RefundUiMoney.scaleOf("JPY"))
        assertEquals(3, RefundUiMoney.scaleOf("BHD"))
        assertEquals(2, RefundUiMoney.scaleOf("usd")) // parse upper-cases
    }

    // ---- RefundLinkUiMapper: one string per result -------------------------------------------------

    @Test
    fun `every refusal maps to the documented string resource`() {
        val expected = mapOf(
            RefundLinkRefusal.NOTHING_TO_APPLY to R.string.refund_refused_nothing_to_apply,
            RefundLinkRefusal.REFUND_LINKED_ELSEWHERE to R.string.refund_refused_linked_elsewhere,
            RefundLinkRefusal.REFUND_BEFORE_PURCHASE to R.string.refund_refused_before_purchase,
            RefundLinkRefusal.CURRENCY_MISMATCH to R.string.refund_refused_currency,
            RefundLinkRefusal.INSTALLMENT_MANAGED to R.string.refund_refused_installment,
            RefundLinkRefusal.GROUP_LINKED to R.string.refund_refused_group,
            RefundLinkRefusal.REFUND_NOT_FOUND to R.string.refund_refused_stale,
            RefundLinkRefusal.PURCHASE_NOT_FOUND to R.string.refund_refused_stale,
            RefundLinkRefusal.SAME_ROW to R.string.refund_refused_stale,
            RefundLinkRefusal.NOT_A_DEPOSIT to R.string.refund_refused_stale,
            RefundLinkRefusal.NOT_A_PURCHASE to R.string.refund_refused_stale,
            RefundLinkRefusal.INVALID_AMOUNT to R.string.refund_refused_stale,
            RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE to R.string.refund_refused_stale
        )
        assertEquals("every refusal code is covered", RefundLinkRefusal.values().toSet(), expected.keys)
        for ((code, res) in expected) {
            assertEquals("$code", res, RefundLinkUiMapper.refusalMessageRes(code))
        }
    }

    @Test
    fun `refusal follow-ups match ux-flows 3_3`() {
        assertEquals(
            RefundRefusalFollowUp.CLOSE_AND_REFRESH,
            RefundLinkUiMapper.refusalFollowUp(RefundLinkRefusal.REFUND_LINKED_ELSEWHERE)
        )
        for (code in listOf(
            RefundLinkRefusal.NOTHING_TO_APPLY,
            RefundLinkRefusal.REFUND_BEFORE_PURCHASE,
            RefundLinkRefusal.CURRENCY_MISMATCH,
            RefundLinkRefusal.INSTALLMENT_MANAGED,
            RefundLinkRefusal.GROUP_LINKED
        )) {
            assertEquals("$code", RefundRefusalFollowUp.RELOAD_CANDIDATES, RefundLinkUiMapper.refusalFollowUp(code))
        }
        for (code in listOf(
            RefundLinkRefusal.REFUND_NOT_FOUND,
            RefundLinkRefusal.PURCHASE_NOT_FOUND,
            RefundLinkRefusal.SAME_ROW,
            RefundLinkRefusal.NOT_A_DEPOSIT,
            RefundLinkRefusal.NOT_A_PURCHASE,
            RefundLinkRefusal.INVALID_AMOUNT,
            RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE
        )) {
            assertEquals("$code", RefundRefusalFollowUp.RELOAD_AND_REFRESH, RefundLinkUiMapper.refusalFollowUp(code))
        }
    }

    @Test
    fun `write failures map to the two existing messages`() {
        assertEquals(R.string.ui_write_blocked_maintenance, RefundLinkUiMapper.failureMessageRes(RefundWriteFailure.WRITE_BLOCKED))
        assertEquals(R.string.recurring_error_generic, RefundLinkUiMapper.failureMessageRes(RefundWriteFailure.INTERNAL))
    }
}
