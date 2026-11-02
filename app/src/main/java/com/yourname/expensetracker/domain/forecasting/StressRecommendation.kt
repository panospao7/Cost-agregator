package com.yourname.expensetracker.domain.forecasting

/**
 * Navigation intent attached to a [StressRecommendationKind] (stress-forecast-actions, INV-3).
 *
 * Pure domain type: the UI maps each action to an existing destination with an exhaustive `when`.
 * [None] marks an informational recommendation that never triggers navigation.
 */
enum class StressRecommendationAction {
    OpenRecurring,
    OpenTransactionsThisMonth,
    OpenSavingsGoals,
    None
}

/**
 * Locale-independent kind of a stress-forecast recommendation. [action] is the single
 * kind-to-intent mapping (INV-3); display text is resolved by the UI from string resources keyed
 * by kind.
 */
enum class StressRecommendationKind(val action: StressRecommendationAction) {
    REVIEW_RECURRING_OBLIGATIONS(StressRecommendationAction.OpenRecurring),
    REDUCE_DISCRETIONARY_SPENDING(StressRecommendationAction.OpenTransactionsThisMonth),
    BUILD_EMERGENCY_BUFFER(StressRecommendationAction.OpenSavingsGoals),
    DELAY_NON_ESSENTIAL_PURCHASES(StressRecommendationAction.None),
    HEALTHY_OUTLOOK(StressRecommendationAction.None),
    NO_IMMEDIATE_CONCERNS(StressRecommendationAction.None)
}

/**
 * A structured stress-forecast recommendation: a [kind] plus the parameters its text needs.
 *
 * Parameter presence (INV-8):
 * - [daysAhead]: only [StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING]; the worst
 *   horizon's days ahead (30, 60 or 90).
 * - [bufferTarget] / [bufferCurrency]: only [StressRecommendationKind.BUILD_EMERGENCY_BUFFER];
 *   the emergency buffer in major units of [bufferCurrency] (the forecast display currency).
 *   No rounding is applied in the domain; display rounding belongs to the UI formatter.
 * - Every other kind carries no parameters.
 */
data class StressRecommendation(
    val kind: StressRecommendationKind,
    val daysAhead: Int? = null,
    val bufferTarget: Double? = null,
    val bufferCurrency: String? = null
)
