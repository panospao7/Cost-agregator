package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * FC-PAID-UNLINKED S1: the pure matcher. Expected values come from the domain spec
 * (`docs/features/fc-paid-unlinked/domain-spec.md`): INV-4 (single pair predicate), INV-5 (windows),
 * INV-6 (ranking), INV-14 (decline test). Robolectric is used only so `org.json` is real.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringPaymentLinkMatcherTest {

    private val key = "acmeproperties"

    private fun startOf(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun noon(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli()

    private fun expense(
        amount: Double = 400.0,
        currency: String = "EUR",
        day: LocalDate = DUE
    ) = Expense(
        amount = amount,
        currency = currency,
        merchant = "Acme Properties",
        transactionType = TransactionType.PURCHASE,
        date = noon(day),
        createdAt = noon(day),
        source = "test"
    )

    private fun occurrence(
        id: Long = 1L,
        due: LocalDate = DUE,
        expected: Double = 400.0,
        currency: String = "EUR"
    ) = RecurringOccurrence(
        id = id,
        sourceType = "RECURRING_RULE",
        sourceId = id,
        occurrenceKey = "RECURRING_RULE|$id|${startOf(due)}|MONTHLY",
        dueDate = startOf(due),
        status = "PLANNED",
        expectedAmount = expected,
        expectedCurrency = currency,
        frequency = "MONTHLY",
        merchant = "Acme Properties"
    )

    private fun event(type: String, metadata: String?) = RecurringLifecycleEvent(
        occurrenceId = 1L,
        eventType = type,
        occurredAt = 1L,
        oldStatus = null,
        newStatus = null,
        metadata = metadata
    )

    private val monthly = RecurringPaymentLinkMatcher.suggestionWindow(
        RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.MONTHLY)
    )

    private fun pair(expense: Expense): Boolean =
        RecurringPaymentLinkMatcher.pairMatches(expense, occurrence(), key, key, monthly)

    // ── INV-5: windows ──────────────────────────────────────────────────────

    @Test
    fun `INV-5 resolvePeriodDays follows the rule frequency`() {
        assertEquals(7, RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.WEEKLY))
        assertEquals(14, RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.BIWEEKLY))
        assertEquals(28, RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.MONTHLY))
        assertEquals(84, RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.QUARTERLY))
        assertEquals(0, RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.IRREGULAR))
        assertEquals(0, RecurringPaymentLinkMatcher.resolvePeriodDays(null))
    }

    @Test
    fun `INV-5 S(P) is symmetric, capped at 7, below half the period and maximal for P 0 to 400`() {
        for (p in 0..400) {
            val s = RecurringPaymentLinkMatcher.suggestionWindow(p)
            assertEquals("P=$p early equals late", s.earlyDays, s.lateDays)
            assertTrue("P=$p s>=0", s.earlyDays >= 0)
            assertTrue("P=$p s<=7", s.earlyDays <= 7)
            if (p >= 1) {
                assertTrue("P=$p 2s<P (neighbouring windows never overlap)", 2 * s.earlyDays < p)
                // Maximal: either capped at 7, or one more day would break 2s<P.
                assertTrue("P=$p maximal", s.earlyDays == 7 || 2 * (s.earlyDays + 1) >= p)
            } else {
                assertEquals("P=$p non-positive period is same-day", 0, s.earlyDays)
            }
        }
    }

    @Test
    fun `INV-5 negative period gives the same-day window`() {
        assertEquals(AutoLinkWindow(0, 0), RecurringPaymentLinkMatcher.suggestionWindow(-5))
    }

    @Test
    fun `INV-5 A(P) is inside S(P) for P 0 to 400`() {
        for (p in 0..400) {
            val a = RecurringLifecycleCoordinator.autoLinkWindow(p)
            val s = RecurringPaymentLinkMatcher.suggestionWindow(p)
            assertTrue("P=$p early A within S", a.earlyDays <= s.earlyDays)
            assertTrue("P=$p late A within S", a.lateDays <= s.lateDays)
        }
    }

    @Test
    fun `INV-5 spec table weekly 1 2 and 3 3`() {
        assertEquals(AutoLinkWindow(1, 2), RecurringLifecycleCoordinator.autoLinkWindow(7))
        assertEquals(AutoLinkWindow(3, 3), RecurringPaymentLinkMatcher.suggestionWindow(7))
    }

    @Test
    fun `INV-5 spec table biweekly 1 3 and 6 6`() {
        assertEquals(AutoLinkWindow(1, 3), RecurringLifecycleCoordinator.autoLinkWindow(14))
        assertEquals(AutoLinkWindow(6, 6), RecurringPaymentLinkMatcher.suggestionWindow(14))
    }

    @Test
    fun `INV-5 spec table monthly 1 3 and 7 7`() {
        val p = RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.MONTHLY)
        assertEquals(AutoLinkWindow(1, 3), RecurringLifecycleCoordinator.autoLinkWindow(p))
        assertEquals(AutoLinkWindow(7, 7), RecurringPaymentLinkMatcher.suggestionWindow(p))
    }

    @Test
    fun `INV-5 spec table daily and irregular are 0 0 on both tiers`() {
        assertEquals(AutoLinkWindow(0, 0), RecurringLifecycleCoordinator.autoLinkWindow(1))
        assertEquals(AutoLinkWindow(0, 0), RecurringPaymentLinkMatcher.suggestionWindow(1))
        val irregular = RecurringPaymentLinkMatcher.resolvePeriodDays(RecurrenceFrequency.IRREGULAR)
        assertEquals(AutoLinkWindow(0, 0), RecurringLifecycleCoordinator.autoLinkWindow(irregular))
        assertEquals(AutoLinkWindow(0, 0), RecurringPaymentLinkMatcher.suggestionWindow(irregular))
    }

    @Test
    fun `INV-5 withinWindow honours the early and late sides separately`() {
        val w = AutoLinkWindow(1, 3)
        assertTrue(RecurringPaymentLinkMatcher.withinWindow(-1, w))
        assertTrue(RecurringPaymentLinkMatcher.withinWindow(3, w))
        assertTrue(RecurringPaymentLinkMatcher.withinWindow(0, w))
        assertFalse(RecurringPaymentLinkMatcher.withinWindow(-2, w))
        assertFalse(RecurringPaymentLinkMatcher.withinWindow(4, w))
    }

    @Test
    fun `INV-5 pair day boundary is inclusive at seven and exclusive at eight for a monthly window`() {
        for (delta in -7..7) {
            assertTrue("d=$delta inside", pair(expense(day = DUE.plusDays(delta.toLong()))))
        }
        assertFalse(pair(expense(day = DUE.plusDays(8))))
        assertFalse(pair(expense(day = DUE.minusDays(8))))
    }

    @Test
    fun `INV-5 pair with the same-day window rejects any other day`() {
        val sameDay = AutoLinkWindow.SAME_DAY
        val occ = occurrence()
        assertTrue(RecurringPaymentLinkMatcher.pairMatches(expense(day = DUE), occ, key, key, sameDay))
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(expense(day = DUE.plusDays(1)), occ, key, key, sameDay))
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(expense(day = DUE.minusDays(1)), occ, key, key, sameDay))
    }

    // ── INV-4: amount, currency, merchant ───────────────────────────────────

    @Test
    fun `AE-7 amounts inside the 10 percent band match, both bounds inclusive`() {
        assertTrue(RecurringPaymentLinkMatcher.amountMatches(400.0, 400.0))
        assertTrue(RecurringPaymentLinkMatcher.amountMatches(400.0, 360.0))
        assertTrue(RecurringPaymentLinkMatcher.amountMatches(400.0, 430.0))
        assertTrue(RecurringPaymentLinkMatcher.amountMatches(400.0, 440.0))
    }

    @Test
    fun `AE-7 amounts outside the band do not match`() {
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, 359.99))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, 441.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, 450.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, 4.0))
    }

    @Test
    fun `AE-7 a refund never matches a positive expected amount`() {
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, -400.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, 0.0))
    }

    @Test
    fun `INV-4 amountMatches rejects zero, NaN and infinite values`() {
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(0.0, 0.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(0.0, 5.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(Double.NaN, 400.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, Double.NaN))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(Double.POSITIVE_INFINITY, 400.0))
        assertFalse(RecurringPaymentLinkMatcher.amountMatches(400.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `INV-4 pair amount bounds through the full predicate`() {
        assertTrue(pair(expense(amount = 360.0)))
        assertTrue(pair(expense(amount = 440.0)))
        assertFalse(pair(expense(amount = 359.99)))
        assertFalse(pair(expense(amount = 441.0)))
        assertFalse(pair(expense(amount = 4.0)))
        assertFalse(pair(expense(amount = -400.0)))
    }

    @Test
    fun `AE-8 a different currency never matches even when the number is close`() {
        assertFalse(pair(expense(amount = 345.0, currency = "GBP")))
        assertFalse(pair(expense(amount = 400.0, currency = "GBP")))
        assertFalse(RecurringPaymentLinkMatcher.currencyMatches("EUR", "GBP"))
    }

    @Test
    fun `INV-4 currency comparison ignores case`() {
        assertTrue(RecurringPaymentLinkMatcher.currencyMatches("EUR", "eur"))
        assertTrue(pair(expense(currency = "eur")))
    }

    @Test
    fun `AE-9 a partial payment of half the bill does not match`() {
        assertFalse(pair(expense(amount = 200.0, day = DUE.minusDays(2))))
        assertFalse(pair(expense(amount = 200.0, day = DUE.minusDays(1))))
    }

    @Test
    fun `AE-25 a blank merchant key on either side never matches`() {
        val e = expense()
        val occ = occurrence()
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(e, occ, key, "", monthly))
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(e, occ, "", key, monthly))
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(e, occ, "", "", monthly))
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(e, occ, "  ", "  ", monthly))
    }

    @Test
    fun `INV-4 different merchant keys do not match and equal keys do`() {
        assertFalse(RecurringPaymentLinkMatcher.pairMatches(expense(), occurrence(), key, "other", monthly))
        assertTrue(RecurringPaymentLinkMatcher.pairMatches(expense(), occurrence(), key, key, monthly))
    }

    // ── INV-6: ranking ──────────────────────────────────────────────────────

    @Test
    fun `AE-6 equal day distance ranks the closer amount first`() {
        val a = occurrence(id = 2L, expected = 400.0)
        val b = occurrence(id = 1L, expected = 420.0)

        val ranked = RecurringPaymentLinkMatcher.rankByNearest(expense(amount = 400.0), listOf(b, a))

        assertEquals(listOf(2L, 1L), ranked.map { it.id })
        assertEquals(2L, RecurringPaymentLinkMatcher.nearestOwner(expense(amount = 400.0), listOf(b, a))!!.id)
    }

    @Test
    fun `INV-6 absolute day distance outranks amount closeness`() {
        val exactButFar = occurrence(id = 1L, due = DUE.minusDays(2), expected = 400.0)
        val nearButOff = occurrence(id = 2L, due = DUE.minusDays(1), expected = 430.0)

        val ranked = RecurringPaymentLinkMatcher.rankByNearest(expense(amount = 400.0), listOf(exactButFar, nearButOff))

        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun `INV-6 early and late distances are compared by absolute value`() {
        val late = occurrence(id = 1L, due = DUE.minusDays(3))   // d = +3
        val early = occurrence(id = 2L, due = DUE.plusDays(2))   // d = -2

        assertEquals(2L, RecurringPaymentLinkMatcher.nearestOwner(expense(), listOf(late, early))!!.id)
    }

    @Test
    fun `INV-6 an equal day distance and amount distance goes to the earlier due date`() {
        val later = occurrence(id = 1L, due = DUE.plusDays(1))   // d = -1
        val earlier = occurrence(id = 2L, due = DUE.minusDays(1)) // d = +1

        val ranked = RecurringPaymentLinkMatcher.rankByNearest(expense(), listOf(later, earlier))

        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun `INV-6 a full tie goes to the lower occurrence id`() {
        val high = occurrence(id = 9L)
        val low = occurrence(id = 3L)

        val ranked = RecurringPaymentLinkMatcher.rankByNearest(expense(), listOf(high, low))

        assertEquals(listOf(3L, 9L), ranked.map { it.id })
    }

    @Test
    fun `INV-6 nearestOwner returns null for no matches and does not filter by status`() {
        assertNull(RecurringPaymentLinkMatcher.nearestOwner(expense(), emptyList()))

        val paidNearest = occurrence(id = 1L).copy(status = "PAID", linkedExpenseId = 77L)
        val openFarther = occurrence(id = 2L, due = DUE.plusDays(2))

        assertEquals(1L, RecurringPaymentLinkMatcher.nearestOwner(expense(), listOf(openFarther, paidNearest))!!.id)
    }

    // ── INV-14: decline test ────────────────────────────────────────────────

    @Test
    fun `INV-14 a decline event for the same expense is detected`() {
        val events = listOf(event("LINK_SUGGESTION_DECLINED", """{"expenseId":42,"source":"user_declined_match"}"""))

        assertTrue(RecurringPaymentLinkMatcher.isDeclined(events, 42L))
    }

    @Test
    fun `INV-14 a decline for another expense does not match`() {
        val events = listOf(event("LINK_SUGGESTION_DECLINED", """{"expenseId":42}"""))

        assertFalse(RecurringPaymentLinkMatcher.isDeclined(events, 43L))
    }

    @Test
    fun `INV-14 another event type with the same metadata is not a decline`() {
        val events = listOf(event("OCCURRENCE_PAID", """{"expenseId":42}"""))

        assertFalse(RecurringPaymentLinkMatcher.isDeclined(events, 42L))
    }

    @Test
    fun `INV-14 malformed or missing metadata is not a decline`() {
        val bad = listOf("not json", "{", "", "[]", "{}", """{"other":42}""", null)
        for (metadata in bad) {
            val events = listOf(event("LINK_SUGGESTION_DECLINED", metadata))
            assertFalse("metadata=$metadata", RecurringPaymentLinkMatcher.isDeclined(events, 42L))
        }
    }

    @Test
    fun `INV-14 the decline is found among unrelated events and an empty list is not a decline`() {
        val events = listOf(
            event("OCCURRENCE_PAID", """{"expenseId":42}"""),
            event("LINK_SUGGESTION_DECLINED", "garbage"),
            event("LINK_SUGGESTION_DECLINED", """{"expenseId":7}"""),
            event("LINK_SUGGESTION_DECLINED", """{"expenseId":42}""")
        )

        assertTrue(RecurringPaymentLinkMatcher.isDeclined(events, 42L))
        assertTrue(RecurringPaymentLinkMatcher.isDeclined(events, 7L))
        assertFalse(RecurringPaymentLinkMatcher.isDeclined(events, 8L))
        assertFalse(RecurringPaymentLinkMatcher.isDeclined(emptyList(), 42L))
    }

    @Test
    fun `INV-14 a large expense id round-trips`() {
        val id = 9_000_000_000L
        val events = listOf(event("LINK_SUGGESTION_DECLINED", """{"expenseId":$id}"""))

        assertTrue(RecurringPaymentLinkMatcher.isDeclined(events, id))
        assertFalse(RecurringPaymentLinkMatcher.isDeclined(events, id + 1))
    }

    @Test
    fun `INV-14 the persisted event type literal is the spec value`() {
        assertEquals("LINK_SUGGESTION_DECLINED", RecurringPaymentLinkMatcher.LINK_SUGGESTION_DECLINED)
    }

    private companion object {
        val DUE: LocalDate = LocalDate.of(2026, 9, 10)
    }
}
