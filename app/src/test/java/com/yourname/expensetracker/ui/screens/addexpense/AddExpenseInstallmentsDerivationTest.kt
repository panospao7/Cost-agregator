package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Loan-installments S11c: the pure [AddExpenseInstallmentsDerivation] (default first payment, before-purchase
 * flag, preview states). Oracle: the plan amendment "S11 candidate review dispositions" (NEW SLICE S11c items 1-2)
 * and ux-flows 3.1 (preview card, past-due notice). Expected values are hand-written literals, never recomputed
 * by the production rule. Pure JVM: no Robolectric, no static state. Synthetic data only.
 */
class AddExpenseInstallmentsDerivationTest {

    private val athens = ZoneId.of("Europe/Athens")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun day(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

    /** A form dated [purchase], EUR, amount [amount], installments [on] with [plan] terms. */
    private fun form(
        purchase: Long = ms(athens, 2025, 10, 16),
        amount: String = "600",
        currency: String? = "EUR",
        plan: AddExpenseInstallmentsState = AddExpenseInstallmentsState(on = true)
    ) = AddExpenseState(date = purchase, amount = amount, selectedCurrency = currency, installments = plan)

    private val today: Long = day(2025, 10, 16)

    private fun derive(state: AddExpenseState, zone: ZoneId = athens, todayEpochDay: Long = today) =
        AddExpenseInstallmentsDerivation.derive(state, zone, todayEpochDay)

    private fun ready(state: AddExpenseState, zone: ZoneId = athens, todayEpochDay: Long = today) =
        derive(state, zone, todayEpochDay).preview as AddExpenseInstallmentPreview.Ready

    // ---- the one "+1 month" rule: month-end clamp ---------------------------------------------------------------

    @Test
    fun `default first due is the purchase civil date plus one month`() {
        assertEquals(day(2025, 11, 16), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2025, 10, 16)))
        assertEquals(day(2026, 1, 15), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2025, 12, 15)))
    }

    @Test
    fun `default first due clamps a month-end purchase to the last day of the next month`() {
        // Jan 31 -> Feb 28 (common year) and Feb 29 (leap year); Mar 31 -> Apr 30; Dec 31 -> Jan 31 (control).
        assertEquals(day(2025, 2, 28), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2025, 1, 31)))
        assertEquals(day(2024, 2, 29), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2024, 1, 31)))
        assertEquals(day(2025, 4, 30), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2025, 3, 31)))
        assertEquals(day(2026, 1, 31), AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(day(2025, 12, 31)))
    }

    @Test
    fun `derive month-end purchase gives the clamped default and uses it as the effective first due`() {
        val derived = derive(form(purchase = ms(athens, 2025, 1, 31)))

        assertEquals(day(2025, 2, 28), derived.defaultFirstDueEpochDay)
        assertEquals(day(2025, 2, 28), derived.effectiveFirstDueEpochDay)
        assertFalse(derived.firstDueBeforePurchase)
    }

    // ---- zone-dependent purchase day ----------------------------------------------------------------------------

    @Test
    fun `default first due follows the entry zone civil date of the same instant`() {
        // 2025-10-15T21:30Z is 15 Oct 17:30 in New York and 16 Oct 06:30 in Tokyo.
        val instant = ms(ZoneId.of("UTC"), 2025, 10, 15, 21, 30)
        val state = form(purchase = instant)

        assertEquals(day(2025, 11, 15), derive(state, newYork).defaultFirstDueEpochDay)
        assertEquals(day(2025, 11, 16), derive(state, tokyo).defaultFirstDueEpochDay)
    }

    @Test
    fun `before-purchase flag is judged on the civil day in the entry zone`() {
        // Explicit first due 15 Nov: not before the purchase day in either zone (control for the boundary below).
        val instant = ms(ZoneId.of("UTC"), 2025, 10, 15, 21, 30)
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 10, 15))

        // New York purchase day is 15 Oct: first due 15 Oct is NOT before it.
        assertFalse(derive(form(purchase = instant, plan = plan), newYork).firstDueBeforePurchase)
        // Tokyo purchase day is 16 Oct: the same explicit 15 Oct IS before it.
        assertTrue(derive(form(purchase = instant, plan = plan), tokyo).firstDueBeforePurchase)
    }

    // ---- firstDueBeforePurchase ----------------------------------------------------------------------------------

    @Test
    fun `before-purchase flag is false for the default and for an explicit pick on the purchase day`() {
        assertFalse(derive(form()).firstDueBeforePurchase)
        val sameDay = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 10, 16))
        val derived = derive(form(plan = sameDay))
        assertFalse(derived.firstDueBeforePurchase)
        assertEquals(day(2025, 10, 16), derived.effectiveFirstDueEpochDay)
    }

    @Test
    fun `before-purchase flag is true for an explicit pick one day before the purchase`() {
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 10, 15))

        val derived = derive(form(plan = plan))

        assertTrue(derived.firstDueBeforePurchase)
        assertEquals(day(2025, 10, 15), derived.effectiveFirstDueEpochDay)
        // The default is unaffected by the explicit pick.
        assertEquals(day(2025, 11, 16), derived.defaultFirstDueEpochDay)
    }

    @Test
    fun `a before-purchase first due is never a Refused preview and still shows the schedule`() {
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 9, 1))

        val preview = derive(form(plan = plan)).preview

        assertTrue(preview is AddExpenseInstallmentPreview.Ready)
    }

    // ---- preview states ------------------------------------------------------------------------------------------

    @Test
    fun `preview is Hidden while installments are off`() {
        val derived = derive(form(plan = AddExpenseInstallmentsState(on = false)))

        assertEquals(AddExpenseInstallmentPreview.Hidden, derived.preview)
        // The date values are still derived (they do not depend on the switch).
        assertEquals(day(2025, 11, 16), derived.defaultFirstDueEpochDay)
    }

    @Test
    fun `preview is Hidden for an invalid count`() {
        for (bad in listOf("", "0", "1", "481", "x")) {
            val plan = AddExpenseInstallmentsState(on = true, countText = bad)
            assertEquals("count '$bad'", AddExpenseInstallmentPreview.Hidden, derive(form(plan = plan)).preview)
        }
        // Boundary controls: 2 and 480 are previewed.
        for (good in listOf("2", "480")) {
            val plan = AddExpenseInstallmentsState(on = true, countText = good)
            assertTrue("count '$good'", derive(form(plan = plan)).preview is AddExpenseInstallmentPreview.Ready)
        }
    }

    @Test
    fun `preview is Hidden for a fixed split without a valid fixed payment`() {
        for (bad in listOf("", "abc", "0", "-5")) {
            val plan = AddExpenseInstallmentsState(
                on = true,
                split = InstallmentSplitMethod.FIXED_PAYMENT,
                fixedText = bad
            )
            assertEquals("fixed '$bad'", AddExpenseInstallmentPreview.Hidden, derive(form(plan = plan)).preview)
        }
    }

    @Test
    fun `an equal split ignores a leftover invalid fixed text`() {
        val plan = AddExpenseInstallmentsState(on = true, fixedText = "abc")

        assertTrue(derive(form(plan = plan)).preview is AddExpenseInstallmentPreview.Ready)
    }

    @Test
    fun `preview is Hidden when the currency is Unset or not an installment currency`() {
        assertEquals(AddExpenseInstallmentPreview.Hidden, derive(form(currency = null)).preview)
        assertEquals(AddExpenseInstallmentPreview.Hidden, derive(form(currency = "JPY")).preview)
        assertEquals(AddExpenseInstallmentPreview.Hidden, derive(form(currency = "GBP")).preview)
        // Control: EUR and USD are previewed.
        assertTrue(derive(form(currency = "EUR")).preview is AddExpenseInstallmentPreview.Ready)
        assertEquals(InstallmentCurrency.USD, ready(form(currency = "USD")).currency)
    }

    @Test
    fun `preview needs an amount when the amount is blank, zero or not a number`() {
        for (bad in listOf("", "   ", "0", "0.00", "abc", "-5")) {
            assertEquals(
                "amount '$bad'",
                AddExpenseInstallmentPreview.NeedsAmount,
                derive(form(amount = bad)).preview
            )
        }
    }

    @Test
    fun `preview is Refused with the calculator code when the amount is too small for the count`() {
        // 0.05 EUR is 5 minor units: fewer than 12 payments of one minor unit.
        val preview = derive(form(amount = "0.05")).preview

        assertEquals(
            AddExpenseInstallmentPreview.Refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT),
            preview
        )
    }

    @Test
    fun `a Refused preview never carries the before-purchase code`() {
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 9, 1))

        val preview = derive(form(amount = "0.05", plan = plan)).preview

        assertTrue(preview is AddExpenseInstallmentPreview.Refused)
        assertNotEquals(
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE,
            (preview as AddExpenseInstallmentPreview.Refused).code
        )
    }

    // ---- Ready: equal split, residual, fixed payment, financing cost ---------------------------------------------

    @Test
    fun `Ready equal split EUR 600 over 12 is 12 payments of 50 with no residual and no financing cost`() {
        val preview = ready(form())

        assertEquals(12, preview.count)
        assertEquals(InstallmentCurrency.EUR, preview.currency)
        assertEquals(5_000L, preview.firstPaymentMinor)
        assertEquals(5_000L, preview.regularPaymentMinor)
        assertEquals(60_000L, preview.totalMinor)
        assertFalse(preview.hasResidual)
        assertNull(preview.financingCostMinor)
        assertEquals(0, preview.pastDueCount)
        // First due 16 Nov 2025, twelfth payment 16 Oct 2026.
        assertEquals(day(2026, 10, 16), preview.lastDueEpochDay)
    }

    @Test
    fun `Ready equal split with a residual flags it on the first payment and conserves the total`() {
        // 100.00 over 3 = 3334 + 3333 + 3333 minor units (the residual goes on the first payment).
        val plan = AddExpenseInstallmentsState(on = true, countText = "3")

        val preview = ready(form(amount = "100", plan = plan))

        assertTrue(preview.hasResidual)
        assertEquals(3_334L, preview.firstPaymentMinor)
        assertEquals(3_333L, preview.regularPaymentMinor)
        assertEquals(10_000L, preview.totalMinor)
        assertNull(preview.financingCostMinor)
    }

    @Test
    fun `Ready fixed payment above the price gives a positive financing cost`() {
        val plan = AddExpenseInstallmentsState(
            on = true,
            split = InstallmentSplitMethod.FIXED_PAYMENT,
            fixedText = "55.00"
        )

        val preview = ready(form(plan = plan))

        assertEquals(5_500L, preview.firstPaymentMinor)
        assertEquals(5_500L, preview.regularPaymentMinor)
        assertEquals(66_000L, preview.totalMinor)
        assertFalse(preview.hasResidual)
        assertEquals(6_000L, preview.financingCostMinor)
    }

    @Test
    fun `Ready fixed payment below the price gives a negative financing cost, not null and not zero`() {
        val plan = AddExpenseInstallmentsState(
            on = true,
            split = InstallmentSplitMethod.FIXED_PAYMENT,
            fixedText = "45.00"
        )

        val preview = ready(form(plan = plan))

        assertEquals(54_000L, preview.totalMinor)
        assertEquals(-6_000L, preview.financingCostMinor)
    }

    @Test
    fun `Ready fixed payment equal to the share gives a zero financing cost that is distinct from not applicable`() {
        val plan = AddExpenseInstallmentsState(
            on = true,
            split = InstallmentSplitMethod.FIXED_PAYMENT,
            fixedText = "50.00"
        )

        val fixed = ready(form(plan = plan))
        val equal = ready(form())

        assertNotNull(fixed.financingCostMinor)
        assertEquals(0L, fixed.financingCostMinor)
        assertNull(equal.financingCostMinor)
    }

    // ---- past-due count --------------------------------------------------------------------------------------------

    @Test
    fun `pastDueCount counts payments due strictly before today for a past first due`() {
        // Payments on the 16th from Jul: Jul 16, Aug 16, Sep 16 are before 16 Oct; 16 Oct itself is not.
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 7, 16))

        assertEquals(3, ready(form(plan = plan)).pastDueCount)
    }

    @Test
    fun `pastDueCount boundary a first due today is 0 and yesterday is 1`() {
        val dueToday = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 10, 16))
        val dueYesterday = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 10, 15))

        assertEquals(0, ready(form(plan = dueToday)).pastDueCount)
        assertEquals(1, ready(form(plan = dueYesterday)).pastDueCount)
    }

    @Test
    fun `pastDueCount is 0 for the default first due in the future`() {
        assertEquals(0, ready(form()).pastDueCount)
    }

    @Test
    fun `pastDueCount follows the injected today`() {
        val plan = AddExpenseInstallmentsState(on = true, firstDueEpochDay = day(2025, 7, 16))

        // Today 17 Sep: Jul 16, Aug 16 and Sep 16 are before it. Today 16 Sep: Sep 16 itself is not.
        assertEquals(3, ready(form(plan = plan), todayEpochDay = day(2025, 9, 17)).pastDueCount)
        assertEquals(2, ready(form(plan = plan), todayEpochDay = day(2025, 9, 16)).pastDueCount)
    }
}
