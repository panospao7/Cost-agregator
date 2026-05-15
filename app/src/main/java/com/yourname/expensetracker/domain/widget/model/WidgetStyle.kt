package com.yourname.expensetracker.domain.widget.model

/**
 * Visual style options for dashboard widgets.
 */
enum class WidgetStyle {
    MODERN,   // Clean, rounded, Material Design aesthetic
    RETRO     // 8-bit arcade, pixel borders, neon colors
}

/**
 * Per-widget style configuration.
 * Maps widget IDs to their preferred style.
 */
data class WidgetStyleConfig(
    val widgetStyles: Map<String, WidgetStyle> = emptyMap()
) {
    /**
     * Get the style for a specific widget. Defaults to MODERN if not set.
     */
    fun getStyle(widgetId: String): WidgetStyle {
        return widgetStyles[widgetId] ?: WidgetStyle.MODERN
    }
    
    /**
     * Set the style for a specific widget.
     */
    fun setStyle(widgetId: String, style: WidgetStyle): WidgetStyleConfig {
        return copy(widgetStyles = widgetStyles + (widgetId to style))
    }
    
    /**
     * Toggle between MODERN and RETRO for a specific widget.
     */
    fun toggleStyle(widgetId: String): WidgetStyleConfig {
        val currentStyle = getStyle(widgetId)
        val newStyle = if (currentStyle == WidgetStyle.MODERN) WidgetStyle.RETRO else WidgetStyle.MODERN
        return setStyle(widgetId, newStyle)
    }
}

/**
 * S4-001R: Delegates to DashboardWidgetRegistry.styleableIds — no independent hardcoded list.
 */
object StyledWidgets {
    const val BUDGET_BLOCK_PARTY = com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry.BUDGET_BLOCK_PARTY
    const val TOP_CATEGORIES     = com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry.TOP_CATEGORIES
    const val TOTALS_DASHBOARD   = com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry.TOTALS_DASHBOARD

    val all: Set<String> get() = com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry.styleableIds
}
