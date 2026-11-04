package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-1.0.4 (FL-06) weather summary: [NarrativeGenerator] details read the EFFECTIVE spend and
 * effective health. The "all budgets on track" section needs a known effective health for every
 * budget; a gross spend is never printed.
 *
 * The forecast comes from the real [SynthesisEngine] (fixed clock 2024-01-15 12:00 UTC).
 */
class NarrativeGeneratorEffectiveBudgetTest {

    private val nowMs = 1_705_320_000_000L
    private val periodStart = 1_704_067_200_000L // 2024-01-01 00:00 UTC
    private val monthMs = 31 * 86_400_000L

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = nowMs
    }
    private val engine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
    private val generator = NarrativeGenerator()

    private val pace = SpendingPace(
        currentMonthSpent = 100.0,
        daysElapsed = 15,
        daysInMonth = 31,
        projectedTotal = 200.0,
        previousMonthTotal = null,
        averageMonthlyTotal = null,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR"
    )

    private fun snapshot(
        gross: Double,
        effective: Double?,
        effectiveHealth: BudgetHealthStatus?,
        limit: Double = 100.0,
        categoryId: Long? = null,
        categoryName: String? = null
    ) = BudgetStatusSnapshot(
        budgetCategoryId = categoryId,
        budgetAmount = limit,
        categoryName = categoryName,
        spentAmount = gross,
        remainingAmount = limit - gross,
        percentUsed = gross / limit,
        healthStatus = if (gross >= limit) BudgetHealthStatus.EXCEEDED else BudgetHealthStatus.ON_TRACK,
        periodStart = periodStart,
        periodEnd = periodStart + monthMs,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = effective,
        effectiveHealthStatus = effectiveHealth
    )

    private suspend fun details(budgets: List<BudgetStatusSnapshot>) =
        generator.generate(
            forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = budgets,
                spendingPace = pace,
                displayCurrency = "EUR"
            ),
            budgetStatuses = budgets,
            homeCurrency = "EUR"
        ).details

    private fun money(amount: Double) =
        UiTextArg.Money(amount, currency = "EUR", currencyAssumption = "ASSUMED_HOME_CURRENCY", showCents = false)

    private val allOnTrack = UiText.fromKey(DomainTextKeys.NARRATIVE_ALL_BUDGETS_ON_TRACK)

    @Test
    fun `null effective health never prints all on track even when gross health is on track`() = runTest {
        val sections = details(listOf(snapshot(gross = 40.0, effective = null, effectiveHealth = null)))

        assertTrue(
            "a budget with no effective health must not be reported as on track",
            sections.none { allOnTrack in it.items }
        )
        assertTrue(sections.none { it.title == UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_HEALTH) })
    }

    @Test
    fun `null effective health with gross exceeded lists no alert and no spend`() = runTest {
        val sections = details(listOf(snapshot(gross = 120.0, effective = null, effectiveHealth = null)))

        assertTrue("no alert section without an effective health", sections.none {
            it.title == UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_ALERTS)
        })
        assertTrue(sections.none { allOnTrack in it.items })
    }

    @Test
    fun `all on track needs every budget to have a known effective health`() = runTest {
        val sections = details(
            listOf(
                snapshot(gross = 40.0, effective = 40.0, effectiveHealth = BudgetHealthStatus.ON_TRACK),
                snapshot(
                    gross = 40.0, effective = null, effectiveHealth = null,
                    categoryId = 5L, categoryName = "Food"
                )
            )
        )

        assertTrue(sections.none { allOnTrack in it.items })
    }

    @Test
    fun `control all known on track prints the all on track section`() = runTest {
        val sections = details(
            listOf(
                snapshot(gross = 40.0, effective = 40.0, effectiveHealth = BudgetHealthStatus.ON_TRACK),
                snapshot(
                    gross = 30.0, effective = 30.0, effectiveHealth = BudgetHealthStatus.ON_TRACK,
                    categoryId = 5L, categoryName = "Food"
                )
            )
        )

        val section = sections.single { allOnTrack in it.items }
        assertEquals(UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_HEALTH), section.title)
        assertEquals(listOf(allOnTrack), section.items)
    }

    @Test
    fun `details print 90 for gross 120 and effective 90`() = runTest {
        val sections = details(
            listOf(snapshot(gross = 120.0, effective = 90.0, effectiveHealth = BudgetHealthStatus.CRITICAL))
        )

        val alerts = sections.single { it.title == UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_ALERTS) }
        assertEquals(
            listOf(UiText.fromKey(DomainTextKeys.NARRATIVE_TOTAL_BUDGET_CRITICAL_SPENT_FORMAT, money(90.0))),
            alerts.items
        )
        assertFalse(
            "the gross 120 must never be printed",
            alerts.items.any { UiText.fromKey(DomainTextKeys.NARRATIVE_TOTAL_BUDGET_EXCEEDED_SPENT_FORMAT, money(120.0)) == it }
        )
    }

    @Test
    fun `category budget prints its effective spend with its name`() = runTest {
        val sections = details(
            listOf(
                snapshot(
                    gross = 120.0, effective = 90.0, effectiveHealth = BudgetHealthStatus.CRITICAL,
                    categoryId = 5L, categoryName = "Food"
                )
            )
        )

        val alerts = sections.single { it.title == UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_ALERTS) }
        assertEquals(
            listOf(UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_CRITICAL_SPENT_FORMAT, "Food", money(90.0))),
            alerts.items
        )
    }

    private suspend fun narrative(budgets: List<BudgetStatusSnapshot>) =
        generator.generate(
            forecast = engine.synthesize(
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = budgets,
                spendingPace = pace,
                displayCurrency = "EUR"
            ),
            budgetStatuses = budgets,
            homeCurrency = "EUR"
        )

    private val unavailableHeadline = UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_UNAVAILABLE)
    private val unavailableSummary = UiText.fromKey(DomainTextKeys.WEATHER_SUMMARY_UNAVAILABLE)

    @Test
    fun `overall budget with effective spend unavailable gives UNKNOWN weather and the unavailable text`() = runTest {
        val result = narrative(
            listOf(snapshot(gross = 110.0, effective = null, effectiveHealth = null, limit = 100.0))
        )

        assertEquals(com.yourname.expensetracker.domain.model.dashboard.WeatherState.UNKNOWN, result.state)
        assertEquals(unavailableHeadline, result.headline)
        assertEquals(unavailableSummary, result.summary)
    }

    @Test
    fun `unstamped legacy default row gives UNKNOWN weather even with an effective spend`() = runTest {
        val legacy = snapshot(
            gross = 110.0, effective = 120.0, effectiveHealth = BudgetHealthStatus.EXCEEDED, limit = 100.0
        ).copy(currencyAssumption = "LEGACY_DEFAULT")

        val result = narrative(listOf(legacy))

        assertEquals(com.yourname.expensetracker.domain.model.dashboard.WeatherState.UNKNOWN, result.state)
        assertEquals(unavailableHeadline, result.headline)
        assertEquals(unavailableSummary, result.summary)
    }

    @Test
    fun `DSH-N3 effective 50 with an unstamped overall row prints no all on track section`() = runTest {
        val legacy = snapshot(
            gross = 50.0, effective = 50.0, effectiveHealth = BudgetHealthStatus.ON_TRACK, limit = 100.0
        ).copy(currencyAssumption = "LEGACY_DEFAULT")

        val result = narrative(listOf(legacy))

        assertEquals(unavailableHeadline, result.headline)
        assertTrue(result.details.none { allOnTrack in it.items })
    }

    @Test
    fun `DSH-N3 effective 50 with partial overall budget prints no all on track section`() = runTest {
        val partial = snapshot(
            gross = 50.0, effective = 50.0, effectiveHealth = BudgetHealthStatus.ON_TRACK, limit = 100.0
        ).copy(isPartial = true)

        val result = narrative(listOf(partial))

        assertTrue(result.details.none { allOnTrack in it.items })
    }

    @Test
    fun `DSH-N3 control category-only budgets still print all on track`() = runTest {
        val food = snapshot(
            gross = 30.0, effective = 30.0, effectiveHealth = BudgetHealthStatus.ON_TRACK,
            categoryId = 5L, categoryName = "Food"
        )

        val result = narrative(listOf(food))

        assertTrue(result.details.any { allOnTrack in it.items })
    }

    @Test
    fun `no budgets keeps the existing weather path`() = runTest {
        val result = narrative(emptyList())

        assertFalse(result.summary == unavailableSummary)
        assertFalse(result.headline == unavailableHeadline)
    }

    @Test
    fun `non positive overall limit keeps the existing weather path`() = runTest {
        val result = narrative(
            listOf(snapshot(gross = 0.0, effective = 0.0, effectiveHealth = BudgetHealthStatus.ON_TRACK, limit = 0.0))
        )

        assertFalse(result.summary == unavailableSummary)
        assertFalse(result.headline == unavailableHeadline)
    }

    @Test
    fun `UNKNOWN effective health never prints all on track`() = runTest {
        val sections = details(
            listOf(snapshot(gross = 40.0, effective = 40.0, effectiveHealth = BudgetHealthStatus.UNKNOWN))
        )

        assertTrue(sections.none { allOnTrack in it.items })
    }

    @Test
    fun `gross 90 and effective 120 prints the exceeded line with 120`() = runTest {
        val sections = details(
            listOf(snapshot(gross = 90.0, effective = 120.0, effectiveHealth = BudgetHealthStatus.EXCEEDED))
        )

        val alerts = sections.single { it.title == UiText.fromKey(DomainTextKeys.NARRATIVE_BUDGET_ALERTS) }
        assertEquals(
            listOf(UiText.fromKey(DomainTextKeys.NARRATIVE_TOTAL_BUDGET_EXCEEDED_SPENT_FORMAT, money(120.0))),
            alerts.items
        )
    }
}
