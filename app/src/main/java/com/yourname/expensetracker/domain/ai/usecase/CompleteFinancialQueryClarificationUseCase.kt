package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.ClarificationCompletion
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.PresentationChoice
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.withPeriod
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

/**
 * Completes a pending structured clarification into an executable intent (ai-fixes INV-C13).
 *
 * Pure: no I/O, no interpreter, no provider, no network. Only the clarified slot changes; every other
 * field of [ClarificationCompletion.baseIntent] (categories, merchants, types, ownership, amounts,
 * currency, comparison, answer mode, raw/normalized query) is preserved as is.
 *
 * - PERIOD: `filters.period` and `filters.periodGranularity` are both replaced from
 *   [AssistantPeriodResolver.resolveChoice] at tap time.
 * - BREAKDOWN: metric = TOTAL, grouping kept.
 * - TRANSACTION_LIST: metric = LIST, grouping = NONE.
 *
 * The only clock is the injected [TimeProvider] (read once, only as the default of `nowMs`).
 */
class CompleteFinancialQueryClarificationUseCase @Inject constructor(
    private val timeProvider: TimeProvider
) {

    operator fun invoke(
        completion: ClarificationCompletion,
        choice: ClarificationChoice,
        nowMs: Long = timeProvider.now()
    ): FinancialQueryIntent {
        val base = completion.baseIntent
        return when (choice) {
            is ClarificationChoice.Period -> {
                val resolved = AssistantPeriodResolver.resolveChoice(choice.choice, nowMs)
                base.copy(
                    filters = base.filters.withPeriod(
                        startMs = resolved.startMs,
                        endMs = resolved.endMs,
                        granularity = resolved.granularity
                    )
                )
            }

            is ClarificationChoice.Presentation -> when (choice.choice) {
                PresentationChoice.BREAKDOWN -> base.copy(metric = QueryMetric.TOTAL)
                PresentationChoice.TRANSACTION_LIST -> base.copy(
                    metric = QueryMetric.LIST,
                    grouping = QueryGrouping.NONE
                )
            }
        }
    }
}
