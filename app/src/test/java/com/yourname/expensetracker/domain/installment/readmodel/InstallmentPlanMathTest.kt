package com.yourname.expensetracker.domain.installment.readmodel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import java.time.LocalDate
import org.junit.Test

/**
 * Pure unit tests of [InstallmentPlanMath]. The oracle is the loan-installments domain spec:
 * INV-6 (lifetime counted), INV-11 (money boundary), INV-16 (per-currency totals), INV-17
 * (unavailable is not zero), INV-26 (history derivation), section 9 (financing cost, OQ-24),
 * section 11.1 (history row) and the acceptance examples AE-5, AE-7, AE-8, AE-9, AE-11, AE-16,
 * AE-18, AE-26, AE-27, AE-28 and AE-29.
 *
 * Expected values are literals from the spec (or hand-computed minor units), never derived with
 * the production formulas. Dates are civil epoch days built from explicit LocalDate values, and no
 * test reads a clock or a default zone.
 */
class InstallmentPlanMathTest {

    private val eur = InstallmentCurrency.EUR
    private val usd = InstallmentCurrency.USD

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    /**
     * One REGULAR entry of a monthly schedule. Due date = [firstDue] plus (seq - 1) months. PAID
     * and REVERSED entries get a linked expense: gross defaults to [minor]; effective defaults to
     * the gross for PAID and to 0 for REVERSED (a reversed expense counts 0).
     */
    private fun regular(
        seq: Int,
        status: InstallmentEntryStatus,
        minor: Long = 5000L,
        firstDue: LocalDate = LocalDate.of(2027, 4, 10),
        linkedGross: Long? = null,
        linkedEffective: Long? = null,
        linkedCurrency: String = "EUR"
    ): EntryMoney {
        val gross = linkedGross ?: minor
        val effective = linkedEffective
            ?: if (status == InstallmentEntryStatus.REVERSED) 0L else gross
        val linked = when (status) {
            InstallmentEntryStatus.PAID, InstallmentEntryStatus.REVERSED ->
                LinkedExpenseMoney(linkedCurrency, gross, effective)

            else -> null
        }
        return EntryMoney(
            kind = InstallmentEntryKind.REGULAR,
            status = status,
            dueEpochDay = firstDue.plusMonths((seq - 1).toLong()).toEpochDay(),
            scheduledMinor = minor,
            linkedExpense = linked
        )
    }

    private fun payoff(dueEpochDay: Long, minor: Long): EntryMoney = EntryMoney(
        kind = InstallmentEntryKind.PAYOFF,
        status = InstallmentEntryStatus.PAID,
        dueEpochDay = dueEpochDay,
        scheduledMinor = minor,
        linkedExpense = LinkedExpenseMoney("EUR", minor, minor)
    )

    private fun plan(
        status: InstallmentPlanStatus,
        entries: List<EntryMoney>,
        purchaseMinor: Long? = null,
        purchaseCurrency: String = "EUR",
        currency: InstallmentCurrency = eur
    ) = PlanMoneyInput(
        status = status,
        currency = currency,
        purchase = purchaseMinor?.let { PurchaseMoney(purchaseCurrency, it) },
        entries = entries
    )

    /** AE-1 shape: 12 monthly entries of 50.00 EUR from 2027-04-10, statuses given by [statusOf]. */
    private fun ae1Entries(statusOf: (Int) -> InstallmentEntryStatus): List<EntryMoney> =
        (1..12).map { seq -> regular(seq, statusOf(seq)) }

    /**
     * AE-29 base: 12 x 90.00 EUR from 2027-02-10; entries 1-2 PAID_BEFORE_TRACKING, 3-5 PAID
     * (linked 90.00, 90.00, 92.00), 6-12 SCHEDULED. [changes] replace entries by seq.
     */
    private fun ae29Entries(vararg changes: Pair<Int, EntryMoney>): List<EntryMoney> {
        val first = LocalDate.of(2027, 2, 10)
        val base = (1..12).map { seq ->
            when {
                seq <= 2 -> regular(seq, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 9000L, first)
                seq == 5 -> regular(seq, InstallmentEntryStatus.PAID, 9000L, first, linkedGross = 9200L)
                seq <= 4 -> regular(seq, InstallmentEntryStatus.PAID, 9000L, first)
                else -> regular(seq, InstallmentEntryStatus.SCHEDULED, 9000L, first)
            }
        }.toMutableList()
        for ((seq, entry) in changes) base[seq - 1] = entry
        return base
    }

    private fun ae29Plan(
        entries: List<EntryMoney>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE
    ) = plan(status, entries, purchaseMinor = 100000L)

    private fun assertUnavailable(
        amount: InstallmentAmount,
        reason: InstallmentUnavailableReason
    ) {
        assertThat(amount).isEqualTo(InstallmentAmount.Unavailable(reason))
        // Unavailable is never rendered as zero (INV-17).
        assertThat(amount).isNotEqualTo(InstallmentAmount.Available(0L))
    }

    // ---------------------------------------------------------------------------------------
    // INV-16 / AE-5: remaining scheduled payments are kept per currency
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_16_AE5_remaining_scheduled_is_300_plus_224_eur_and_500_usd_as_two_separate_lines() {
        // Plan A: EUR, 12 x 50.00, 6 PAID.
        val planA = plan(
            InstallmentPlanStatus.ACTIVE,
            ae1Entries { seq -> if (seq <= 6) InstallmentEntryStatus.PAID else InstallmentEntryStatus.SCHEDULED }
        )
        // Plan B: STANDALONE, USD, FIXED 125.00 x 8, 4 PAID.
        val planB = plan(
            InstallmentPlanStatus.ACTIVE,
            (1..8).map { seq ->
                regular(
                    seq,
                    if (seq <= 4) InstallmentEntryStatus.PAID else InstallmentEntryStatus.SCHEDULED,
                    minor = 12500L,
                    linkedCurrency = "USD"
                )
            },
            currency = usd
        )
        // Plan C: purchase 240.00 USD, plan currency EUR, 4 x 56.00, 0 PAID.
        val planC = plan(
            InstallmentPlanStatus.ACTIVE,
            (1..4).map { seq -> regular(seq, InstallmentEntryStatus.SCHEDULED, minor = 5600L) },
            purchaseMinor = 24000L,
            purchaseCurrency = "USD"
        )

        val totals = InstallmentPlanMath.remainingScheduledByCurrency(listOf(planA, planB, planC))

        // 300.00 + 224.00 = 524.00 EUR, and 500.00 USD. No combined entry exists.
        assertThat(totals).containsExactly(eur, 52400L, usd, 50000L)
        assertThat(totals).hasSize(2)
    }

    @Test
    fun INV_16_AE5_financing_cost_is_unavailable_for_standalone_B_and_currency_differing_C() {
        val planB = plan(
            InstallmentPlanStatus.ACTIVE,
            (1..8).map { seq ->
                regular(
                    seq,
                    if (seq <= 4) InstallmentEntryStatus.PAID else InstallmentEntryStatus.SCHEDULED,
                    minor = 12500L,
                    linkedCurrency = "USD"
                )
            },
            currency = usd
        )
        val planC = plan(
            InstallmentPlanStatus.ACTIVE,
            (1..4).map { seq -> regular(seq, InstallmentEntryStatus.SCHEDULED, minor = 5600L) },
            purchaseMinor = 24000L,
            purchaseCurrency = "USD"
        )

        assertUnavailable(InstallmentPlanMath.financingCost(planB), InstallmentUnavailableReason.NO_PURCHASE)
        assertUnavailable(InstallmentPlanMath.financingCost(planC), InstallmentUnavailableReason.CURRENCY_DIFFERS)
    }

    @Test
    fun INV_16_two_active_plans_in_the_same_currency_add_up() {
        val first = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 10000L), regular(2, InstallmentEntryStatus.SCHEDULED, 10000L))
        )
        val second = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 2550L))
        )

        assertThat(InstallmentPlanMath.remainingScheduledByCurrency(listOf(first, second)))
            .containsExactly(eur, 22550L)
    }

    @Test
    fun INV_16_a_currency_with_no_active_plan_shows_no_line_not_zero() {
        val onlyEur = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 5000L))
        )

        val totals = InstallmentPlanMath.remainingScheduledByCurrency(listOf(onlyEur))

        assertThat(totals.keys).containsExactly(eur)
        assertThat(totals).doesNotContainKey(usd)
    }

    @Test
    fun INV_16_no_plans_gives_no_lines() {
        assertThat(InstallmentPlanMath.remainingScheduledByCurrency(emptyList())).isEmpty()
    }

    @Test
    fun INV_16_AE11_a_completed_plan_leaves_the_remaining_line_when_it_was_the_only_one_in_that_currency() {
        val completed = plan(
            InstallmentPlanStatus.COMPLETED,
            ae1Entries { InstallmentEntryStatus.PAID }
        )

        assertThat(InstallmentPlanMath.remainingScheduledByCurrency(listOf(completed))).isEmpty()
    }

    @Test
    fun INV_16_non_active_plans_add_no_currency_line() {
        val cancelledUsd = plan(
            InstallmentPlanStatus.CANCELLED,
            listOf(regular(1, InstallmentEntryStatus.CANCELLED, 12500L)),
            currency = usd
        )
        val refundedUsd = plan(
            InstallmentPlanStatus.REFUNDED,
            listOf(regular(1, InstallmentEntryStatus.REVERSED, 12500L, linkedCurrency = "USD")),
            currency = usd
        )
        val paidOffUsd = plan(
            InstallmentPlanStatus.PAID_OFF,
            listOf(regular(1, InstallmentEntryStatus.CANCELLED, 12500L)),
            currency = usd
        )
        val activeEur = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 5000L))
        )

        val totals = InstallmentPlanMath.remainingScheduledByCurrency(
            listOf(cancelledUsd, refundedUsd, paidOffUsd, activeEur)
        )

        assertThat(totals).containsExactly(eur, 5000L)
    }

    @Test
    fun INV_16_remaining_counts_only_SCHEDULED_entries_of_a_plan() {
        val entries = listOf(
            regular(1, InstallmentEntryStatus.PAID, 1000L),
            regular(2, InstallmentEntryStatus.SKIPPED, 2000L),
            regular(3, InstallmentEntryStatus.CANCELLED, 4000L),
            regular(4, InstallmentEntryStatus.REVERSED, 8000L),
            regular(5, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 16000L),
            regular(6, InstallmentEntryStatus.SCHEDULED, 32000L),
            regular(7, InstallmentEntryStatus.SCHEDULED, 64000L)
        )

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(96000L)
    }

    @Test
    fun INV_16_AE26_remaining_is_800_00_after_declaring_with_4_paid_before_tracking() {
        val first = LocalDate.of(2027, 2, 5)
        val entries = (1..12).map { seq ->
            regular(
                seq,
                if (seq <= 4) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.SCHEDULED,
                minor = 10000L,
                firstDue = first
            )
        }

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(80000L)
    }

    @Test
    fun INV_16_AE27_marking_entry_5_lowers_remaining_to_700_00_and_reverting_entry_3_raises_it_by_100_00() {
        val first = LocalDate.of(2027, 2, 5)
        fun entries(statusOf: (Int) -> InstallmentEntryStatus) =
            (1..12).map { seq -> regular(seq, statusOf(seq), minor = 10000L, firstDue = first) }

        val afterMark = entries { seq ->
            if (seq <= 5) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.SCHEDULED
        }
        assertThat(InstallmentPlanMath.remainingScheduledMinor(afterMark)).isEqualTo(70000L)

        val afterRevert = entries { seq ->
            when {
                seq == 3 -> InstallmentEntryStatus.SCHEDULED
                seq <= 5 -> InstallmentEntryStatus.PAID_BEFORE_TRACKING
                else -> InstallmentEntryStatus.SCHEDULED
            }
        }
        assertThat(InstallmentPlanMath.remainingScheduledMinor(afterRevert)).isEqualTo(80000L)
    }

    @Test
    fun INV_16_AE18_standalone_240_x_523_17_has_remaining_scheduled_payments_of_125560_80_eur() {
        val entries = (1..240).map { seq ->
            regular(seq, InstallmentEntryStatus.SCHEDULED, minor = 52317L, firstDue = LocalDate.of(2027, 5, 1))
        }

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(12_556_080L)
        assertThat(
            InstallmentPlanMath.remainingScheduledByCurrency(listOf(plan(InstallmentPlanStatus.ACTIVE, entries)))
        ).containsExactly(eur, 12_556_080L)
    }

    // ---------------------------------------------------------------------------------------
    // INV-6: lifetime counted total and paid-so-far
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_6_AE1_control_all_12_entries_PAID_gives_declaredTotal_60000() {
        val p = plan(InstallmentPlanStatus.COMPLETED, ae1Entries { InstallmentEntryStatus.PAID })

        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(60000L))
        assertThat(InstallmentPlanMath.paidSoFar(p)).isEqualTo(InstallmentAmount.Available(60000L))
    }

    @Test
    fun INV_6_AE7_stop_after_3_paid_gives_150_00_and_the_9_cancelled_entries_add_0() {
        val p = plan(
            InstallmentPlanStatus.CANCELLED,
            ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.PAID else InstallmentEntryStatus.CANCELLED }
        )

        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(15000L))
    }

    @Test
    fun INV_6_AE8_refund_reverses_everything_so_lifetime_counted_is_0_00_but_available() {
        val p = plan(
            InstallmentPlanStatus.REFUNDED,
            ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.REVERSED else InstallmentEntryStatus.CANCELLED }
        )

        // Available(0) is a real zero here, not "unavailable".
        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_6_a_REVERSED_entry_adds_0_even_if_a_stale_linked_effective_amount_were_nonzero() {
        val reversedWithStaleAmount = regular(
            1,
            InstallmentEntryStatus.REVERSED,
            linkedGross = 5000L,
            linkedEffective = 5000L
        )
        val p = plan(InstallmentPlanStatus.REFUNDED, listOf(reversedWithStaleAmount))

        assertThat(InstallmentPlanMath.paidSoFar(p)).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_6_AE16_a_skipped_entry_adds_0_so_the_lifetime_total_is_550_00() {
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            ae1Entries { seq -> if (seq == 2) InstallmentEntryStatus.SKIPPED else InstallmentEntryStatus.PAID }
        )

        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(55000L))
    }

    @Test
    fun INV_6_SCHEDULED_and_PAID_BEFORE_TRACKING_entries_add_0() {
        val p = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(
                regular(1, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 5000L),
                regular(2, InstallmentEntryStatus.SCHEDULED, 5000L)
            )
        )

        assertThat(InstallmentPlanMath.paidSoFar(p)).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_6_AE26_equal_split_with_4_paid_before_tracking_and_8_paid_equals_declaredTotal_minus_400_00() {
        val first = LocalDate.of(2027, 2, 5)
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            (1..12).map { seq ->
                regular(
                    seq,
                    if (seq <= 4) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.PAID,
                    minor = 10000L,
                    firstDue = first
                )
            },
            purchaseMinor = 120000L
        )

        // 1,200.00 - 4 x 100.00 = 800.00.
        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(80000L))
    }

    @Test
    fun INV_6_payoff_entry_counts_with_the_paid_entries_AE9() {
        val entries = (1..3).map { regular(it, InstallmentEntryStatus.PAID) } +
            (4..12).map { regular(it, InstallmentEntryStatus.CANCELLED) } +
            payoff(day(2027, 7, 5), 45000L)
        val p = plan(InstallmentPlanStatus.PAID_OFF, entries, purchaseMinor = 60000L)

        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(60000L))
    }

    @Test
    fun INV_6_uses_the_linked_effective_amount_not_the_scheduled_amount() {
        // The linked expense was edited to 92.00 while the schedule says 90.00.
        val p = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(regular(1, InstallmentEntryStatus.PAID, 9000L, linkedGross = 9200L))
        )

        assertThat(InstallmentPlanMath.paidSoFar(p)).isEqualTo(InstallmentAmount.Available(9200L))
    }

    @Test
    fun INV_6_AE29_marked_not_mine_expense_counts_0_so_lifetime_counted_drops_to_182_00() {
        val notMine = regular(
            4,
            InstallmentEntryStatus.PAID,
            9000L,
            LocalDate.of(2027, 2, 10),
            linkedGross = 9000L,
            linkedEffective = 0L
        )

        val p = ae29Plan(ae29Entries(4 to notMine))

        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(18200L))
    }

    @Test
    fun INV_6_variance_is_linked_effective_minus_scheduled() {
        assertThat(InstallmentPlanMath.varianceMinor(scheduledMinor = 9000L, linkedEffectiveMinor = 9200L)).isEqualTo(200L)
        assertThat(InstallmentPlanMath.varianceMinor(scheduledMinor = 5000L, linkedEffectiveMinor = 4500L)).isEqualTo(-500L)
        assertThat(InstallmentPlanMath.varianceMinor(scheduledMinor = 5000L, linkedEffectiveMinor = 5000L)).isEqualTo(0L)
    }

    // ---------------------------------------------------------------------------------------
    // INV-17: unavailable is not zero (paid-so-far side)
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_17_paid_so_far_is_unavailable_when_a_linked_expense_currency_drifted() {
        val p = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(
                regular(1, InstallmentEntryStatus.PAID, 5000L),
                regular(2, InstallmentEntryStatus.PAID, 5000L, linkedCurrency = "USD")
            )
        )

        assertUnavailable(InstallmentPlanMath.paidSoFar(p), InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
        assertUnavailable(InstallmentPlanMath.lifetimeCounted(p), InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
    }

    @Test
    fun INV_17_paid_so_far_is_unavailable_when_a_PAID_entry_has_no_linked_expense() {
        val orphanPaid = EntryMoney(
            kind = InstallmentEntryKind.REGULAR,
            status = InstallmentEntryStatus.PAID,
            dueEpochDay = day(2027, 4, 10),
            scheduledMinor = 5000L,
            linkedExpense = null
        )
        val p = plan(InstallmentPlanStatus.ACTIVE, listOf(orphanPaid))

        assertUnavailable(InstallmentPlanMath.paidSoFar(p), InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
    }

    @Test
    fun INV_17_currency_drift_is_checked_per_plan_currency_for_a_usd_plan_too() {
        val usdPlan = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(
                regular(1, InstallmentEntryStatus.PAID, 12500L, linkedCurrency = "USD"),
                regular(2, InstallmentEntryStatus.PAID, 12500L, linkedCurrency = "EUR")
            ),
            currency = usd
        )

        assertUnavailable(InstallmentPlanMath.paidSoFar(usdPlan), InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
    }

    // ---------------------------------------------------------------------------------------
    // Financing cost: AE-9, AE-18, AE-29 and INV-17 availability
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_17_AE9_payoff_of_450_00_gives_financing_cost_0_00_as_a_real_zero() {
        val entries = (1..3).map { regular(it, InstallmentEntryStatus.PAID) } +
            (4..12).map { regular(it, InstallmentEntryStatus.CANCELLED) } +
            payoff(day(2027, 7, 5), 45000L)
        val p = plan(InstallmentPlanStatus.PAID_OFF, entries, purchaseMinor = 60000L)

        // (3 x 50.00 + 450.00) - 600.00 = 0.00. CANCELLED(PAID_OFF_EARLY) entries are excluded.
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_17_AE9_payoff_of_440_00_gives_financing_cost_minus_10_00_shown_as_is() {
        val entries = (1..3).map { regular(it, InstallmentEntryStatus.PAID) } +
            (4..12).map { regular(it, InstallmentEntryStatus.CANCELLED) } +
            payoff(day(2027, 7, 5), 44000L)
        val p = plan(InstallmentPlanStatus.PAID_OFF, entries, purchaseMinor = 60000L)

        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(-1000L))
        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(59000L))
    }

    @Test
    fun INV_17_AE18_purchase_1000_declared_fixed_12_x_87_50_has_total_1050_and_financing_cost_50_00() {
        val entries = (1..12).map { regular(it, InstallmentEntryStatus.SCHEDULED, minor = 8750L) }
        val p = plan(InstallmentPlanStatus.ACTIVE, entries, purchaseMinor = 100000L)

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(105000L)
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(5000L))
    }

    @Test
    fun INV_17_AE18_standalone_plan_has_unavailable_financing_cost_not_zero() {
        val entries = (1..240).map { regular(it, InstallmentEntryStatus.SCHEDULED, minor = 52317L) }
        val p = plan(InstallmentPlanStatus.ACTIVE, entries, purchaseMinor = null)

        assertUnavailable(InstallmentPlanMath.financingCost(p), InstallmentUnavailableReason.NO_PURCHASE)
    }

    @Test
    fun INV_17_AE29_financing_cost_is_8200_minor_units_with_lifetime_272_and_remaining_630() {
        val p = ae29Plan(ae29Entries())

        // 18000 (PAID_BEFORE_TRACKING) + 27200 (PAID linked 90 + 90 + 92) + 63000 (SCHEDULED) - 100000.
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(8200L))
        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(27200L))
        assertThat(InstallmentPlanMath.remainingScheduledMinor(p.entries)).isEqualTo(63000L)
    }

    @Test
    fun INV_17_AE29_skipping_entry_6_excludes_it_so_financing_cost_is_minus_8_00_not_unavailable_or_zero() {
        val skipped = regular(6, InstallmentEntryStatus.SKIPPED, 9000L, LocalDate.of(2027, 2, 10))

        val p = ae29Plan(ae29Entries(6 to skipped))

        // 18000 + 27200 + 54000 - 100000 = -800 minor units.
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(-800L))
        assertThat(InstallmentPlanMath.remainingScheduledMinor(p.entries)).isEqualTo(54000L)
    }

    @Test
    fun INV_17_AE29_reverting_entry_1_moves_its_amount_from_the_pbt_term_to_the_scheduled_term_so_cost_stays_82_00() {
        val reverted = regular(1, InstallmentEntryStatus.SCHEDULED, 9000L, LocalDate.of(2027, 2, 10))

        val p = ae29Plan(ae29Entries(1 to reverted))

        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(8200L))
        assertThat(InstallmentPlanMath.remainingScheduledMinor(p.entries)).isEqualTo(72000L)
    }

    @Test
    fun INV_17_AE29_not_mine_linked_expense_uses_gross_amount_so_financing_cost_stays_82_00() {
        val notMine = regular(
            4,
            InstallmentEntryStatus.PAID,
            9000L,
            LocalDate.of(2027, 2, 10),
            linkedGross = 9000L,
            linkedEffective = 0L
        )

        val p = ae29Plan(ae29Entries(4 to notMine))

        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(8200L))
    }

    @Test
    fun INV_17_AE29_payoff_of_600_00_gives_financing_cost_52_00_with_cancelled_entries_excluded() {
        val first = LocalDate.of(2027, 2, 10)
        val cancelled = (6..12).map { seq -> seq to regular(seq, InstallmentEntryStatus.CANCELLED, 9000L, first) }
        val entries = ae29Entries(*cancelled.toTypedArray()) + payoff(day(2027, 6, 15), 60000L)

        val p = ae29Plan(entries, InstallmentPlanStatus.PAID_OFF)

        // 18000 + 27200 + 60000 - 100000 = 5200.
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(5200L))
        assertThat(InstallmentPlanMath.lifetimeCounted(p)).isEqualTo(InstallmentAmount.Available(87200L))
    }

    @Test
    fun INV_17_AE29_stop_makes_financing_cost_unavailable() {
        val first = LocalDate.of(2027, 2, 10)
        val cancelled = (6..12).map { seq -> seq to regular(seq, InstallmentEntryStatus.CANCELLED, 9000L, first) }

        val p = ae29Plan(ae29Entries(*cancelled.toTypedArray()), InstallmentPlanStatus.CANCELLED)

        assertUnavailable(InstallmentPlanMath.financingCost(p), InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
    }

    @Test
    fun INV_17_AE29_REVERSED_entries_in_a_refunded_plan_leave_financing_cost_unavailable() {
        val p = plan(
            InstallmentPlanStatus.REFUNDED,
            ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.REVERSED else InstallmentEntryStatus.CANCELLED },
            purchaseMinor = 60000L
        )

        assertUnavailable(InstallmentPlanMath.financingCost(p), InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
    }

    @Test
    fun INV_17_formula_excludes_REVERSED_SKIPPED_and_CANCELLED_entries_even_when_present_in_an_eligible_plan() {
        val entries = listOf(
            regular(1, InstallmentEntryStatus.PAID, 5000L),
            regular(2, InstallmentEntryStatus.SKIPPED, 7000L),
            regular(3, InstallmentEntryStatus.CANCELLED, 11000L),
            regular(4, InstallmentEntryStatus.REVERSED, 13000L),
            regular(5, InstallmentEntryStatus.SCHEDULED, 17000L),
            regular(6, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 19000L)
        )
        val p = plan(InstallmentPlanStatus.ACTIVE, entries, purchaseMinor = 40000L)

        // 5000 (PAID) + 17000 (SCHEDULED) + 19000 (PAID_BEFORE_TRACKING) - 40000 = 1000.
        assertThat(InstallmentPlanMath.financingCost(p)).isEqualTo(InstallmentAmount.Available(1000L))
    }

    @Test
    fun INV_17_status_eligibility_is_ACTIVE_COMPLETED_PAID_OFF_and_a_zero_result_is_never_confused_with_unavailable() {
        // One SCHEDULED entry equal to the purchase: an eligible plan yields a real Available(0).
        val entries = listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 5000L))

        for (status in listOf(InstallmentPlanStatus.ACTIVE, InstallmentPlanStatus.COMPLETED, InstallmentPlanStatus.PAID_OFF)) {
            assertWithMessage("status %s", status)
                .that(InstallmentPlanMath.financingCost(plan(status, entries, purchaseMinor = 5000L)))
                .isEqualTo(InstallmentAmount.Available(0L))
        }
        for (status in listOf(InstallmentPlanStatus.CANCELLED, InstallmentPlanStatus.REFUNDED)) {
            assertWithMessage("status %s", status)
                .that(InstallmentPlanMath.financingCost(plan(status, entries, purchaseMinor = 5000L)))
                .isEqualTo(InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE))
        }
    }

    @Test
    fun INV_17_purchase_currency_differing_from_plan_currency_is_unavailable_in_both_directions() {
        val eurEntries = listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 5000L))
        val usdEntries = listOf(regular(1, InstallmentEntryStatus.SCHEDULED, 5000L))

        assertUnavailable(
            InstallmentPlanMath.financingCost(
                plan(InstallmentPlanStatus.ACTIVE, eurEntries, purchaseMinor = 5000L, purchaseCurrency = "USD")
            ),
            InstallmentUnavailableReason.CURRENCY_DIFFERS
        )
        assertUnavailable(
            InstallmentPlanMath.financingCost(
                plan(
                    InstallmentPlanStatus.ACTIVE,
                    usdEntries,
                    purchaseMinor = 5000L,
                    purchaseCurrency = "EUR",
                    currency = usd
                )
            ),
            InstallmentUnavailableReason.CURRENCY_DIFFERS
        )
    }

    @Test
    fun INV_17_financing_cost_is_unavailable_while_paid_so_far_is_unavailable() {
        val p = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(
                regular(1, InstallmentEntryStatus.PAID, 5000L, linkedCurrency = "USD"),
                regular(2, InstallmentEntryStatus.SCHEDULED, 5000L)
            ),
            purchaseMinor = 10000L
        )

        assertUnavailable(InstallmentPlanMath.financingCost(p), InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
    }

    @Test
    fun INV_17_financing_cost_is_unavailable_when_a_PAID_entry_lost_its_linked_expense() {
        val orphanPaid = EntryMoney(
            InstallmentEntryKind.REGULAR,
            InstallmentEntryStatus.PAID,
            day(2027, 4, 10),
            5000L,
            null
        )
        val p = plan(
            InstallmentPlanStatus.ACTIVE,
            listOf(orphanPaid, regular(2, InstallmentEntryStatus.SCHEDULED, 5000L)),
            purchaseMinor = 10000L
        )

        assertUnavailable(InstallmentPlanMath.financingCost(p), InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
    }

    // ---------------------------------------------------------------------------------------
    // Counts, display state, payoff bounds
    // ---------------------------------------------------------------------------------------

    @Test
    fun AE28_entry_counts_after_stop_are_0_paid_4_paid_before_tracking_8_cancelled() {
        val first = LocalDate.of(2027, 2, 5)
        val entries = (1..12).map { seq ->
            regular(
                seq,
                if (seq <= 4) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.CANCELLED,
                10000L,
                first
            )
        }

        assertThat(InstallmentPlanMath.entryCounts(entries)).isEqualTo(
            InstallmentEntryCounts(
                scheduled = 0,
                paid = 0,
                skipped = 0,
                cancelled = 8,
                reversed = 0,
                paidBeforeTracking = 4
            )
        )
    }

    @Test
    fun entry_counts_count_every_status_separately_and_a_paid_payoff_is_one_paid() {
        val entries = listOf(
            regular(1, InstallmentEntryStatus.PAID),
            regular(2, InstallmentEntryStatus.PAID),
            regular(3, InstallmentEntryStatus.SKIPPED),
            regular(4, InstallmentEntryStatus.CANCELLED),
            regular(5, InstallmentEntryStatus.CANCELLED),
            regular(6, InstallmentEntryStatus.CANCELLED),
            regular(7, InstallmentEntryStatus.REVERSED),
            regular(8, InstallmentEntryStatus.PAID_BEFORE_TRACKING),
            regular(9, InstallmentEntryStatus.SCHEDULED),
            payoff(day(2027, 7, 5), 1000L)
        )

        assertThat(InstallmentPlanMath.entryCounts(entries)).isEqualTo(
            InstallmentEntryCounts(
                scheduled = 1,
                paid = 3,
                skipped = 1,
                cancelled = 3,
                reversed = 1,
                paidBeforeTracking = 1
            )
        )
    }

    @Test
    fun AE26_dueState_of_a_scheduled_entry_is_UPCOMING_DUE_or_OVERDUE_by_comparing_with_today() {
        val today = day(2027, 6, 20)

        assertThat(InstallmentPlanMath.dueState(InstallmentEntryStatus.SCHEDULED, day(2027, 6, 21), today))
            .isEqualTo(InstallmentDueState.UPCOMING)
        assertThat(InstallmentPlanMath.dueState(InstallmentEntryStatus.SCHEDULED, day(2027, 6, 20), today))
            .isEqualTo(InstallmentDueState.DUE)
        assertThat(InstallmentPlanMath.dueState(InstallmentEntryStatus.SCHEDULED, day(2027, 6, 19), today))
            .isEqualTo(InstallmentDueState.OVERDUE)
        // AE-26: entry 5, due 2027-06-05, is OVERDUE-unrecorded on 2027-06-20.
        assertThat(InstallmentPlanMath.dueState(InstallmentEntryStatus.SCHEDULED, day(2027, 6, 5), today))
            .isEqualTo(InstallmentDueState.OVERDUE)
    }

    @Test
    fun INV_32_dueState_is_null_for_every_status_other_than_SCHEDULED_including_PAID_BEFORE_TRACKING() {
        val today = day(2027, 6, 20)
        for (status in InstallmentEntryStatus.entries) {
            if (status == InstallmentEntryStatus.SCHEDULED) continue
            for (due in listOf(day(2027, 6, 5), today, day(2027, 7, 5))) {
                assertWithMessage("status %s due %s", status, due)
                    .that(InstallmentPlanMath.dueState(status, due, today))
                    .isNull()
            }
        }
    }

    @Test
    fun AE9_default_payoff_amount_is_the_sum_of_scheduled_entries_450_00() {
        val entries = ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.PAID else InstallmentEntryStatus.SCHEDULED }

        assertThat(InstallmentPlanMath.defaultPayoffAmountMinor(entries)).isEqualTo(45000L)
    }

    @Test
    fun INV_32_default_payoff_amount_excludes_paid_before_tracking_and_includes_overdue_unrecorded_scheduled() {
        val first = LocalDate.of(2027, 2, 5)
        // Entries 1-4 PBT, entry 5 (due 2027-06-05) SCHEDULED and overdue, entries 6-12 SCHEDULED.
        val entries = (1..12).map { seq ->
            regular(
                seq,
                if (seq <= 4) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.SCHEDULED,
                10000L,
                first
            )
        }

        assertThat(InstallmentPlanMath.defaultPayoffAmountMinor(entries)).isEqualTo(80000L)
    }

    @Test
    fun INV_32_payoff_date_lower_bound_is_the_latest_due_among_PAID_and_PAID_BEFORE_TRACKING() {
        val first = LocalDate.of(2027, 2, 5)
        // PBT entries 1-4 (latest due 2027-05-05), PAID entry 5 (due 2027-06-05), SKIPPED entry 6
        // (due 2027-07-05) must not count.
        val entries = listOf(
            regular(1, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(2, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(3, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(4, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(5, InstallmentEntryStatus.PAID, 10000L, first),
            regular(6, InstallmentEntryStatus.SKIPPED, 10000L, first),
            regular(7, InstallmentEntryStatus.CANCELLED, 10000L, first),
            regular(8, InstallmentEntryStatus.SCHEDULED, 10000L, first)
        )

        assertThat(InstallmentPlanMath.payoffDateLowerBoundEpochDay(entries)).isEqualTo(day(2027, 6, 5))
    }

    @Test
    fun INV_32_payoff_date_lower_bound_comes_from_a_paid_before_tracking_entry_when_no_entry_is_PAID() {
        val first = LocalDate.of(2027, 2, 5)
        val entries = listOf(
            regular(1, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(2, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first),
            regular(3, InstallmentEntryStatus.SCHEDULED, 10000L, first)
        )

        assertThat(InstallmentPlanMath.payoffDateLowerBoundEpochDay(entries)).isEqualTo(day(2027, 3, 5))
    }

    @Test
    fun payoff_date_lower_bound_is_null_when_nothing_was_paid() {
        val entries = ae1Entries { InstallmentEntryStatus.SCHEDULED }

        assertThat(InstallmentPlanMath.payoffDateLowerBoundEpochDay(entries)).isNull()
        assertThat(InstallmentPlanMath.payoffDateLowerBoundEpochDay(emptyList())).isNull()
    }

    // ---------------------------------------------------------------------------------------
    // INV-26 / section 11.1: history derivation from plan, entry and expense rows alone
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_26_AE7_stop_history_is_cancelled_2027_04_10_to_2027_07_05_total_150_00_3_paid_9_cancelled() {
        val p = plan(
            InstallmentPlanStatus.CANCELLED,
            ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.PAID else InstallmentEntryStatus.CANCELLED },
            purchaseMinor = 60000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 7, 5))

        assertThat(history).isEqualTo(
            InstallmentHistorySummary(
                currency = eur,
                finalStatus = InstallmentPlanStatus.CANCELLED,
                startEpochDay = day(2027, 4, 10),
                endEpochDay = day(2027, 7, 5),
                totalCounted = InstallmentAmount.Available(15000L),
                counts = InstallmentEntryCounts(
                    scheduled = 0,
                    paid = 3,
                    skipped = 0,
                    cancelled = 9,
                    reversed = 0,
                    paidBeforeTracking = 0
                ),
                financingCost = InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
            )
        )
    }

    @Test
    fun INV_26_AE8_refund_history_has_total_0_with_3_reversed_9_cancelled_and_unavailable_financing_cost() {
        val p = plan(
            InstallmentPlanStatus.REFUNDED,
            ae1Entries { seq -> if (seq <= 3) InstallmentEntryStatus.REVERSED else InstallmentEntryStatus.CANCELLED },
            purchaseMinor = 60000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 7, 5))!!

        assertThat(history.finalStatus).isEqualTo(InstallmentPlanStatus.REFUNDED)
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(0L))
        assertThat(history.counts.reversed).isEqualTo(3)
        assertThat(history.counts.cancelled).isEqualTo(9)
        assertThat(history.counts.paid).isEqualTo(0)
        assertThat(history.endEpochDay).isEqualTo(day(2027, 7, 5))
        assertUnavailable(history.financingCost, InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
    }

    @Test
    fun INV_26_AE9_paid_off_history_has_total_600_00_end_at_the_terminal_date_and_financing_cost_0_00() {
        val entries = (1..3).map { regular(it, InstallmentEntryStatus.PAID) } +
            (4..12).map { regular(it, InstallmentEntryStatus.CANCELLED) } +
            payoff(day(2027, 7, 5), 45000L)
        val p = plan(InstallmentPlanStatus.PAID_OFF, entries, purchaseMinor = 60000L)

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 7, 5))!!

        assertThat(history.finalStatus).isEqualTo(InstallmentPlanStatus.PAID_OFF)
        assertThat(history.startEpochDay).isEqualTo(day(2027, 4, 10))
        assertThat(history.endEpochDay).isEqualTo(day(2027, 7, 5))
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(60000L))
        assertThat(history.financingCost).isEqualTo(InstallmentAmount.Available(0L))
        // The payoff entry is one PAID entry.
        assertThat(history.counts.paid).isEqualTo(4)
        assertThat(history.counts.cancelled).isEqualTo(9)
    }

    @Test
    fun INV_26_AE11_completed_history_ends_at_the_latest_due_of_a_paid_entry_not_at_the_supplied_terminal_date() {
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            ae1Entries { InstallmentEntryStatus.PAID },
            purchaseMinor = 60000L
        )

        // A terminal date later than the last due date is supplied on purpose.
        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2028, 3, 11))!!

        assertThat(history.startEpochDay).isEqualTo(day(2027, 4, 10))
        assertThat(history.endEpochDay).isEqualTo(day(2028, 3, 10))
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(60000L))
        assertThat(history.counts.paid).isEqualTo(12)
        assertThat(history.financingCost).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_26_AE27_completed_by_declaration_history_is_2027_02_05_to_2027_04_05_total_0_3_paid_before_tracking_cost_0() {
        val first = LocalDate.of(2027, 2, 5)
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            (1..3).map { seq -> regular(seq, InstallmentEntryStatus.PAID_BEFORE_TRACKING, 10000L, first) },
            purchaseMinor = 30000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 6, 20))!!

        assertThat(history.startEpochDay).isEqualTo(day(2027, 2, 5))
        assertThat(history.endEpochDay).isEqualTo(day(2027, 4, 5))
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(0L))
        assertThat(history.counts.paid).isEqualTo(0)
        assertThat(history.counts.paidBeforeTracking).isEqualTo(3)
        assertThat(history.financingCost).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun INV_26_AE28_stop_history_is_2027_02_05_to_2027_06_21_total_0_with_4_paid_before_tracking_8_cancelled() {
        val first = LocalDate.of(2027, 2, 5)
        val p = plan(
            InstallmentPlanStatus.CANCELLED,
            (1..12).map { seq ->
                regular(
                    seq,
                    if (seq <= 4) InstallmentEntryStatus.PAID_BEFORE_TRACKING else InstallmentEntryStatus.CANCELLED,
                    10000L,
                    first
                )
            },
            purchaseMinor = 120000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 6, 21))!!

        assertThat(history.startEpochDay).isEqualTo(day(2027, 2, 5))
        assertThat(history.endEpochDay).isEqualTo(day(2027, 6, 21))
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(0L))
        assertThat(history.counts.paid).isEqualTo(0)
        assertThat(history.counts.paidBeforeTracking).isEqualTo(4)
        assertThat(history.counts.cancelled).isEqualTo(8)
        assertUnavailable(history.financingCost, InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE)
    }

    @Test
    fun INV_26_an_active_plan_has_no_history_row() {
        val p = plan(InstallmentPlanStatus.ACTIVE, ae1Entries { InstallmentEntryStatus.SCHEDULED })

        assertThat(InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 7, 5))).isNull()
    }

    @Test
    fun INV_26_history_statuses_are_exactly_COMPLETED_CANCELLED_REFUNDED_and_PAID_OFF() {
        assertThat(InstallmentPlanMath.isHistoryStatus(InstallmentPlanStatus.ACTIVE)).isFalse()
        assertThat(InstallmentPlanMath.isHistoryStatus(InstallmentPlanStatus.COMPLETED)).isTrue()
        assertThat(InstallmentPlanMath.isHistoryStatus(InstallmentPlanStatus.CANCELLED)).isTrue()
        assertThat(InstallmentPlanMath.isHistoryStatus(InstallmentPlanStatus.REFUNDED)).isTrue()
        assertThat(InstallmentPlanMath.isHistoryStatus(InstallmentPlanStatus.PAID_OFF)).isTrue()
    }

    @Test
    fun INV_26_history_total_is_unavailable_not_zero_when_a_linked_currency_drifted() {
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            listOf(regular(1, InstallmentEntryStatus.PAID, 5000L, linkedCurrency = "USD")),
            purchaseMinor = 5000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 5, 10))!!

        assertUnavailable(history.totalCounted, InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
        assertUnavailable(history.financingCost, InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
    }

    @Test
    fun INV_26_history_is_per_currency_a_usd_plan_reports_usd_without_conversion() {
        val p = plan(
            InstallmentPlanStatus.CANCELLED,
            (1..8).map { seq ->
                regular(
                    seq,
                    if (seq <= 4) InstallmentEntryStatus.PAID else InstallmentEntryStatus.CANCELLED,
                    12500L,
                    linkedCurrency = "USD"
                )
            },
            currency = usd
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 9, 1))!!

        assertThat(history.currency).isEqualTo(usd)
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(50000L))
    }

    // Interpretation 6 (handoff S5): the spec defines the COMPLETED end date as the latest due date
    // among PAID and PAID_BEFORE_TRACKING entries but is silent when there is none (a plan whose
    // entries all ended SKIPPED). The implementation falls back to the supplied terminal date.
    @Test
    fun interpretation_6_completed_plan_with_no_paid_or_pbt_entry_ends_at_the_terminal_date() {
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            ae1Entries { InstallmentEntryStatus.SKIPPED },
            purchaseMinor = 60000L
        )

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 8, 1))!!

        assertThat(history.endEpochDay).isEqualTo(day(2027, 8, 1))
        assertThat(history.totalCounted).isEqualTo(InstallmentAmount.Available(0L))
    }

    @Test
    fun interpretation_6_without_a_paid_or_pbt_entry_and_no_terminal_date_the_end_is_null() {
        val p = plan(
            InstallmentPlanStatus.COMPLETED,
            ae1Entries { InstallmentEntryStatus.SKIPPED },
            purchaseMinor = 60000L
        )

        assertThat(InstallmentPlanMath.historySummary(p, terminalEpochDay = null)!!.endEpochDay).isNull()
    }

    // Pin (not a numbered interpretation): the spec says start is "first entry due date, PAID_BEFORE_
    // TRACKING entries included". The implementation reads that as the first REGULAR entry, so an
    // early PAYOFF entry (payoff before the first due date) does not move the start.
    @Test
    fun pin_history_start_ignores_a_PAYOFF_entry_dated_before_the_first_regular_entry() {
        val entries = (1..4).map { regular(it, InstallmentEntryStatus.CANCELLED) } +
            payoff(day(2027, 3, 20), 20000L)
        val p = plan(InstallmentPlanStatus.PAID_OFF, entries, purchaseMinor = 20000L)

        val history = InstallmentPlanMath.historySummary(p, terminalEpochDay = day(2027, 3, 20))!!

        assertThat(history.startEpochDay).isEqualTo(day(2027, 4, 10))
    }

    // ---------------------------------------------------------------------------------------
    // INV-11: money boundary that feeds the math (HALF_UP at two decimals, OD-7)
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_11_control_two_decimal_amounts_convert_exactly_to_minor_units() {
        assertThat(InstallmentMinorUnits.toMinorOrNull(523.17)).isEqualTo(52317L)
        assertThat(InstallmentMinorUnits.toMinorOrNull(87.50)).isEqualTo(8750L)
        assertThat(InstallmentMinorUnits.toMinorOrNull(0.01)).isEqualTo(1L)
        assertThat(InstallmentMinorUnits.toMinorOrNull(0.0)).isEqualTo(0L)
        assertThat(InstallmentMinorUnits.toMinorOrNull(-10.00)).isEqualTo(-1000L)
    }

    @Test
    fun INV_11_OD7_half_up_rounds_a_tie_up_not_to_even() {
        // 0.125 is a tie at two decimals: HALF_UP gives 13, HALF_EVEN would give 12.
        assertThat(InstallmentMinorUnits.toMinorOrNull(0.125)).isEqualTo(13L)
        // 1.005 must round on its decimal text (100.5 minor units), not on the binary product.
        assertThat(InstallmentMinorUnits.toMinorOrNull(1.005)).isEqualTo(101L)
        assertThat(InstallmentMinorUnits.toMinorOrNull(0.124)).isEqualTo(12L)
    }

    @Test
    fun INV_11_non_finite_and_out_of_range_amounts_convert_to_null_never_zero() {
        assertThat(InstallmentMinorUnits.toMinorOrNull(Double.NaN)).isNull()
        assertThat(InstallmentMinorUnits.toMinorOrNull(Double.POSITIVE_INFINITY)).isNull()
        assertThat(InstallmentMinorUnits.toMinorOrNull(Double.NEGATIVE_INFINITY)).isNull()
        assertThat(InstallmentMinorUnits.toMinorOrNull(1_000_000_000.0)).isNull()
        assertThat(InstallmentMinorUnits.toMinorOrNull(-1_000_000_000.0)).isNull()
    }

    @Test
    fun INV_11_the_largest_accepted_amount_round_trips_through_minor_units() {
        assertThat(InstallmentMinorUnits.toMinorOrNull(999_999_999.99)).isEqualTo(99_999_999_999L)
        assertThat(InstallmentMinorUnits.fromMinor(99_999_999_999L)).isEqualTo(999_999_999.99)
        assertThat(InstallmentMinorUnits.fromMinor(52317L)).isEqualTo(523.17)
    }

    @Test
    fun INV_11_sums_over_480_maximum_entries_are_exact_in_long_minor_units() {
        val max = 99_999_999_999L
        val entries = (1..480).map { seq ->
            regular(seq, InstallmentEntryStatus.SCHEDULED, minor = max, firstDue = LocalDate.of(2027, 5, 1))
        }

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(480L * max)
    }

    @Test
    fun INV_11_aggregation_does_not_depend_on_floating_point_sums() {
        // 0.1 + 0.2 != 0.3 in Double; the same amounts in minor units add up exactly.
        val entries = listOf(
            regular(1, InstallmentEntryStatus.SCHEDULED, minor = 10L),
            regular(2, InstallmentEntryStatus.SCHEDULED, minor = 20L)
        )

        assertThat(InstallmentPlanMath.remainingScheduledMinor(entries)).isEqualTo(30L)
    }
}
