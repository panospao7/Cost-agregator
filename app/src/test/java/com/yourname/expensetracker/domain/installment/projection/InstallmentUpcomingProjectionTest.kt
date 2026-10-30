package com.yourname.expensetracker.domain.installment.projection

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * S8b-3: [InstallmentUpcomingProjection], the 31-day per-currency list behind the Home installment
 * rows. The fake port runs the REAL projection rule, so the INV-15 bounds are the product's. Rows stay
 * in plan currency (INV-16): they are never converted and never summed across currencies. Synthetic
 * data only.
 */
class InstallmentUpcomingProjectionTest {

    private val today = LocalDate.parse("2026-04-01")
    private val todayDay = today.toEpochDay()

    private fun day(offset: Long): Long = todayDay + offset

    private fun entry(
        id: Long,
        dueEpochDay: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        minor: Long = 5_000L,
        seq: Int = id.toInt()
    ) = ProjectionEntryInput(
        entryId = id,
        seq = seq,
        kind = InstallmentEntryKind.REGULAR,
        status = status,
        dueEpochDay = dueEpochDay,
        scheduledMinor = minor,
        recordGeneration = 0
    )

    private fun plan(
        id: Long,
        entries: List<ProjectionEntryInput>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR
    ) = ProjectionPlanInput(id, "plan-uuid-$id", "Synthetic plan $id", currency, status, entries)

    private fun portOf(vararg plans: ProjectionPlanInput) = fakeInstallmentProjectionPort(plans.toList(), todayDay)

    /** A port that ignores the requested horizon, to prove the projection applies its own window. */
    private fun unboundedPortOf(vararg plans: ProjectionPlanInput) = object : InstallmentProjectionPort {
        override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
            InstallmentProjectionRule.project(plans.toList(), todayDay, null)
    }

    @Test
    fun `an empty port gives an empty list`() = runTest {
        assertTrue(InstallmentUpcomingProjection.load(emptyInstallmentProjectionPort(), todayDay).isEmpty())
    }

    @Test
    fun `the window is 31 civil days - today and today plus 30 are in, today plus 31 is out`() = runTest {
        val p = plan(
            1L,
            listOf(
                entry(1L, day(0)),
                entry(2L, day(30)),
                entry(3L, day(31))
            )
        )

        // The bounded port honours the horizon; the unbounded one proves the projection filters itself.
        for (port in listOf(portOf(p), unboundedPortOf(p))) {
            val rows = InstallmentUpcomingProjection.load(port, todayDay)
            assertEquals(listOf(1L, 2L), rows.map { it.entryId })
        }
        assertEquals(31, InstallmentUpcomingProjection.HORIZON_DAYS)
        assertEquals(day(30), InstallmentUpcomingProjection.horizonEndEpochDay(todayDay))
    }

    @Test
    fun `INV-15 only SCHEDULED entries of ACTIVE plans due today or later are listed`() = runTest {
        val active = plan(
            1L,
            listOf(
                entry(1L, day(-5)), // overdue, not projected
                entry(2L, day(2), status = InstallmentEntryStatus.PAID),
                entry(3L, day(3), status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                entry(4L, day(4), status = InstallmentEntryStatus.SKIPPED),
                entry(5L, day(5), status = InstallmentEntryStatus.CANCELLED),
                entry(6L, day(6), status = InstallmentEntryStatus.REVERSED),
                entry(7L, day(7)) // the only projected one
            )
        )

        val rows = InstallmentUpcomingProjection.load(portOf(active), todayDay)

        assertEquals(listOf(7L), rows.map { it.entryId })
        assertEquals(day(7), rows.single().dueEpochDay)
    }

    @Test
    fun `AE-7 after Stop and AE-11 after completion nothing is listed`() = runTest {
        val stopped = plan(
            1L,
            listOf(
                entry(1L, day(3), status = InstallmentEntryStatus.CANCELLED),
                entry(2L, day(10), status = InstallmentEntryStatus.CANCELLED)
            ),
            status = InstallmentPlanStatus.CANCELLED
        )
        val completed = plan(
            2L,
            listOf(entry(3L, day(3), status = InstallmentEntryStatus.PAID)),
            status = InstallmentPlanStatus.COMPLETED
        )
        // Even if a terminal plan still carried SCHEDULED rows, the plan status excludes it.
        val terminalWithScheduled = listOf(
            plan(3L, listOf(entry(4L, day(3))), status = InstallmentPlanStatus.CANCELLED),
            plan(4L, listOf(entry(5L, day(3))), status = InstallmentPlanStatus.REFUNDED),
            plan(5L, listOf(entry(6L, day(3))), status = InstallmentPlanStatus.PAID_OFF),
            plan(6L, listOf(entry(7L, day(3))), status = InstallmentPlanStatus.COMPLETED)
        )

        assertTrue(InstallmentUpcomingProjection.load(portOf(stopped, completed), todayDay).isEmpty())
        assertTrue(InstallmentUpcomingProjection.load(portOf(*terminalWithScheduled.toTypedArray()), todayDay).isEmpty())
    }

    @Test
    fun `rows are ordered by due day then plan id then sequence`() = runTest {
        val a = plan(2L, listOf(entry(21L, day(5), seq = 1), entry(22L, day(9), seq = 2)))
        val b = plan(1L, listOf(entry(11L, day(5), seq = 1), entry(12L, day(2), seq = 2)))

        val rows = InstallmentUpcomingProjection.load(portOf(a, b), todayDay)

        assertEquals(listOf(12L, 11L, 21L, 22L), rows.map { it.entryId })
    }

    @Test
    fun `INV-16 rows keep their plan currency and the per-currency groups never mix currencies`() = runTest {
        val eur = plan(1L, listOf(entry(1L, day(2), minor = 5_000L), entry(2L, day(9), minor = 2_500L)))
        val usd = plan(2L, listOf(entry(3L, day(4), minor = 7_000L)), currency = InstallmentCurrency.USD)

        val rows = InstallmentUpcomingProjection.load(portOf(eur, usd), todayDay)
        val groups = InstallmentUpcomingProjection.groupByCurrency(rows)

        assertEquals(
            listOf(InstallmentCurrency.EUR, InstallmentCurrency.USD, InstallmentCurrency.EUR),
            rows.map { it.currency }
        )
        assertEquals(listOf(5_000L, 7_000L, 2_500L), rows.map { it.amountMinor })
        assertEquals(2, groups.size)
        val eurGroup = groups.single { it.currency == InstallmentCurrency.EUR }
        val usdGroup = groups.single { it.currency == InstallmentCurrency.USD }
        assertEquals(7_500L, eurGroup.totalMinor)
        assertEquals(7_000L, usdGroup.totalMinor)
        assertEquals(listOf(1L, 2L), eurGroup.rows.map { it.entryId })
        assertEquals(listOf(3L), usdGroup.rows.map { it.entryId })
    }

    @Test
    fun `a non-positive scheduled amount is omitted and never listed as zero`() = runTest {
        val p = plan(1L, listOf(entry(1L, day(2), minor = 0L), entry(2L, day(3), minor = -100L), entry(3L, day(4))))

        val rows = InstallmentUpcomingProjection.load(portOf(p), todayDay)

        assertEquals(listOf(3L), rows.map { it.entryId })
    }

    @Test
    fun `the list is bounded`() = runTest {
        // 4 plans x 31 daily entries = 124 projected entries inside the window.
        val plans = (1L..4L).map { planId ->
            plan(planId, (0L..30L).map { offset -> entry(planId * 100 + offset, day(offset), seq = offset.toInt() + 1) })
        }

        val rows = InstallmentUpcomingProjection.load(portOf(*plans.toTypedArray()), todayDay)

        assertEquals(InstallmentUpcomingProjection.MAX_ROWS, rows.size)
        // The cut keeps the earliest rows: the sort is applied before the bound.
        assertEquals(rows.sortedWith(compareBy({ it.dueEpochDay }, { it.planId }, { it.seq })), rows)
    }
}
