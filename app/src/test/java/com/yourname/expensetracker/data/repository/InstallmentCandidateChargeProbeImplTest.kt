package com.yourname.expensetracker.data.repository

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * [InstallmentCandidateChargeProbeImpl] over a real in-memory Room database (S7a-1 part A): the
 * mode windows, the one-minor-unit amount match, the currency match, the expense eligibility
 * (plan purchase record, linked to an entry or an occurrence, non-spending type), the entry-side
 * preconditions and the fail-closed answer for unreadable stored values. Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentCandidateChargeProbeImplTest {

    private lateinit var fx: InstallmentCoordinatorFixture
    private lateinit var probe: InstallmentCandidateChargeProbeImpl

    /** Entry due date used by every test: 2027-04-10. */
    private val due = day(2027, 4, 10)

    @Before
    fun setUp() = runBlocking {
        fx = InstallmentCoordinatorFixture.create(ApplicationProvider.getApplicationContext())
        probe = InstallmentCandidateChargeProbeImpl(fx.facts, fx.planDao)
    }

    @After
    fun tearDown() {
        if (::fx.isInitialized) fx.close()
    }

    private class Subject(val plan: InstallmentPlan, val entry: InstallmentEntry)

    private suspend fun subject(
        mode: String,
        uuid: String = "plan-under-test",
        status: String = "SCHEDULED",
        linkedExpenseId: Long? = null,
        currency: String = "EUR",
        amount: Double = 50.0
    ): Subject {
        val planId = fx.seedPlan(
            uuid = uuid,
            entries = listOf(
                InstallmentCoordinatorFixture.SeedEntry(
                    seq = 1,
                    status = status,
                    linkedExpenseId = linkedExpenseId,
                    dueEpochDay = due,
                    amount = amount
                )
            ),
            currency = currency,
            mode = mode
        )
        return Subject(fx.planDao.getPlanById(planId)!!, fx.entries(planId).single())
    }

    private suspend fun hasCandidate(s: Subject): Boolean = probe.hasCandidate(s.plan, s.entry, ATHENS)

    private suspend fun chargeOn(month: Int, dayOfMonth: Int, amount: Double = 50.0, currency: String = "EUR"): Long =
        fx.seedCharge(athensMillis(2027, month, dayOfMonth), amount = amount, currency = currency)

    // ------------------------------------------------------------------ windows

    @Test
    fun a_confirm_charge_inside_the_window_is_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 12)
        assertTrue(hasCandidate(s))
    }

    @Test
    fun a_confirm_window_runs_from_five_days_before_to_ten_days_after_the_due_date() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 4)
        assertFalse("due - 6 days is outside", hasCandidate(s))
        chargeOn(4, 5)
        assertTrue("due - 5 days is inside", hasCandidate(s))
    }

    @Test
    fun a_confirm_charge_after_the_window_is_not_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 21)
        assertFalse("due + 11 days is outside", hasCandidate(s))
    }

    @Test
    fun a_confirm_charge_on_the_last_window_day_is_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 20)
        assertTrue("due + 10 days is inside", hasCandidate(s))
    }

    @Test
    fun an_auto_record_window_runs_from_three_days_before_to_the_due_date() = runBlocking {
        val s = subject("AUTO_RECORD")
        chargeOn(4, 6)
        assertFalse("due - 4 days is outside", hasCandidate(s))
        chargeOn(4, 11)
        assertFalse("due + 1 day is outside", hasCandidate(s))
        chargeOn(4, 7)
        assertTrue("due - 3 days is inside", hasCandidate(s))
    }

    @Test
    fun an_auto_record_charge_on_the_due_date_is_a_candidate() = runBlocking {
        val s = subject("AUTO_RECORD")
        chargeOn(4, 10)
        assertTrue(hasCandidate(s))
    }

    @Test
    fun no_charge_at_all_is_not_a_candidate() = runBlocking {
        assertFalse(hasCandidate(subject("AUTO_RECORD")))
    }

    // ------------------------------------------------------------------ amount and currency

    @Test
    fun an_amount_within_one_minor_unit_is_a_candidate_and_two_minor_units_are_not() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 10, amount = 50.02)
        assertFalse("two minor units away", hasCandidate(s))
        chargeOn(4, 10, amount = 50.01)
        assertTrue("one minor unit away", hasCandidate(s))
    }

    @Test
    fun a_charge_in_another_currency_is_not_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 10, currency = "USD")
        assertFalse(hasCandidate(s))
    }

    // ------------------------------------------------------------------ eligibility of the charge

    @Test
    fun a_charge_already_linked_to_another_entry_is_not_a_candidate() = runBlocking {
        val charge = chargeOn(4, 10)
        fx.seedPlan(
            uuid = "other-plan",
            entries = listOf(
                InstallmentCoordinatorFixture.SeedEntry(
                    seq = 1,
                    status = "PAID",
                    linkedExpenseId = charge,
                    dueEpochDay = day(2028, 1, 1)
                )
            )
        )
        assertFalse(hasCandidate(subject("CONFIRM")))
    }

    @Test
    fun a_plan_purchase_record_is_not_a_candidate() = runBlocking {
        val purchase = chargeOn(4, 10)
        fx.seedPlan(
            uuid = "purchase-plan",
            entries = listOf(InstallmentCoordinatorFixture.SeedEntry(seq = 1, dueEpochDay = day(2028, 1, 1))),
            kind = "PURCHASE_ATTACHED",
            purchaseExpenseId = purchase
        )
        assertFalse(hasCandidate(subject("CONFIRM")))
    }

    @Test
    fun a_charge_linked_to_a_recurring_occurrence_is_not_a_candidate() = runBlocking {
        val charge = chargeOn(4, 10)
        fx.linkToOccurrence(charge)
        assertFalse(hasCandidate(subject("CONFIRM")))
    }

    @Test
    fun a_non_spending_expense_is_not_a_candidate() = runBlocking {
        fx.seedExpense(
            amount = 50.0,
            type = TransactionType.WITHDRAWAL,
            dateMillis = athensMillis(2027, 4, 10),
            merchant = "Synthetic Cash Point"
        )
        assertFalse(hasCandidate(subject("CONFIRM")))
    }

    @Test
    fun an_ineligible_charge_does_not_hide_an_eligible_one() = runBlocking {
        val linked = chargeOn(4, 10)
        fx.linkToOccurrence(linked)
        chargeOn(4, 9)
        assertTrue(hasCandidate(subject("CONFIRM")))
    }

    // ------------------------------------------------------------------ entry-side preconditions

    @Test
    fun an_entry_that_is_not_scheduled_has_no_candidate() = runBlocking {
        chargeOn(4, 10)
        assertFalse(hasCandidate(subject("CONFIRM", status = "CANCELLED")))
    }

    @Test
    fun an_entry_that_is_already_linked_has_no_candidate() = runBlocking {
        val paidBy = chargeOn(4, 12)
        val s = subject("CONFIRM")
        chargeOn(4, 10)
        assertTrue("an eligible charge exists for the unlinked entry", hasCandidate(s))
        // In-memory copy only: a linked entry never has a candidate, whatever sits in its window.
        assertFalse(probe.hasCandidate(s.plan, s.entry.copy(linkedExpenseId = paidBy), ATHENS))
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    fun an_unreadable_plan_mode_reports_a_candidate_so_the_caller_does_not_record() = runBlocking {
        val s = subject("CONFIRM")
        assertTrue(probe.hasCandidate(s.plan.copy(fulfilmentMode = "NOT_A_MODE"), s.entry, ATHENS))
    }

    @Test
    fun an_unreadable_plan_currency_reports_a_candidate_so_the_caller_does_not_record() = runBlocking {
        val s = subject("CONFIRM")
        assertTrue(probe.hasCandidate(s.plan.copy(currency = "XXX-NOT-SUPPORTED"), s.entry, ATHENS))
    }

    @Test
    fun an_unreadable_entry_amount_reports_a_candidate_so_the_caller_does_not_record() = runBlocking {
        val s = subject("CONFIRM")
        assertTrue(probe.hasCandidate(s.plan, s.entry.copy(scheduledAmount = Double.NaN), ATHENS))
    }

    // ------------------------------------------------------------------ parity with the read repository suggestions

    /**
     * One owner for the candidate rule: the read repository suggests a link for the entry exactly when
     * the probe reports a candidate. Today is the due date (12:00 Athens), so every window has started.
     */
    private suspend fun assertSuggestionAndProbeAgree(s: Subject, expectedCandidate: Boolean) {
        fx.time.setTime(athensMillis(2027, 4, 10, 12, 0))
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
        try {
            val repo = InstallmentPlanReadRepositoryImpl(
                fx.planDao,
                fx.facts,
                fx.time,
                InstallmentCandidateChargeProbeImpl(fx.facts, fx.planDao)
            )
            val suggested = repo.loadSuggestions().any { it.entryId == s.entry.id }
            val probed = hasCandidate(s)
            assertEquals("suggestion and probe must agree", suggested, probed)
            assertEquals(expectedCandidate, probed)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun parity_a_confirm_charge_inside_the_window_is_suggested_and_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 9)
        assertSuggestionAndProbeAgree(s, expectedCandidate = true)
    }

    @Test
    fun parity_an_auto_record_charge_inside_the_window_is_suggested_and_a_candidate() = runBlocking {
        val s = subject("AUTO_RECORD")
        chargeOn(4, 9)
        assertSuggestionAndProbeAgree(s, expectedCandidate = true)
    }

    @Test
    fun parity_a_charge_outside_the_window_is_neither_suggested_nor_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 4)
        assertSuggestionAndProbeAgree(s, expectedCandidate = false)
    }

    @Test
    fun parity_an_amount_two_minor_units_away_is_neither_suggested_nor_a_candidate() = runBlocking {
        val s = subject("AUTO_RECORD")
        chargeOn(4, 9, amount = 50.02)
        assertSuggestionAndProbeAgree(s, expectedCandidate = false)
    }

    @Test
    fun parity_a_charge_linked_to_an_occurrence_is_neither_suggested_nor_a_candidate() = runBlocking {
        val s = subject("AUTO_RECORD")
        fx.linkToOccurrence(chargeOn(4, 9))
        assertSuggestionAndProbeAgree(s, expectedCandidate = false)
    }

    @Test
    fun parity_a_charge_in_another_currency_is_neither_suggested_nor_a_candidate() = runBlocking {
        val s = subject("CONFIRM")
        chargeOn(4, 9, currency = "USD")
        assertSuggestionAndProbeAgree(s, expectedCandidate = false)
    }
}
