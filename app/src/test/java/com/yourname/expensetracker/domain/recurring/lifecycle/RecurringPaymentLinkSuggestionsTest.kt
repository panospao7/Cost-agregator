package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkTestFixture.Companion.MERCHANT
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkTestFixture.Companion.TODAY
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * FC-PAID-UNLINKED S2: [RecurringLifecycleCoordinator.findPaymentLinkSuggestions].
 *
 * Invariants: INV-8 (suggestion tier), INV-9 (read scope), INV-10 (read only), INV-11 (fail closed),
 * with INV-4/INV-6/INV-14 seen through the read. Expected outcomes are the spec's acceptance
 * examples (AE-1, AE-2, AE-3, AE-4, AE-5, AE-6, AE-7, AE-8, AE-15, AE-16, AE-17, AE-18, AE-21, AE-24,
 * AE-25, AE-26, AE-27); the rent fixture is rule "Rent", merchant "Acme Properties", 400 EUR, monthly,
 * due Nov 27, 2026, with today = Nov 26 and the payment on Nov 25 (day delta -2).
 *
 * Harness: [PaymentLinkTestFixture], a REAL in-memory Room database, REAL barriers, REAL coordinator.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringPaymentLinkSuggestionsTest {

    private lateinit var f: PaymentLinkTestFixture
    private val originalZone: TimeZone = TimeZone.getDefault()

    private val due: LocalDate = TODAY.plusDays(1) // Nov 27
    private val twoDaysEarly: LocalDate = TODAY.minusDays(1) // Nov 25

    @Before
    fun setup() {
        f = PaymentLinkTestFixture()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
        f.close()
    }

    private fun replaceFixture(replacement: PaymentLinkTestFixture) {
        f.close()
        f = replacement
    }

    /** Rent rule 1 with the Nov 27 occurrence; returns its occurrence id. */
    private suspend fun seedRent(): Long {
        f.seedRule(1L)
        return f.seedOccurrence(1L, due)
    }

    // -- INV-8: the suggestion appears inside S(P) and not outside ---------------------------

    @Test
    fun `INV-8 AE-1 a payment two days before the bill is suggested with its day delta`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)

            val items = f.suggestionItems()

            assertEquals(1, items.size)
            val s = items.single()
            assertEquals(occ, s.occurrenceId)
            assertEquals(1L, s.ruleId)
            assertEquals(e, s.expenseId)
            assertEquals(f.occurrence(occ).occurrenceKey, s.occurrenceKey)
            assertEquals(f.startOf(due), s.dueDate)
            assertEquals(f.at(twoDaysEarly), s.expenseDate)
            assertEquals(400.0, s.expectedAmount, 0.0)
            assertEquals("EUR", s.expectedCurrency)
            assertEquals(400.0, s.paidAmount, 0.0)
            assertEquals("EUR", s.paidCurrency)
            assertEquals(-2, s.dayDelta)
            assertEquals(emptyList<Long>(), s.alternatives)
        }

    @Test
    fun `INV-8 of two bills of one rule inside the window the nearer one owns the payment`() =
        runTest(timeout = 60.seconds) {
            val nearer = seedRent() // Nov 27, d = -1 for a Nov 26 payment
            val farther = f.seedOccurrence(1L, TODAY.minusDays(3)) // Nov 23, d = +3
            f.seedExpense(TODAY)

            val items = f.suggestionItems()

            assertEquals(listOf(nearer), items.map { it.occurrenceId })
            assertEquals(-1, items.single().dayDelta)
            assertEquals(listOf(farther), items.single().alternatives)
            assertEquals("PLANNED", f.status(farther))
        }

    @Test
    fun `INV-8 control - a payment exactly on the due day has day delta zero`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, TODAY) // due today
        f.seedExpense(TODAY)

        val s = f.suggestionItems().single()

        assertEquals(occ, s.occurrenceId)
        assertEquals(0, s.dayDelta)
    }

    @Test
    fun `INV-8 monthly window - seven days early is suggested and eight days early is not`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val edge = f.seedExpense(due.minusDays(7)) // Nov 20, d = -7

            assertEquals(listOf(occ to edge), f.suggestionItems().map { it.occurrenceId to it.expenseId })
        }

    @Test
    fun `INV-8 failing boundary - monthly eight days early gets no suggestion`() = runTest(timeout = 60.seconds) {
        seedRent()
        f.seedExpense(due.minusDays(8)) // Nov 19, d = -8

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `INV-8 monthly window - seven days late is suggested`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, TODAY.minusDays(7)) // Nov 19
        val e = f.seedExpense(TODAY) // d = +7

        val s = f.suggestionItems().single()

        assertEquals(occ, s.occurrenceId)
        assertEquals(e, s.expenseId)
        assertEquals(7, s.dayDelta)
    }

    @Test
    fun `INV-8 failing boundary - monthly eight days late gets no suggestion`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        f.seedOccurrence(1L, TODAY.minusDays(8)) // Nov 18
        f.seedExpense(TODAY) // d = +8

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `AE-15 weekly window is three days - Thursday pays Monday and Friday pays next Monday`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, amount = 100.0, frequency = RecurrenceFrequency.WEEKLY)
            val mon16 = f.seedOccurrence(1L, LocalDate.of(2026, 11, 16), expected = 100.0, frequency = "WEEKLY")
            val mon23 = f.seedOccurrence(1L, LocalDate.of(2026, 11, 23), expected = 100.0, frequency = "WEEKLY")
            val thursday = f.seedExpense(LocalDate.of(2026, 11, 19), amount = 100.0) // d = +3 / -4

            val first = f.suggestionItems().single()
            assertEquals(mon16, first.occurrenceId)
            assertEquals(thursday, first.expenseId)
            assertEquals(3, first.dayDelta)

            // Replace the Thursday payment by a Friday one: d = +4 to Nov 16 (out), d = -3 to Nov 23 (in).
            f.expenseDao.delete(f.expenseDao.getById(thursday)!!)
            val friday = f.seedExpense(LocalDate.of(2026, 11, 20), amount = 100.0)

            val second = f.suggestionItems().single()
            assertEquals(mon23, second.occurrenceId)
            assertEquals(friday, second.expenseId)
            assertEquals(-3, second.dayDelta)
        }

    @Test
    fun `AE-15 failing boundary - weekly four days away from the only bill gets no suggestion`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, amount = 100.0, frequency = RecurrenceFrequency.WEEKLY)
            f.seedOccurrence(1L, LocalDate.of(2026, 11, 16), expected = 100.0, frequency = "WEEKLY")
            f.seedExpense(LocalDate.of(2026, 11, 20), amount = 100.0) // d = +4

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-16 month end - a payment two days before Feb 28 is suggested and the Mar 31 bill is not an alternative`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(PaymentLinkTestFixture(today = LocalDate.of(2027, 3, 4)))
            f.seedRule(1L)
            val feb = f.seedOccurrence(1L, LocalDate.of(2027, 2, 28))
            f.seedOccurrence(1L, LocalDate.of(2027, 3, 31))
            val e = f.seedExpense(LocalDate.of(2027, 2, 26))

            val s = f.suggestionItems().single()

            assertEquals(feb, s.occurrenceId)
            assertEquals(e, s.expenseId)
            assertEquals(-2, s.dayDelta)
            assertEquals(emptyList<Long>(), s.alternatives)
        }

    @Test
    fun `AE-16 month end - a payment on Mar 3 is three days late for Feb 28 and never reaches Mar 31`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(PaymentLinkTestFixture(today = LocalDate.of(2027, 3, 4)))
            f.seedRule(1L)
            val feb = f.seedOccurrence(1L, LocalDate.of(2027, 2, 28))
            val mar = f.seedOccurrence(1L, LocalDate.of(2027, 3, 31))
            val e = f.seedExpense(LocalDate.of(2027, 3, 3))

            val items = f.suggestionItems()

            assertEquals(listOf(feb), items.map { it.occurrenceId })
            assertEquals(3, items.single().dayDelta)
            assertEquals("PLANNED", f.status(mar))
            assertEquals(e, items.single().expenseId)
        }

    @Test
    fun `AE-17 the day delta uses calendar days across the Athens DST change`() = runTest(timeout = 60.seconds) {
        // DST ended on Oct 25, 2026 in Europe/Athens: Oct 24 23:00 to Oct 27 00:00 is 50 hours, not 72.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
        replaceFixture(PaymentLinkTestFixture())
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, LocalDate.of(2026, 10, 27))
        val e = f.seedExpense(LocalDate.of(2026, 10, 24), hour = 23)

        val s = f.suggestionItems().single()

        assertEquals(occ, s.occurrenceId)
        assertEquals(e, s.expenseId)
        assertEquals(-3, s.dayDelta)
    }

    // -- INV-4 through the read: amount, currency, merchant ----------------------------------

    @Test
    fun `AE-7 amounts at the inclusive tolerance edge are suggested - 360`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, amount = 360.0)

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    @Test
    fun `AE-7 amounts at the inclusive tolerance edge are suggested - 440`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, amount = 440.0)

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    @Test
    fun `AE-7 an amount inside the tolerance is suggested - 430`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, amount = 430.0)

        val s = f.suggestionItems().single()

        assertEquals(e, s.expenseId)
        assertEquals(430.0, s.paidAmount, 0.0)
    }

    @Test
    fun `AE-7 failing boundary - amounts outside the tolerance or not a payment get no suggestion`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedExpense(twoDaysEarly, amount = 359.99)
            f.seedExpense(twoDaysEarly, amount = 441.0)
            f.seedExpense(twoDaysEarly, amount = 450.0)
            f.seedExpense(twoDaysEarly, amount = 4.0) // a coffee
            f.seedExpense(twoDaysEarly, amount = -400.0) // a refund
            f.seedExpense(twoDaysEarly, amount = 0.0)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-8 failing boundary - a payment in another currency is never suggested`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedExpense(twoDaysEarly, amount = 345.0, currency = "GBP")
            f.seedExpense(twoDaysEarly, amount = 400.0, currency = "GBP")

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-8 failing boundary - an occurrence expected in another currency does not match a EUR payment`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, currency = "USD")
            f.seedOccurrence(1L, due, expectedCurrency = "USD")
            f.seedExpense(twoDaysEarly, amount = 400.0, currency = "EUR")

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-8 control - currency codes are compared ignoring case`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, currency = "eur")

        val s = f.suggestionItems().single()

        assertEquals(e, s.expenseId)
        assertEquals("eur", s.paidCurrency)
    }

    @Test
    fun `AE-25 failing boundary - a blank or missing occurrence merchant never matches`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, merchant = "")
            f.seedOccurrence(1L, due, merchant = "")
            f.seedOccurrence(1L, due.plusDays(1), merchant = null)
            f.seedExpense(twoDaysEarly)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-4 failing boundary - a different merchant is not suggested`() = runTest(timeout = 60.seconds) {
        seedRent()
        f.seedExpense(twoDaysEarly, merchant = "Some Other Landlord")

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `INV-4 control - the merchant key ignores letter case`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, merchant = MERCHANT.uppercase())

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    // -- eligibility of the expense ----------------------------------------------------------

    @Test
    fun `INV-8 control - a withdrawal is link-eligible`() = runTest(timeout = 60.seconds) {
        seedRent()
        val e = f.seedExpense(twoDaysEarly, type = TransactionType.WITHDRAWAL)

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    @Test
    fun `INV-8 failing boundary - transfers deposits unknown types and not-mine payments are never suggested`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedExpense(twoDaysEarly, type = TransactionType.TRANSFER)
            f.seedExpense(twoDaysEarly, type = TransactionType.DEPOSIT)
            f.seedExpense(twoDaysEarly, type = TransactionType.UNKNOWN)
            f.seedExpense(twoDaysEarly, isNotMine = true)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-26 failing boundary - an expense dated after today is never suggested`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedExpense(TODAY.plusDays(2)) // Nov 28, d = +1

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-26 control - an expense late in the evening of today is still suggested`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            val e = f.seedExpense(TODAY, hour = 23)

            assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
        }

    @Test
    fun `INV-2 an expense that is already linked to an occurrence is not suggested for another bill`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedRule(2L)
            val e = f.seedExpense(twoDaysEarly)
            f.seedOccurrence(2L, LocalDate.of(2026, 11, 1), status = "PAID", linkedExpenseId = e)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    // -- rule state --------------------------------------------------------------------------

    @Test
    fun `AE-27 failing boundary - a paused rule gets no suggestion`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L, active = false)
        f.seedOccurrence(1L, due)
        f.seedExpense(twoDaysEarly)

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `AE-27 control - the same bill of an active rule is suggested`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L, active = true)
        val occ = f.seedOccurrence(1L, due)
        f.seedExpense(twoDaysEarly)

        assertEquals(listOf(occ), f.suggestionItems().map { it.occurrenceId })
    }

    @Test
    fun `INV-8 failing boundary - an occurrence whose rule row is gone gets no suggestion`() =
        runTest(timeout = 60.seconds) {
            f.seedOccurrence(9L, due)
            f.seedExpense(twoDaysEarly)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-8 failing boundary - a non-rule occurrence is never a suggestion target`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedOccurrence(1L, TODAY, sourceType = "SUBSCRIPTION")
            f.seedExpense(TODAY)

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    // -- INV-14: declined pairs ---------------------------------------------------------------

    @Test
    fun `AE-2 a declined pair is not suggested again`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId }) // control: shown before the decline

        f.seedDecline(occ, """{"expenseId":$e,"source":"user_declined_match"}""")

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `INV-14 a decline of another expense does not hide this pair`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        val other = f.seedExpense(TODAY.minusDays(30), amount = 12.0)
        f.seedDecline(occ, """{"expenseId":$other,"source":"user_declined_match"}""")

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    @Test
    fun `INV-14 a decline recorded on another occurrence does not hide this pair`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        f.seedRule(2L, merchant = "Unrelated Gym")
        val unrelated = f.seedOccurrence(2L, due.plusDays(20), merchant = "Unrelated Gym")
        f.seedDecline(unrelated, """{"expenseId":$e,"source":"user_declined_match"}""")

        assertEquals(listOf(occ), f.suggestionItems().map { it.occurrenceId })
    }

    @Test
    fun `INV-14 a malformed or missing decline payload is not a decline and the pair is still suggested`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.seedDecline(occ, "not json")
            f.seedDecline(occ, null)
            f.seedDecline(occ, "{}")
            f.seedDecline(occ, """{"expenseId":"$e"}""") // a string id is not a numeric id

            assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
        }

    @Test
    fun `INV-14 an event of another type with the same payload is not a decline`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        f.seedDecline(occ, """{"expenseId":$e}""", eventType = "REMINDER_SUPPRESSED_PAID")

        assertEquals(listOf(e), f.suggestionItems().map { it.expenseId })
    }

    @Test
    fun `INV-14 a declined nearest bill leaves the next nearest bill suggested`() = runTest(timeout = 60.seconds) {
        val nearest = seedRent() // Nov 27, |d| 2
        f.seedRule(2L)
        val next = f.seedOccurrence(2L, due.plusDays(2)) // Nov 29, |d| 4
        val e = f.seedExpense(twoDaysEarly)

        val before = f.suggestionItems().single()
        assertEquals(nearest, before.occurrenceId)
        assertEquals(listOf(next), before.alternatives)

        f.seedDecline(nearest, """{"expenseId":$e,"source":"user_declined_match"}""")

        val after = f.suggestionItems().single()
        assertEquals(next, after.occurrenceId)
        assertEquals(e, after.expenseId)
        assertEquals(emptyList<Long>(), after.alternatives)
    }

    // -- INV-6: the nearest owner gate --------------------------------------------------------

    @Test
    fun `INV-6 CANCELLED and IGNORED rows never own a payment - the open bill is still suggested`() =
        runTest(timeout = 60.seconds) {
            val open = seedRent() // Nov 27, |d| 2
            f.seedRule(2L)
            f.seedRule(3L)
            f.seedOccurrence(2L, twoDaysEarly, status = "CANCELLED") // |d| 0, would own the payment
            f.seedOccurrence(3L, twoDaysEarly, status = "IGNORED")
            val e = f.seedExpense(twoDaysEarly)

            val s = f.suggestionItems().single()

            assertEquals(open, s.occurrenceId)
            assertEquals(e, s.expenseId)
        }

    @Test
    fun `INV-6 failing boundary - a lone CANCELLED row gives no suggestion`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        f.seedOccurrence(1L, due, status = "CANCELLED")
        f.seedExpense(twoDaysEarly)

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    private suspend fun assertNearestHandledBillBlocks(status: String) {
        val open = seedRent() // Nov 27, |d| 2
        f.seedRule(2L)
        val handled = f.seedOccurrence(2L, TODAY, status = status) // Nov 26, |d| 1: nearest owner
        f.seedExpense(twoDaysEarly)

        assertEquals("a $status nearest owner must block the farther open bill", emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        assertEquals("PLANNED", f.status(open))
        assertEquals(status, f.status(handled))
    }

    @Test
    fun `AE-4 a PAID nearest owner blocks the suggestion for a farther open bill`() = runTest(timeout = 60.seconds) {
        assertNearestHandledBillBlocks("PAID")
    }

    @Test
    fun `AE-4 a SKIPPED nearest owner blocks the suggestion for a farther open bill`() = runTest(timeout = 60.seconds) {
        assertNearestHandledBillBlocks("SKIPPED")
    }

    @Test
    fun `AE-4 a MISSED nearest owner blocks the suggestion for a farther open bill`() = runTest(timeout = 60.seconds) {
        assertNearestHandledBillBlocks("MISSED")
    }

    @Test
    fun `AE-5 a farther SKIPPED bill does not block the nearest open bill`() = runTest(timeout = 60.seconds) {
        val open = seedRent() // Nov 27, |d| 2
        f.seedRule(2L)
        f.seedOccurrence(2L, TODAY.plusDays(4), status = "SKIPPED") // Nov 30, |d| 5
        val e = f.seedExpense(twoDaysEarly)

        val s = f.suggestionItems().single()

        assertEquals(open, s.occurrenceId)
        assertEquals(e, s.expenseId)
        assertEquals(emptyList<Long>(), s.alternatives) // a SKIPPED bill is not a pair-eligible alternative
    }

    @Test
    fun `AE-5 second variant - a skipped bill one day away blocks a tied open bill that is due later`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            f.seedOccurrence(1L, TODAY.minusDays(2), status = "SKIPPED") // Nov 24, |d| 1, earlier due
            f.seedOccurrence(2L, TODAY) // Nov 26, |d| 1
            f.seedExpense(twoDaysEarly) // Nov 25

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `AE-5 control - the same tie with the open bill due earlier suggests it`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        f.seedRule(2L)
        val open = f.seedOccurrence(1L, TODAY.minusDays(2)) // Nov 24, |d| 1, earlier due
        f.seedOccurrence(2L, TODAY, status = "SKIPPED") // Nov 26, |d| 1
        f.seedExpense(twoDaysEarly)

        assertEquals(listOf(open), f.suggestionItems().map { it.occurrenceId })
    }

    @Test
    fun `AE-3 two same-merchant bills - only the nearest bill is suggested and the other is an alternative`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val a = f.seedOccurrence(1L, TODAY.minusDays(2)) // Nov 24, |d| 2
            val b = f.seedOccurrence(2L, TODAY.plusDays(4)) // Nov 30, |d| 4
            val e = f.seedExpense(TODAY)

            val items = f.suggestionItems()

            assertEquals(1, items.size)
            assertEquals(a, items.single().occurrenceId)
            assertEquals(e, items.single().expenseId)
            assertEquals(listOf(b), items.single().alternatives)
            assertEquals("PLANNED", f.status(b))
        }

    @Test
    fun `AE-6 a tie on distance goes to the closer amount`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L, amount = 400.0)
        f.seedRule(2L, amount = 420.0)
        // The farther-amount bill is inserted first so the row order cannot decide the rank.
        val b = f.seedOccurrence(2L, due, expected = 420.0)
        val a = f.seedOccurrence(1L, due, expected = 400.0)
        val e = f.seedExpense(twoDaysEarly, amount = 400.0)

        val items = f.suggestionItems()

        assertEquals(1, items.size)
        assertEquals(a, items.single().occurrenceId)
        assertEquals(e, items.single().expenseId)
        assertEquals(listOf(b), items.single().alternatives)
    }

    @Test
    fun `INV-8 each occurrence shows at most one expense - the nearest day wins`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        f.seedExpense(due.minusDays(6)) // Nov 21, |d| 6, inserted first
        val near = f.seedExpense(twoDaysEarly) // |d| 2

        val items = f.suggestionItems()

        assertEquals(listOf(occ to near), items.map { it.occurrenceId to it.expenseId })
    }

    @Test
    fun `INV-8 each occurrence shows at most one expense - same day goes to the closer amount`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            f.seedExpense(twoDaysEarly, amount = 410.0) // inserted first, farther amount
            val closer = f.seedExpense(twoDaysEarly, amount = 400.0)

            val items = f.suggestionItems()

            assertEquals(listOf(occ to closer), items.map { it.occurrenceId to it.expenseId })
        }

    @Test
    fun `INV-8 the result is ordered by due date and then occurrence id`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L) // Acme Properties, due Nov 29
        f.seedRule(2L, merchant = "City Gym", amount = 30.0)
        f.seedRule(3L, merchant = "Water Utility", amount = 50.0)
        val acme = f.seedOccurrence(1L, TODAY.plusDays(3))
        val gym = f.seedOccurrence(2L, TODAY.minusDays(2), expected = 30.0, merchant = "City Gym") // Nov 24
        val water = f.seedOccurrence(3L, TODAY.minusDays(2), expected = 50.0, merchant = "Water Utility") // Nov 24
        f.seedExpense(TODAY, amount = 400.0)
        f.seedExpense(TODAY, amount = 30.0, merchant = "City Gym")
        f.seedExpense(TODAY, amount = 50.0, merchant = "Water Utility")

        val items = f.suggestionItems()

        assertEquals(listOf(gym, water, acme), items.map { it.occurrenceId })
    }

    // -- INV-9: read scope --------------------------------------------------------------------

    @Test
    fun `INV-9 a bill due 31 days ago is still a target`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, TODAY.minusDays(31))
        f.seedExpense(TODAY.minusDays(31))

        assertEquals(listOf(occ), f.suggestionItems().map { it.occurrenceId })
    }

    @Test
    fun `INV-9 failing boundary - a bill due 32 days ago is no longer a target`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        f.seedOccurrence(1L, TODAY.minusDays(32))
        f.seedExpense(TODAY.minusDays(32))

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `INV-9 a bill due seven days from now is a target`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, TODAY.plusDays(7))
        f.seedExpense(TODAY)

        val s = f.suggestionItems().single()

        assertEquals(occ, s.occurrenceId)
        assertEquals(-7, s.dayDelta)
    }

    @Test
    fun `INV-9 failing boundary - a bill due eight days from now is not a target`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        f.seedOccurrence(1L, TODAY.plusDays(8)) // the read ends before today + 8
        f.seedExpense(TODAY) // d = -8, also outside the monthly window

        assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
    }

    @Test
    fun `INV-9 a tie at the plus seven edge goes to the earlier due bill and a later SKIPPED bill there does not block`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val open = f.seedOccurrence(1L, TODAY.minusDays(7)) // |d| 7, earlier due
            f.seedOccurrence(2L, TODAY.plusDays(7), status = "SKIPPED") // |d| 7, later due, last readable day
            f.seedExpense(TODAY)

            assertEquals(listOf(open), f.suggestionItems().map { it.occurrenceId })
        }

    private suspend fun seedLookbackEdgeScenario(blockerDaysAgo: Int, blockerStatus: String): Pair<Long, Long> {
        f.seedRule(1L)
        f.seedRule(2L)
        // Target: the oldest due day still inside the target range (today - 31).
        val target = f.seedOccurrence(1L, TODAY.minusDays(31))
        // The payment sits on the oldest day the expense read covers (today - 38): d = -7 to the target.
        val e = f.seedExpense(TODAY.minusDays(38))
        // A handled bill with the same |d| = 7 to the payment but an earlier due day wins the nearest-owner tie.
        val blockerExpense = if (blockerStatus == "PAID") f.seedExpense(TODAY.minusDays(blockerDaysAgo.toLong())) else null
        f.seedOccurrence(
            2L,
            TODAY.minusDays(blockerDaysAgo.toLong()),
            status = blockerStatus,
            linkedExpenseId = blockerExpense
        )
        return target to e
    }

    @Test
    fun `INV-9 control - a blocker 46 days back is outside the read so the open bill is suggested`() =
        runTest(timeout = 60.seconds) {
            val (target, e) = seedLookbackEdgeScenario(blockerDaysAgo = 46, blockerStatus = "SKIPPED")

            val s = f.suggestionItems().single()

            assertEquals(target, s.occurrenceId)
            assertEquals(e, s.expenseId)
            assertEquals(-7, s.dayDelta)
        }

    @Test
    fun `INV-9 a SKIPPED blocker exactly 45 days back is read and blocks the open bill`() =
        runTest(timeout = 60.seconds) {
            seedLookbackEdgeScenario(blockerDaysAgo = 45, blockerStatus = "SKIPPED")

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-9 a MISSED blocker exactly 45 days back is read and blocks the open bill`() =
        runTest(timeout = 60.seconds) {
            seedLookbackEdgeScenario(blockerDaysAgo = 45, blockerStatus = "MISSED")

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-9 a PAID blocker exactly 45 days back is read and blocks the open bill`() =
        runTest(timeout = 60.seconds) {
            seedLookbackEdgeScenario(blockerDaysAgo = 45, blockerStatus = "PAID")

            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-9 a PAID blocker 46 days back is outside the read and does not block`() =
        runTest(timeout = 60.seconds) {
            val (target, _) = seedLookbackEdgeScenario(blockerDaysAgo = 46, blockerStatus = "PAID")

            assertEquals(listOf(target), f.suggestionItems().map { it.occurrenceId })
        }

    // -- INV-5 / AE-10: the installment gate of the suggestion tier ----------------------------

    private enum class InstallmentRelation { ENTRY_PAYMENT, PLAN_PURCHASE_ROW, PURCHASE_FLAG, REVERSED_FLAG }

    /** Seeds the nearer (|d| 2) installment-related payment and returns its id. */
    private suspend fun seedInstallmentRelatedNearPayment(relation: InstallmentRelation): Long = when (relation) {
        InstallmentRelation.ENTRY_PAYMENT ->
            f.seedExpense(twoDaysEarly).also { f.linkToInstallmentEntry(it) }
        InstallmentRelation.PLAN_PURCHASE_ROW ->
            f.seedExpense(twoDaysEarly).also { f.markAsPlanPurchaseRecord(it) }
        InstallmentRelation.PURCHASE_FLAG ->
            f.seedFlaggedExpense(twoDaysEarly, "INSTALLMENT_PURCHASE")
        InstallmentRelation.REVERSED_FLAG ->
            f.seedFlaggedExpense(twoDaysEarly, "INSTALLMENT_REVERSED")
    }

    private suspend fun assertInstallmentRelatedPaymentIsNotSuggested(relation: InstallmentRelation) {
        val occ = seedRent()
        val installmentRelated = seedInstallmentRelatedNearPayment(relation)
        val ordinary = f.seedExpense(due.minusDays(6)) // Nov 21, |d| 6: farther, but the only eligible one

        val items = f.suggestionItems()

        assertEquals("$relation", listOf(occ to ordinary), items.map { it.occurrenceId to it.expenseId })
        assertTrue(items.none { it.expenseId == installmentRelated })
    }

    @Test
    fun `AE-10 INV-5 an expense that pays an installment entry is never suggested`() =
        runTest(timeout = 60.seconds) {
            assertInstallmentRelatedPaymentIsNotSuggested(InstallmentRelation.ENTRY_PAYMENT)
        }

    @Test
    fun `AE-10 INV-5 a plan purchase record row is never suggested`() = runTest(timeout = 60.seconds) {
        assertInstallmentRelatedPaymentIsNotSuggested(InstallmentRelation.PLAN_PURCHASE_ROW)
    }

    @Test
    fun `AE-10 INV-5 an expense flagged INSTALLMENT_PURCHASE is never suggested`() = runTest(timeout = 60.seconds) {
        assertInstallmentRelatedPaymentIsNotSuggested(InstallmentRelation.PURCHASE_FLAG)
    }

    @Test
    fun `AE-10 INV-5 an expense flagged INSTALLMENT_REVERSED is never suggested`() = runTest(timeout = 60.seconds) {
        assertInstallmentRelatedPaymentIsNotSuggested(InstallmentRelation.REVERSED_FLAG)
    }

    @Test
    fun `AE-10 INV-5 a lone installment-linked payment gives Available empty and the same payment unlinked is suggested`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            assertEquals(listOf(occ to e), f.suggestionItems().map { it.occurrenceId to it.expenseId }) // control

            f.linkToInstallmentEntry(e)

            assertEquals(PaymentLinkSuggestions.Available(emptyList()), f.suggestions())
        }

    // -- empty states and INV-10 --------------------------------------------------------------

    @Test
    fun `AE-24 no rules and no expenses gives Available empty`() = runTest(timeout = 60.seconds) {
        assertEquals(PaymentLinkSuggestions.Available(emptyList()), f.suggestions())
    }

    @Test
    fun `AE-24 a bill without any expense gives Available empty`() = runTest(timeout = 60.seconds) {
        seedRent()

        assertEquals(PaymentLinkSuggestions.Available(emptyList()), f.suggestions())
    }

    @Test
    fun `AE-24 an expense without any bill gives Available empty`() = runTest(timeout = 60.seconds) {
        f.seedExpense(twoDaysEarly)

        assertEquals(PaymentLinkSuggestions.Available(emptyList()), f.suggestions())
    }

    @Test
    fun `INV-10 AE-21 reading suggestions leaves every persisted row untouched and tops nothing up`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val ruleBefore = f.ruleDao.getById(1L)
            val occurrencesBefore = f.allOccurrences()
            val expenseBefore = f.expenseDao.getById(e)
            val eventsBefore = f.allEventCount()

            assertEquals(1, f.suggestionItems().size)
            assertEquals(1, f.suggestionItems().size) // a second read is the same

            assertEquals(ruleBefore, f.ruleDao.getById(1L))
            assertEquals(occurrencesBefore, f.allOccurrences())
            assertEquals(expenseBefore, f.expenseDao.getById(e))
            assertEquals(eventsBefore, f.allEventCount())
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.occurrence(occ).linkedExpenseId)
            coVerify(exactly = 0) { f.ruleCoordinator.topUpHorizon(any(), any()) }
            coVerify(exactly = 0) { f.ruleCoordinator.advanceNextDate(any(), any()) }
        }

    // -- INV-11: fail closed ------------------------------------------------------------------

    @Test
    fun `AE-18 INV-11 every non-normal maintenance mode gives Unavailable READ_BLOCKED never an empty list`() =
        runTest(timeout = 60.seconds) {
            seedRent()
            f.seedExpense(twoDaysEarly)
            assertEquals(1, f.suggestionItems().size) // control: readable in NORMAL mode

            val blockedModes = RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }
            assertTrue("the enum must have non-normal modes", blockedModes.isNotEmpty())
            for (mode in blockedModes) {
                f.maintenanceMode = mode
                assertEquals(
                    "mode $mode",
                    PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_BLOCKED),
                    f.suggestions()
                )
            }

            f.maintenanceMode = RestoreMaintenanceMode.Mode.NORMAL
            assertEquals(1, f.suggestionItems().size) // not sticky
        }

    @Test
    fun `INV-11 a blocked barrier reads nothing`() = runTest(timeout = 60.seconds) {
        val occurrences = mockk<RecurringOccurrenceDao>() // strict: any stray call would throw
        replaceFixture(PaymentLinkTestFixture(occurrenceDaoOverride = occurrences))
        f.maintenanceMode = RestoreMaintenanceMode.Mode.RESTORE_STAGING

        assertEquals(
            PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_BLOCKED),
            f.suggestions()
        )
        coVerify(exactly = 0) { occurrences.getByDateRange(any(), any()) }
    }

    @Test
    fun `INV-11 a failing occurrence read gives Unavailable READ_FAILED with no raw text`() =
        runTest(timeout = 60.seconds) {
            val occurrences = mockk<RecurringOccurrenceDao>()
            coEvery { occurrences.getByDateRange(any(), any()) } throws IllegalStateException(SECRET)
            replaceFixture(PaymentLinkTestFixture(occurrenceDaoOverride = occurrences))

            val result = f.suggestions()

            assertEquals(PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED), result)
            assertFalse(result.toString().contains(SECRET))
        }

    @Test
    fun `INV-11 an IOException from the occurrence read gives READ_FAILED`() = runTest(timeout = 60.seconds) {
        val occurrences = mockk<RecurringOccurrenceDao>()
        coEvery { occurrences.getByDateRange(any(), any()) } throws IOException(SECRET)
        replaceFixture(PaymentLinkTestFixture(occurrenceDaoOverride = occurrences))

        assertEquals(
            PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED),
            f.suggestions()
        )
    }

    @Test
    fun `INV-11 a failing expense read gives Unavailable READ_FAILED`() = runTest(timeout = 60.seconds) {
        val expenses = mockk<ExpenseDao>()
        coEvery { expenses.getExpensesBetween(any(), any()) } throws IllegalStateException(SECRET)
        replaceFixture(PaymentLinkTestFixture(expenseDaoOverride = expenses))
        seedRent()
        f.seedExpense(twoDaysEarly)

        assertEquals(
            PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED),
            f.suggestions()
        )
    }

    @Test
    fun `INV-11 a failing lifecycle event read gives Unavailable READ_FAILED`() = runTest(timeout = 60.seconds) {
        val events = mockk<RecurringLifecycleEventDao>()
        coEvery { events.getEventsForOccurrence(any()) } throws IllegalStateException(SECRET)
        replaceFixture(PaymentLinkTestFixture(lifecycleEventDaoOverride = events))
        seedRent()
        f.seedExpense(twoDaysEarly)

        assertEquals(
            PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED),
            f.suggestions()
        )
    }

    @Test
    fun `INV-11 CancellationException from the occurrence read is rethrown and not mapped to Unavailable`() =
        runTest(timeout = 60.seconds) {
            val occurrences = mockk<RecurringOccurrenceDao>()
            coEvery { occurrences.getByDateRange(any(), any()) } throws CancellationException("cancelled")
            replaceFixture(PaymentLinkTestFixture(occurrenceDaoOverride = occurrences))

            try {
                f.suggestions()
                fail("a CancellationException must propagate, but a result was returned")
            } catch (e: CancellationException) {
                assertEquals("cancelled", e.message)
            }
        }

    @Test
    fun `INV-11 CancellationException from the expense read is rethrown and not mapped to Unavailable`() =
        runTest(timeout = 60.seconds) {
            val expenses = mockk<ExpenseDao>()
            coEvery { expenses.getExpensesBetween(any(), any()) } throws CancellationException("cancelled")
            replaceFixture(PaymentLinkTestFixture(expenseDaoOverride = expenses))
            seedRent()
            f.seedExpense(twoDaysEarly)

            try {
                f.suggestions()
                fail("a CancellationException must propagate, but a result was returned")
            } catch (e: CancellationException) {
                assertEquals("cancelled", e.message)
            }
        }

    @Test
    fun `INV-11 CancellationException from the event read is rethrown and not mapped to Unavailable`() =
        runTest(timeout = 60.seconds) {
            val events = mockk<RecurringLifecycleEventDao>()
            coEvery { events.getEventsForOccurrence(any()) } throws CancellationException("cancelled")
            replaceFixture(PaymentLinkTestFixture(lifecycleEventDaoOverride = events))
            seedRent()
            f.seedExpense(twoDaysEarly)

            try {
                f.suggestions()
                fail("a CancellationException must propagate, but a result was returned")
            } catch (e: CancellationException) {
                assertEquals("cancelled", e.message)
            }
        }

    private companion object {
        const val SECRET = "secret-sql-text /data/user/0/private.db"
    }
}
