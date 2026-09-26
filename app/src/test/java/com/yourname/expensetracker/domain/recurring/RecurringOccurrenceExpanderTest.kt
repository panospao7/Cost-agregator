package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * RP-04 slice A3: month-end anchor semantics tests for
 * [RecurringOccurrenceExpander] (plan section P4-005, decision D7).
 *
 * Contract under test:
 * - MONTHLY/QUARTERLY/SEMI_ANNUALLY expansion advances every period from the
 *   FIXED anchor day derived once from [RecurringOccurrenceExpander.ExpandRequest.anchorDate],
 *   clamped to each target month's length (Jan 31 -> Feb 28/29 -> Mar 31).
 * - No cumulative drift across leap-year and short-month boundaries.
 * - Expansion from an already-drifted persisted nextDate does NOT claim original
 *   anchor restoration (Option A, no-schema).
 * - Date boundaries stay half-open [startDate, endDate) and date-only (local midnight).
 */
class RecurringOccurrenceExpanderTest {

    private val expander = RecurringOccurrenceExpander()

    private fun dayOf(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun localDayOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun request(
        frequency: RecurrenceFrequency,
        anchorDate: Long,
        startDate: Long,
        endDate: Long,
        sourceId: Long = 1L
    ) = RecurringOccurrenceExpander.ExpandRequest(
        merchant = "Test Merchant",
        amount = 10.0,
        currency = "EUR",
        frequency = frequency,
        categoryId = null,
        startDate = startDate,
        endDate = endDate,
        anchorDate = anchorDate,
        sourceType = "RECURRING_RULE",
        sourceId = sourceId
    )

    // ── Monthly Jan-31 across leap year ─────────────────────────────────────

    @Test
    fun `monthlyJan31UsesFixedExpansionAnchorAcrossLeapYear`() {
        // Anchor: 2027-01-31 (non-leap year 2027 followed by non-leap 2028 start;
        // Feb 2027 has 28 days — exercises clamping without the leap bailout).
        val anchor = dayOf(2027, 1, 31)
        val start = dayOf(2027, 1, 1)
        val end = dayOf(2027, 5, 1)

        val result = expander.expand(request(RecurrenceFrequency.MONTHLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 4, 30)
            ),
            dueDays
        )
    }

    @Test
    fun `monthly jan31 anchor recovers day 31 after February in leap year`() {
        // Leap year: Feb 2028 has 29 days; anchor 31 must clamp to 29 then restore to 31.
        val anchor = dayOf(2028, 1, 31)
        val start = dayOf(2028, 1, 1)
        val end = dayOf(2028, 4, 1)

        val result = expander.expand(request(RecurrenceFrequency.MONTHLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2028, 1, 31),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 31)
            ),
            dueDays
        )
    }

    // ── Quarterly Jan-31 clamped each target month, no cumulative drift ─────

    @Test
    fun `quarterlyJan31ClampsEachTargetMonthWithoutCumulativeDrift`() {
        val anchor = dayOf(2027, 1, 31)
        val start = dayOf(2027, 1, 1)
        val end = dayOf(2028, 1, 1)

        val result = expander.expand(request(RecurrenceFrequency.QUARTERLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        // Each target month is re-derived from anchor day 31: Q2 (Apr) has 30 days,
        // and Jul/Oct recover to 31 — the Feb clamp never propagates.
        assertEquals(
            listOf(
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 4, 30),
                LocalDate.of(2027, 7, 31),
                LocalDate.of(2027, 10, 31)
            ),
            dueDays
        )
    }

    // ── Semi-annual no drift ────────────────────────────────────────────────

    @Test
    fun `semiAnnualAnchorDoesNotDrift`() {
        // Anchor Jan 31, 2026; two semi-annual steps cross Feb (28 days, 2026 is
        // not a leap year) and Aug (31 days). The Aug step must land back on 31.
        val anchor = dayOf(2026, 1, 31)
        val start = dayOf(2026, 1, 1)
        val end = dayOf(2027, 2, 1)

        val result = expander.expand(request(RecurrenceFrequency.SEMI_ANNUALLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2027, 1, 31)
            ),
            dueDays
        )
    }

    @Test
    fun `semi annual anchor landing in February clamps without downstream drift`() {
        // Anchor Aug 31, 2026 -> Feb 2027 (28 days) -> Aug 2027 (31 days).
        val anchor = dayOf(2026, 8, 31)
        val start = dayOf(2026, 8, 1)
        val end = dayOf(2027, 9, 1)

        val result = expander.expand(request(RecurrenceFrequency.SEMI_ANNUALLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 8, 31)
            ),
            dueDays
        )
    }

    // ── Persisted-drift semantics (no anchor restoration) ───────────────────

    @Test
    fun `expansionFromPersistedDriftedNextDateDoesNotClaimOriginalAnchorRestoration`() {
        // A rule historically created on Jan 31 drifted to Feb 28 under the old
        // addMonths path; the persisted nextDate is now Feb 28, 2027. Option A
        // derives anchor day 28 — expansion must continue on the 28th (28 -> Mar 28),
        // NOT claim restoration to day 31.
        val driftedAnchor = dayOf(2027, 2, 28)
        val start = dayOf(2027, 2, 1)
        val end = dayOf(2027, 5, 1)

        val result = expander.expand(request(RecurrenceFrequency.MONTHLY, driftedAnchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 28),
                LocalDate.of(2027, 4, 28)
            ),
            dueDays
        )
        // Anchor restoration would have produced the 31st — explicitly forbid it.
        assertTrue(dueDays.none { it.dayOfMonth == 31 })
    }

    // ── Boundary: half-open range and date-only due dates ───────────────────

    @Test
    fun `dateBoundaryIsHalfOpenAndDateOnly`() {
        // Anchor on the range start: inclusive. An occurrence landing exactly on
        // endDate: exclusive. All due dates must be local midnight (date-only).
        val anchor = dayOf(2027, 1, 31)
        val start = dayOf(2027, 1, 31)
        val end = dayOf(2027, 3, 31) // the Mar step would land exactly here

        val result = expander.expand(request(RecurrenceFrequency.MONTHLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        // Jan 31 (== start, inclusive) in; Mar 31 (== end, exclusive) out. Feb clamp
        // lands Feb 28 which is inside.
        assertEquals(
            listOf(
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28)
            ),
            dueDays
        )

        // Date-only: every due date is local midnight.
        result.forEach { candidate ->
            val zoned = Instant.ofEpochMilli(candidate.dueDate).atZone(ZoneId.systemDefault())
            assertEquals(0, zoned.hour)
            assertEquals(0, zoned.minute)
            assertEquals(0, zoned.second)
            assertEquals(0, zoned.nano)
        }
    }

    @Test
    fun `anchor before range is advanced without emitting pre-range occurrences`() {
        // Anchor Dec 31, 2026, range starts Feb 1: the Dec/Jan occurrences must be
        // skipped (not returned), and the Feb occurrence must be the first emitted.
        val anchor = dayOf(2026, 12, 31)
        val start = dayOf(2027, 2, 1)
        val end = dayOf(2027, 4, 1)

        val result = expander.expand(request(RecurrenceFrequency.MONTHLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 31)
            ),
            dueDays
        )
    }

    // ── Quarterly interval agreement with RecurrenceCalculator ──────────────

    @Test
    fun `monthAndQuarterIntervalsMatchOccurrenceExpander`() {
        // Expansion steps and RecurrenceCalculator.addFrequencyInterval must agree
        // step-for-step for month-based frequencies when the calculator walk pins
        // the SAME fixed anchor day as the expansion operation (RP-04 P4-005).
        // ANNUALLY included (RP-04 A3 reviewer fix): both paths now use the
        // fixed-anchor 12-month advance, so a Feb-29 annual anchor clamps and
        // recovers identically in expander and calculator.
        for (frequency in listOf(
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.QUARTERLY,
            RecurrenceFrequency.SEMI_ANNUALLY,
            RecurrenceFrequency.ANNUALLY
        )) {
            val anchor = dayOf(2027, 1, 31)
            val anchorDayOfMonth = TimePeriodUtils.getDayOfMonth(TimePeriodUtils.getStartOfDay(anchor))
            val steps = 4
            val end = dayOf(2032, 12, 31)

            val expanded = expander.expand(
                request(frequency, anchor, startDate = dayOf(2027, 1, 1), endDate = end, sourceId = 7L)
            )

            // Walk the calculator independently from the same anchor, passing the
            // same fixed anchor day every step so clamping never compounds.
            val calculatorDates = mutableListOf(anchor)
            var cursor = anchor
            repeat(steps) {
                cursor = com.yourname.expensetracker.domain.logic.RecurrenceCalculator
                    .addFrequencyInterval(cursor, frequency, anchorDayOfMonth = anchorDayOfMonth)
                calculatorDates.add(cursor)
            }

            val expanderDates = expanded.map { it.dueDate }
            // The first `steps + 1` expansion dates must equal the calculator walk.
            assertEquals(
                "Frequency ${frequency.name}: expander and calculator disagree",
                calculatorDates,
                expanderDates.take(calculatorDates.size)
            )
            assertTrue(expanderDates.size >= calculatorDates.size)
        }
    }

    @Test
    fun `annuallyFeb29AnchorClampsAndRecoversAcrossLeapCycle`() {
        // RP-04 A3 reviewer fix: the annual path uses the fixed-anchor 12-month
        // advance — a Feb-29 anchor clamps to Feb 28 in non-leap years and
        // recovers to Feb 29 in leap years (addYears would permanently drift
        // down to Feb 28). Anchor 2024-02-29 (leap): 2025-02-28, 2026-02-28,
        // 2027-02-28, 2028-02-29 (leap recovery).
        val anchor = dayOf(2024, 2, 29)
        val start = dayOf(2024, 2, 1)
        val end = dayOf(2029, 3, 1)

        val result = expander.expand(request(RecurrenceFrequency.ANNUALLY, anchor, start, end))

        val dueDays = result.map { localDayOf(it.dueDate) }
        assertEquals(
            listOf(
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2029, 2, 28)
            ),
            dueDays
        )
    }

    @Test
    fun `detectedPatternRollForwardAgreesWithExpansionForMonthEndAnchors`() {
        // RP-04 A3 reviewer fix: the engine/assembler roll-forward loops pin the
        // anchor day ONCE from the date entering the loop, so a detected pattern's
        // rolled-forward next date must equal the expansion chain from the same
        // anchor. This pins the roll-forward vs expansion agreement for Jan-31
        // monthly/quarterly — per-step re-derivation (the pre-fix behavior) would
        // produce Jul 30 instead of Jul 31 for quarterly.
        for (frequency in listOf(
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.QUARTERLY
        )) {
            val anchor = dayOf(2027, 1, 31)
            val anchorDayOfMonth = TimePeriodUtils.getDayOfMonth(TimePeriodUtils.getStartOfDay(anchor))
            val today = dayOf(2028, 6, 15)

            // Expansion chain from the anchor (same semantics as roll-forward).
            val expanded = expander.expand(
                request(frequency, anchor, startDate = dayOf(2027, 1, 1), endDate = dayOf(2028, 12, 31), sourceId = 7L)
            )
            // Roll-forward stops at the FIRST expanded candidate >= today; taking
            // .last here would compare against the far end of the window and
            // guarantee a mismatch for every frequency.
            val expectedRollForward = expanded.first { it.dueDate >= today }.dueDate

            // Detected-pattern roll-forward: walk with the pinned anchor until >= today.
            var rolled = anchor
            var guard = 0
            while (rolled < today) {
                val candidate = com.yourname.expensetracker.domain.logic.RecurrenceCalculator
                    .addFrequencyInterval(rolled, frequency, anchorDayOfMonth = anchorDayOfMonth)
                if (candidate == rolled) break
                rolled = candidate
                if (++guard > 1000) break
            }

            assertEquals(
                "Frequency ${frequency.name}: roll-forward disagrees with expansion",
                expectedRollForward,
                rolled
            )
        }
    }
}
