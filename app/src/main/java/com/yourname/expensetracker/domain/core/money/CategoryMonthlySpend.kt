package com.yourname.expensetracker.domain.core.money

/**
 * RP-08 (P6-002/P6-003): Shared domain-money contract for historical budget
 * autopilot history — one row per `(scope, monthKey)` bucket.
 *
 * Produced by `MultiCurrencyRepository.getHistoricalCategoryMonthlySpend()`
 * and consumed by the budget autopilot/forecasting engines. This value object
 * replaces the raw mixed-currency `SUM` rows and the reflection bridge that
 * previously fed `BudgetAutopilotEngine`.
 *
 * == Contract (RP-08 "Contract gate") ==
 *
 * - **PURCHASE-only**: only `PURCHASE` transactions are included. Transfers,
 *   income/deposits, and `isNotMine` rows are excluded entirely.
 * - **Home-currency normalization on [RateBasis.TRANSACTION_DATE]**: every
 *   source row is converted to the home currency at the rate valid on its own
 *   transaction date, through the canonical [MoneyNormalizationEngine] /
 *   [MoneyAggregate] pipeline.
 * - **Month keys per the `TimePeriodUtils` policy**: local-time `yyyy-MM`
 *   keys produced by `TimePeriodUtils.formatMonthKey` — the same policy used
 *   by `BudgetHistorySeriesBuilder` / `BudgetForecastingEngine`.
 * - **Half-open date range**: the source query covers `[startDate, endDate)`.
 * - **No rate-sentinel substitution**: a missing/invalid rate never causes a
 *   latest-rate or home-currency fallback amount to be substituted. Failed
 *   conversions are excluded only by the normalizer and are surfaced through
 *   [MoneyAggregate.isPartial], [MoneyAggregate.conversionFailures], and
 *   `MoneyAggregate.metadata.excludedTransactionCount`.
 * - **Typed failures propagate**: repository-level failures (home-currency
 *   resolution, DAO errors) are thrown as typed exceptions
 *   (e.g. `HomeCurrencyUnavailableException`) and must never be converted
 *   into an empty list by producers.
 * - **Scope semantics**: [SpendScope.Category]`(`null`)` is a real bucket for
 *   uncategorized purchases. The overall budget is a *separate*
 *   [SpendScope.Overall] aggregate across every included category, including
 *   uncategorized rows. A nullable category id alone must never be used to
 *   represent both scopes.
 *
 * Rows with no qualifying transactions in a month are not produced; zero-fill
 * of gaps between the first and last included months is the responsibility of
 * the series-building layer, not of this contract.
 *
 * @property scope   Which bucket this row belongs to (overall or category).
 * @property monthKey Canonical local-time month key (`yyyy-MM`).
 * @property aggregate The home-currency [MoneyAggregate] for this bucket.
 */
data class CategoryMonthlySpend(
    val scope: SpendScope,
    val monthKey: String,
    val aggregate: MoneyAggregate
)

/**
 * Scope selector for [CategoryMonthlySpend] rows.
 *
 * [Overall] and [Category] are deliberately distinct subtypes so that a
 * nullable category id can never be overloaded to mean "overall budget".
 */
sealed interface SpendScope {

    /**
     * The overall budget bucket: every included PURCHASE transaction in the
     * month across all categories, including uncategorized rows.
     */
    data object Overall : SpendScope

    /**
     * A per-category bucket. [categoryId] == null means the uncategorized
     * bucket (expenses without a category) — it is a real, first-class bucket,
     * not a synonym for [Overall].
     */
    data class Category(val categoryId: Long?) : SpendScope
}
