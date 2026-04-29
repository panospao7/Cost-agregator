package com.yourname.expensetracker.ui.mappers

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.util.CurrencyFormatter

data class MonteCarloBudgetImpactUi(
    val title: String,
    val overrunLabel: String?,
    val isOverrunHighlighted: Boolean
)

class MonteCarloBudgetImpactUiMapper(
    private val context: Context
) {
    fun map(impact: MonteCarloBudgetImpact, homeCurrency: String = "EUR"): MonteCarloBudgetImpactUi {
        val hasMeaningfulOverrun = impact.expectedOverrun > 0.0
        val formattedOverrun = if (hasMeaningfulOverrun) {
            CurrencyFormatter.format(impact.expectedOverrun, homeCurrency)
        } else {
            null
        }

        val title = when (impact.riskTier) {
            MonteCarloBudgetImpact.RiskTier.LOW -> context.getString(R.string.money_radar_budget_impact_on_track)
            MonteCarloBudgetImpact.RiskTier.MEDIUM -> {
                if (formattedOverrun != null) {
                    context.getString(R.string.money_radar_budget_impact_maybe_overrun_format, formattedOverrun)
                } else {
                    context.getString(R.string.money_radar_budget_impact_probability_warning)
                }
            }

            MonteCarloBudgetImpact.RiskTier.HIGH -> {
                if (formattedOverrun != null) {
                    context.getString(R.string.money_radar_budget_impact_high_overrun_format, formattedOverrun)
                } else {
                    context.getString(R.string.money_radar_budget_impact_probability_warning)
                }
            }

            MonteCarloBudgetImpact.RiskTier.CRITICAL -> {
                if (formattedOverrun != null) {
                    context.getString(R.string.money_radar_budget_impact_critical_overrun_format, formattedOverrun)
                } else {
                    context.getString(R.string.money_radar_budget_impact_probability_critical)
                }
            }
        }

        return MonteCarloBudgetImpactUi(
            title = title,
            overrunLabel = formattedOverrun,
            isOverrunHighlighted = hasMeaningfulOverrun
        )
    }
}
