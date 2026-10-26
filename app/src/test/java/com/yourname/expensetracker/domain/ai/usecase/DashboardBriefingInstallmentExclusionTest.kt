package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.DashboardBriefingPromptFormatter
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loan-installments S8a, INV-31 / spec 11.5 / AE-23 (payload part).
 *
 * Installment plan data must never reach a cloud-bound DASHBOARD_BRIEFING payload. The seam is
 * hard-wired in [DashboardBriefingInputBuilder]: only explicitly mapped [UpcomingItem] variants
 * are copied into the payload, anything else is dropped whole before the cap. It is never a
 * user setting.
 *
 * Today no installment projection is wired into [FinancialWeather] and plans are NOT injected
 * into any fixture here (S8b does the wiring). The differential-style tests below (AE-23 payload,
 * differential, transaction insight) are therefore PLACEHOLDERS that S8b must REPLACE, not merely
 * keep green, with the end-to-end differential (A15/F5): real FinancialWeatherRepository with a
 * fake projection port returning a projected entry, through the briefing use case / worker, to
 * the CloudDashboardBriefingService text, identical with and without plans.
 *
 * What these tests do enforce today: the builder allow-list drops an unmapped [UpcomingItem]
 * variant whole before the cap, and the sealed-variant tripwire fails when a new variant is
 * added, so its author must add an explicit exclusion assertion here instead of the variant
 * silently riding along.
 */
class DashboardBriefingInstallmentExclusionTest {

    private val now = 1_700_000_000_000L
    private val builder = DashboardBriefingInputBuilder(FakeTimeProvider(fixedTime = now))
    private val formatter = DashboardBriefingPromptFormatter()

    // Plan-side facts that must never appear anywhere in a cloud payload.
    private val lenderLabel = "Zebra Lender 0% Visa"
    private val planUuid = "7c1f3e2a-plan-uuid-ae23"
    private val projectedAmountMinor = 987_654L
    private val projectedAmountText = "9876.54"

    private fun weather(items: List<UpcomingItem>) = FinancialWeather(
        state = WeatherState.PARTLY_CLOUDY,
        headline = UiText.from("Mostly fine"),
        summary = UiText.from("Two bills this month"),
        icon = "x",
        riskLevel = 10,
        totalCommitted = 250.0,
        totalLikely = 320.0,
        predictedDiscretionary = 400.0,
        discretionaryBudget = 600.0,
        upcomingItems = items
    )

    private fun fixtureItems(): List<UpcomingItem> = listOf(
        UpcomingItem.Recurring(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 12.5,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 1,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 86_400_000L,
                confidence = 0.9f,
                previousDates = emptyList()
            )
        ),
        UpcomingItem.Planned(
            PlannedExpense(
                id = 7L,
                description = "Dentist",
                amount = 80.0,
                currency = "EUR",
                date = now + 2 * 86_400_000L,
                categoryId = null,
                isRecurring = false,
                priority = PlannedExpensePriority.LIKELY
            )
        )
    )

    private fun processed(weather: FinancialWeather): ProcessedDashboardData {
        val data = DashboardData(
            expenses = emptyList(),
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 2,
            weather = weather,
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 300.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 3
        )
        return ProcessedDashboardData(
            data = data,
            summary = summary,
            categoryBreakdown = listOf(
                DashboardCategoryBreakdown(
                    categoryId = 1L,
                    categoryName = "Food",
                    categoryIcon = "i",
                    categoryColor = "#FFFFFF",
                    amount = 120.0,
                    percentage = 40.0,
                    changeFromLastPeriod = 0.0
                )
            )
        )
    }

    private fun payloadText(input: DashboardBriefingInput): String =
        input.toString() + "\n" +
            formatter.buildPrompt(input, shouldRedact = false) + "\n" +
            formatter.buildPrompt(input, shouldRedact = true)

    // PLACEHOLDER until S8b (A15/F5): plans are not injected; S8b replaces this with the
    // end-to-end differential. Today it only proves the plan-side constants are absent from a
    // payload built from a plan-free fixture.
    @Test
    fun `AE-23 briefing payload with plans present contains no plan fields display names or projected amounts`() {
        val input = builder.build(processed(weather(fixtureItems())))
        val text = payloadText(input)

        assertFalse(text.contains(lenderLabel))
        assertFalse(text.contains("Zebra"))
        assertFalse(text.contains(planUuid))
        assertFalse(text.contains(projectedAmountMinor.toString()))
        assertFalse(text.contains(projectedAmountText))
        assertFalse(text.contains("installment", ignoreCase = true))
        assertEquals(listOf("Netflix", "Dentist"), input.upcomingItems.map { it.description })
    }

    // PLACEHOLDER until S8b (A15/F5): plans are not injected; S8b replaces this with the
    // end-to-end differential. Today both builds use the identical plan-free fixture, so equality
    // is vacuous and only pins builder determinism and the derived sums.
    @Test
    fun `AE-23 differential - same fixture with and without plans yields identical input and prompt`() {
        val withoutPlans = builder.build(processed(weather(fixtureItems())))
        val withPlans = builder.build(processed(weather(fixtureItems())))

        assertEquals(withoutPlans, withPlans)
        assertEquals(
            formatter.buildPrompt(withoutPlans, shouldRedact = false),
            formatter.buildPrompt(withPlans, shouldRedact = false)
        )
        assertEquals(
            formatter.buildPrompt(withoutPlans, shouldRedact = true),
            formatter.buildPrompt(withPlans, shouldRedact = true)
        )
        // Derived sums are untouched by the presence of plans.
        assertEquals(250.0, withPlans.totalCommitted, 0.0)
        assertEquals(320.0, withPlans.totalLikely, 0.0)
        assertEquals(600.0, withPlans.discretionaryBudget, 0.0)
        assertEquals(UiText.from("Mostly fine"), withPlans.weatherHeadline)
        assertEquals(UiText.from("Two bills this month"), withPlans.weatherSummary)
    }

    // Upcoming items cap at five. Drop-before-cap ORDERING (excluded variants never consume cap
    // slots) is enforced by code review and the variant tripwire below only: MockK cannot build an
    // unmapped UpcomingItem (it instantiates a real sealed subclass), so no test can plant one.
    @Test
    fun `upcoming items cap at five`() {
        val many = (1..8).map { index ->
            UpcomingItem.Planned(
                PlannedExpense(
                    id = index.toLong(),
                    description = "Bill $index",
                    amount = 10.0 * index,
                    currency = "EUR",
                    date = now + index * 86_400_000L,
                    categoryId = null,
                    isRecurring = false,
                    priority = PlannedExpensePriority.LIKELY
                )
            )
        }
        val input = builder.build(processed(weather(many)))
        assertEquals(5, input.upcomingItems.size)
        assertEquals((1..5).map { "Bill $it" }, input.upcomingItems.map { it.description })
    }

    // Tripwire: a new UpcomingItem variant is excluded from the cloud payload by the builder's
    // else branch. Whoever adds one (S8b projection item) must add an explicit exclusion
    // assertion to this class and review the builder allow-list before updating this set.
    @Test
    fun `UpcomingItem variants are exactly the reviewed set - a new variant needs an explicit cloud exclusion test`() {
        val variants = UpcomingItem::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertEquals(setOf("Recurring", "Occurrence", "Planned"), variants)
    }

    // PLACEHOLDER until S8b (A15/F5): plans are not injected; S8b replaces this with the
    // end-to-end differential. GenerateTransactionInsightUseCase input assembly reads one Expense
    // only; no plan data path exists, so this pins that structure and the hard-wired empty list.
    @Test
    fun `AE-23 transaction insight input is independent of plans and carries no upcoming items`() {
        val expense = mockk<Expense>()
        every { expense.amount } returns 42.0
        every { expense.merchant } returns "Corner Shop"
        every { expense.currency } returns "EUR"
        every { expense.date } returns now

        val insightBuilder = TransactionInsightInputBuilder()
        val withoutPlans = insightBuilder.build(expense, shouldRedact = false)
        val withPlans = insightBuilder.build(expense, shouldRedact = false)

        assertEquals(withoutPlans, withPlans)
        assertTrue(withPlans.upcomingItems.isEmpty())
        val text = payloadText(withPlans)
        assertFalse(text.contains("Zebra"))
        assertFalse(text.contains(planUuid))
        assertFalse(text.contains(projectedAmountText))
        assertFalse(text.contains("installment", ignoreCase = true))
    }
}
