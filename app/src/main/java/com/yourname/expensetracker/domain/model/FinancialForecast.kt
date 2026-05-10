package com.yourname.expensetracker.domain.model

import java.time.Instant

/**
 * Financial forecast result.
 *
 * ## O4: AI cash flow forecast has no confidence indicator
 * This model already carries a [confidence] field (0.0–1.0) that represents
 * the overall confidence in the forecast. The AI cash flow forecast path
 * (if one is added in the future) should populate this field with an
 * appropriate confidence value derived from AI response quality.
 *
 * The deterministic [FinancialStressForecastEngine] uses [StressHorizon.probabilityOfCrunch]
 * as a probability-based confidence indicator instead.
 */
data class FinancialForecast(
    val horizon: ForecastHorizon,
    val generatedAt: Instant,
    val confidence: Double, // 0.0 - 1.0
    val components: ForecastComponents,
    val actionableInsights: List<UiText>,
    /** @suppress Currency code this forecast is denominated in (e.g. "EUR", "USD"). */
    val displayCurrency: String = ""
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
        require(actionableInsights.none { insight -> (insight as? UiText.DynamicString)?.value?.isBlank() == true }) {
            "actionableInsights cannot contain blank entries"
        }
    }
}

enum class ForecastHorizon(
    val displayName: String,
    val kind: Kind,
    val fixedDays: Int? = null
) {
    NEXT_7_DAYS(
        displayName = "Next 7 Days",
        kind = Kind.FIXED_DAYS,
        fixedDays = 7
    ),
    NEXT_30_DAYS(
        displayName = "Next 30 Days",
        kind = Kind.FIXED_DAYS,
        fixedDays = 30
    ),
    REST_OF_MONTH(
        displayName = "Rest of Month",
        kind = Kind.REST_OF_MONTH
    );

    enum class Kind {
        FIXED_DAYS,
        REST_OF_MONTH
    }

    val isCalendarBound: Boolean
        get() = kind == Kind.REST_OF_MONTH

    /**
     * REST_OF_MONTH is calendar-bound. Use the [kind] property and compute the actual
     * day count via [com.yourname.expensetracker.domain.util.TimePeriodUtils] at call sites.
     */
    @Deprecated(
        message = "Use fixedDays and kind. REST_OF_MONTH is calendar-bound and has no fixed day count.",
        replaceWith = ReplaceWith("fixedDays")
    )
    val days: Int
        get() = fixedDays ?: throw UnsupportedOperationException(
            "${name} is calendar-bound and does not have a fixed day count"
        )
}

/**
 * A materialised occurrence from a recurring rule within the forecast window.
 * Carries enough information for downstream consumers (SynthesisEngine,
 * FinancialWeatherRepository) to avoid re-estimating WEEKLY/BIWEEKLY occurrence counts
 * from a single [RecurringPattern.nextExpectedDate].
 */
data class ConfirmedOccurrence(
    val dueDate: Long,
    val expectedAmount: Double,
    val expectedCurrency: String,
    val merchant: String?,
    val categoryId: Long?,
    val status: String = "PLANNED"
)

data class ForecastComponents(
    val recurringExpenses: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense> = emptyList(), // Manual intentions
    val goalReserves: Double = 0.0, // Money locked in goals

    // Timeline Data
    val pastSpendingPoints: List<Double>, // Cumulative daily spend up to today
    val projectedSpendingPoints: List<Double>, // Projected cumulative daily spend for rest of month
    
    // Synthesis Metrics
    val totalCommitted: Double,        // High confidence (bills, manual)
    val totalLikely: Double,           // Medium confidence (patterns, manual)
    val predictedDiscretionary: Double, // Habit-based predicted spending
    val discretionaryBudget: Double,   // "Safe-to-Spend"
    val riskLevel: RiskLevel,

    /**
     * Materialised occurrences from manual recurring rules within the forecast window.
     * Prefer this over [recurringExpenses] for computing committed/likely spend,
     * because it contains ALL generated occurrences (not just the next expected date).
     * Empty when no manual recurring rules exist or occurrence generation failed.
     */
    val confirmedOccurrences: List<ConfirmedOccurrence> = emptyList(),

    /** @suppress Currency code these components are denominated in (e.g. "EUR", "USD"). */
    val displayCurrency: String = ""
) {
    init {
        require(goalReserves.isFinite() && goalReserves >= 0.0) { "goalReserves must be a non-negative finite number" }
        require(pastSpendingPoints.all { it.isFinite() }) { "pastSpendingPoints must contain only finite values" }
        require(projectedSpendingPoints.all { it.isFinite() }) { "projectedSpendingPoints must contain only finite values" }
        require(totalCommitted.isFinite()) { "totalCommitted must be finite" }
        require(totalLikely.isFinite()) { "totalLikely must be finite" }
        require(predictedDiscretionary.isFinite()) { "predictedDiscretionary must be finite" }
        require(discretionaryBudget.isFinite()) { "discretionaryBudget must be finite" }
    }
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class WeatherNarrative(
    val state: com.yourname.expensetracker.domain.model.dashboard.WeatherState,
    val icon: String,
    val headline: UiText,
    val summary: UiText,
    val details: List<NarrativeSection> = emptyList()
) {
    init {
        require(icon.isNotBlank()) { "icon cannot be blank" }
        require((headline as? UiText.DynamicString)?.value?.isBlank() != true) { "headline cannot be blank" }
        require((summary as? UiText.DynamicString)?.value?.isBlank() != true) { "summary cannot be blank" }
    }
}

data class NarrativeSection(
    val title: UiText,
    val icon: String,
    val items: List<UiText>
) {
    init {
        require(icon.isNotBlank()) { "icon cannot be blank" }
        require(items.none { item -> (item as? UiText.DynamicString)?.value?.isBlank() == true }) {
            "items cannot contain blank entries"
        }
    }
}
