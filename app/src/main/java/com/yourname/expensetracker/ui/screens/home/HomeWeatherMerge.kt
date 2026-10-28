package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget

/**
 * The single Home merge point (loan-installments S8b-3, plan A15 option A) as a pure function.
 *
 * The installment-aware weather REPLACES the baseline [DashboardWidget.FinancialWeatherWidget] that
 * `ComputeDashboardWidgetsUseCase.compute()` produced. It never adds a second weather widget, never
 * adds the aware numbers to the baseline numbers, and never touches any other widget. The widget
 * keeps its position. When the aware weather is null (seam off, no projected entry, or a failure) or
 * there is no weather widget to replace, the input is returned unchanged.
 *
 * `upcomingItems` of the replacement is taken from the baseline widget it replaces, so projections can
 * never appear in the shared upcoming list even by accident (they are carried separately as rows).
 */
internal object HomeWeatherMerge {

    fun replaceWeather(data: CompiledDashboardData, aware: FinancialWeather?): CompiledDashboardData {
        if (aware == null) return data
        if (data.allWidgets.none { it is DashboardWidget.FinancialWeatherWidget }) return data
        return data.copy(allWidgets = replaceWeather(data.allWidgets, aware))
    }

    fun replaceWeather(widgets: List<DashboardWidget>, aware: FinancialWeather): List<DashboardWidget> =
        widgets.map { widget ->
            if (widget is DashboardWidget.FinancialWeatherWidget) {
                DashboardWidget.FinancialWeatherWidget(aware.copy(upcomingItems = widget.weather.upcomingItems))
            } else {
                widget
            }
        }
}
