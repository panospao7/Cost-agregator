package com.yourname.expensetracker.domain.installment.schedule

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure unit tests of [InstallmentScheduleCalculator]. The oracle is the loan-installments domain
 * spec: INV-9 (equal-split conservation), INV-10 (fixed payment), INV-11 (money boundary),
 * INV-18 (due-date arithmetic and noon stamp) and the acceptance examples AE-2, AE-3, AE-4, AE-18,
 * AE-19, AE-22, AE-26 and AE-27 (calculator part).
 *
 * Expected values are literals taken from the spec (or hand-computed minor units), never derived
 * with the production formulas. All zones are explicit and no test reads a clock.
 */
class InstallmentScheduleCalculatorTest {

    private val athens = ZoneId.of("Europe/Athens")
    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    private fun iso(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /** EQUAL_SPLIT request; defaults follow AE-1 (first due 2027-04-10, today 2027-03-12, purchase 2027-03-10). */
    private fun equalRequest(
        total: Double?,
        count: Int,
        placement: InstallmentResidualPlacement = InstallmentResidualPlacement.FIRST,
        currency: String = "EUR",
        firstDue: Long = day(2027, 4, 10),
        declaredOn: Long = day(2027, 3, 12),
        purchase: Long? = day(2027, 3, 10),
        paidBefore: Int = 0
    ) = InstallmentScheduleRequest(
        splitMethod = InstallmentSplitMethod.EQUAL_SPLIT,
        currency = currency,
        installmentCount = count,
        declaredTotal = total,
        fixedPaymentAmount = null,
        firstDueEpochDay = firstDue,
        residualPlacement = placement,
        declaredOnEpochDay = declaredOn,
        purchaseEpochDay = purchase,
        paidBeforeTrackingCount = paidBefore
    )

    /** FIXED_PAYMENT request; defaults describe a STANDALONE plan (no purchase). */
    private fun fixedRequest(
        payment: Double?,
        count: Int,
        placement: InstallmentResidualPlacement = InstallmentResidualPlacement.FIRST,
        currency: String = "EUR",
        firstDue: Long = day(2027, 5, 1),
        declaredOn: Long = day(2027, 4, 20),
        purchase: Long? = null,
        paidBefore: Int = 0
    ) = InstallmentScheduleRequest(
        splitMethod = InstallmentSplitMethod.FIXED_PAYMENT,
        currency = currency,
        installmentCount = count,
        declaredTotal = null,
        fixedPaymentAmount = payment,
        firstDueEpochDay = firstDue,
        residualPlacement = placement,
        declaredOnEpochDay = declaredOn,
        purchaseEpochDay = purchase,
        paidBeforeTrackingCount = paidBefore
    )

    private fun schedule(result: ScheduleGenerationResult): GeneratedSchedule = when (result) {
        is ScheduleGenerationResult.Success -> result.schedule
        is ScheduleGenerationResult.Refused -> throw AssertionError("expected a schedule but was refused with ${result.code}")
    }

    private fun assertRefused(expected: InstallmentRefusalCode, result: ScheduleGenerationResult) {
        assertThat(result).isEqualTo(ScheduleGenerationResult.Refused(expected))
    }

    private fun minors(schedule: GeneratedSchedule): List<Long> = schedule.entries.map { it.amountMinor }

    // ---------------------------------------------------------------------------------------
    // INV-9 / AE-1: equal split control
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_9_AE1_control_600_over_12_gives_twelve_entries_of_50_00_due_2027_04_10_to_2028_03_10() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(equalRequest(total = 600.00, count = 12))
        )

        assertThat(s.currency).isEqualTo(InstallmentCurrency.EUR)
        assertThat(s.totalMinor).isEqualTo(60000L)
        assertThat(minors(s)).isEqualTo(List(12) { 5000L })
        assertThat(s.entries.map { it.seq }).isEqualTo((1..12).toList())
        assertThat(iso(s.entries.first().dueEpochDay)).isEqualTo("2027-04-10")
        assertThat(iso(s.entries.last().dueEpochDay)).isEqualTo("2028-03-10")
        assertThat(s.lastDueEpochDay).isEqualTo(s.entries.last().dueEpochDay)
        assertThat(s.anchorDayOfMonth).isEqualTo(10)
        assertThat(s.scheduledCount).isEqualTo(12)
        assertThat(s.completesImmediately).isFalse()
        assertThat(s.entries.map { it.status }.toSet()).containsExactly(InstallmentEntryStatus.SCHEDULED)
    }

    // ---------------------------------------------------------------------------------------
    // INV-9 / AE-2: 100.00 / 3 with residual FIRST and LAST
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_9_AE2_100_over_3_residual_FIRST_gives_33_34_33_33_33_33() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, placement = InstallmentResidualPlacement.FIRST)
            )
        )

        assertThat(minors(s)).isEqualTo(listOf(3334L, 3333L, 3333L))
        assertThat(s.totalMinor).isEqualTo(10000L)
        assertThat(minors(s).sum()).isEqualTo(10000L)
    }

    @Test
    fun INV_9_AE2_100_over_3_residual_LAST_gives_33_33_33_33_33_34() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, placement = InstallmentResidualPlacement.LAST)
            )
        )

        assertThat(minors(s)).isEqualTo(listOf(3333L, 3333L, 3334L))
        assertThat(minors(s).sum()).isEqualTo(10000L)
    }

    @Test
    fun INV_9_AE2_entry_amount_doubles_are_exact_two_decimal_values_compared_as_BigDecimal() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, placement = InstallmentResidualPlacement.FIRST)
            )
        )

        assertThat(BigDecimal.valueOf(s.entries[0].amount).compareTo(BigDecimal("33.34"))).isEqualTo(0)
        assertThat(BigDecimal.valueOf(s.entries[1].amount).compareTo(BigDecimal("33.33"))).isEqualTo(0)
        assertThat(BigDecimal.valueOf(s.entries[2].amount).compareTo(BigDecimal("33.33"))).isEqualTo(0)
    }

    // ---------------------------------------------------------------------------------------
    // INV-9 / AE-3: other residuals and the too-small boundary
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_9_AE3_200_over_7_FIRST_gives_28_58_then_six_times_28_57_summing_to_200_00() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 200.00, count = 7, placement = InstallmentResidualPlacement.FIRST)
            )
        )

        assertThat(minors(s)).isEqualTo(listOf(2858L, 2857L, 2857L, 2857L, 2857L, 2857L, 2857L))
        assertThat(minors(s).sum()).isEqualTo(20000L)
    }

    @Test
    fun INV_9_AE3_002_over_3_is_refused_AMOUNT_TOO_SMALL_FOR_COUNT_for_both_placements() {
        assertRefused(
            InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 0.02, count = 3, placement = InstallmentResidualPlacement.FIRST)
            )
        )
        assertRefused(
            InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 0.02, count = 3, placement = InstallmentResidualPlacement.LAST)
            )
        )
    }

    @Test
    fun INV_9_boundary_total_minor_equal_to_count_is_accepted_with_one_cent_each() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(equalRequest(total = 0.03, count = 3))
        )

        assertThat(minors(s)).isEqualTo(listOf(1L, 1L, 1L))
        assertThat(s.totalMinor).isEqualTo(3L)
    }

    @Test
    fun INV_9_boundary_total_minor_one_below_count_is_refused() {
        assertRefused(
            InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = 0.01, count = 2))
        )
    }

    @Test
    fun INV_9_splitEqually_direct_returns_null_for_invalid_count_or_too_small_total() {
        assertThat(
            InstallmentScheduleCalculator.splitEqually(100L, 0, InstallmentResidualPlacement.FIRST)
        ).isNull()
        assertThat(
            InstallmentScheduleCalculator.splitEqually(2L, 3, InstallmentResidualPlacement.LAST)
        ).isNull()
        assertThat(
            InstallmentScheduleCalculator.splitEqually(10000L, 3, InstallmentResidualPlacement.LAST)
        ).isEqualTo(listOf(3333L, 3333L, 3334L))
    }

    @Test
    fun INV_9_splitEqually_re_spread_shape_matches_AE21_500_00_over_5_remaining_gives_100_00_each() {
        // Spec 7.4 E2 / AE-21: re-spread applies the same rule to the remaining SCHEDULED sum (500.00 over 5).
        assertThat(
            InstallmentScheduleCalculator.splitEqually(50000L, 5, InstallmentResidualPlacement.FIRST)
        ).isEqualTo(List(5) { 10000L })
    }

    // ---------------------------------------------------------------------------------------
    // INV-9 conservation over several amounts and counts, residual FIRST and LAST
    // ---------------------------------------------------------------------------------------

    private data class ConservationCase(
        val total: Double,
        val count: Int,
        val totalMinor: Long,
        val baseMinor: Long,
        val residualEntryMinor: Long
    )

    private val conservationCases = listOf(
        ConservationCase(100.00, 3, 10000L, 3333L, 3334L),
        ConservationCase(200.00, 7, 20000L, 2857L, 2858L),
        ConservationCase(1000.00, 12, 100000L, 8333L, 8337L),
        ConservationCase(1234.57, 13, 123457L, 9496L, 9505L),
        ConservationCase(0.10, 10, 10L, 1L, 1L),
        ConservationCase(0.07, 7, 7L, 1L, 1L),
        ConservationCase(600.00, 12, 60000L, 5000L, 5000L),
        ConservationCase(999999.99, 480, 99999999L, 208333L, 208492L),
        ConservationCase(0.99, 2, 99L, 49L, 50L)
    )

    @Test
    fun INV_9_conservation_sum_of_entry_minor_units_equals_total_for_FIRST_placement() {
        for (case in conservationCases) {
            val s = schedule(
                InstallmentScheduleCalculator.generate(
                    equalRequest(case.total, case.count, InstallmentResidualPlacement.FIRST)
                )
            )
            assertThat(s.totalMinor).isEqualTo(case.totalMinor)
            assertThat(minors(s).sum()).isEqualTo(case.totalMinor)
            assertThat(s.entries).hasSize(case.count)
            assertThat(s.entries.first().amountMinor).isEqualTo(case.residualEntryMinor)
            assertThat(minors(s).drop(1).toSet()).containsExactly(case.baseMinor)
        }
    }

    @Test
    fun INV_9_conservation_sum_of_entry_minor_units_equals_total_for_LAST_placement() {
        for (case in conservationCases) {
            val s = schedule(
                InstallmentScheduleCalculator.generate(
                    equalRequest(case.total, case.count, InstallmentResidualPlacement.LAST)
                )
            )
            assertThat(s.totalMinor).isEqualTo(case.totalMinor)
            assertThat(minors(s).sum()).isEqualTo(case.totalMinor)
            assertThat(s.entries.last().amountMinor).isEqualTo(case.residualEntryMinor)
            assertThat(minors(s).dropLast(1).toSet()).containsExactly(case.baseMinor)
        }
    }

    @Test
    fun INV_9_conservation_residual_is_never_negative_and_below_one_extra_cent_per_entry() {
        // r = totalMinor - base * n satisfies 0 <= r < n, so the residual entry is base + r.
        for (case in conservationCases) {
            val s = schedule(
                InstallmentScheduleCalculator.generate(
                    equalRequest(case.total, case.count, InstallmentResidualPlacement.FIRST)
                )
            )
            val extra = case.residualEntryMinor - case.baseMinor
            assertThat(extra).isAtLeast(0L)
            assertThat(extra).isLessThan(case.count.toLong())
            assertThat(s.entries.all { it.amountMinor >= 1L }).isTrue()
        }
    }

    // ---------------------------------------------------------------------------------------
    // INV-10 / INV-11 / AE-18: fixed payment, standalone, rounding point
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_10_AE18_fixed_12_times_87_50_has_every_entry_equal_and_total_1050_00() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(
                    payment = 87.50,
                    count = 12,
                    firstDue = day(2027, 4, 10),
                    declaredOn = day(2027, 3, 12),
                    purchase = day(2027, 3, 10)
                )
            )
        )

        assertThat(minors(s)).isEqualTo(List(12) { 8750L })
        assertThat(s.totalMinor).isEqualTo(105000L)
    }

    @Test
    fun INV_10_fixed_payment_has_no_residual_for_either_placement() {
        val first = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 33.33, count = 7, placement = InstallmentResidualPlacement.FIRST)
            )
        )
        val last = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 33.33, count = 7, placement = InstallmentResidualPlacement.LAST)
            )
        )

        assertThat(minors(first)).isEqualTo(List(7) { 3333L })
        assertThat(minors(last)).isEqualTo(List(7) { 3333L })
        assertThat(first.totalMinor).isEqualTo(23331L)
        assertThat(last.totalMinor).isEqualTo(23331L)
    }

    @Test
    fun AE18_standalone_240_times_523_17_first_due_2027_05_01_ends_2047_04_01_total_125560_80() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(
                    payment = 523.17,
                    count = 240,
                    firstDue = day(2027, 5, 1),
                    declaredOn = day(2027, 4, 20),
                    purchase = null
                )
            )
        )

        assertThat(iso(s.lastDueEpochDay)).isEqualTo("2047-04-01")
        assertThat(iso(s.entries.last().dueEpochDay)).isEqualTo("2047-04-01")
        assertThat(s.entries).hasSize(240)
        assertThat(s.totalMinor).isEqualTo(12556080L)
        assertThat(BigDecimal.valueOf(s.totalMinor, 2).compareTo(BigDecimal("125560.80"))).isEqualTo(0)
        // At creation every entry is SCHEDULED, so the remaining scheduled payments are the whole total.
        val remaining = s.entries.filter { it.status == InstallmentEntryStatus.SCHEDULED }.sumOf { it.amountMinor }
        assertThat(remaining).isEqualTo(12556080L)
        assertThat(s.entries.map { it.amountMinor }.toSet()).containsExactly(52317L)
        assertThat(s.anchorDayOfMonth).isEqualTo(1)
    }

    @Test
    fun INV_11_inputs_with_more_than_two_decimals_are_normalised_HALF_UP_not_HALF_EVEN() {
        // 33.325 is 3332.5 minor units: HALF_UP gives 3333 (HALF_EVEN would give 3332).
        val s = schedule(
            InstallmentScheduleCalculator.generate(fixedRequest(payment = 33.325, count = 2))
        )

        assertThat(minors(s)).isEqualTo(listOf(3333L, 3333L))
        assertThat(s.totalMinor).isEqualTo(6666L)
    }

    @Test
    fun INV_11_equal_split_total_with_three_decimals_rounds_HALF_UP_before_the_split() {
        // 33.325 -> 3333 minor, 3333 / 2 = 1666 remainder 1, FIRST residual -> 1667, 1666.
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 33.325, count = 2, placement = InstallmentResidualPlacement.FIRST)
            )
        )

        assertThat(s.totalMinor).isEqualTo(3333L)
        assertThat(minors(s)).isEqualTo(listOf(1667L, 1666L))
    }

    @Test
    fun INV_11_a_total_that_rounds_to_zero_minor_units_is_INVALID_AMOUNT_not_zero_substitution() {
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = 0.004, count = 2))
        )
    }

    @Test
    fun INV_11_floating_point_representation_error_does_not_leak_into_minor_units() {
        // 0.1 + 0.2 style inputs: 1234.57 and 19.99 must convert exactly.
        val s = schedule(
            InstallmentScheduleCalculator.generate(fixedRequest(payment = 19.99, count = 3))
        )

        assertThat(minors(s)).isEqualTo(listOf(1999L, 1999L, 1999L))
        assertThat(s.totalMinor).isEqualTo(5997L)

        val big = schedule(
            InstallmentScheduleCalculator.generate(fixedRequest(payment = 1234.57, count = 2))
        )

        assertThat(minors(big)).isEqualTo(listOf(123457L, 123457L))
        assertThat(big.totalMinor).isEqualTo(246914L)
    }

    // ---------------------------------------------------------------------------------------
    // INV-18 / AE-4: month-end arithmetic
    // ---------------------------------------------------------------------------------------

    private fun dues(first: Long, count: Int): List<String> =
        schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(
                    payment = 10.00,
                    count = count,
                    firstDue = first,
                    declaredOn = first - 1L,
                    purchase = null
                )
            )
        ).entries.map { iso(it.dueEpochDay) }

    @Test
    fun INV_18_AE4_anchor_2027_01_31_six_entries_clamp_to_month_end_without_chaining() {
        assertThat(dues(day(2027, 1, 31), 6)).isEqualTo(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31", "2027-06-30")
        )
    }

    @Test
    fun INV_18_AE4_anchor_2028_01_31_leap_year_gives_29_february_then_31_march() {
        assertThat(dues(day(2028, 1, 31), 3)).isEqualTo(listOf("2028-01-31", "2028-02-29", "2028-03-31"))
    }

    @Test
    fun INV_18_AE4_leap_day_anchor_14_entries_entry_13_is_2029_02_28_and_entry_14_is_2029_03_29() {
        val all = dues(day(2028, 2, 29), 14)

        assertThat(all[0]).isEqualTo("2028-02-29")
        assertThat(all[1]).isEqualTo("2028-03-29")
        assertThat(all[12]).isEqualTo("2029-02-28")
        assertThat(all[13]).isEqualTo("2029-03-29")
    }

    @Test
    fun INV_18_AE4_anchor_day_is_the_day_of_month_of_the_first_due_date() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 10.00, count = 4, firstDue = day(2027, 1, 31), declaredOn = day(2027, 1, 1))
            )
        )

        assertThat(s.anchorDayOfMonth).isEqualTo(31)
        assertThat(s.entries.first().dueEpochDay).isEqualTo(day(2027, 1, 31))
    }

    @Test
    fun INV_18_dueDateEpochDay_is_computed_from_the_anchor_so_march_is_31_not_28() {
        // A chained implementation (28 Feb + 1 month) would give 28 Mar.
        assertThat(
            iso(InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 1, 31), 31, 2))
        ).isEqualTo("2027-03-31")
        assertThat(
            iso(InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 1, 31), 31, 3))
        ).isEqualTo("2027-04-30")
    }

    @Test
    fun INV_18_dueDateEpochDay_E4_style_new_anchor_from_a_later_base_month() {
        // Base month March 2027 with anchor 31 -> 31 Mar, then 30 Apr.
        assertThat(
            iso(InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 3, 15), 31, 0))
        ).isEqualTo("2027-03-31")
        assertThat(
            iso(InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 3, 15), 31, 1))
        ).isEqualTo("2027-04-30")
        // Anchor 1 across a year boundary.
        assertThat(
            iso(InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 11, 20), 1, 2))
        ).isEqualTo("2028-01-01")
    }

    @Test
    fun INV_18_dueDates_helper_lists_consecutive_months_with_entry_one_at_offset_zero() {
        assertThat(
            InstallmentScheduleCalculator.dueDates(day(2027, 1, 31), 31, 3).map(::iso)
        ).isEqualTo(listOf("2027-01-31", "2027-02-28", "2027-03-31"))
    }

    @Test
    fun INV_18_maximum_480_entries_are_strictly_ascending_and_end_2066_12_31_for_a_31st_anchor() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(
                    payment = 1.00,
                    count = 480,
                    firstDue = day(2027, 1, 31),
                    declaredOn = day(2027, 1, 1)
                )
            )
        )

        val days = s.entries.map { it.dueEpochDay }
        assertThat(days.zipWithNext().all { (a, b) -> b > a }).isTrue()
        assertThat(iso(days.last())).isEqualTo("2066-12-31")
        assertThat(days.all { LocalDate.ofEpochDay(it).dayOfMonth <= 31 }).isTrue()
    }

    @Test
    fun INV_18_dueDateEpochDay_rejects_out_of_range_arguments() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 1, 31), 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 1, 31), 32, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.dueDateEpochDay(day(2027, 1, 31), 31, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.dueDateEpochDay(day(1969, 12, 31), 31, 0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // INV-18 / AE-19: noon-local stamp, time zones and DST
    // ---------------------------------------------------------------------------------------

    @Test
    fun INV_18_AE19_entry_due_2027_03_28_is_stamped_noon_Athens_and_lands_in_the_march_bucket() {
        val stamp = InstallmentScheduleCalculator.recordTimestampMillis(day(2027, 3, 28), athens)

        val local = Instant.ofEpochMilli(stamp).atZone(athens)
        assertThat(local.toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 28))
        assertThat(local.hour).isEqualTo(12)
        assertThat(local.minute).isEqualTo(0)
        assertThat(local.second).isEqualTo(0)
        assertThat(local.nano).isEqualTo(0)
        // EU DST starts on this very day: 12:00 Athens is EEST (UTC+3) = 09:00Z.
        assertThat(Instant.ofEpochMilli(stamp)).isEqualTo(Instant.parse("2027-03-28T09:00:00Z"))
        assertThat(local.month.value).isEqualTo(3)
    }

    @Test
    fun INV_18_AE19_entry_due_2027_04_01_recorded_noon_Athens_is_in_april_when_viewed_in_New_York() {
        val stamp = InstallmentScheduleCalculator.recordTimestampMillis(day(2027, 4, 1), athens)

        assertThat(Instant.ofEpochMilli(stamp)).isEqualTo(Instant.parse("2027-04-01T09:00:00Z"))
        val inNewYork = Instant.ofEpochMilli(stamp).atZone(newYork)
        assertThat(inNewYork.toLocalDate()).isEqualTo(LocalDate.of(2027, 4, 1))
        assertThat(inNewYork.month.value).isEqualTo(4)
        assertThat(
            InstallmentScheduleCalculator.civilEpochDay(stamp, newYork)
        ).isEqualTo(day(2027, 4, 1))
    }

    @Test
    fun INV_18_AE19_counter_example_start_of_day_athens_stamp_would_fall_in_march_in_london_noon_does_not() {
        val startOfDayAthens = ZonedDateTime.of(2027, 4, 1, 0, 0, 0, 0, athens).toInstant().toEpochMilli()
        val noonAthens = InstallmentScheduleCalculator.recordTimestampMillis(day(2027, 4, 1), athens)

        // The bug this guards against: a midnight stamp lands on 31 March in London.
        assertThat(InstallmentScheduleCalculator.civilEpochDay(startOfDayAthens, london)).isEqualTo(day(2027, 3, 31))
        // The noon stamp stays on 1 April in London.
        assertThat(InstallmentScheduleCalculator.civilEpochDay(noonAthens, london)).isEqualTo(day(2027, 4, 1))
    }

    @Test
    fun INV_18_noon_stamp_keeps_the_due_day_in_zones_within_eleven_hours_of_the_record_zone() {
        val due = day(2027, 4, 1)
        val stamp = InstallmentScheduleCalculator.recordTimestampMillis(due, athens)

        for (zoneId in listOf("America/New_York", "America/Los_Angeles", "Europe/London", "Asia/Tokyo", "Pacific/Auckland", "UTC")) {
            assertThat(InstallmentScheduleCalculator.civilEpochDay(stamp, ZoneId.of(zoneId))).isEqualTo(due)
        }
    }

    @Test
    fun INV_18_a_late_catch_up_stamp_uses_the_due_date_not_the_run_date() {
        // recordTimestampMillis takes only the due day and the zone, so a run three days later
        // cannot move the expense: the same inputs give the same instant.
        val due = day(2027, 3, 28)
        val first = InstallmentScheduleCalculator.recordTimestampMillis(due, athens)
        val second = InstallmentScheduleCalculator.recordTimestampMillis(due, athens)

        assertThat(second).isEqualTo(first)
        assertThat(InstallmentScheduleCalculator.civilEpochDay(first, athens)).isEqualTo(due)
    }

    @Test
    fun INV_18_noon_stamp_depends_on_the_supplied_zone_only() {
        val due = day(2027, 7, 15)

        val athensStamp = InstallmentScheduleCalculator.recordTimestampMillis(due, athens)
        val newYorkStamp = InstallmentScheduleCalculator.recordTimestampMillis(due, newYork)

        assertThat(Instant.ofEpochMilli(athensStamp)).isEqualTo(Instant.parse("2027-07-15T09:00:00Z"))
        assertThat(Instant.ofEpochMilli(newYorkStamp)).isEqualTo(Instant.parse("2027-07-15T16:00:00Z"))
    }

    @Test
    fun INV_18_recordTimestampMillis_rejects_a_due_day_outside_the_supported_range() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.recordTimestampMillis(day(1969, 12, 31), athens)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallmentScheduleCalculator.recordTimestampMillis(day(2201, 1, 1), athens)
        }
    }

    // ---------------------------------------------------------------------------------------
    // AE-22 / spec 7.0: plan-input refusals
    // ---------------------------------------------------------------------------------------

    @Test
    fun AE22_N_equal_1_is_refused_INVALID_COUNT() {
        assertRefused(
            InstallmentRefusalCode.INVALID_COUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = 100.00, count = 1))
        )
    }

    @Test
    fun AE22_count_boundaries_2_and_480_are_accepted_0_negative_and_481_are_refused() {
        assertThat(
            schedule(InstallmentScheduleCalculator.generate(equalRequest(total = 100.00, count = 2))).entries
        ).hasSize(2)
        assertThat(
            schedule(InstallmentScheduleCalculator.generate(equalRequest(total = 1000.00, count = 480))).entries
        ).hasSize(480)

        for (bad in listOf(0, -1, 481, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertRefused(
                InstallmentRefusalCode.INVALID_COUNT,
                InstallmentScheduleCalculator.generate(equalRequest(total = 1000.00, count = bad))
            )
        }
    }

    @Test
    fun AE22_CHF_is_refused_UNSUPPORTED_CURRENCY() {
        assertRefused(
            InstallmentRefusalCode.UNSUPPORTED_CURRENCY,
            InstallmentScheduleCalculator.generate(equalRequest(total = 100.00, count = 3, currency = "CHF"))
        )
    }

    @Test
    fun AE22_EUR_and_USD_are_accepted_but_lowercase_empty_and_look_alike_codes_are_refused() {
        assertThat(
            schedule(InstallmentScheduleCalculator.generate(equalRequest(100.00, 3, currency = "USD"))).currency
        ).isEqualTo(InstallmentCurrency.USD)
        assertThat(
            schedule(InstallmentScheduleCalculator.generate(equalRequest(100.00, 3, currency = "EUR"))).currency
        ).isEqualTo(InstallmentCurrency.EUR)

        for (bad in listOf("eur", "Eur", "", " EUR", "EUR ", "GBP", "JPY", "XXX")) {
            assertRefused(
                InstallmentRefusalCode.UNSUPPORTED_CURRENCY,
                InstallmentScheduleCalculator.generate(equalRequest(100.00, 3, currency = bad))
            )
        }
    }

    @Test
    fun AE22_first_due_before_the_purchase_date_is_refused_FIRST_DUE_BEFORE_PURCHASE() {
        assertRefused(
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE,
            InstallmentScheduleCalculator.generate(
                equalRequest(
                    total = 600.00,
                    count = 12,
                    purchase = day(2027, 3, 10),
                    firstDue = day(2027, 3, 9),
                    declaredOn = day(2027, 3, 12)
                )
            )
        )
    }

    @Test
    fun AE22_first_due_equal_to_the_purchase_date_is_accepted_boundary() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                equalRequest(
                    total = 600.00,
                    count = 12,
                    purchase = day(2027, 3, 10),
                    firstDue = day(2027, 3, 10),
                    declaredOn = day(2027, 3, 12)
                )
            )
        )

        assertThat(s.entries.first().dueEpochDay).isEqualTo(day(2027, 3, 10))
    }

    @Test
    fun AE22_standalone_plan_has_no_purchase_so_no_first_due_check_applies() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 50.00, count = 3, firstDue = day(2020, 1, 15), declaredOn = day(2027, 1, 1), purchase = null)
            )
        )

        assertThat(iso(s.entries.first().dueEpochDay)).isEqualTo("2020-01-15")
    }

    @Test
    fun AE22_zero_negative_NaN_infinite_and_missing_amounts_are_refused_INVALID_AMOUNT() {
        val badTotals = listOf(0.0, -0.0, -100.00, -0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (bad in badTotals) {
            assertRefused(
                InstallmentRefusalCode.INVALID_AMOUNT,
                InstallmentScheduleCalculator.generate(equalRequest(total = bad, count = 3))
            )
            assertRefused(
                InstallmentRefusalCode.INVALID_AMOUNT,
                InstallmentScheduleCalculator.generate(fixedRequest(payment = bad, count = 3))
            )
        }
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = null, count = 3))
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(fixedRequest(payment = null, count = 3))
        )
    }

    @Test
    fun AE22_the_amount_that_matters_is_the_one_of_the_chosen_split_method() {
        // EQUAL_SPLIT ignores fixedPaymentAmount, FIXED_PAYMENT ignores declaredTotal.
        val equalWithOnlyFixedSet = equalRequest(total = null, count = 3).copy(fixedPaymentAmount = 50.00)
        val fixedWithOnlyTotalSet = fixedRequest(payment = null, count = 3).copy(declaredTotal = 150.00)

        assertRefused(InstallmentRefusalCode.INVALID_AMOUNT, InstallmentScheduleCalculator.generate(equalWithOnlyFixedSet))
        assertRefused(InstallmentRefusalCode.INVALID_AMOUNT, InstallmentScheduleCalculator.generate(fixedWithOnlyTotalSet))
    }

    @Test
    fun AE22_smallest_positive_amount_one_cent_is_accepted_for_a_fixed_payment() {
        val s = schedule(InstallmentScheduleCalculator.generate(fixedRequest(payment = 0.01, count = 2)))

        assertThat(minors(s)).isEqualTo(listOf(1L, 1L))
        assertThat(s.totalMinor).isEqualTo(2L)
    }

    @Test
    fun AE22_validation_order_follows_spec_7_0_count_then_amount_then_currency_then_dates_then_size_then_k() {
        // count beats amount and currency
        assertRefused(
            InstallmentRefusalCode.INVALID_COUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = -1.0, count = 1, currency = "CHF"))
        )
        // amount beats currency
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = -1.0, count = 3, currency = "CHF"))
        )
        // currency beats first-due-before-purchase
        assertRefused(
            InstallmentRefusalCode.UNSUPPORTED_CURRENCY,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, currency = "CHF", purchase = day(2027, 3, 10), firstDue = day(2027, 3, 1))
            )
        )
        // the date rule (last generated due date out of range) beats first-due-before-purchase,
        // amount-too-small and invalid k
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 2, purchase = day(2200, 12, 20), firstDue = day(2200, 12, 15))
            )
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 0.01, count = 2, firstDue = day(2200, 12, 15))
            )
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 2, firstDue = day(2200, 12, 15), paidBefore = 99)
            )
        )
        // first-due-before-purchase beats amount-too-small
        assertRefused(
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 0.02, count = 3, purchase = day(2027, 3, 10), firstDue = day(2027, 3, 1))
            )
        )
        // amount-too-small beats invalid k
        assertRefused(
            InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = 0.02, count = 3, paidBefore = 99))
        )
    }

    @Test
    fun AE22_a_refusal_carries_only_the_bounded_code_and_repeated_calls_are_identical() {
        val request = equalRequest(total = 100.00, count = 1)

        assertThat(InstallmentScheduleCalculator.generate(request))
            .isEqualTo(InstallmentScheduleCalculator.generate(request))
        assertThat(InstallmentScheduleCalculator.generate(request))
            .isEqualTo(ScheduleGenerationResult.Refused(InstallmentRefusalCode.INVALID_COUNT))
    }

    // ---------------------------------------------------------------------------------------
    // AE-26: declared after payments already made (INV-32)
    // ---------------------------------------------------------------------------------------

    private fun ae26Request(paidBefore: Int, declaredOn: Long = day(2027, 6, 20)) = equalRequest(
        total = 1200.00,
        count = 12,
        placement = InstallmentResidualPlacement.FIRST,
        firstDue = day(2027, 2, 5),
        declaredOn = declaredOn,
        purchase = day(2027, 1, 5),
        paidBefore = paidBefore
    )

    @Test
    fun INV_32_AE26_twelve_entries_of_100_due_2027_02_05_to_2028_01_05_entries_1_to_4_paid_before_tracking() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 4)))

        assertThat(s.entries).hasSize(12)
        assertThat(minors(s)).isEqualTo(List(12) { 10000L })
        assertThat(s.totalMinor).isEqualTo(120000L)
        assertThat(iso(s.entries.first().dueEpochDay)).isEqualTo("2027-02-05")
        assertThat(iso(s.entries.last().dueEpochDay)).isEqualTo("2028-01-05")
        assertThat(s.entries.take(4).map { it.status }.toSet())
            .containsExactly(InstallmentEntryStatus.PAID_BEFORE_TRACKING)
        assertThat(s.entries.drop(4).map { it.status }.toSet())
            .containsExactly(InstallmentEntryStatus.SCHEDULED)
        assertThat(s.scheduledCount).isEqualTo(8)
        assertThat(s.completesImmediately).isFalse()
    }

    @Test
    fun INV_32_AE26_remaining_scheduled_payments_are_800_00_over_entries_5_to_12() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 4)))

        val remaining = s.entries.filter { it.status == InstallmentEntryStatus.SCHEDULED }
        assertThat(remaining.map { it.seq }).isEqualTo((5..12).toList())
        assertThat(remaining.sumOf { it.amountMinor }).isEqualTo(80000L)
        // Entries 6..12 (not the overdue entry 5) would be projected: 700.00.
        assertThat(remaining.filter { it.dueEpochDay >= day(2027, 6, 20) }.sumOf { it.amountMinor }).isEqualTo(70000L)
    }

    @Test
    fun INV_32_AE26_P_is_5_on_2027_06_20_so_entries_1_to_5_are_due_before_the_declaration_date() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 0)))

        assertThat(s.dueBeforeDeclaredCount).isEqualTo(5)
        assertThat(iso(s.entries[4].dueEpochDay)).isEqualTo("2027-06-05")
    }

    @Test
    fun INV_32_AE26_k_equal_to_P_is_accepted_boundary() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 5)))

        assertThat(s.entries.take(5).map { it.status }.toSet())
            .containsExactly(InstallmentEntryStatus.PAID_BEFORE_TRACKING)
        assertThat(s.scheduledCount).isEqualTo(7)
    }

    @Test
    fun INV_32_AE26_k_6_is_refused_INVALID_PAID_BEFORE_TRACKING_because_P_is_5() {
        assertRefused(
            InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
            InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 6))
        )
    }

    @Test
    fun INV_32_AE26_negative_k_is_refused_INVALID_PAID_BEFORE_TRACKING() {
        assertRefused(
            InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
            InstallmentScheduleCalculator.generate(ae26Request(paidBefore = -1))
        )
    }

    @Test
    fun INV_32_AE26_strictness_declared_on_2027_06_05_gives_P_4_and_k_5_is_refused() {
        val declaredOnDueDate = day(2027, 6, 5)

        val withZero = schedule(InstallmentScheduleCalculator.generate(ae26Request(0, declaredOnDueDate)))
        assertThat(withZero.dueBeforeDeclaredCount).isEqualTo(4)

        assertRefused(
            InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
            InstallmentScheduleCalculator.generate(ae26Request(5, declaredOnDueDate))
        )
        val withFour = schedule(InstallmentScheduleCalculator.generate(ae26Request(4, declaredOnDueDate)))
        assertThat(withFour.scheduledCount).isEqualTo(8)
        // Entry 5 (due 2027-06-05) stays SCHEDULED: it is not due before the declaration date.
        assertThat(withFour.entries[4].status).isEqualTo(InstallmentEntryStatus.SCHEDULED)
    }

    @Test
    fun INV_32_k_zero_keeps_every_entry_SCHEDULED() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 0)))

        assertThat(s.entries).hasSize(12)
        assertThat(s.entries.map { it.status }.toSet()).containsExactly(InstallmentEntryStatus.SCHEDULED)
        assertThat(s.scheduledCount).isEqualTo(12)
    }

    @Test
    fun INV_32_k_three_yields_only_PAID_BEFORE_TRACKING_and_SCHEDULED_entries() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae26Request(paidBefore = 3)))

        assertThat(s.entries.map { it.status }.toSet()).containsExactly(
            InstallmentEntryStatus.PAID_BEFORE_TRACKING,
            InstallmentEntryStatus.SCHEDULED
        )
    }

    @Test
    fun INV_32_P_is_zero_when_the_first_due_date_is_on_or_after_the_declaration_date_so_k_1_is_refused() {
        val request = equalRequest(total = 600.00, count = 12, declaredOn = day(2027, 4, 10), paidBefore = 0)
        assertThat(schedule(InstallmentScheduleCalculator.generate(request)).dueBeforeDeclaredCount).isEqualTo(0)

        assertRefused(
            InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
            InstallmentScheduleCalculator.generate(request.copy(paidBeforeTrackingCount = 1))
        )
    }

    // ---------------------------------------------------------------------------------------
    // AE-27 (calculator part): k leaves no SCHEDULED entry, the plan completes immediately
    // ---------------------------------------------------------------------------------------

    private fun ae27Request(paidBefore: Int) = equalRequest(
        total = 300.00,
        count = 3,
        firstDue = day(2027, 2, 5),
        declaredOn = day(2027, 6, 20),
        purchase = day(2027, 1, 5),
        paidBefore = paidBefore
    )

    @Test
    fun INV_21_AE27_k_3_of_3_leaves_no_scheduled_entry_and_completes_immediately() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae27Request(paidBefore = 3)))

        assertThat(s.dueBeforeDeclaredCount).isEqualTo(3)
        assertThat(s.scheduledCount).isEqualTo(0)
        assertThat(s.completesImmediately).isTrue()
        assertThat(s.entries.map { it.status }.toSet()).containsExactly(InstallmentEntryStatus.PAID_BEFORE_TRACKING)
        assertThat(minors(s)).isEqualTo(listOf(10000L, 10000L, 10000L))
        // History start and end: 2027-02-05 to 2027-04-05.
        assertThat(iso(s.entries.first().dueEpochDay)).isEqualTo("2027-02-05")
        assertThat(iso(s.lastDueEpochDay)).isEqualTo("2027-04-05")
    }

    @Test
    fun INV_21_AE27_k_2_of_3_leaves_one_scheduled_entry_and_does_not_complete_immediately() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae27Request(paidBefore = 2)))

        assertThat(s.scheduledCount).isEqualTo(1)
        assertThat(s.completesImmediately).isFalse()
        assertThat(s.entries.last().status).isEqualTo(InstallmentEntryStatus.SCHEDULED)
    }

    @Test
    fun INV_21_AE27_k_4_of_3_is_refused_INVALID_PAID_BEFORE_TRACKING() {
        assertRefused(
            InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING,
            InstallmentScheduleCalculator.generate(ae27Request(paidBefore = 4))
        )
    }

    @Test
    fun INV_21_AE27_k_zero_never_completes_immediately() {
        val s = schedule(InstallmentScheduleCalculator.generate(ae27Request(paidBefore = 0)))

        assertThat(s.completesImmediately).isFalse()
        assertThat(s.scheduledCount).isEqualTo(3)
    }

    // ---------------------------------------------------------------------------------------
    // Civil-date helper and supported range
    // ---------------------------------------------------------------------------------------

    @Test
    fun civilEpochDay_uses_the_supplied_zone_and_never_a_default_zone() {
        val instant = Instant.parse("2027-03-31T22:30:00Z").toEpochMilli()

        assertThat(InstallmentScheduleCalculator.civilEpochDay(instant, ZoneId.of("UTC"))).isEqualTo(day(2027, 3, 31))
        assertThat(InstallmentScheduleCalculator.civilEpochDay(instant, athens)).isEqualTo(day(2027, 4, 1))
        assertThat(InstallmentScheduleCalculator.civilEpochDay(instant, newYork)).isEqualTo(day(2027, 3, 31))
    }

    @Test
    fun isSupportedDay_covers_1970_01_01_through_2200_12_31_inclusive() {
        assertThat(InstallmentScheduleCalculator.isSupportedDay(day(1970, 1, 1))).isTrue()
        assertThat(InstallmentScheduleCalculator.isSupportedDay(day(2200, 12, 31))).isTrue()
        assertThat(InstallmentScheduleCalculator.isSupportedDay(day(1969, 12, 31))).isFalse()
        assertThat(InstallmentScheduleCalculator.isSupportedDay(day(2201, 1, 1))).isFalse()
    }

    // ---------------------------------------------------------------------------------------
    // Implementer interpretations pinned for review (S5 handoff (1) and (2)); not spec AEs.
    // ---------------------------------------------------------------------------------------

    @Test
    fun interpretation_1_dates_outside_1970_to_2200_are_refused_INVALID_DATE() {
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, firstDue = day(2201, 1, 1), declaredOn = day(2027, 3, 12))
            )
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, firstDue = day(2027, 4, 10), declaredOn = day(1969, 12, 31))
            )
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 3, purchase = day(1969, 12, 31))
            )
        )
    }

    @Test
    fun interpretation_1_a_schedule_whose_last_due_date_is_after_2200_12_31_is_refused_INVALID_DATE() {
        // First due 2200-12-15 is inside the range, but entry 2 falls on 2201-01-15.
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 10.00, count = 2, firstDue = day(2200, 12, 15))
            )
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_DATE,
            InstallmentScheduleCalculator.generate(
                equalRequest(total = 100.00, count = 24, firstDue = day(2199, 12, 31))
            )
        )
    }

    @Test
    fun interpretation_1_control_a_schedule_whose_last_due_date_is_inside_the_range_is_accepted() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 10.00, count = 2, firstDue = day(2200, 11, 15))
            )
        )

        assertThat(iso(s.lastDueEpochDay)).isEqualTo("2200-12-15")
        assertThat(InstallmentScheduleCalculator.isSupportedDay(s.lastDueEpochDay)).isTrue()
    }

    @Test
    fun interpretation_1_the_supported_range_edges_themselves_are_accepted() {
        val s = schedule(
            InstallmentScheduleCalculator.generate(
                fixedRequest(payment = 1.00, count = 2, firstDue = day(1970, 1, 1), declaredOn = day(1970, 1, 1))
            )
        )

        assertThat(iso(s.entries.first().dueEpochDay)).isEqualTo("1970-01-01")
    }

    @Test
    fun interpretation_2_amounts_of_one_billion_or_more_are_refused_INVALID_AMOUNT_below_is_accepted() {
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(fixedRequest(payment = 1_000_000_000.0, count = 2))
        )
        assertRefused(
            InstallmentRefusalCode.INVALID_AMOUNT,
            InstallmentScheduleCalculator.generate(equalRequest(total = 1_000_000_000.0, count = 2))
        )
        // Spec Q6: any 2 dp value below 10^9 round-trips exactly.
        val s = schedule(InstallmentScheduleCalculator.generate(fixedRequest(payment = 999_999_999.99, count = 2)))
        assertThat(s.totalMinor).isEqualTo(199_999_999_998L)
    }
}
