package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Currency provenance of the cloud briefing payload ([DashboardBriefingInputBuilder]):
 * a Planned item has no stored currency provenance for the cloud payload, so its
 * `currencyCode` must stay null (never a guessed or defaulted code), while a Recurring
 * item keeps the currency of its pattern.
 */
class DashboardBriefingInputBuilderCurrencyTest {

    private val now = 1_700_000_000_000L
    private val builder = DashboardBriefingInputBuilder(FakeTimeProvider(fixedTime = now))

    private fun planned(id: Long, currency: String) = UpcomingItem.Planned(
        PlannedExpense(
            id = id,
            description = "Planned $id",
            amount = 25.0,
            currency = currency,
            date = now + 86_400_000L,
            categoryId = null,
            isRecurring = false,
            priority = PlannedExpensePriority.LIKELY
        )
    )

    private fun recurring(currency: String) = UpcomingItem.Recurring(
        RecurringPattern(
            merchantName = "Synthetic subscription",
            averageAmount = 12.0,
            currency = currency,
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = now + 2 * 86_400_000L,
            confidence = 0.9f,
            previousDates = emptyList()
        )
    )

    private fun processed(items: List<UpcomingItem>): ProcessedDashboardData {
        val weather = FinancialWeather(
            state = WeatherState.PARTLY_CLOUDY,
            headline = UiText.from("Mostly fine"),
            summary = UiText.from("Summary"),
            icon = "x",
            riskLevel = 10,
            totalCommitted = 0.0,
            totalLikely = 0.0,
            predictedDiscretionary = 0.0,
            discretionaryBudget = 0.0,
            upcomingItems = items
        )
        val data = DashboardData(
            expenses = emptyList(),
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 0,
            weather = weather,
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 0
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    @Test
    fun `planned items keep currencyCode null whatever currency the planned expense holds`() {
        val input = builder.build(processed(listOf(planned(1, "EUR"), planned(2, "USD"), planned(3, "GBP"))))

        assertEquals(3, input.upcomingItems.size)
        for (item in input.upcomingItems) {
            assertNull("Planned ${item.description} must not carry a currency code", item.currencyCode)
        }
    }

    @Test
    fun `control recurring item keeps the currency of its pattern while planned stays null`() {
        val input = builder.build(processed(listOf(recurring("USD"), planned(1, "USD"))))

        assertEquals("USD", input.upcomingItems.single { it.description == "Synthetic subscription" }.currencyCode)
        assertNull(input.upcomingItems.single { it.description == "Planned 1" }.currencyCode)
    }
}
