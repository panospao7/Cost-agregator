package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method

/**
 * N-08-5: the retro totals card ranks a period by its spend as a percentage of the average.
 * A period whose total is unavailable has no spend figure, so it gets NO rank (and therefore no
 * gold medal); a placeholder 0.0 must never read as "0% of average".
 *
 * The ranking helper is a file-private function of RetroTotalsDashboardCard.kt, so it is reached
 * by reflection on the file facade class. The medal thresholds themselves are pinned through the
 * returned percentage (the composables map it with the fixed 80/120/150 cut-offs).
 */
class RetroTotalsRankingTest {

    private val rank: Method = Class.forName("com.yourname.expensetracker.ui.components.RetroTotalsDashboardCardKt")
        .getDeclaredMethod("retroPercentageOfAverage", PeriodTotal::class.java, Double::class.javaPrimitiveType)
        .also { it.isAccessible = true }

    private fun percentage(period: PeriodTotal, average: Double): Double? =
        rank.invoke(null, period, average) as Double?

    private fun period(total: Double, unavailable: Boolean = false, partial: Boolean = false) = PeriodTotal(
        periodLabel = "2024",
        periodKey = "2024",
        totalAmount = total,
        transactionCount = if (unavailable) 0 else 3,
        periodType = PeriodType.YEAR,
        startDateMs = 0L,
        endDateMs = 1L,
        status = PeriodStatus.NO_DATA,
        isPartial = partial,
        isUnavailable = unavailable
    )

    @Test
    fun `helper is reachable so the ranking tests are not vacuous`() {
        assertNotNull(rank)
    }

    @Test
    fun `N-08-5 unavailable period has no rank so no medal whatever the average`() {
        assertNull(percentage(period(0.0, unavailable = true), 1_000.0))
        assertNull(percentage(period(0.0, unavailable = true), 0.0))
        assertNull("even a non-zero placeholder must not rank", percentage(period(500.0, unavailable = true), 1_000.0))
    }

    @Test
    fun `N-08-5 a genuinely zero available period is ranked at 0 percent and is the gold case`() {
        val ranked = percentage(period(0.0), 1_000.0)

        assertEquals(0.0, ranked!!, 0.0)
    }

    @Test
    fun `N-08-5 available periods are ranked as a percentage of the average`() {
        assertEquals(50.0, percentage(period(500.0), 1_000.0)!!, 0.0001)
        assertEquals(100.0, percentage(period(1_000.0), 1_000.0)!!, 0.0001)
        assertEquals(150.0, percentage(period(1_500.0), 1_000.0)!!, 0.0001)
    }

    @Test
    fun `N-08-5 partial but available period is still ranked`() {
        assertEquals(80.0, percentage(period(800.0, partial = true), 1_000.0)!!, 0.0001)
    }

    @Test
    fun `N-08-5 available period with no positive average ranks at 100 percent not zero`() {
        assertEquals(100.0, percentage(period(300.0), 0.0)!!, 0.0001)
    }
}
