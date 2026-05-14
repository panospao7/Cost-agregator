package com.yourname.expensetracker.domain.model

/**
 * State for the totals drill-down widget.
 *
 * S4-009: Uses a breadcrumb stack instead of a single parentPeriod to support
 * multi-level navigation (Year → Month → Week → Day) without losing context.
 */
data class PeriodDrillDownState(
    val currentLevel: PeriodType,
    val selectedPeriod: PeriodTotal?,
    /** S4-009: Breadcrumb stack replaces single parentPeriod. Last item = immediate parent. */
    val breadcrumb: List<PeriodTotal> = emptyList(),
    val periodTotals: List<PeriodTotal>,
    val categoryBreakdown: List<CategoryBreakdown>,
    val isLoading: Boolean = false,
    val error: UiText? = null
) {
    /** Backward-compatible accessor for the immediate parent period. */
    val parentPeriod: PeriodTotal? get() = breadcrumb.lastOrNull()

    /** True if there is a parent to navigate back to. */
    val canDrillUp: Boolean get() = breadcrumb.isNotEmpty()
}
